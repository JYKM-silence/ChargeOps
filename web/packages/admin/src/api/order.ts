/**
 * 订单接口（管理端）。
 *
 * ★★ 这一组接口的性质：**它是「设备上报」的替身，不是普通的业务 CRUD** ★★
 *
 *   本项目没有设备接入层（没有 MQTT / TCP 长连接、没有设备证书）。
 *   真实系统里 start / end 这两件事由充电桩自己推上来，写入路径完全不经过 HTTP。
 *   这里把它们挂在管理端，由运维**代为录入设备看到的数据**。
 *
 *   这个前提直接决定了 UI 的形态：
 *     · 表单里填的是「设备读数」，不是「业务参数」—— 所以字段旁要写清读数从哪看
 *     · 操作者是人，会手滑 —— 所以每个动作都要二次确认
 *     · 后端把它们记进了 `charging_order_event`，**留痕是这套设计的前提**
 *
 * ★★ 四个动作**都不动钱**（除了 refund 是往外送钱）★★
 *   `start` / `end` / `settle` 只改状态与金额计算，钱在建单时就冻结了。
 *   所以「启动充电」不需要资金级确认，但 `refund` 需要。
 */

import { http } from './http'
import type {
  AdminOrderDetailVO,
  AdminOrderPageQuery,
  AdminOrderVO,
  OrderEndDTO,
  OrderRefundDTO,
  OrderStartDTO,
  Paged,
  ServerTime,
} from '@chargeops/shared'

const BASE = '/api/admin/orders'

/**
 * 订单分页查询。
 *
 * ★★ 调用方**必须**带上近 7 天的时间范围 ★★
 *   后端注释原话：「不带任何条件时是一次全表 filesort」——
 *   排序是 `create_time DESC`，而没有 `status` 条件时
 *   `idx_order_status_create` 索引用不上。
 *   这不是性能优化，是**不加就查不出来**（会超时）。
 *   默认值统一从 `defaultOrderRange()` 取，页面里不要自己算日期。
 */
export function pageOrders(query: AdminOrderPageQuery): Promise<Paged<AdminOrderVO>> {
  return http.page<AdminOrderVO>(BASE, query)
}

/**
 * 订单详情。
 *
 * ★ `events` 里带着 `abnormal` 那一行，而 `detail` 字段有**具体原因** ——
 *   一笔卡在「9 异常挂账」的订单，「它为什么变成 9」的答案只在那里。
 *   这是排查的唯一依据，所以详情页的事件时间轴不是装饰，是核心功能。
 */
export function getOrder(id: number | string): Promise<AdminOrderDetailVO | null> {
  return http.get<AdminOrderDetailVO>(`${BASE}/${id}`)
}

/**
 * 启动充电（1待启动 → 2充电中）。
 *
 * ★★ `startMeterKwh` 可空，且**缺数据时不要填 0** ★★
 *   它是**累计读数**（电表从出厂到现在的总度数），不是本次充电量。
 *   填 0 会让 `totalEnergyKwh = end - 0 = 整个电表读数`，
 *   算出一笔几百度的订单 —— 而**这个错误不会报错**。
 *   留空的话结算会因为缺数据进异常挂账，那是一个有人会去看的失败。
 *   → 表单里这个字段允许留空，且要写明「不知道就留空，不要填 0」。
 */
export function startOrder(id: number | string, dto: OrderStartDTO): Promise<null> {
  return http.post<null>(`${BASE}/${id}/start`, dto)
}

/**
 * 结束充电（2充电中 → 3待结算）。
 *
 * ★★ 结果**不一定是 3** ★★
 *   电量 = 结束读数 − 起始读数。读数缺失或倒退时，「充电结束了」这个事实
 *   照常落库，但订单会紧接着转 **9 异常挂账**并带上原因。
 *   → 调用方**不要假设成功后状态是 3**，必须重新拉一次详情看实际结果。
 *
 * ★ `endTime` 可空。空 = 服务端取 `now()`，但那是**降级不是等价** ——
 *   计费窗口用哪个时间会直接改变金额（跨尖峰/平谷边界时差一个电价档）。
 *   有设备上报时间就填上。
 */
export function endOrder(id: number | string, dto: OrderEndDTO): Promise<null> {
  return http.post<null>(`${BASE}/${id}/end`, dto)
}

/**
 * 结算（3待结算 → 4待支付）。**无请求体**。
 *
 * ★★ 失败时订单**留在 3**，不是坏事 ★★
 *   电价规则有缺口时后端抛异常、整个事务回滚，订单留在 3 可以重试。
 *   后端注释：只有人工判断「重试也不会好」时才该转 9。
 *   → 所以结算失败的提示应该是「检查该站点的计费模板是否覆盖了全天 24 小时」，
 *     并给一个重试入口，而不是引导用户去挂账。
 */
export function settleOrder(id: number | string): Promise<null> {
  return http.post<null>(`${BASE}/${id}/settle`)
}

/**
 * 退款（5已支付 → 7已退款）。
 *
 * ★★ 「待支付」不能退款 ★★
 *   那时候钱还没扣，要退回的是**冻结**，走的是取消 / 挂账，不是退款。
 *   按钮的可用性由 `orderRules.canDo('refund', status, 'admin')` 决定，别自己判。
 *
 * ★★ 后端只校验绝对上限（0.01 ~ 100000.00），**没有校验 ≤ 实付金额** ★★
 *   也就是说可以退超过实付的钱。前端必须自己挡 ——
 *   用 `isRefundableAmount(value, payAmount)`，误差容忍见该函数注释。
 */
export function refundOrder(id: number | string, dto: OrderRefundDTO): Promise<null> {
  return http.post<null>(`${BASE}/${id}/refund`, dto)
}

/** 订单动作的返回类型别名，方便页面写函数签名。 */
export type OrderActionCall = (id: number | string) => Promise<unknown>

/** 详情页刷新时用。把 `ServerTime` 再导出，省得页面多 import 一次。 */
export type { ServerTime }
