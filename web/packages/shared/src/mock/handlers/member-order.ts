/**
 * 车主端订单：建单 / 我的订单 / 详情 / 取消 / 支付。
 *
 * ★★ 这五条是车主端唯一「会动钱」的一组，所以每一条的鉴权/状态组合都不一样 ★★
 *   · `create` / `pageMine` / `getMine` / `cancel`：`assertActive` + 归属校验；
 *   · `pay`：**既不查账号状态，也不校验归属** —— 两条都不是漏写，
 *     前者是后端注释里写明的（补上它会制造一个死结），
 *     后者是后端的一个真实缺口（见 `pay` handler 上的注释）。
 *   把这五条按「同一种模式」复制粘贴，一定会在这两条上出错。
 *
 * ★ 建单的十步（站点/桩/枪 → 车辆 → 模板 → 估算 → 建单 → 抢枪 → 锁权益 →
 *   冻钱 → 事件）里，**只有前四步和「锁哪一份权益」在这里**：
 *   从「建单」往后的所有写入都在 `db.createOrder` 里，和种子的路径共用同一段代码。
 *   在这里再走一遍那几步的话，mock 就会出现两条建单路径，而其中一条永远不会被演示验证。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type { OrderCreateDTO } from '../../types/member'
import type { PageResult } from '../../http/types'
import { assertActive, checkPageParams, envelope, fail, guardMember, missingRequired, numParam, ok, okVoid, paginate, pageParams } from './core'
import type { MockContext } from './core'
import { TEXT, vehicleBusy } from './texts'
import { describeConnectorProblem, estimateFreezeCents, resolveTemplateIdAt } from './order-rules'
import { toMemberOrderDetailVo, toMemberOrderVo } from './vo'

/** `PayMethod.BALANCE` —— 本项目唯一走得通的支付方式。 */
const PAY_METHOD_BALANCE = 1
/** `PayMethod.WECHAT` / `ALIPAY` 的标签，只用于那句「暂未支持」的后缀。 */
const PAY_METHOD_LABEL: Record<number, string> = { 2: '微信', 3: '支付宝' }

/**
 * 路径参数里的 id。见 `admin-member.ts` 的同名函数。
 *
 * ★ 非数字 → 「未知错误」：后端 `Long orderId` 收到 `abc` 会在参数绑定阶段
 *   抛 `MethodArgumentTypeMismatchException`，它落到 catch-all，
 *   而不是这个接口自己的「订单不存在」。
 */
function pathId(raw: string | undefined): number | null {
  if (raw === undefined) return null
  const n = Number(raw)
  return Number.isFinite(n) && Number.isInteger(n) ? n : null
}

export function memberOrderHandlers(ctx: MockContext): RequestHandler[] {
  return [
    /**
     * 建单。
     *
     * ★★ 九步的顺序是承重的（逐条抄 `ChargingOrderServiceImpl.create`）★★
     *   ① DTO 必填 → ② 账号状态 → ③ 支付方式 → ④ 站点/桩/枪三层 →
     *   ⑤ 车辆（可选）→ ⑥ 解析计费模板 → ⑦ 估算冻结额 → ⑧ 建单+抢枪+锁权益+冻钱 → ⑨ 事件
     *   · ③ 在 ④ 之前：一个「微信支付」的请求不该先被枪的状态拦住，
     *     那是**两件不同的事**（一个要改支付方式、一个要换一把枪）。
     *   · ⑥ 在 ⑦ 之前：估算要用模板里的最贵单价，没有模板就没有价格。
     *   · ⑧ 内部的顺序（抢枪 → 锁权益 → 冻钱）不能挪，见 `db.createOrder` 的注释。
     *
     * ★★ 站点此刻没有生效的计费方案 = **建单失败**，不是「用默认价」★★
     *   `resolveTemplateIdAt` 抛的是 `STATION_TARIFF_NOT_BOUND`，mock 照做。
     *   回退到一个没人批准过的价格是**静默的资金错误**：车主按 A 价充了电、
     *   账单按 B 价出，而两边都不会报错。
     *
     * ★★ 车辆可空（扫码即充）★★ 没绑车也能充：订单的 `vehicleId` / `plateNo`
     *   都是 null，详情页显示「未绑定车辆」—— 那是一个**合法状态**，
     *   不是数据缺失。写成必填会让「扫码即充」这条最常用的路径直接走不通。
     *   有车时校验两件事：**车是我的**（`getByIdAndMember`）、
     *   **这辆车没有在充**（`listHoldingByVehicle`）—— 少了后者，
     *   同一辆车能同时挂着两笔满额订单，而设备只充了一次电。
     *
     * ★ 冻结额是**上界**（容量 × 最贵单价，封顶 500 元），不是估计值 ——
     *   见 `estimateFreezeCents`。它冻的是**下单这一刻的价**：订单上存的是
     *   `tariffTemplateId`，结算时按那个模板算，即使运营中途改了价。
     */
    http.post('/api/member/orders', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const body = (await request.json().catch(() => ({}))) as OrderCreateDTO
        // ① 顺序 = DTO 字段声明顺序（stationId → chargerId → connectorId）。
        const missing = missingRequired([
          [body.stationId, '站点不能为空'],
          [body.chargerId, '充电桩不能为空'],
          [body.connectorId, '充电枪不能为空'],
        ])
        if (missing) return missing

        // ③ 支付方式。★ `null` 当余额（后端 `PayMethod.of(code == null ? BALANCE : ...)`），
        //   而**不是**「没选就报错」—— 前端不传时就是要用默认的余额。
        const payMethod = body.payMethod ?? PAY_METHOD_BALANCE
        if (payMethod !== PAY_METHOD_BALANCE) {
          const label = PAY_METHOD_LABEL[payMethod]
          // ★ 未实现的支付方式必须**明确拒绝**，不能静默当成余额：
          //   一个「微信支付」按钮点了之后从余额扣钱，比没有这个按钮严重得多。
          //   ★ 未知码值（比如 9）走「未知的支付方式：9」—— 那是 `PayMethod.of` 抛的，
          //     和「暂未支持」是两句话：前者是前端传错了，后者是功能没做。
          return fail(
            label === undefined
              ? `未知的支付方式：${payMethod}`
              : `${TEXT.ORDER_PAY_METHOD_UNSUPPORTED}：${label}`,
          )
        }

        // ④ 站点/桩/枪：存在性 → 归属关系 → 桩可用 → 枪空闲 → 枪上没有进行中的订单。
        //    错误文案都在 `order-rules.ts` 里（那一份也被种子数据用着）。
        const problem = describeConnectorProblem(
          ctx.db,
          body.stationId,
          body.chargerId,
          body.connectorId,
        )
        if (problem) return fail(problem.msg)

        // ⑤ 车辆（可选）。
        let vehicleId: number | null = null
        let batteryKwh: number | null = null
        if (body.vehicleId !== undefined && body.vehicleId !== null) {
          const vehicle = ctx.db.vehicles.find(
            (v) => v.id === body.vehicleId && v.memberId === g.ctx.memberId,
          )
          if (!vehicle) return fail(TEXT.VEHICLE_NOT_FOUND)

          // ★ 判据是 `holdingConnectorCodes()`（{1,2,3}）而不是「有没有未完结的订单」：
          //   4 待支付 / 6 支付失败的订单**不再占枪**，那辆车已经拔了枪，
          //   拿它再建一笔新单是合法的。
          const holding = ctx.db.orders.find(
            (o) => o.memberId === g.ctx.memberId && o.vehicleId === body.vehicleId
              && (o.status === 1 || o.status === 2 || o.status === 3),
          )
          if (holding) return fail(vehicleBusy(holding.orderNo))

          vehicleId = vehicle.id
          batteryKwh = vehicle.batteryCapacityKwh
        }

        // ⑥ 解析此刻生效的计费模板（`status = 1` + 半开区间 + 三层排序）。
        const at = ctx.db.now()
        const templateId = resolveTemplateIdAt(ctx.db, body.stationId, at)
        if (templateId === null) return fail(TEXT.STATION_TARIFF_NOT_BOUND)

        // ⑦ 估算冻结额：`max(电池容量, 60 度)` × 模板里最贵的一条（电费 + 服务费），封顶 500 元。
        const frozenAmount = estimateFreezeCents(ctx.db, batteryKwh, templateId)

        // ⑧ 锁哪一份权益 —— 在这里选，锁定动作在 `createOrder` 里（它才拿得到新订单 id）。
        const memberPackageId = pickPackageToLock(ctx, g.ctx.memberId, at)

        const created = ctx.db.createOrder({
          memberId: g.ctx.memberId,
          vehicleId,
          stationId: body.stationId,
          chargerId: body.chargerId,
          connectorId: body.connectorId,
          tariffTemplateId: templateId,
          payMethod,
          frozenAmount,
          memberPackageId,
          at,
        })
        // ★ 失败只有两种：枪在「检查 → 抢枪」之间被别人抢走（真并发），
        //   或者冻钱时余额不足。两种都由 `createOrder` 回滚干净，订单不会残留。
        if (!created.ok) return fail(created.msg)
        // ★ 纯类型层的一道门：`ok` 时 `createOrder` 一定带回了订单
        //   （它只有两条失败返回，且都带 msg）。走到这里说明 db.ts 被改坏了，
        //   给一个明确的失败比让下面那行读到 undefined 更像话。
        if (!created.order) return fail(TEXT.UNKNOWN_ERROR)

        return ok(toMemberOrderVo(created.order))
      }),
    ),

    /**
     * 我的订单（分页）。
     *
     * ★ `status` 可空 = 全部订单。★ 一次只能筛一个状态：后端的查询对象里
     *   就是一个 `Integer status`，「待支付 + 充电中」这种组合要靠前端分标签页。
     * ★ 排序 `create_time DESC, id DESC` —— 末尾的 `id` 是为了**全序**：
     *   同一毫秒建的两笔订单在两次请求里的相对顺序必须一致，
     *   否则翻页时会出现重复行或漏行。
     */
    http.get('/api/member/orders', ({ request }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const url = new URL(request.url)
        const badPage = checkPageParams(url.searchParams)
        if (badPage) return badPage

        const status = numParam(url.searchParams, 'status')

        const rows = ctx.db.orders
          .filter((o) => o.memberId === g.ctx.memberId)
          .filter((o) => (status === undefined ? true : o.status === status))
          .sort((a, b) => b.createTime.getTime() - a.createTime.getTime() || b.id - a.id)

        const { pageNum, pageSize } = pageParams(url.searchParams)
        const page: PageResult<ReturnType<typeof toMemberOrderVo>> = paginate(
          rows.map(toMemberOrderVo),
          pageNum,
          pageSize,
        )
        return ok(page)
      }),
    ),

    /**
     * 订单详情（含分段与事件）。
     *
     * ★★ 查不到和「不是你的」返回**同一句**「订单不存在」★★
     *   分开会让这个接口变成「这个订单 id 存在吗」的探测器 ——
     *   而订单 id 是连续自增的，撞库的成本极低。
     *   归属校验写在查询条件里（`id = ? AND member_id = ?`），不是先查再判：
     *   那样「忘了判断」还能写得出来，而写进 WHERE 之后就写不出来了。
     */
    http.get('/api/member/orders/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)

        const order = ctx.db.orders.find((o) => o.id === id && o.memberId === g.ctx.memberId)
        if (!order) return fail(TEXT.ORDER_NOT_FOUND)

        return ok(toMemberOrderDetailVo(ctx.db, order))
      }),
    ),

    /**
     * 取消订单。
     *
     * ★★ 只有 `1 待启动` 能取消 ★★ 让「充电中」也走这条路等于给了一条
     *   「充完电把订单作废」的路径 —— 那不是取消，是偷电。
     *   状态判断不在 handler 里做，由 `db.markCancelled` 的 CAS
     *   （`WHERE status = 1`）裁决，失败时返回的是**带前后状态的那句话**：
     *   「订单 SO-xxx 当前是「2 充电中」，该操作要求「1 待启动」并转入「8 已取消」…」
     *   —— 那句话是用户和客服判断「这是并发（刷新重试即可）还是用错了流程」的唯一依据。
     *
     * ★★ 一次取消要放三样东西，缺一样都会留下痕迹 ★★
     *   ① 解冻的钱（0 元不写流水，避免对账噪音）；
     *   ② 那把枪（不放的话它会永远停在「2 已占用」上再也点不动 ——
     *      而这笔订单从头到尾没充过电）；
     *   ③ 权益锁（**余量不动**：取消不该消耗任何权益）。
     *   这三步都在 `db.markCancelled` 里，和「转异常挂账」共用同一条释放路径。
     */
    http.post('/api/member/orders/:id/cancel', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const denied = assertActive(ctx, g.ctx.memberId)
        if (denied) return denied

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)

        const order = ctx.db.orders.find((o) => o.id === id && o.memberId === g.ctx.memberId)
        if (!order) return fail(TEXT.ORDER_NOT_FOUND)

        const result = ctx.db.markCancelled(id)
        if (!result.ok) return fail(result.msg)
        return okVoid()
      }),
    ),

    /**
     * 支付（余额支付，没有第三方网关）。
     *
     * ★★ 这是车主端**唯一**不查账号状态的订单接口，照抄后端 ★★
     *   补上它会制造一个解不开的死结：被禁用的车主手上若有一笔「4 待支付」，
     *   钱正冻在钱包里，而 —— `cancel` 被 `assertActive` 挡住、
     *   `refund` 的 CAS 谓词是 `status = 5` 够不着「4」——
     *   `pay` 再挡住的话，**这笔冻结资金就没有任何一条路能出去**。
     *   ★ 放行的方向也是对的：支付是**了结旧账**（电已经充进去了），
     *     不是产生新消费。真正要挡的是「被禁用了还能再建单」，那在 `create` 上。
     *
     * ★★ 这个接口**不校验订单归属** —— 这是后端的真实缺口，mock 不替它补 ★★
     *   后端 `pay(orderId)` 调的是 `loadOrder`（只按 id 查，没有 `member_id` 条件），
     *   所以任何一个登录着的车主都能对别人的订单发起支付，扣的是**别人的**余额。
     *   它不泄露任何数据（响应是空的）、只有拿得到订单 id 才做得到，
     *   但它确实是一个缺口。mock 照抄的理由是「演示时要看到的每个行为都和真后端一致」：
     *   在这里补一道真后端没有的校验，会让前端在某天真的接上后端时表现不同，
     *   而那种差异正是 mock 最不该制造的东西。
     *   ★ 归属校验只有在**查详情**时才有（`GET /orders/:id`），前端也是先拿到自己的
     *     订单 id 才会去点支付 —— 所以这条路径在演示里永远走不到。
     *
     * ★ 支付的四种结果，全部由 `db.markPaid` 决定：
     *   CAS 失败（不是 4/6）→ 带前后状态的冲突句；
     *   余额不足 → 「结算失败：可用余额不足以支付 …」，**整笔回滚**
     *     （订单退回 4、钱退回冻结），**紧接着再由这里记一次「支付失败」**；
     *   成功 → 解冻 + 扣款 + 写两条流水 + 一条 `pay` 事件。
     *   ★ 「4 待支付」的账单在 15 分钟后会被定时任务标成「6 支付失败」，
     *     而 6 **也能付**（充值后重付），所以 CAS 的谓词是 `status IN (4, 6)`。
     *
     * ★★ 余额不足那一步**必须分两次写**，这是后端 `OrderController.pay` 的形状 ★★
     *   后端：`try { pay() } catch (BalanceNotEnoughException e) { markPayFailed(id, e.getMessage()); throw e; }`
     *   —— 失败的 `pay` 已经整体回滚（订单回到 4），「这次尝试失败了」是在
     *   **另一个事务**里记的：订单 4 → 6 + 一条 `abnormal` 事件
     *   （`source` 仍然是 3 定时任务，不是用户端 —— 后端写死了 `EventSource.SCHEDULED`）。
     *   ★ `markPayFailed` 的 CAS 谓词是 `status = 4`，所以**重付一笔 6 又失败**时它什么都不做
     *     （不会多出一条事件）—— 后端 `rows == 0` 时也只是打个日志。
     *   ★ 顺序也不能反：先记失败再回滚的话，回滚会把刚写的事件一起抹掉。
     */
    http.post('/api/member/orders/:id/pay', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardMember(ctx, request)
        if (!g.ok) return g.response

        const id = pathId(params.id as string | undefined)
        if (id === null) return fail(TEXT.UNKNOWN_ERROR)

        const order = ctx.db.orderById(id)
        if (!order) return fail(TEXT.ORDER_NOT_FOUND)

        const result = ctx.db.markPaid(id)
        if (!result.ok) {
          if (result.insufficientBalance) ctx.db.markPayFailed(id, result.msg)
          return fail(result.msg)
        }
        return okVoid()
      }),
    ),
  ]
}

/**
 * 挑一份**可以给这笔订单锁上**的权益，没有就返回 `null`。
 *
 * ★★ 三层顺序，一层都不能省 ★★
 *   ① 按 `PackageType` 的**枚举顺序**挑类型（电量包 → 时长包 → 服务费折扣包 → 次卡）；
 *   ② 同一类型内按「先到期的先用」（`ORDER BY expire_time, id`）；
 *   ③ 整个类型里一份都没有时**换下一个类型**，不能跳出循环 ——
 *      枚举顺序把电量包排在最前，一个只持有次卡的用户如果在这里 break，
 *      他的权益会被**静默忽略**（订单照常建、钱照常冻），
 *      而他看到的是「我的套餐没用上」。真正的「只锁一份」由找到后立刻返回保证。
 *
 * ★ 只选不锁：锁定要写新订单的 id，而那个 id 在 `createOrder` 之前不存在。
 *   并发下「选中的这一份刚被别人抢走」由 `createOrder` 里的
 *   `lockedOrderId === null && status === 1` 兜住（顺序不对就退回纯余额支付）。
 *
 * ★ 判据逐条抄 `MemberPackageMapper.listUsable`：状态、未锁定、
 *   `effectiveFrom <= now`、`expireTime > now`，外加**按类型各自的余量列 > 0**。
 *   少判一条的后果都是「一份已经用光的包被锁上」，而结算时它抵扣 0 元 ——
 *   订单正常、钱正常，只有车主发现套餐白买了。
 */
function pickPackageToLock(ctx: MockContext, memberId: number, at: Date): number | null {
  /** `PackageType.values()` 的顺序 —— 电量包优先，服务费折扣包在次卡之前。 */
  const typeOrder = [1, 2, 3, 4]

  for (const type of typeOrder) {
    const candidates = ctx.db.memberPackages
      .filter((p) => p.memberId === memberId && p.packageType === type && p.status === 1)
      .filter((p) => p.lockedOrderId === null)
      // ★ SQL 是 `effective_from <= now AND expire_time > now`，两列都可空、
      //   而 `NULL` 参与比较**恒不成立**。所以这里也把 null 判成「不能用」，
      //   而不是「没设开始时刻 = 立即生效」—— 后者会让一份真后端上永远选不中的
      //   权益在 mock 里被锁上，两边对不上。
      .filter((p) => p.effectiveFrom !== null && p.effectiveFrom.getTime() <= at.getTime())
      .filter((p) => p.expireTime !== null && p.expireTime.getTime() > at.getTime())
      .filter((p) => {
        // ★ 折扣包没有「余量」这一对列（它不是消耗品，是有效期内的修饰符），
        //   所以它的余量条件恒成立 —— 给它编一个 total_discount 是错的。
        if (type === 1) return (p.remainingKwh ?? 0) > 0
        if (type === 2) return (p.remainingMinutes ?? 0) > 0
        if (type === 4) return (p.remainingTimes ?? 0) > 0
        return true
      })
      .sort((a, b) => {
        const ae = a.expireTime?.getTime() ?? 0
        const be = b.expireTime?.getTime() ?? 0
        return ae - be || a.id - b.id
      })

    if (candidates.length > 0) return candidates[0].id
  }
  return null
}
