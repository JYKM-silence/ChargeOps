/**
 * 订单事件字典。
 *
 * ★★ 这是全项目**仅有的两处「后端不给 label」**之一（另一处是 `fromStatus`/`toStatus`），
 *   所以必须由前端补。已核实（`ChargingOrderEventVO.java`）：
 *
 *     · `eventType`  —— **字符串**枚举（`create` / `start` / …），**没有 label**
 *     · `fromStatus` / `toStatus` —— **裸 Integer**，**没有 label**
 *     · `source` —— 有 `sourceLabel` ✔（`EventSource` 1~5）
 *     · `detail` —— 有，且是「这笔订单为什么变成这样」的**唯一线索**
 *
 *   注意 `eventType` 是**字符串**而不是数字 —— 别按数字枚举去写。
 *
 * ★★ `abnormal` 有**两种含义**，只看 `eventType` 会渲染错 ★★
 *
 *   已核实：余额不足时支付失败，订单 `4 → 6`，但写进去的 `eventType`
 *   仍然是 `abnormal`。也就是说 `abnormal` 同时表示：
 *
 *     · 结算/启动阶段的真异常 → 转入 `9 异常挂账`
 *     · 支付阶段余额不足     → 转入 `6 支付失败`（**不是异常**，用户充值即可）
 *
 *   这两件事对用户的意义完全相反（一个是「找运维」、一个是「去充值」），
 *   所以**必须结合 `toStatus` 一起判断**。见 `describeOrderEvent()`。
 */

import { orderStatusLabel } from './orderStatus'

/** `OrderEventType` 的字符串值。**是字符串，不是数字。** */
export const ORDER_EVENT_TYPE = {
  CREATE: 'create',
  START: 'start',
  END: 'end',
  SETTLE: 'settle',
  PAY: 'pay',
  REFUND: 'refund',
  CANCEL: 'cancel',
  ABNORMAL: 'abnormal',
  DUPLICATE_DROPPED: 'duplicate_dropped',
} as const

export type OrderEventTypeCode = (typeof ORDER_EVENT_TYPE)[keyof typeof ORDER_EVENT_TYPE]

interface EventTypeMeta {
  label: string
  /** 时间轴圆点的色调。 */
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'accent'
  /** 默认复述句（`detail` 为空时用）。 */
  phrase: string
}

/**
 * 事件类型 → 展示信息。
 *
 * ★ 文案口径：这里的是**兜底复述**，只有 `detail` 为空时才显示。
 *   后端写过 `detail` 的时候**一律显示 `detail`** —— 它才是事实，
 *   我们这些短语是猜的（见 `describeOrderEvent`）。
 */
const EVENT_META: Record<string, EventTypeMeta> = {
  [ORDER_EVENT_TYPE.CREATE]: { label: '创建订单', tone: 'info', phrase: '订单已创建，预估金额已冻结' },
  [ORDER_EVENT_TYPE.START]: { label: '启动充电', tone: 'primary', phrase: '设备已启动，开始充电' },
  [ORDER_EVENT_TYPE.END]: { label: '结束充电', tone: 'primary', phrase: '充电结束，账单生成中' },
  [ORDER_EVENT_TYPE.SETTLE]: { label: '结算', tone: 'warning', phrase: '账单已生成' },
  [ORDER_EVENT_TYPE.PAY]: { label: '支付', tone: 'success', phrase: '支付成功' },
  [ORDER_EVENT_TYPE.REFUND]: { label: '退款', tone: 'accent', phrase: '退款已原路返回余额' },
  [ORDER_EVENT_TYPE.CANCEL]: { label: '取消订单', tone: 'info', phrase: '订单已取消，冻结金额已释放' },
  [ORDER_EVENT_TYPE.ABNORMAL]: { label: '异常', tone: 'danger', phrase: '出现异常，已挂起等待人工处理' },
  [ORDER_EVENT_TYPE.DUPLICATE_DROPPED]: {
    label: '重复上报已忽略',
    tone: 'info',
    // ★ 这条事件是「什么都没发生」的记录，它的存在本身是好事
    //   （说明后端挡住了重复上报），所以色调是最弱的 info，不吓人。
    phrase: '收到重复的设备上报，已忽略（订单状态未改变）',
  },
}

/** 事件类型的中文标签。未知类型返回原字符串 —— 露出原始值便于发现后端新增。 */
export function orderEventLabel(eventType: string | null | undefined): string {
  if (!eventType) return '-'
  return EVENT_META[eventType]?.label ?? eventType
}

export function orderEventTone(eventType: string | null | undefined): EventTypeMeta['tone'] {
  if (!eventType) return 'info'
  return EVENT_META[eventType]?.tone ?? 'info'
}

/** 事件在时间轴上是否算「真的推进了状态」。 */
export function isNoopEvent(eventType: string | null | undefined): boolean {
  return eventType === ORDER_EVENT_TYPE.DUPLICATE_DROPPED
}

export interface OrderEventLike {
  eventType?: string | null
  fromStatus?: number | null
  toStatus?: number | null
  detail?: string | null
}

export interface DescribedEvent {
  /** 主标题：事件类型的中文名。 */
  title: string
  /** 副标题：**优先用后端的 `detail`**，其次才是状态迁移描述。 */
  description: string
  tone: EventTypeMeta['tone']
  /** 状态迁移是否有效（`from === to` 或缺值时不算）。 */
  hasTransition: boolean
}

/**
 * 把一条事件渲染成「标题 + 说明」。
 *
 * ★★ 为什么说明**优先取 `detail`** 而不是自己拼状态迁移：★★
 *   `detail` 是后端在那一刻写下的**原始原因**（设备报文、异常信息）。
 *   我们自己拼的「4 → 6」只说明「变成什么」，而用户真正要知道的是
 *   「**为什么**变成这样」—— 而那个信息**只存在于 `detail` 里**。
 *   把它换掉等于用一句猜测覆盖唯一的事实，而且看起来更专业、更不容置疑。
 *
 * ★ `abnormal` 的两种含义在这里被拆开：`toStatus === 6` 说明是
 *   「余额不足导致支付失败」，不是真异常 —— 文案要说「余额不足，充值后可重新支付」，
 *   而不是「异常挂账，请联系运维」。**这两句话会把用户导向完全不同的动作。**
 */
export function describeOrderEvent(event: OrderEventLike): DescribedEvent {
  const { eventType, fromStatus, toStatus, detail } = event
  const title = orderEventLabel(eventType)
  const tone = orderEventTone(eventType)
  const hasTransition =
    typeof fromStatus === 'number' && typeof toStatus === 'number' && fromStatus !== toStatus

  const trimmedDetail = detail?.trim()

  // ① 有 detail 就用 detail —— 它是后端写下的事实
  if (trimmedDetail) {
    return { title, description: trimmedDetail, tone, hasTransition }
  }

  // ② 没有 detail：abnormal 按 toStatus 分流（★ 见文件头注释）
  if (eventType === ORDER_EVENT_TYPE.ABNORMAL) {
    if (toStatus === 6) {
      return {
        title: '余额不足',
        description: '扣款未成功，冻结金额仍在。充值后可重新支付。',
        tone: 'danger',
        hasTransition,
      }
    }
    return {
      title,
      description: '系统无法算出结算结果，已挂起等待人工处理（不会自动重试）。',
      tone,
      hasTransition,
    }
  }

  // ③ 其余用兜底短语
  return {
    title,
    description: EVENT_META[eventType ?? '']?.phrase ?? '状态已更新',
    tone,
    hasTransition,
  }
}

/**
 * 状态迁移的展示串：`待启动 → 充电中`。
 *
 * ★ 这里就是 `fromStatus`/`toStatus` 没有 label 的补偿 —— 用
 *   `orderStatusLabel()` 把裸数字翻译成中文。
 * ★ 缺值时返回 `null` 而不是「? → ?」：首条事件（`create`）的 `fromStatus`
 *   就是 null，显示成「? → 待启动」很难看，显示成「创建订单」就够了。
 */
export function transitionText(
  fromStatus: number | null | undefined,
  toStatus: number | null | undefined,
): string | null {
  if (typeof fromStatus !== 'number' || typeof toStatus !== 'number') return null
  if (fromStatus === toStatus) return null
  return `${orderStatusLabel(fromStatus)} → ${orderStatusLabel(toStatus)}`
}
