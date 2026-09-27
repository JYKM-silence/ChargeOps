/**
 * 车主 / 车主权益接口（管理端）。
 *
 * ★★ 这个模块里**没有**「查余额」的接口，不是漏了 ★★
 *   `MemberVO` 上没有 `balance`，管理端全项目也只有
 *   `POST /api/admin/wallet/{memberId}/adjust` 一个钱包接口 ——
 *   它**只在响应里**返回调账后的 `WalletVO`。
 *   所以任何页面都**不要**去拼一个「余额」列：它只能是 `--`，
 *   而永远显示 `--` 的列会被当成 bug。文案见 `WalletAdjustView`。
 *
 * ★ 为什么权益（`/api/admin/member-package`）也在这个文件里：
 *   它的入口就是「某个车主」—— 查询条件是 `memberId`、作废的对象是车主名下的一行，
 *   和车主接口共用同一套「先选中车主」的上下文。拆成两个文件会让
 *   「车主 → 他的权益」这个动作横跨两个模块，而它是一次业务动作。
 */

import { http } from './http'
import type {
  AdminOrderVO,
  MemberPackageVO,
  MemberPackageVoidDTO,
  MemberPackageVoidVO,
  MemberPageQuery,
  MemberStatusDTO,
  MemberVO,
  Paged,
} from '@chargeops/shared'

const MEMBER_BASE = '/api/admin/member'
const PACKAGE_BASE = '/api/admin/member-package'
/**
 * 订单列表的路径**写在这里而不是 import 订单域的 api 模块**：
 * 车主详情页要的是「这个人的订单」，它是车主域的一个视图；
 * 而订单域那一侧还在被别的改动覆盖，跨模块 import 会让一边的调整
 * 影响另一边的编译。接口本身是同一个后端端点（`OrderAdminController`）。
 */
const ORDER_BASE = '/api/admin/orders'

/**
 * 车主分页查询。
 *
 * ★ `{ ...query }` 这个展开**不是多余的**：`MemberPageQuery` 是 `interface`，
 *   而 interface **没有隐式索引签名**，直接传进 `http.page()` 的
 *   `Record<string, unknown>` 参数会报 TS2345。
 *   展开成一个新对象字面量就有了。别把它「简化」掉。
 *
 * ★ `phone` 是**前缀**匹配 —— 调用方必须先过 `canSearchByPhone()`（≥6 位数字），
 *   见 `domain/queryDefaults.ts`。
 */
export function pageMembers(query: MemberPageQuery): Promise<Paged<MemberVO>> {
  return http.page<MemberVO>(`${MEMBER_BASE}/page`, { ...query })
}

/**
 * 按手机号前缀搜车主，给**远程搜索下拉**用。
 *
 * ★ 只取前 `LIMIT` 条就够：下拉是给人点选的，不是给人翻页的。
 *   命中过多时应当让用户多输几位（见 `canSearchByPhone`），
 *   而不是把 200 个同名手机号倒进下拉框。
 */
export async function searchMembersByPhone(phone: string, limit = 20): Promise<MemberVO[]> {
  const paged = await pageMembers({ phone, pageNum: 1, pageSize: limit })
  return paged.records
}

/** 车主详情。查不到时后端返回 `code:0`「车主不存在」，调用方按错误分支处理。 */
export function getMember(id: number): Promise<MemberVO | null> {
  return http.get<MemberVO>(`${MEMBER_BASE}/${id}`)
}

/**
 * 启用 / 禁用车主。
 *
 * ★★ `status` 走**请求体**，不是路径或 query 参数 ★★
 *   这是后端刻意选的形状（`@Valid` 在另外两种写法下会静默失效）。
 *   两个取值：`1 正常` / `0 禁用`。
 *
 * ★★ 调用方必须知道：**禁用不会撤销已经签发的 token** ★★
 *   拦截器只验签名、不查库，member token 有效期 7 天 ——
 *   禁用之后旧 token 最长还能用 7 天，只有资金端点在 Service 层会拒绝。
 *   所以：它不是「立刻踢下线」，UI 文案不能这么承诺。
 */
export function updateMemberStatus(id: number, status: number): Promise<null> {
  const body: MemberStatusDTO = { status }
  return http.put<null>(`${MEMBER_BASE}/${id}/status`, body)
}

/**
 * 某车主名下的**全部**权益（不过滤状态）。
 *
 * ★★ 返回的是**裸数组**，不是分页信封 ★★
 *   后端签名是 `Result<List<MemberPackageVO>>`。
 *   拿 `http.page()` 去接会读到两个 `undefined`（没有 `records`/`total`），
 *   表现为「永远空列表且不报错」—— 和 `category/list` 是同一个坑。
 *
 * ★ 不过滤状态是**后端刻意**的：运营查这个车主十次里有九次是为了回答
 *   「这份权益为什么不能用了」，滤掉已过期/已用尽/已作废等于把答案藏起来。
 *   所以页面**必须**把它们显示出来（灰态），不能自己再筛一遍。
 */
export async function listMemberPackages(memberId: number): Promise<MemberPackageVO[]> {
  // `http.get` 在 data 为 null 时返回 null，这里兜底成空数组
  return (await http.get<MemberPackageVO[]>(PACKAGE_BASE, { memberId })) ?? []
}

/**
 * 作废一份权益 —— **会真的退钱**。
 *
 * ★★ 这是资金动作，调用方**必须**包 `withActionLock` ★★
 *   后端在这一处没有幂等键：`PACKAGE_REFUND` 流水的 `order_id` 是 NULL，
 *   而唯一索引 `uk_txn_order_type` 是 `(order_id, type)` ——
 *   **唯一索引不约束 NULL**，所以数据库层也拦不住第二次。
 *   后端的 CAS（`voidPackage` 条件更新）只挡并发，挡不住「隔一会儿再点一次」。
 *
 * ★ 金额**不在请求里**：退款额取权益上的 `payAmount` 快照（只退完全没用过的、全额退）。
 *   想退一个不一样的数走 `adjustWallet()`，那会在流水里留下不同的 type。
 *
 * ★ `remark` 是**必填**（后端 `@NotBlank` + 200 字上限），会同时写进权益备注和资金流水。
 *   那条流水没有 `order_id`，所以这行字是它唯一的解释。
 */
export function voidMemberPackage(
  id: number,
  dto: MemberPackageVoidDTO,
): Promise<MemberPackageVoidVO | null> {
  return http.post<MemberPackageVoidVO>(`${PACKAGE_BASE}/${id}/void`, dto)
}

/** 车主详情页的订单查询条件。**只有这几个字段**，不要顺手加时间范围。 */
export type MemberOrderQuery = {
  /**
   * ★ 前缀匹配。空串会被 `http` 层的 query 清洗丢掉，而**丢掉就等于不过滤** ——
   *   后端会返回全平台的订单（列有数据、分页也对、一条错都不报）。
   *   所以调用方在拿不到手机号时**不要发这个请求**。
   */
  memberPhone: string
  pageNum?: number
  pageSize?: number
}

/**
 * 某个车主的订单（按手机号前缀收窄到人）。
 *
 * ★ 刻意**不带**近 7 天的默认区间：订单列表页需要那个区间是因为
 *   「没有任何过滤条件时是全表 filesort」，而这里的 `memberPhone`
 *   已经把范围收窄到一个人；再叠一个区间会**藏掉这个人的历史订单**，
 *   而运营打开这个 Tab 十次有九次就是来看历史的。
 */
export function pageMemberOrders(query: MemberOrderQuery): Promise<Paged<AdminOrderVO>> {
  return http.page<AdminOrderVO>(ORDER_BASE, { ...query })
}
