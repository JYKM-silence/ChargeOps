/**
 * 车主端订单。
 *
 * ★★ 这个文件里每一个方法都对应订单状态机的一次真实流转 ★★
 *   状态码见 `shared/constants/orderStatus.ts`；
 *   能做什么由 `orderRules.canDo(action, status, 'member')` 决定，
 *   **不要在页面里写 `status === 2`** —— 规则表是单一真源。
 *
 * ★★ 后端没有幂等键 ★★
 *   `pay` 与 `create` 都是「点两次就真的执行两次」的接口，
 *   调用点必须套 `useSubmitLock` / `withActionLock`（见 shared/http/locks.ts）。
 */

import { http } from './http'
import type {
  ChargingOrderDetailVO,
  ChargingOrderVO,
  OrderCreateDTO,
  OrderPageQuery,
  Paged,
} from '@chargeops/shared'

const BASE = '/api/member/orders'

/**
 * 建单（并**按预估价冻结余额**）。
 *
 * ★★ 余额支付是「建单即冻结」，不是「结算时才扣」★★
 *   所以建单失败的最常见原因是**余额不足**，而不是枪不可用。
 *   两种失败的提示方向完全不同（一个去充值、一个换把枪），
 *   调用方要能区分 —— 但**后端只给一句中文 msg**，所以只能按文案分流。
 *
 * ★★ `available` 是快照不是预订 ★★
 *   列表上看到枪空闲 → 点进来 → 别人先占了 → 这里返回 `code:0`。
 *   这是**正常的并发结果，不是 bug**，UI 要提示「换一把」并**刷新枪列表**，
 *   而不是弹一个原始错误就完事。
 */
export function createOrder(dto: OrderCreateDTO): Promise<ChargingOrderVO | null> {
  return http.post<ChargingOrderVO>(BASE, dto)
}

/**
 * 我的订单分页。
 *
 * ★★ **只有 `pageNum` / `pageSize` / `status`** ★★
 *   没有任何时间范围或站点筛选 —— 「查上个月的记录」在 H5 上做不到。
 *   别放一个点了没反应的日期筛选器，直接在页面上写明。
 */
export function pageOrders(query: OrderPageQuery): Promise<Paged<ChargingOrderVO>> {
  return http.page<ChargingOrderVO>(BASE, query)
}

/**
 * 订单详情（带 `segments` 与 `events`）。
 *
 * ★ `events` 里带着「余额不足」那一条（`eventType` 是 `abnormal`、
 *   `toStatus` 是 6）—— 那是「为什么支付失败」的唯一解释来源。
 */
export function getOrder(id: number | string): Promise<ChargingOrderDetailVO | null> {
  return http.get<ChargingOrderDetailVO>(`${BASE}/${id}`)
}

/**
 * 取消订单。**无请求体**。
 *
 * ★ 只有「还没开始充电」的订单能取消（规则表里 `cancel` 的来源状态很窄）。
 * ★ 取消会**释放冻结金额**，所以是资金动作 —— 要二次确认。
 */
export function cancelOrder(id: number | string): Promise<null> {
  return http.post<null>(`${BASE}/${id}/cancel`)
}

/**
 * 支付。**无请求体**。支付方式在建单时就定了。
 *
 * ★★ 「支付失败」(6) **可以重新支付**，不是死路 ★★
 *   后端的 CAS 是 `status IN (4, 6)` —— 已核实。所以 6 的订单
 *   只要余额够了就能再 `pay` 一次变成 5。
 *   → 详情页对 6 必须有「重新支付」按钮，而不是只引导充值。
 *
 * ★★ 余额不足时后端**自己**把订单 4 → 6 ★★
 *   但写进去的 `eventType` 是 `abnormal`。所以事件流里 `abnormal`
 *   有两种含义，渲染必须结合 `toStatus`（见 `describeOrderEvent`）。
 *
 * ★★ 待支付有 15 分钟窗口，超时被定时任务扫成 6 ★★
 *   本地倒计时到点**不等于立刻变状态**（5 分钟一轮），
 *   所以文案要说「预计」，不能说「已超时」。
 */
export function payOrder(id: number | string): Promise<null> {
  return http.post<null>(`${BASE}/${id}/pay`)
}
