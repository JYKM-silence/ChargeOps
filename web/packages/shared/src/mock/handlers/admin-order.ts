/**
 * 管理端订单域：列表、详情、以及四个**状态机动作**（start / end / settle / refund）。
 *
 * ★★ 这一组接口的本质：**它们是「设备上报」的替身，不是普通 CRUD** ★★
 *   本项目没有设备接入层（没有 MQTT、没有设备证书），真实系统里
 *   「开始充电 / 结束充电」由充电桩自己推上来、写入路径完全不经过 HTTP。
 *   这里把它们挂在管理端，由运维**代为录入设备看到的数据**。
 *   这个前提决定了 mock 的两件事：
 *     ① 入参是**设备读数**（累计电表值），所以 `startMeterKwh` 可空是
 *        **一条合法路径**，不是容错 —— 见下面的 start handler；
 *     ② 每个动作都会写一行 `charging_order_event`。**留痕是这套设计的前提**：
 *        详情页的事件时间轴是排查「这单为什么变成 9」的唯一依据。
 *
 * ★★ 四个动作**都不动钱**（refund 除外）★★
 *   `start` / `end` / `settle` 只改状态与算账，钱在建单时就冻结了；
 *   `refund` 是唯一一笔往外送的钱。所以 mock 里只有 refund 碰钱包 ——
 *   在 start 里「顺手」动一下余额，会让对账演示里多出一笔谁也解释不了的流水。
 *
 * ★ 与 `member-order.ts` 的分工：那边是车主视角（只有自己的单），
 *   这里是全平台视角，且多出 start / end / settle 三个运维专用动作。
 *   两者共用 `order-rules.ts` 里的建单前置检查，共用 `db.ts` 的状态机。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type { AdminOrderDetailVO, AdminOrderVO, OrderEndDTO, OrderRefundDTO, OrderStartDTO } from '../../types/admin'
import type { PageResult } from '../../http/types'
import { envelope, fail, guardStaff, missingRequired, numParam, ok, okVoid, paginate, pageParams, checkPageParams, strParam, timeParam, toNumber } from './core'
import type { MockContext } from './core'
import { MEMBER_PREFIX_TOO_BROAD_FULL, TEXT } from './texts'
import { toAdminOrderDetailVo, toAdminOrderVo } from './vo'
import { tsRequired } from '../db'
import type { MockDb } from '../db'
import { parseServerTime } from '../../utils/datetime'

/**
 * `ChargingOrderServiceImpl.MEMBER_PREFIX_LIMIT`。
 *
 * ★★ 为什么是「拒绝」而不是「截断到 500 条」★★
 *   截断会给出一个**看起来完全正常**的错误答案：列表有数据、分页也对、
 *   一条错都不报，而搜订单的运维不会去数「是不是还有更多」。
 *   ★ 取满也拒绝，不区分「正好 500」和「超过 500」—— 两种情况该做的事
 *     都是「请多输入几位数字」。
 * ★ 500 这个数不是算出来的，是「正常使用下永远碰不到」的量级：
 *   一个足以定位到人的前缀（`1380000`）通常只命中个位数。
 */
const MEMBER_PREFIX_LIMIT = 500

/** 路径参数里的 id。见 `admin-member.ts` 的 `pathId` —— 非数字会变成「未知错误」。 */
function pathId(raw: string | undefined): number | null {
  if (raw === undefined) return null
  const n = Number(raw)
  return Number.isFinite(n) && Number.isInteger(n) ? n : null
}

/**
 * 手机号前缀的解析结果。
 *
 * ★★ 四种情况**必须分开表达**，合并任意两种都会产生一个静默的错误 ★★
 *   `all`  没传手机号            → **不过滤**，返回全部订单
 *   `none` 传了但一个都没匹配到  → **返回 0 行**（SQL 里是 `AND 1 = 0`）
 *   `ids`  收窄到这些车主
 *   `broad`命中过多              → 报错，让用户多输几位
 *
 *   `all` 和 `none` 看起来只差一点，结论却**相反**：把「没匹配到」当成
 *   「不过滤」，就是一个看起来完全正常的错误 —— 搜一个不存在的手机号，
 *   返回**全平台**的订单，列表有数据、分页也对、一条错都不报。
 *   用「有没有传参数」之外的信息去区分这两者，迟早会有人把它们合到一起，
 *   所以这里用四个具名的 kind，让 `switch` 必须写全。
 */
type PhoneScope =
  | { kind: 'all' }
  | { kind: 'none' }
  | { kind: 'broad' }
  | { kind: 'ids'; ids: number[] }

/** 把手机号前缀解析成车主范围。语义见 `PhoneScope`。 */
function resolvePhoneScope(db: MockDb, phonePrefix: string | undefined): PhoneScope {
  // ★ `trim()` 是必要的：`" 138"` 匹配不到任何手机号，而用户会以为
  //   「这个人没有订单」（前端的 `cleanParams` 也会丢掉纯空白串）。
  if (phonePrefix === undefined || phonePrefix.trim() === '') return { kind: 'all' }

  const matched = db.members
    .filter((m) => m.phone.startsWith(phonePrefix.trim()))
    .slice(0, MEMBER_PREFIX_LIMIT)
  // ★ 触到上限即拒绝 —— 后端就是 `LIMIT 500` 之后判 `size() >= 500`，
  //   所以「真实的匹配数正好是 500」也一样被拒绝。抄的是这个**行为**，
  //   不是它「应该」的样子：多加一个「再查一条看看是不是刚好」的技巧，
  //   mock 就会放行一个真后端会拒绝的查询。
  if (matched.length >= MEMBER_PREFIX_LIMIT) return { kind: 'broad' }
  if (matched.length === 0) return { kind: 'none' }
  return { kind: 'ids', ids: matched.map((m) => m.id) }
}

export function adminOrderHandlers(ctx: MockContext): RequestHandler[] {
  return [
    /**
     * 订单分页。
     *
     * ★★ `createTimeFrom/To` 是**闭区间**（`>=` / `<=`），且格式是
     *   `yyyy-MM-dd HH:mm:ss`（**不是 ISO**）★★
     *   后端拿它直接和 `datetime(3)` 列比较；写成 ISO（`2026-09-21T00:00:00`）
     *   在 MySQL 里是**能解析**的，所以不会报错、只会筛错 —— 这正是要用
     *   `parseServerTime`（全项目唯一的时间解析入口）而不是 `new Date()` 的原因：
     *   后者在 Safari 上对空格分隔的串返回 `Invalid Date`，
     *   而 `Invalid Date` 参与比较**恒为 false**，表现是「加了时间范围，一条都没筛掉」。
     *
     * ★ 排序 `create_time DESC, id DESC`：第二个键不是装饰 ——
     *   同一秒建的两笔单靠 `create_time` 分不出先后，而分页是**逐页查库**的，
     *   排序不稳定会让同一条记录在两页里各出现一次（或一次都不出现）。
     *   mock 是「先全量排序再切片」，天然稳定，但仍照抄两个键。
     */
    http.get('/api/admin/orders', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const url = new URL(request.url)
        const badPage = checkPageParams(url.searchParams)
        if (badPage) return badPage

        const orderNo = strParam(url.searchParams, 'orderNo')
        const status = numParam(url.searchParams, 'status')
        const stationId = numParam(url.searchParams, 'stationId')
        const memberPhone = strParam(url.searchParams, 'memberPhone')
        const from = timeParam(url.searchParams, 'createTimeFrom')
        const to = timeParam(url.searchParams, 'createTimeTo')

        const scope = resolvePhoneScope(ctx.db, memberPhone)
        if (scope.kind === 'broad') return fail(MEMBER_PREFIX_TOO_BROAD_FULL)

        const rows = ctx.db.orders
          .filter((o) => (orderNo ? o.orderNo.startsWith(orderNo) : true))
          .filter((o) => (status !== undefined ? o.status === status : true))
          .filter((o) => (stationId !== undefined ? o.stationId === stationId : true))
          // ★ `none` → 0 行，**不是**不过滤；`all` 才是不过滤。
          .filter((o) =>
            scope.kind === 'all' ? true : scope.kind === 'none' ? false : scope.ids.includes(o.memberId),
          )
          .filter((o) => (from ? o.createTime.getTime() >= from.getTime() : true))
          .filter((o) => (to ? o.createTime.getTime() <= to.getTime() : true))
          .sort((a, b) => b.createTime.getTime() - a.createTime.getTime() || b.id - a.id)

        const { pageNum, pageSize } = pageParams(url.searchParams)
        const page: PageResult<AdminOrderVO> = paginate(
          rows.map((o) => toAdminOrderVo(ctx.db, o)),
          pageNum,
          pageSize,
        )
        return ok(page)
      }),
    ),

    /**
     * 订单详情 —— 比列表多出「分段」和「事件」两段。
     *
     * ★ 派生字段（车主手机号 / 站点名）查不到就是 null，**不报错**：
     *   车主注销、站点被删都不该让一笔**真实存在**的订单打不开。
     *   mock 里没有「注销车主」这条路径，但这个约定要保住 ——
     *   一旦这里改成「查不到就报错」，将来加注销功能时订单页会整片崩掉。
     */
    http.get('/api/admin/orders/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const order = ctx.db.orderById(id)
        if (!order) return fail(TEXT.ORDER_NOT_FOUND)

        const vo: AdminOrderDetailVO = toAdminOrderDetailVo(ctx.db, order)
        return ok(vo)
      }),
    ),

    /**
     * 启动充电（1 待启动 → 2 充电中）。
     *
     * ★★ `startMeterKwh` **可空**，而且缺数据时**不要填 0** ★★
     *   它是电表的**累计读数**（出厂到现在的总度数），不是本次充电量。
     *   填 0 的后果是 `totalEnergyKwh = end - 0 = 整个电表读数`，
     *   也就是**一笔几百度的账单，而且不报错**。
     *   留空则会让结算因缺数据进异常挂账 —— 那是一个**有人会去看的失败**。
     *   → 这是两种失败之间的取舍，mock 必须两条都留得出来，
     *     所以 `db.markStarted` 的入参是可空的（见 db.ts 上的注释）。
     *
     * ★ 不动钱：钱在建单时已经冻好了。这个端点连钱包都不碰 ——
     *   在这里「顺手」补一笔流水会让对账演示里多出一条谁也解释不了的记录。
     */
    http.post('/api/admin/orders/:id/start', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as OrderStartDTO
        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        if (!ctx.db.orderById(id)) return fail(TEXT.ORDER_NOT_FOUND)

        const result = ctx.db.markStarted(id, toNumber(body.startMeterKwh), toNumber(body.startSoc))
        if (!result.ok) return fail(result.msg)
        return okVoid()
      }),
    ),

    /**
     * 结束充电（2 充电中 → 3 待结算，**或者 → 9 异常挂账**）。
     *
     * ★★ 结果**不一定是 3** ★★
     *   电量 = 结束读数 − 起始读数。读数缺失或倒退时，「充电结束了」这个
     *   物理事实照常落库（`end_time` 先写进去），订单紧接着转 **9** 并带上原因。
     *   这一步在 `db.markEnded` 里，**不是** handler 的职责 ——
     *   「结束」和「挂账」必须由同一个 CAS 会话决定，分两处写会出现
     *   「end 成功了但没人去挂账」的中间态。
     *
     * ★★ `endTime` **不校验先后关系** ★★
     *   传一个和 `startTime` 同一秒（或更早）的时刻，`end` 会**成功**，
     *   而失败会推迟到 `settle`（那一步才做「结束必须晚于开始」的检查）。
     *   这是后端的真实行为，mock **不许修**：修了的话「end 成功 → settle 报
     *   计费结束时间必须晚于开始时间」这条排查路径在演示里就永远走不到，
     *   而那正是运维最容易踩的一个坑（设备时钟没校准）。
     *
     * ★ `stopReason` 可空，且 **NULL 有信息量**（设备没上报）——
     *   事件详情里会渲染成「未上报」，见 `db.ts` 的 `describeStopReason` 复刻。
     */
    http.post('/api/admin/orders/:id/end', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as OrderEndDTO
        // ★ DTO 的 `@NotNull` 先于 Service 跑 —— 所以「读数没填」比「订单不存在」
        //   先报出来。顺序反了的话，运维会以为自己的单号填错了。
        const missing = missingRequired([[body.endMeterKwh, '结束电表读数不能为空']])
        if (missing) return missing

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        if (!ctx.db.orderById(id)) return fail(TEXT.ORDER_NOT_FOUND)

        const endTime = body.endTime ? parseServerTime(body.endTime) : null
        const result = ctx.db.markEnded(id, {
          endMeterKwh: toNumber(body.endMeterKwh) ?? 0,
          endSoc: toNumber(body.endSoc),
          stopReason: toNumber(body.stopReason),
          endTime,
        })
        if (!result.ok) return fail(result.msg)
        return okVoid()
      }),
    ),

    /**
     * 结算（3 待结算 → 4 待支付）。**无请求体**。
     *
     * ★★ 失败时订单**留在 3**，这不是坏事 ★★
     *   电价规则有缺口时后端抛异常、**整个事务回滚**：分段不写、枪不释放、
     *   次数不累计。订单一动不动地停在 3，可以重试。
     *   → 所以这里的失败提示应当是「检查该站点的计费模板是否覆盖全天 24 小时」，
     *     而不是引导用户去挂账。挂在 9 是**人工判断**之后的事，不是自动行为。
     *
     * ★★ 下面那段起止时间检查**不能挪进 `db.markSettled`**，也不能删 ★★
     *   它复刻的是 `BillingRequest` 的紧凑构造器：`endTime` 和 `startTime`
     *   都截断到整秒**之后**再比大小。截秒这一步是承重的 ——
     *   「10:00:00.500 ~ 10:00:00.900」满足「结束晚于开始」，截到整秒后两者相等。
     *   截秒前放行的话，请求会走到计费引擎、产出 0 个分段，
     *   最后炸在「分段未铺满窗口」的内部断言上（HTTP 500）——
     *   而它明明是一个可以指名的入参问题。
     *   ★ 这条只可能在这里被触发：`end` 不校验先后关系（见上），
     *     所以「同一秒内开始又结束」的订单是能被造出来的。
     */
    http.post('/api/admin/orders/:id/settle', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        const order = ctx.db.orderById(id)
        if (!order) return fail(TEXT.ORDER_NOT_FOUND)

        // ★ 三个字段齐全时才做时间检查：缺电量/缺时间那两条是 `bill()` 里更早的
        //   判断，由 `db.markSettled` 自己报（文案在 db.ts 里，和 bill() 逐字对齐）。
        //   在这里再判一次会把那两句正确的消息顶掉。
        if (order.totalEnergyKwh !== null && order.startTime !== null && order.endTime !== null) {
          const floorSec = (d: Date) => Math.floor(d.getTime() / 1000)
          const from = floorSec(order.startTime)
          const to = floorSec(order.endTime)
          if (to <= from) {
            // ★ 时间用 shared 的 `tsRequired` 渲染（`yyyy-MM-dd HH:mm:ss`），
            //   而后端那句里是 Java 的 `LocalDateTime.toString()`
            //   （`2026-09-21T10:00:00.500`）。**只差渲染，判据和边界一致** ——
            //   这句话前端不做内容匹配，所以不值得为它造一个 Java 时间渲染器。
            return fail(
              `计费结束时间必须晚于开始时间：${tsRequired(order.startTime)} ~ ${tsRequired(order.endTime)}` +
                `（截断到整秒后为 ${tsRequired(new Date(from * 1000))} ~ ${tsRequired(new Date(to * 1000))}）`,
            )
          }
          if (to - from > 30 * 24 * 3600) {
            return fail(
              `计费跨度超过 30 天，疑似时间字段填错：${tsRequired(new Date(from * 1000))} ~ ${tsRequired(new Date(to * 1000))}`,
            )
          }
        }

        const result = ctx.db.markSettled(id)
        if (!result.ok) return fail(result.msg)
        return okVoid()
      }),
    ),

    /**
     * 退款（5 已支付 → 7 已退款）。
     *
     * ★★ 「待支付」不能退款 ★★
     *   那时候钱还没扣，要退的是**冻结**，走的是取消 / 挂账而不是退款 ——
     *   所以 CAS 的谓词是 `status = 5`，一笔 4 待支付的单会收到
     *   「订单当前状态不允许该操作」。
     *
     * ★★ 后端**没有**校验「退款金额 ≤ 实付金额」★★
     *   只有 `@DecimalMin(0.01)` / `@DecimalMax(100000)` 两道绝对边界。
     *   也就是说，真后端允许退超过实付的钱。mock **照抄这个缺口**，
     *   不在这里补一道 —— 补了的话，前端那套 `isRefundableAmount` 的
     *   限制逻辑就永远得不到验证，而它存在的全部理由就是后端不管这件事。
     *
     * ★ 顺序：DTO 校验 → 订单存在 → CAS → 钱包加钱。DTO 在 Controller 上，
     *   所以「金额没填」比「订单不存在」先报。
     */
    http.post('/api/admin/orders/:id/refund', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const body = (await request.json().catch(() => ({}))) as OrderRefundDTO
        const amount = toNumber(body.amount)
        // ★ 这里**不能**复用 `missingRequired`：金额的三种错（缺 / 太小 / 太大）
        //   在 DTO 上是三个不同的注解、三句不同的话，但都要按字段顺序拼成一句。
        const problems: string[] = []
        if (body.amount === null || body.amount === undefined || body.amount === '') {
          problems.push('退款金额不能为空')
        } else if (amount !== null) {
          if (amount < 0.01) problems.push('退款金额必须大于 0')
          else if (amount > 100000) problems.push('退款金额超出上限，请确认是否填错')
        }
        if (problems.length > 0) return fail(problems.join('；'))

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)
        if (!ctx.db.orderById(id)) return fail(TEXT.ORDER_NOT_FOUND)

        // ★ `markRefunded` 一次做完 CAS 5→7、加钱、写 `5 退款` 流水、记事件 ——
        //   和 `WalletServiceImpl.refund` 的位置关系一致（钱动在 CAS **之后**）。
        const result = ctx.db.markRefunded(id, body.amount)
        if (!result.ok) return fail(result.msg)
        return okVoid()
      }),
    ),
  ]
}
