/**
 * 订单状态机动作表 —— **所有操作按钮的唯一依据**。
 *
 * ★★ 每个 `from` 都是照 `ChargingOrderMapper.xml` 的条件更新抄的 ★★
 *   后端每次流转都是 `UPDATE ... WHERE id = ? AND status = <来源>`，
 *   影响 0 行就拒绝。所以前端**能提前知道**哪些动作会成功：
 *   把来源集合抄在这里，就不会出现「点了按钮才被拒绝」。
 *
 * | 动作    | 后端语句            | 来源        | 目标      |
 * |---------|---------------------|-------------|-----------|
 * | start   | `markStarted`       | `1`         | 2         |
 * | end     | `markEnded`         | `2`         | 3         |
 * | settle  | `markSettled`       | `3`         | 4         |
 * | pay     | `markPaid`          | **`4, 6`**  | 5 或 6    |
 * | refund  | `markRefunded`      | `5`         | 7         |
 * | cancel  | `markCancelled`     | `1`         | 8         |
 * | abnormal| `markAbnormal`      | `1,2,3,4,6` | 9         |
 *
 * ★★ `pay` 的来源是 `4, 6` —— **支付失败(6)可以重新支付** ★★
 *   所以 H5 的「支付失败」订单必须有**重新支付**按钮。
 *   如果只按 `status === 4` 显示支付按钮，用户充值之后就再也付不了了 ——
 *   钱冻着、订单卡在 6，只能找客服。
 *
 * ★★ `cancel` 的来源**只有 `1`**，这是刻意的 ★★
 *   后端注释原话：让「2 充电中」也能取消，等于给了一条「充完电把订单作废」的路径 ——
 *   车已经被充过、电已经用掉，而订单被抹掉。**那不是取消，是偷电。**
 *   所以 H5 上「充电中」的订单**没有取消按钮**，想提前走要去站点结束充电。
 */

import { ORDER_STATUS, orderStatusLabel } from '../constants/orderStatus'

export type OrderAction = 'start' | 'end' | 'settle' | 'refund' | 'cancel' | 'pay' | 'markAbnormal'

export type End = 'admin' | 'member'

export interface ActionRule {
  action: OrderAction
  label: string
  /** 允许的**来源**状态。空数组表示不由人工触发（如 abnormal）。 */
  from: readonly number[]
  /** 目标状态。`pay` 是条件性的，用函数表达。 */
  to: number | 'conditional'
  /** 哪个端有这个动作。 */
  ends: readonly End[]
  /** 是否必须二次确认（资金动作 / 不可逆）。 */
  confirm: boolean
  /** 危险动作 —— 用红色按钮。 */
  danger?: boolean
  /** 请求体是否必填（决定要不要弹表单）。 */
  body: 'none' | 'optional' | 'required'
  /** 一句话说明「这个动作做了什么」。 */
  hint: string
  /** 不可用时的解释（用于按钮 tooltip）。 */
  unavailableReason: string
}

const RULES: readonly ActionRule[] = [
  {
    action: 'start',
    label: '启动充电',
    from: [ORDER_STATUS.CREATED],
    to: ORDER_STATUS.CHARGING,
    ends: ['admin'],
    confirm: false,
    body: 'optional',
    hint: '设备上报启动成功后订单进入「充电中」。',
    unavailableReason: '只有「待启动」的订单可以启动。',
  },
  {
    action: 'end',
    label: '结束充电',
    from: [ORDER_STATUS.CHARGING],
    to: ORDER_STATUS.SETTLING,
    ends: ['admin'],
    confirm: true,
    body: 'required',
    hint: '结束电表读数是**必填**的，账单按它算电量。',
    unavailableReason: '只有「充电中」的订单可以结束。',
  },
  {
    action: 'settle',
    label: '结算',
    from: [ORDER_STATUS.SETTLING],
    to: ORDER_STATUS.PENDING_PAY,
    ends: ['admin'],
    confirm: true,
    // ★ 无请求体 —— 结算走的是电价规则，不接受人工输入金额。
    body: 'none',
    hint: '按订单开始时刻的电价规则生成账单。成功后进入「待支付」。',
    unavailableReason: '只有「充电结束待结算」的订单可以结算。',
  },
  {
    action: 'pay',
    label: '支付',
    from: [ORDER_STATUS.PENDING_PAY, ORDER_STATUS.PAY_FAILED],
    to: 'conditional',
    ends: ['member'],
    confirm: true,
    // 支付不需要请求体：金额取自订单账单，扣款方式固定走余额
    body: 'none',
    hint: '从余额扣款。余额不足会转入「支付失败」，充值后可再次支付。',
    unavailableReason: '只有「待支付」或「支付失败」的订单可以支付。',
  },
  {
    action: 'refund',
    label: '退款',
    from: [ORDER_STATUS.PAID],
    to: ORDER_STATUS.REFUNDED,
    ends: ['admin'],
    confirm: true,
    danger: true,
    body: 'required',
    hint: '退款金额不得超过实付金额。这是「已支付」订单唯一能做的操作。',
    unavailableReason: '只有「已支付」的订单可以退款。',
  },
  {
    action: 'cancel',
    label: '取消订单',
    from: [ORDER_STATUS.CREATED],
    to: ORDER_STATUS.CANCELLED,
    ends: ['member'],
    confirm: true,
    danger: true,
    body: 'none',
    hint: '取消会释放冻结的金额。',
    unavailableReason: '只有「待启动」的订单可以取消；已经开始充电的订单不能取消。',
  },
  {
    action: 'markAbnormal',
    label: '转入异常挂账',
    // 与 `OrderStatus.holdingMoneyCodes()` / `markAbnormal` 的 IN 集合一致
    from: [1, 2, 3, 4, 6],
    to: ORDER_STATUS.ABNORMAL,
    // ★ 后端没有开放这个端点（只由 Service/定时任务调用），保留定义是为了
    //   前端能**解释**「为什么这笔订单变成了 9」，不是用来触发它。
    ends: [],
    confirm: true,
    danger: true,
    body: 'required',
    hint: '把「钱还冻着但账结不掉」的订单挂起，等人工处理。需要填写原因。',
    unavailableReason: '当前状态不在可挂账的范围内。',
  },
]

const BY_ACTION = new Map<OrderAction, ActionRule>(RULES.map((r) => [r.action, r]))

export function actionRule(action: OrderAction): ActionRule {
  const rule = BY_ACTION.get(action)
  if (!rule) throw new Error(`未定义的动作：${action}`)
  return rule
}

/** 某个状态下，某个端**可以做**的动作。 */
export function availableActions(status: number | null | undefined, end: End): ActionRule[] {
  if (status === null || status === undefined) return []
  return RULES.filter((r) => r.ends.includes(end) && r.from.includes(status))
}

/** 某个动作在当前状态下是否可用。 */
export function canDo(action: OrderAction, status: number | null | undefined, end: End): boolean {
  if (status === null || status === undefined) return false
  const rule = actionRule(action)
  return rule.ends.includes(end) && rule.from.includes(status)
}

/**
 * 为什么某个动作现在不可用 —— 给按钮的 tooltip 用。
 *
 * ★ 把「按钮为什么是灰的」写出来，比灰着不解释强得多：
 *   灰按钮不解释时，用户会怀疑是权限问题或者页面坏了。
 */
export function whyNot(action: OrderAction, status: number | null | undefined): string {
  const rule = actionRule(action)
  // 带上「现在是什么状态」：只说「只有待启动的订单可以取消」时，
  // 用户还得自己回去找当前状态是什么，多一步推理。
  const current = orderStatusLabel(status)
  if (current === '-') return rule.unavailableReason
  return `当前状态「${current}」不能执行此操作。${rule.unavailableReason}`
}

/**
 * 动作的接口路径。两端前缀不同，所以在这里统一拼。
 *
 * ★ 这些路径逐条对应后端 Controller：
 *   admin  → `/api/admin/orders/{id}/{action}`
 *   member → `/api/member/orders/{id}/{action}`
 *   `pay` / `cancel` 是车主端专有，`start` / `end` / `settle` / `refund` 是管理端专有。
 */
export function actionPath(action: OrderAction, orderId: number | string, end: End): string {
  const prefix = end === 'admin' ? '/api/admin/orders' : '/api/member/orders'
  return `${prefix}/${orderId}/${action}`
}

/**
 * 支付后的**可能**结果。
 *
 * ★ 前端**无法预知**支付会成功还是失败 —— 余额够不够由后端在事务里判，
 *   而且判定因素（冻结金额）前端看不到。
 *   所以支付按钮点击后不能乐观地改成「已支付」，必须**等响应**：
 *   响应的 `status` 是 5 就是成功、6 就是失败。这一条决定了
 *   支付流程不能做乐观更新（见 http/locks.ts 的「四层防重」第 4 层）。
 */
export const PAY_OUTCOME_HINT =
  '支付结果由后端返回：余额充足则进入「已支付」，不足则转入「支付失败」并保留冻结金额。'
