/**
 * 订单状态字典 —— **逐字抄自后端 `enumeration/OrderStatus.java`**。
 *
 * ★★ 为什么前端还要抄一遍：后端确实在 VO 里给了 `statusLabel`，
 *   但**筛选下拉框拿不到它**。下拉要的是「全部 9 个状态的中文名」，
 *   而 `statusLabel` 只存在于某一条记录的 VO 上 —— 页面上没有订单时，
 *   下拉就是空的。所以这张表**必须**在前端有一份。
 *
 * ★ 标签**逐字**对齐后端（`3` 是「充电结束待结算」，不是「待结算」）。
 *   前端自作主张缩写会造成「同一个状态在两个页面显示不同的字」，
 *   用户会以为是两个不同的状态。
 *
 * ★★ 三个状态集合是**后端业务规则的一部分**，不是展示细节 ★★
 *   它们的用途见下面各自的注释。抄错的后果不是「显示不好看」，
 *   而是「删除按钮该禁用时没禁用 / 不该禁用时禁用了」。
 */

/** 状态码。用常量而不是裸数字，避免 `status === 5` 这种到处出现的魔数。 */
export const ORDER_STATUS = {
  CREATED: 1,
  CHARGING: 2,
  SETTLING: 3,
  PENDING_PAY: 4,
  PAID: 5,
  PAY_FAILED: 6,
  REFUNDED: 7,
  CANCELLED: 8,
  ABNORMAL: 9,
} as const

export type OrderStatusCode = (typeof ORDER_STATUS)[keyof typeof ORDER_STATUS]

export interface OrderStatusMeta {
  code: OrderStatusCode
  /** 与后端 `OrderStatus.getLabel()` 逐字一致。 */
  label: string
  /**
   * 展示色调。
   *
   * ★ 这几处是**刻意区分**的，改之前先看理由：
   *   · `6 支付失败` 与 `9 异常挂账` 都是危险，但 6 是**浅色 + 可重试**、
   *     9 是**实心底 + 警示图标**。两者靠图标和深浅区分 —— 因为用户在这两个
   *     状态下要做的事完全不同（6 充值后重试即可，9 要找运维）。
   *   · `7 已退款` 用 accent（紫），**刻意不用绿色**：绿色会和「已支付」混淆，
   *     而「退过款」和「付过款」在财务上是对立的。
   *   · `8 已取消` 用最弱的 info + 虚线描边 + 降饱和，让它在列表里视觉后退。
   */
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'accent'
  /** 是否带呼吸动效（**全项目只有「充电中」一个状态有动效**）。 */
  pulse?: boolean
  /** 是否用虚线描边（视觉后退，用于已取消）。 */
  dashed?: boolean
  /** 是否实心填充（只有异常挂账，用于强提醒）。 */
  solid?: boolean
  /** 是否需要在标签上带警示图标。 */
  warnIcon?: boolean
  /** 一句话状态说明，用于详情页顶部的解释（`title` 提示）。 */
  hint: string
}

/** 9 个状态的完整定义。**顺序即展示顺序**（筛选下拉按这个顺序排）。 */
export const ORDER_STATUS_LIST: readonly OrderStatusMeta[] = [
  {
    code: 1,
    label: '待启动',
    tone: 'info',
    hint: '已冻结预估金额，等待设备启动。可以取消。',
  },
  {
    code: 2,
    label: '充电中',
    tone: 'primary',
    pulse: true,
    hint: '设备已上报启动成功，正在充电。',
  },
  {
    code: 3,
    label: '充电结束待结算',
    tone: 'warning',
    dashed: true,
    hint: '枪已停止，账单尚未生成。可能由用户端、定时任务或设备触发结算。',
  },
  {
    code: 4,
    label: '待支付',
    tone: 'warning',
    hint: '账单已生成，等待支付。超过 15 分钟会被定时任务转为「支付失败」。',
  },
  {
    code: 5,
    label: '已支付',
    tone: 'success',
    hint: '已完成收款。此后只能退款，不能再次收款。',
  },
  {
    code: 6,
    label: '支付失败',
    tone: 'danger',
    hint: '扣款未成功，冻结的金额仍在。充值后可以重新支付。',
  },
  {
    code: 7,
    label: '已退款',
    tone: 'accent',
    hint: '终态。退款金额已回到车主余额。',
  },
  {
    code: 8,
    label: '已取消',
    tone: 'info',
    dashed: true,
    hint: '终态。取消前已释放冻结的金额。',
  },
  {
    code: 9,
    label: '异常挂账',
    tone: 'danger',
    solid: true,
    warnIcon: true,
    hint: '系统算不出结果，已挂起等待人工处理。不会自动重试。',
  },
]

const BY_CODE = new Map<number, OrderStatusMeta>(ORDER_STATUS_LIST.map((s) => [s.code, s]))

/** 取状态元信息。未知码返回 `undefined` —— **由调用方决定怎么显示**。 */
export function orderStatusMeta(code: number | null | undefined): OrderStatusMeta | undefined {
  if (code === null || code === undefined) return undefined
  return BY_CODE.get(code)
}

/**
 * 取中文标签。未知码返回 `状态${code}` 而不是空串或 `-`。
 *
 * ★ 为什么要露出原始码：后端加了新状态而前端没跟上时，
 *   显示「状态10」能立刻让人知道是「前端字典没更新」；
 *   显示 `-` 或空白则会被当成「这条数据坏了」，排查方向直接跑偏。
 */
export function orderStatusLabel(code: number | null | undefined): string {
  if (code === null || code === undefined) return '-'
  return BY_CODE.get(code)?.label ?? `状态${code}`
}

/** 筛选下拉的选项。 */
export const ORDER_STATUS_OPTIONS = ORDER_STATUS_LIST.map((s) => ({
  label: s.label,
  value: s.code as number,
}))

// ── 三个状态集合（照抄后端 OrderStatus 的三个方法）─────────────────────────

/**
 * 终态：`7 已退款` / `8 已取消`。对应后端 `isTerminal()`。
 *
 * ★ 用它而不是到处写 `status === 7 || status === 8` —— 加了新终态会漏掉，
 *   而漏掉的那处表现为「已退款的订单还能被取消」。
 */
export const TERMINAL_STATUSES: readonly number[] = [7, 8]

export function isTerminal(code: number | null | undefined): boolean {
  return code !== null && code !== undefined && TERMINAL_STATUSES.includes(code)
}

/**
 * 还占着**一把枪**。对应后端 `holdsConnector()` → `{1,2,3}`。
 *
 * ★ 用途：H5 选枪时，「这把枪上有未结束的订单」的判断就是它。
 *   注意 `3 充电结束待结算` 也在里面 —— 枪停了但没结算，
 *   这时枪在物理上已经可以拔了，但订单还占着它，**不能拿来开新单**。
 */
export const HOLDING_CONNECTOR_STATUSES: readonly number[] = [1, 2, 3]

/**
 * 还占着**车主的钱**（冻结中，或已扣但可退）。对应后端 `holdsMoney()` → `{1,2,3,4,6}`。
 *
 * ★★ `6 支付失败` **在里面**：扣款失败后那笔钱**仍然冻着**，既没扣掉也没退回。
 *   漏掉它的话，「钱还冻着的订单」和这个集合就对不上了 ——
 *   而挂账正是冲着这类订单去的。
 */
export const HOLDING_MONEY_STATUSES: readonly number[] = [1, 2, 3, 4, 6]

export function holdsMoney(code: number | null | undefined): boolean {
  return code !== null && code !== undefined && HOLDING_MONEY_STATUSES.includes(code)
}

/**
 * 还会**妨碍删除站点 / 充电桩**。对应后端 `blocksDeviceDeletion()` → `{1,2,3,4,6,9}`。
 *
 * ★★ 这是第三個集合，和上面两个都不一样，用错会静默失败。★★
 *
 *   判据是 `holdsMoney()` **或** `9 异常挂账`（挂起等人工核销）。
 *   **不是「非终态」** —— 那个说法差一个 `5 已支付`。
 *
 * ★★ 为什么前端要关心它：站点/充电桩的删除按钮。★★
 *   后端 `StationServiceImpl` 删除前会查这个集合，命中就拒绝。
 *   前端如果用它来**提前禁用删除按钮并说明原因**，用户就不会
 *   「点删除 → 转一圈 → 弹一句我不懂的错」。
 *
 * ★ `5 已支付` **不在**里面，所以**不要**因为「这个站点有历史订单」就禁用删除 ——
 *   已支付订单只是历史快照，它不阻止删除。这正是后端注释里那个
 *   真实 bug（曾经写成 `!isTerminal()` → 任何完成过一次充电的站点永久不可删）的教训。
 */
export const BLOCKING_STATUSES: readonly number[] = [1, 2, 3, 4, 6, 9]

export function blocksDeviceDeletion(code: number | null | undefined): boolean {
  return code !== null && code !== undefined && BLOCKING_STATUSES.includes(code)
}

/**
 * 待支付超时窗口：**15 分钟**（与后端定时任务口径一致）。
 * 扫描间隔 5 分钟 —— 所以「到点」不等于「立刻变状态」。
 */
export const PENDING_PAY_MINUTES = 15
