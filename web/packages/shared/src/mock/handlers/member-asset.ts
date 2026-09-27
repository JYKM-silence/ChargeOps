/**
 * 车主端「我的资产」：资料、车辆、钱包、权益。
 *
 * ★★ 这三块为什么合在一个文件，而订单单独一个 ★★
 *   它们的共同点是**「属于我的一份东西」**：查询永远带着隐含的
 *   「只能是我的」（`requireMine`），也没有分页之外的筛选。
 *   订单不是 —— 它有一条 9 个状态的状态机、有 CAS、有跨端共用的一套动作规则，
 *   那是一个独立的域（见 `member-order.ts`）。
 *
 * ★★ `assertActive` 在这里出现的**次数是不均匀的，而且不是漏了** ★★
 *   钱包（3 个）和权益（3 个）都查账号状态，**车辆一个都不查**。
 *   理由是钱：被禁用的人不该还能充值、买东西；而「看一下自己的车」
 *   不涉及任何资金动作，多查一次只是让禁用状态在更多地方显示成错误。
 *   mock 逐条照抄后端调了 `assertActive` 的位置 —— 多一处会让
 *   「被禁用之后到底哪些操作还能用」这件事在演示里失真。
 *
 * ★ 还有两处**刻意缺席**：
 *   · `GET /me` 不查状态：它要做的恰恰是把 `status: 0` **原样返回**给前端探针。
 *     在这里拦住，H5 就永远看不到那个字段，强制登出这条链路就断了。
 *   · `PUT /me`（改资料）也不查状态：后端 `updateProfile` 只调了
 *     `requireCurrentMember`（不判 status），照抄。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type {
  MemberPasswordDTO,
  MemberProfileVO,
  MemberUpdateDTO,
  VehicleDTO,
  VehicleUpdateDTO,
  WalletRechargeDTO,
} from '../../types/member'
import type { Decimal, WalletVO } from '../../types/admin'
import type { PageResult } from '../../http/types'
import type { DbMemberPackage, DbPackage, DbVehicle, MockDb } from '../db'
import { kwh } from '../db'
import { parseServerTime } from '../../utils/datetime'
import {
  assertActive,
  checkPageParams,
  envelope,
  fail,
  guardMember,
  missingRequired,
  numParam,
  ok,
  okVoid,
  paginate,
  pageParams,
  toNumber,
} from './core'
import type { MockContext } from './core'
import {
  packageBenefitMissing,
  packageBenefitNotPositive,
  packageDiscountInvalid,
  packagePriceInvalid,
  TEXT,
} from './texts'
import {
  sortMemberPackages,
  toMemberPackageVo,
  toMemberVo,
  toPackageVo,
  toVehicleVo,
  toWalletTxnVo,
  toWalletVo,
} from './vo'

/**
 * 「永久有效」的哨兵值。
 *
 * ★ 逐字抄 `MemberPackage.PERMANENT_EXPIRE_TIME`（`9999-12-31 23:59:59`），
 *   而**不是** `null`：`null` 的语义是「不知道」（V3 之前的老行），
 *   两者在前端是两个分支 —— 一个是「永久有效」，一个是「--」。
 * ★ 也**不能**图省事写 `9999-12-31 24:00:00` 之类的形状：这一列是
 *   `datetime(3)`，哨兵值必须是它能表示的最大值，否则第一次购买就会写库失败。
 */
const PERMANENT_EXPIRE_TIME = '9999-12-31 23:59:59'

/**
 * 路径参数里的 id。
 *
 * ★ 非数字 → `未知错误`，而不是「不存在」：后端 `Long id` 收到 `abc` 会在
 *   参数绑定阶段就抛 `MethodArgumentTypeMismatchException`，它**没有**专门的
 *   `@ExceptionHandler`，落到 catch-all —— 于是是 `code:0` + 「未知错误」，
 *   而不是那个接口自己的「XX不存在」。见 `admin-member.ts` 的同名函数。
 */
function pathId(raw: string | undefined): number | null {
  if (raw === undefined) return null
  const n = Number(raw)
  return Number.isFinite(n) && Number.isInteger(n) ? n : null
}

/**
 * `VehicleServiceImpl.normalizePlateNo`：去首尾空白 + 转大写。
 *
 * ★ 新增和修改**必须走同一个函数**。只在新增里做的话，修改就是一条绕过它的旁路
 *   （车主能把车牌改成小写，于是同一个人名下出现两种写法）。
 * ★ 转大写不是为了让唯一索引生效（`utf8mb4_0900_ai_ci` 本来就大小写不敏感），
 *   而是**展示统一**：列表里不会同时出现两种写法，客服按车牌反查不用试两种大小写。
 * ★ `trim()` 去掉的尾随空格是一个**正确性**问题：那张排序规则是 NO PAD，
 *   `'粤BD12345' = '粤BD12345 '` 返回 0，唯一索引拦不住带尾空格的重复车牌。
 *   JS 的 `trim()` 与 Java 的 `trim()` 在「去什么」上一致（都含制表符/换行，
 *   都不含 U+00A0），所以这里不必额外处理。
 */
function normalizePlateNo(raw: string): string {
  return raw.trim().toUpperCase()
}

/**
 * 找一辆**属于我的**车。
 *
 * ★★ 查不到和「不是我的」返回**同一句**「车辆不存在」★★
 *   分开会让接口变成「这个 id 存不存在」的探测器：拿别人的 id 试一下，
 *   回「无权操作」就等于确认了那一行存在，而车辆 id 是连续自增的。
 *   后端 `requireMine` 把这一点写成了契约。
 */
function findMine(db: MockDb, memberId: number, id: number): DbVehicle | undefined {
  return db.vehicles.find((v) => v.id === id && v.memberId === memberId)
}

/** `VehicleMapper.listByMemberId` 的 `ORDER BY is_default DESC, id DESC`。 */
function vehiclesOf(db: MockDb, memberId: number): DbVehicle[] {
  return db.vehicles
    .filter((v) => v.memberId === memberId)
    .sort((a, b) => b.isDefault - a.isDefault || b.id - a.id)
}

export function memberAssetHandlers(ctx: MockContext): RequestHandler[] {
  return [
    // ══════════════════════════════════════════════════════════════════════
    // 资料
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 我的资料 —— **H5 的强制登出探针**就打在这个接口上。
     *
     * ★★ 这里没有 `assertActive`，加一个就废掉一条演示路径 ★★
     *   探针的判据是「HTTP 200 **且** `status === 0`」。被禁用的车主在这个接口上
     *   拿到的必须是**正常信封 + status:0**；改成 `code:0`「账号已被禁用」之后，
     *   前端收到的是「业务失败」而不是「禁用信号」——
     *   于是 `locked` 场景演示的会是**另一条**链路（`accountLockedLogsOut`），
     *   而屏幕上的提示看起来差不多，没人会发现探针从来没被验证过。
     *
     * ★ 车主不存在（token 有效但行被删）→ `MEMBER_NOT_FOUND`，不是 401：
     *   token 表认得这个人，这是数据缺失而不是未登录。后端
     *   `requireCurrentMember` 也是这么分的（`log.warn` + AccountNotFound）。
     */
    http.get('/api/member/me', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const member = ctx.db.memberById(g.ctx.memberId)
        if (!member) return fail(TEXT.MEMBER_NOT_FOUND)

        const vo: MemberProfileVO = toMemberVo(member)
        return ok(vo)
      }),
    ),

    /**
     * 改资料。
     *
     * ★★ 三个字段的「不改」判据**不是同一种**，这是后端刻意的，别统一 ★★
     *   · `nickname` / `avatar`：SQL 的 `<if>` 判的是 `!= null and != ''` ——
     *     空串也算「不改」。让空串能清空字段的话，前端一次「表单没填」的提交
     *     就会把昵称抹掉，而用户完全没意识到自己删了东西。
     *   · `gender`：**只判 `!= null`** —— 合法值里有 `0`（未知），
     *     所以不能把 `0` 当成「不传」。「没传」和「传了 0（未知）」必须区分开。
     *   把这三行看成同一种判空，症状是**「性别永远改不成未知」**：
     *   一个只在特定值上出现、且不报错的 bug。
     *
     * ★ 这里复刻了两条 `@Size`（昵称 50 / 头像 255），是本文件唯一的破例：
     *   Service 层没有复查，跳过就等于让 mock 接受一段真后端会拒绝（或截断）的文字，
     *   而那段文字会真的写进内存库。
     */
    http.put('/api/member/me', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as MemberUpdateDTO
        const problems: string[] = []
        if (body.nickname !== undefined && body.nickname !== null && body.nickname.length > 50) {
          problems.push('昵称不能超过 50 个字符')
        }
        if (body.avatar !== undefined && body.avatar !== null && body.avatar.length > 255) {
          problems.push('头像地址不能超过 255 个字符')
        }
        if (body.gender !== undefined && body.gender !== null && (body.gender < 0 || body.gender > 2)) {
          problems.push('性别只能是 0未知、1男、2女')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        const member = ctx.db.memberById(g.ctx.memberId)
        if (!member) return fail(TEXT.MEMBER_NOT_FOUND)

        if (body.nickname !== undefined && body.nickname !== null && body.nickname !== '') {
          member.nickname = body.nickname
        }
        if (body.avatar !== undefined && body.avatar !== null && body.avatar !== '') {
          member.avatar = body.avatar
        }
        // ★ 顺序与后端一致：判 null 就写，`0` 正常落进去。
        if (body.gender !== undefined && body.gender !== null) {
          member.gender = body.gender
        }
        member.updateTime = ctx.db.now()
        return okVoid()
      }),
    ),

    /**
     * 改密码（要验旧密码）。
     *
     * ★★ 「没设过密码的账号」在这里的回答是**原密码错误**，不是
     *   「该账号未设置密码，无法使用密码登录」★★ 两者看着像抄错了，其实是两件事：
     *   登录时服务端回答的是「这个账号能不能用密码登录」（不能）；
     *   改密时客户端**已经提交了一个 oldPassword**，要回答的是「你给的对不对」——
     *   对一个没有密码的账号，答案是「不对」。
     *   ★ 而且**不能**改成「没设过密码就直接设新的」：能走到这里的唯一凭证是一个
     *     token，而这个账号从来没设过密码，没有任何东西能证明来的人是机主 ——
     *     那等于把 7 天的 token 窗口变成一次永久接管。
     *
     * ★ 顺序：字段校验 → 车存在 → 旧密码对。前两条 `@NotBlank` 与长度上限
     *   Service 层都没复查，所以在这里复刻（长度那条和注册共用同一句话）。
     */
    http.put('/api/member/me/password', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as MemberPasswordDTO
        const problems: string[] = []
        if (!body.oldPassword || body.oldPassword.trim() === '') problems.push('原密码不能为空')
        if (!body.newPassword || body.newPassword.trim() === '') problems.push('新密码不能为空')
        if (
          body.newPassword !== undefined &&
          body.newPassword !== null &&
          (body.newPassword.length < 6 || body.newPassword.length > 32)
        ) {
          problems.push('密码长度必须在 6-32 位之间')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        const member = ctx.db.memberById(g.ctx.memberId)
        if (!member) return fail(TEXT.MEMBER_NOT_FOUND)

        // ★ 判空与比对合并成一句：两者对用户是同一个结论（见上面的注释）。
        if (member.password === null || member.password !== body.oldPassword) {
          return fail(TEXT.OLD_PASSWORD_ERROR)
        }

        member.password = body.newPassword
        member.updateTime = ctx.db.now()
        return okVoid()
      }),
    ),

    // ══════════════════════════════════════════════════════════════════════
    // 车辆
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 我的车辆列表。
     *
     * ★ `ORDER BY is_default DESC, id DESC` —— 默认车永远排第一，前端「取列表第一条
     *   当默认车」正是依赖这个顺序。两个键都必要：只按 `is_default` 排的话，
     *   同为非默认时顺序由存储引擎决定，刷新一次列表里的项就跳一次。
     */
    http.get('/api/member/vehicle', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        return ok(vehiclesOf(ctx.db, g.ctx.memberId).map(toVehicleVo))
      }),
    ),

    http.get('/api/member/vehicle/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const vehicle = findMine(ctx.db, g.ctx.memberId, id)
        if (!vehicle) return fail(TEXT.VEHICLE_NOT_FOUND)
        return ok(toVehicleVo(vehicle))
      }),
    ),

    /**
     * 新增一辆车。返回的是**新 id**（不是 VO）—— 前端拿它去跳详情。
     *
     * ★★ 「是不是第一辆车」必须在 **insert 之前**数 ★★
     *   插完再数，结果至少是 1，那个判断就**永远为假** ——
     *   于是新车主的车不是默认车，而「快速下单」里默认选中的车辆就空了。
     *
     * ★★ 两处默认车处理，都不能省 ★★
     *   ① 第一辆车**强制**为默认（不管请求里传的 `isDefault` 是什么）；
     *   ② 显式要求成为默认车时要**清掉其它车的默认标记**（`clearDefaultExcept`）——
     *      否则同一个车主会有两辆默认车，而数据库层没有唯一约束拦得住它，
     *      表现是「设置成功，但下单页给的是另一辆」，且不报任何错。
     *      ★ 排除条件 `id <> 新行` 是正确性要求：此刻新行已经是 1，
     *        没有它就会把自己刚设的标记立刻清掉。
     *   ★ 请求里的 `isDefault` 必须兜底成 `0`：`vehicle.is_default` 是 NOT NULL，
     *     而显式的 `{"isDefault": null}` 是能构造出来的（字段初始值挡不住它）。
     */
    http.post('/api/member/vehicle', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as VehicleDTO
        const missing = missingRequired([
          [body.plateNo, '车牌号不能为空'],
          [body.plateColor, '车牌颜色不能为空'],
        ])
        if (missing) return missing

        const problems: string[] = []
        if (body.plateNo.length > 16) problems.push('车牌号不能超过 16 个字符')
        if (body.plateColor < 1 || body.plateColor > 4) {
          problems.push('车牌颜色只能是 1蓝、2黄、3绿、4渐变绿')
        }
        if (body.brand !== undefined && body.brand !== null && body.brand.length > 50) {
          problems.push('品牌不能超过 50 个字符')
        }
        if (body.model !== undefined && body.model !== null && body.model.length > 50) {
          problems.push('型号不能超过 50 个字符')
        }
        const capacity = toNumber(body.batteryCapacityKwh)
        if (capacity !== null && capacity < 0) problems.push('电池容量不能为负数')
        if (body.remark !== undefined && body.remark !== null && body.remark.length > 255) {
          problems.push('备注不能超过 255 个字符')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        const mine = vehiclesOf(ctx.db, g.ctx.memberId)
        const at = ctx.db.now()
        const isDefault = mine.length === 0 ? 1 : body.isDefault === 1 ? 1 : 0
        const row: DbVehicle = {
          id: ctx.db.nextId('vehicle'),
          memberId: g.ctx.memberId,
          plateNo: normalizePlateNo(body.plateNo),
          plateColor: body.plateColor,
          brand: body.brand ?? null,
          model: body.model ?? null,
          batteryCapacityKwh: capacity,
          isDefault,
          remark: body.remark ?? null,
          createTime: at,
          updateTime: at,
        }
        ctx.db.vehicles.push(row)

        // ★ ②：清了旧的才置新的（新行已经在数组里，所以要排除它自己）。
        if (isDefault === 1) {
          for (const v of ctx.db.vehicles) {
            if (v.memberId === g.ctx.memberId && v.id !== row.id && v.isDefault === 1) {
              v.isDefault = 0
              v.updateTime = at
            }
          }
        }
        return ok(row.id)
      }),
    ),

    /**
     * 改一辆车。
     *
     * ★ 合并方向：DTO 的**非空**值覆盖到库里那行上，`null` 一律表示「不改」。
     *   不用「整体覆盖」——那会让「只改型号」的一次提交把品牌清空
     *   （这正是后端不用 `BeanUtils.copyProperties` 的原因）。
     * ★ `isDefault` **不在这条路径里**（改默认车有专门的端点）：把这件事变成
     *   「不可能一起发生」，比「记得不要一起改」可靠。
     * ★ 即便一个字段都没改，也要推进 `update_time`（后端就是无条件带的）。
     */
    http.put('/api/member/vehicle/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const vehicle = findMine(ctx.db, g.ctx.memberId, id)
        if (!vehicle) return fail(TEXT.VEHICLE_NOT_FOUND)

        const body = (await request.json().catch(() => ({}))) as VehicleUpdateDTO
        const problems: string[] = []
        if (body.plateNo !== undefined && body.plateNo !== null && body.plateNo.length > 16) {
          problems.push('车牌号不能超过 16 个字符')
        }
        if (body.plateColor !== undefined && body.plateColor !== null && (body.plateColor < 1 || body.plateColor > 4)) {
          problems.push('车牌颜色只能是 1蓝、2黄、3绿、4渐变绿')
        }
        if (body.brand !== undefined && body.brand !== null && body.brand.length > 50) {
          problems.push('品牌不能超过 50 个字符')
        }
        if (body.model !== undefined && body.model !== null && body.model.length > 50) {
          problems.push('型号不能超过 50 个字符')
        }
        const capacity = toNumber(body.batteryCapacityKwh)
        if (capacity !== null && capacity < 0) problems.push('电池容量不能为负数')
        if (body.remark !== undefined && body.remark !== null && body.remark.length > 255) {
          problems.push('备注不能超过 255 个字符')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        // ★ 车牌单独一支：它有规范化，混在通用的「非空就覆盖」里容易被人顺手抄走。
        //   `{"plateNo": "   "}` 会规范化成空串，而 SQL 的 `<if>` 判 `!= ''` ——
        //   于是「传了纯空白」和「传 null」等效，都是「不改」。这里照抄。
        if (body.plateNo !== undefined && body.plateNo !== null) {
          const normalized = normalizePlateNo(body.plateNo)
          if (normalized !== '') vehicle.plateNo = normalized
        }
        if (body.plateColor !== undefined && body.plateColor !== null) vehicle.plateColor = body.plateColor
        if (body.brand !== undefined && body.brand !== null) vehicle.brand = body.brand
        if (body.model !== undefined && body.model !== null) vehicle.model = body.model
        if (body.remark !== undefined && body.remark !== null) vehicle.remark = body.remark
        if (capacity !== null) vehicle.batteryCapacityKwh = capacity
        vehicle.updateTime = ctx.db.now()
        return okVoid()
      }),
    ),

    /**
     * 删一辆车。
     *
     * ★★ 顺序：先读（记住它是不是默认车）→ 删 → 按需提升 ★★
     *   删完之后再判断「刚才那辆是不是默认车」已经不可能了 —— 那一行不在了。
     * ★★ 删掉的是默认车时，把**剩下的第一辆**提升为默认 ★★
     *   不补这一步，「有车的车主一定有且只有一辆默认车」这条不变量就断了，
     *   而前端「取列表第一条当默认车」正是依赖它：断了之后下单页默认选中的
     *   车辆是空的，而删车这个动作看起来完全成功。
     * ★ 删的是最后一辆时剩下的列表为空 —— 那时没有车，不变量**前提不成立**，
     *   什么都不用做（不是漏了）。
     */
    http.delete('/api/member/vehicle/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const vehicle = findMine(ctx.db, g.ctx.memberId, id)
        if (!vehicle) return fail(TEXT.VEHICLE_NOT_FOUND)

        const wasDefault = vehicle.isDefault === 1
        ctx.db.vehicles.splice(ctx.db.vehicles.indexOf(vehicle), 1)

        if (wasDefault) {
          // ★ 用和列表同一个排序取「剩下的第一辆」，而不是「id 最大的那辆」：
          //   列表里可能还留着一辆脏数据默认车（多默认车的旧库），那种情况下
          //   真后端提升的是 `listByMemberId` 的第一条（也就是那辆脏数据默认车），
          //   而「id 最大」会得出另一辆 —— 两边从此对不上。
          const rest = vehiclesOf(ctx.db, g.ctx.memberId)
          if (rest.length > 0) {
            rest[0].isDefault = 1
            rest[0].updateTime = ctx.db.now()
          }
        }
        return okVoid()
      }),
    ),

    /**
     * 设为默认车。
     *
     * ★★ 两步：先清旧的，再把这一辆置 1 ★★
     *   只做第二步会留下两辆默认车（`is_default` 上没有唯一约束，数据库拦不住），
     *   而「哪辆是默认车」就变成了「按 id 升序的第一辆」这种隐含规则 ——
     *   表现是车主设了 A、下单页给出的却是 B，全程没有任何报错。
     * ★ 归属校验就是写入条件本身（两步都带 `member_id`），不是一次单独的前置检查 ——
     *   前置检查漏掉时写入仍会成功，而带上归属条件之后漏不掉。
     */
    http.put('/api/member/vehicle/:id/default', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const target = findMine(ctx.db, g.ctx.memberId, id)
        if (!target) return fail(TEXT.VEHICLE_NOT_FOUND)

        const at = ctx.db.now()
        for (const v of ctx.db.vehicles) {
          if (v.memberId === g.ctx.memberId && v.id !== target.id && v.isDefault === 1) {
            v.isDefault = 0
            v.updateTime = at
          }
        }
        target.isDefault = 1
        target.updateTime = at
        return okVoid()
      }),
    ),

    // ══════════════════════════════════════════════════════════════════════
    // 钱包
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 我的钱包。
     *
     * ★★ 没有钱包行时返回**三个 0**，不是报错 ★★
     *   一个没充过值的车主余额就是 0，这是一个正常状态（后端 `WalletVO.empty()`）。
     *   ★ 而且这里**不建行**：读路径上调 `ensureWallet` 会让每个打开过钱包页的人
     *     都多出一行，而那一行没有任何资金含义。真正要动钱的路径
     *     （充值 / 冻结 / 调账）才建 —— `db.walletOf` 就是那个会建行的函数，
     *     所以这里用 `wallets.find` 而不是它，这个区别是有意的。
     */
    http.get('/api/member/wallet', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const wallet = ctx.db.wallets.find((w) => w.memberId === g.ctx.memberId)
        const vo: WalletVO = wallet
          ? toWalletVo(wallet)
          : { balance: 0, frozenAmount: 0, totalRecharge: 0 }
        return ok(vo)
      }),
    ),

    /**
     * 充值（**模拟充值**，没有接真实支付）。
     *
     * ★★ 两条额度限制都要复刻（`@DecimalMin("0.01")` / `@DecimalMax("50000.00")`）★★
     *   Service 层没有任何复查，跳过它们，一笔「充 100 万」在演示里就会成功，
     *   而真后端会拒绝。这类「上限」最容易被当成「校验的事，跟我们 mock 无关」。
     *   ★ `50000` 是**元**，不是分 —— 别把它和库里的分混着写。
     * ★ 金额字段要**先按 `Decimal | null | undefined` 收窄**再判断：接口上的
     *   `Decimal` 是 `string | number`，而 `{"amount": null}` 是能构造出来的
     *   （`@NotNull` 存在的意义就是拦它），两者得分开报「没填」和「填错了」。
     * ★ 返回**充值之后**的钱包：那是车主唯一能立刻看到余额变化的地方，
     *   前端拿它直接刷新，不必再查一次（查两次在弱网下会看到余额来回跳）。
     */
    http.post('/api/member/wallet/recharge', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const body = (await request.json().catch(() => ({}))) as WalletRechargeDTO
        const raw: Decimal | null | undefined = body.amount
        const amount = toNumber(raw)
        const problems: string[] = []
        if (raw === null || raw === undefined || raw === '') {
          problems.push('充值金额不能为空')
        } else if (amount === null || amount < 0.01) {
          // 解析不出数字（`"abc"`）和「小于 1 分」在额度上都是「不够」，归到同一句：
          // 前者在 Spring 里是绑定失败，那句话前端选不到。
          problems.push('充值金额必须大于 0')
        } else if (amount > 50000) {
          problems.push('单笔充值不能超过 50000 元')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        // ★ `db.recharge` = 「加余额 + 累加 totalRecharge + 写一条 `1 充值` 流水」，
        //   与 `WalletServiceImpl.recharge` 一一对应（含 `remark = 充值`）。
        //   它自己还会再判一次金额 > 0 —— 留着，那是「唯一的真源」在兜底。
        const result = ctx.db.recharge(g.ctx.memberId, body.amount)
        if (!result.ok) return fail(result.msg)

        return ok(toWalletVo(ctx.db.walletOf(g.ctx.memberId)))
      }),
    ),

    /**
     * 钱包流水。
     *
     * ★★ 参数**只有** `pageNum` / `pageSize` ★★
     *   后端 `WalletTransactionPageQueryDTO` 里没有 type、没有时间范围 ——
     *   所以「只看充值」这类筛选在这个接口上**做不出来**。
     *   mock 也不接受这些参数：加一个真后端会忽略的筛选，演示时会看到
     *   「筛了没反应」，那比没有筛选器更让人困惑。
     */
    http.get('/api/member/wallet/transactions', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const url = new URL(request.url)
        const badPage = checkPageParams(url.searchParams)
        if (badPage) return badPage

        const rows = ctx.db.walletTxns
          .filter((t) => t.memberId === g.ctx.memberId)
          .sort((a, b) => b.createTime.getTime() - a.createTime.getTime() || b.id - a.id)

        const { pageNum, pageSize } = pageParams(url.searchParams)
        const page: PageResult<ReturnType<typeof toWalletTxnVo>> = paginate(
          rows.map(toWalletTxnVo),
          pageNum,
          pageSize,
        )
        return ok(page)
      }),
    ),

    // ══════════════════════════════════════════════════════════════════════
    // 权益（我的套餐）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 某站点**可购买**的套餐（裸数组）。
     *
     * ★★ `stationId` 缺失时报的是「未知错误」，不是 Service 里那句
     *   「请指定站点…」★★ 这个区别值得写下来：`@RequestParam Long stationId`
     *   是 required 的，缺参时 Spring 在**参数绑定阶段**就抛
     *   `MissingServletRequestParameterException`，它没有专门的 `@ExceptionHandler`，
     *   落到 catch-all 得到 HTTP 200 + `code:0` + 「未知错误」。
     *   Service 层那句是给直接调 Service 的人看的，HTTP 上到不了 ——
     *   照抄「人话」会让这条路径在演示里显示一个真后端永远不会发的提示。
     *
     * ★★ 缺失时**不能**静默放宽成「返回全平台」★★ 那是这个接口最坏的失败方式：
     *   车主在 A 站点看到一张只在 B 站点能用的券，买完到 A 站充电才发现用不了。
     * ★ SQL 是 `WHERE status = 1 AND station_id IN (0, ?)`，排序 `sort ASC, id DESC`。
     *   这两个条件决定了「一张券在哪些站点出现」：抄漏 `0` 会让全平台券
     *   从所有站点的列表里消失（而全平台券恰好是列表里的第一条）。
     *   ★ `null` 的 `station_id`（只可能来自直接改库）在 SQL 里不匹配任何站，
     *     这里也不匹配 —— 不「顺手」把它当成通用券。
     */
    http.get('/api/member/package/available', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const stationId = numParam(new URL(request.url).searchParams, 'stationId')
        if (stationId === undefined) return fail(TEXT.UNKNOWN_ERROR)

        const rows = ctx.db.packages
          .filter((p) => p.status === 1 && (p.stationId === 0 || p.stationId === stationId))
          .sort((a, b) => a.sort - b.sort || b.id - a.id)
        return ok(rows.map(toPackageVo))
      }),
    ),

    /**
     * 购买套餐（**直购**：没有购买单，直接从余额扣款）。
     *
     * ★★ 五步的顺序是承重的（抄自 `doPurchase`）★★
     *   ① 存在 → ② 上架 → ③ 售价合法 → ④ 组装权益（可能因「数据不完整」拒）→ ⑤ 扣钱
     *   · ① 和 ② **必须分开**：对车主是两件不同的事（「换个套餐」vs「你的链接是旧的」）。
     *   · ④ 排在扣钱之前：一个卖不出去的 SKU 不该先动车主的钱。
     *   · ⑤ 在最后：扣钱是唯一不可逆的一步，前面每一步都能给出更具体的理由。
     *   ★ **0 价是合法的**（赠送套餐），负数只可能来自直接改库。
     *   ★ 余额够不够在扣钱处判、且按同一份余额：后端把判断放在 SQL 的
     *     `WHERE balance >= ?` 里（不是先查后扣），mock 是单线程的，
     *     先查后扣与它等价 —— 但**不要**写成「先扣成负数再检查」。
     *
     * ★★ 价格快照必须写在权益行上（`payAmount`）★★
     *   它是退款路径**唯一**的金额来源，不是「退款时再去查 SKU」：SKU 的 price
     *   可编辑，管理员把 100 改成 80 之后，所有按老价格买过的用户都会被按 80 退款。
     *   ★ 0 元套餐写 `0` 而不是 `null`：`null` 的含义是「不知道」（V3 之前的老行），
     *     而作废时 `null` 会直接拒绝操作（算不出该退多少）。两者必须分开。
     *
     * ★ 真后端还有一把 10 秒的 Redis 锁挡重复提交。mock 是单线程的，
     *   那把锁在这里没有对应物，**不假装有**：要演示重复提交，用前端的
     *   `withActionLock`，那是真实存在的那一层。
     */
    http.post('/api/member/package/:packageId/purchase', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const packageId = pathId(params.packageId as string | undefined)
        if (packageId === null) return fail(TEXT.UNKNOWN_ERROR)

        const sku = ctx.db.packages.find((p) => p.id === packageId)
        if (!sku) return fail(TEXT.PACKAGE_NOT_FOUND)
        if (sku.status !== 1) return fail(TEXT.PACKAGE_OFF_SHELF)
        if (sku.price < 0) return fail(packagePriceInvalid(sku.price / 100))

        const benefit = assembleBenefit(sku)
        if (!benefit.ok) return fail(benefit.msg)

        const at = ctx.db.now()
        if (sku.price > 0) {
          const wallet = ctx.db.walletOf(g.ctx.memberId)
          if (wallet.balance < sku.price) return fail(TEXT.BALANCE_NOT_ENOUGH)
          wallet.balance -= sku.price
          wallet.updateTime = at
          ctx.db.recordTxn({
            memberId: g.ctx.memberId,
            // ★ 直购没有购买单，所以 `order_id` 是 null（那张唯一索引
            //   `uk_txn_order_type` 因此按 NULL 处理，不拦第二笔购买）。
            orderId: null,
            type: 6,
            amount: -sku.price,
            // ★ 备注是这笔钱**唯一**的可读线索（没有订单号可查），必须写清买的是什么。
            remark: `购买套餐：${sku.name}`,
            at,
          })
        }

        const row: DbMemberPackage = {
          id: ctx.db.nextId('memberPackage'),
          memberId: g.ctx.memberId,
          packageId: sku.id,
          packageType: sku.packageType,
          // ★ 名称快照：SKU 改名不影响已售出的这一份。
          packageName: sku.name,
          payAmount: sku.price,
          totalKwh: benefit.totalKwh,
          // ★ 剩余量从总量开始，不是从 0 —— 这是「刚买下」的定义。
          remainingKwh: benefit.totalKwh,
          totalMinutes: benefit.totalMinutes,
          remainingMinutes: benefit.totalMinutes,
          totalTimes: benefit.totalTimes,
          remainingTimes: benefit.totalTimes,
          serviceDiscount: benefit.serviceDiscount,
          effectiveFrom: at,
          // ★ `validDays <= 0` 表示永久（约定来自 DDL 的 `0=永久`）。
          expireTime:
            sku.validDays <= 0
              ? (parseServerTime(PERMANENT_EXPIRE_TIME) ?? at)
              : new Date(at.getTime() + sku.validDays * 24 * 3600 * 1000),
          status: 1,
          lockedOrderId: null,
          remark: null,
          createTime: at,
          updateTime: at,
        }
        ctx.db.memberPackages.push(row)
        return ok(toMemberPackageVo(row))
      }),
    ),

    /**
     * 我的权益（裸数组）。
     *
     * ★★ **不过滤状态** ★★ 已用尽 / 已过期 / 已作废的也要显示成灰态 ——
     *   滤掉的话车主会来问客服「我那个 150 度的包去哪了」，而客服在后台查得到：
     *   两边看的是同一个系统，却给出不同的答案。
     * ★ 排序是 `status, expire_time, id`（**不是** id 倒序），
     *   见 `vo.ts` 的 `sortMemberPackages` —— 它和管理端的「某车主的权益」
     *   走的是同一条 SQL，所以顺序是接口契约，不是各页面自己的表现层选择。
     */
    http.get('/api/member/package/mine', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const rows = sortMemberPackages(
          ctx.db.memberPackages.filter((p) => p.memberId === g.ctx.memberId),
        )
        return ok(rows.map(toMemberPackageVo))
      }),
    ),
  ]
}

/**
 * 把 SKU 上的权益字段拷成一份快照 —— `copyBenefit` + `requireBenefit` 的等价实现。
 *
 * ★★ 四种类型各拷各的字段，**非本类型的字段一律留 null** ★★
 *   这是后端注释里点明的「写入形状保证」：残留一个别的类型才看得懂的字段，
 *   会被计费逻辑读走（`applyPackage` 按 type 分支读列），而症状是
 *   **一笔算错的钱**，不是一句报错。
 * ★ 这里返回失败而不是抛异常：调用点只有一处，且就在扣钱之前 ——
 *   一个 early return 比一层 try/catch 更容易看清「钱还没动」。
 * ★ 折扣包先过「缺失 / 必须大于 0」，再过区间判定，与 `copyBenefit` 的先后一致。
 */
function assembleBenefit(sku: DbPackage):
  | {
      ok: true
      totalKwh: number | null
      totalMinutes: number | null
      totalTimes: number | null
      serviceDiscount: number | null
    }
  | { ok: false; msg: string } {
  const none = { totalKwh: null, totalMinutes: null, totalTimes: null, serviceDiscount: null }
  const typeLabel = PACKAGE_TYPE_LABEL[sku.packageType] ?? String(sku.packageType)

  switch (sku.packageType) {
    case 1: {
      if (sku.energyKwh === null) {
        return { ok: false, msg: packageBenefitMissing(typeLabel, 'energy_kwh') }
      }
      if (sku.energyKwh <= 0) {
        return { ok: false, msg: packageBenefitNotPositive(typeLabel, 'energy_kwh', sku.energyKwh) }
      }
      // ★ `kwh()` 对齐到 4 位小数：库列是 decimal(10,4)，VO 出去的就是那个值。
      return { ok: true, ...none, totalKwh: kwh(sku.energyKwh) }
    }
    case 2: {
      if (sku.durationMinutes === null) {
        return { ok: false, msg: packageBenefitMissing(typeLabel, 'duration_minutes') }
      }
      if (sku.durationMinutes <= 0) {
        return {
          ok: false,
          msg: packageBenefitNotPositive(typeLabel, 'duration_minutes', sku.durationMinutes),
        }
      }
      return { ok: true, ...none, totalMinutes: sku.durationMinutes }
    }
    case 4: {
      if (sku.times === null) return { ok: false, msg: packageBenefitMissing(typeLabel, 'times') }
      if (sku.times <= 0) {
        return { ok: false, msg: packageBenefitNotPositive(typeLabel, 'times', sku.times) }
      }
      return { ok: true, ...none, totalTimes: sku.times }
    }
    case 3: {
      if (sku.serviceDiscount === null) {
        return { ok: false, msg: packageBenefitMissing(typeLabel, 'service_discount') }
      }
      if (sku.serviceDiscount <= 0 || sku.serviceDiscount > 1) {
        return { ok: false, msg: packageDiscountInvalid(sku.serviceDiscount) }
      }
      return { ok: true, ...none, serviceDiscount: sku.serviceDiscount }
    }
    default:
      // 未知类型：后端 `PackageType.of` 会抛「未知的套餐类型」，那句在前端选不到。
      // 这里给一句能直接指向数据的话，而不是静默写出一份没有权益的权益。
      return { ok: false, msg: `${TEXT.PACKAGE_FIELD_MISSING}：未知的套餐类型 ${sku.packageType}` }
  }
}

/**
 * `PackageType.getLabel()` 的四个标签。
 *
 * ★ 不 import `constants/dicts.ts` 的 `PACKAGE_TYPE`：那个字典是给 **VO 的
 *   `packageTypeLabel`** 用的（前端显示），而这里是拼**后端报错文本**。
 *   两者今天恰好同字，但一个改的是显示、一个改的是日志，不该绑在一起。
 * ★ 这几句只出现在「数据坏了」的路径上，正常演示永远看不到。
 */
const PACKAGE_TYPE_LABEL: Record<number, string> = {
  1: '电量包',
  2: '时长包',
  3: '服务费折扣包',
  4: '次卡',
}
