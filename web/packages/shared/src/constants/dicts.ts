/**
 * 全部简单枚举字典 —— **逐条抄自 `com.chargeops.enumeration`**。
 *
 * ★ 后端在 VO 上给了 10 个 `*Label` 字段，凡是**能拿到 VO** 的地方就该用它、
 *   别来这里查。这个文件服务的是三类 VO 覆盖不到的场合：
 *
 *     1. **筛选下拉框** —— 需要「全部取值」，而 VO 只描述某一条记录
 *     2. **表单下拉框** —— 新建时还没有 VO
 *     3. **事件时间轴** —— `eventType` / `fromStatus` / `toStatus` 后端不给 label
 *
 * ★ 每一条的标签都与后端 `getLabel()` **逐字一致**。不要「顺手优化」措辞：
 *   同一个状态在两处显示不同的字，用户会以为是两个不同的状态。
 */

import type { Tone } from './tone'

export interface DictItem<T extends number> {
  code: T
  label: string
  /** 展示色调。省略时按 `info` 处理。 */
  tone?: Tone
  /** 一句话解释，用于 `title` 提示和空态说明。 */
  hint?: string
}

export interface Dict<T extends number> {
  /** 全量列表。**顺序即展示顺序**。 */
  list: readonly DictItem<T>[]
  byCode: (code: number | null | undefined) => DictItem<T> | undefined
  /** 取标签。未知码返回 `未知(${code})`，**露出原始值**便于发现后端新增枚举。 */
  label: (code: number | null | undefined) => string
  /** 色调。未知码返回 `info`。 */
  tone: (code: number | null | undefined) => Tone
  /** `<select>` / `el-select` / `van-picker` 直接可用的选项。 */
  options: () => { label: string; value: number }[]
}

/**
 * 字典工厂。
 *
 * ★ 用工厂而不是每个枚举手写一遍 `byCode/label/tone/options`：
 *   手写 14 遍必然有一两处漏了未知码兜底，而那种漏的表现是
 *   「某个下拉框出现空白项」—— 很难定位到是哪个字典。
 */
function createDict<T extends number>(list: readonly DictItem<T>[]): Dict<T> {
  const map = new Map<number, DictItem<T>>(list.map((i) => [i.code, i]))
  return {
    list,
    byCode: (code) => (code === null || code === undefined ? undefined : map.get(code)),
    label: (code) => {
      if (code === null || code === undefined) return '-'
      return map.get(code)?.label ?? `未知(${code})`
    },
    tone: (code) => {
      if (code === null || code === undefined) return 'info'
      return map.get(code)?.tone ?? 'info'
    },
    options: () => list.map((i) => ({ label: i.label, value: i.code as number })),
  }
}

// ── 充电枪状态 ─────────────────────────────────────────────────────────────

/**
 * 充电枪状态。`ConnectorStatus`。
 *
 * ★★ `2 已占用` / `3 充电中` **归订单状态机所有，管理端不能手工设置** ★★
 *   原因（后端注释）：这两个值直接和唯一索引 `uk_connector_current_order`
 *   的语义绑定 —— 手工设成「充电中」会让索引认为这把枪有单，实际没有。
 *
 *   ★ 所以编辑表单的下拉**只能用 `CONNECTOR_ADMIN_SETTABLE_OPTIONS`**，
 *     不能用 `CONNECTOR_STATUS.options()`。后者会把 2/3 也列出来，
 *     提交后被后端拒绝（`ConnectorStatus.isAdminSettable()`），
 *     而用户完全不知道自己错在哪。
 */
export const CONNECTOR_STATUS = createDict([
  { code: 0, label: '离线', tone: 'info', hint: '心跳超时，通信中断（枪本身可能是好的）' },
  { code: 1, label: '空闲', tone: 'success', hint: '可被扫码启动' },
  { code: 2, label: '已占用', tone: 'warning', hint: '有订单占着但还没开始充电' },
  { code: 3, label: '充电中', tone: 'primary', hint: '正在输电' },
  { code: 4, label: '故障', tone: 'danger', hint: '设备上报的错误码' },
  { code: 5, label: '维护', tone: 'accent', hint: '运维主动置位，不参与派单' },
])

/**
 * 管理端**可以**手工设置的枪状态：`{0, 1, 4, 5}`。
 * 与 `ConnectorStatus.isAdminSettable()` 一致（排除 `2 已占用` / `3 充电中`）。
 */
export const CONNECTOR_ADMIN_SETTABLE = [0, 1, 4, 5] as const

/** 编辑表单专用的选项（已排除 2/3）。 */
export const CONNECTOR_ADMIN_SETTABLE_OPTIONS = CONNECTOR_STATUS.list
  .filter((i) => (CONNECTOR_ADMIN_SETTABLE as readonly number[]).includes(i.code))
  .map((i) => ({ label: i.label, value: i.code as number }))

/** 枪是否可用来开新单（H5 选枪时用）。**只有「空闲」可以。** */
export function isConnectorAvailable(status: number | null | undefined): boolean {
  return status === 1
}

// ── 充电桩状态 ─────────────────────────────────────────────────────────────

/**
 * 充电桩状态。`ChargerStatus`。
 *
 * ★★ 与充电枪**不同**：桩的状态**没有白名单**，0~4 都可以手工设置。★★
 *   理由是桩的「充电中」是**派生事实**（它下面有枪在充），设错了不破坏约束，
 *   下一次心跳会纠正它；而枪的 2/3 和唯一索引绑定，设错会破坏约束。
 *
 * ★ 注意 `ChargerStatus.java` 的**类注释是过时的**（写着「管理员手工能改的
 *   只有故障和维护中这两个」），而 `ChargerUpdateDTO` 的注释与实现
 *   （`@Min(0) @Max(4)`、Service 里无白名单检查）都表明是 0~4 全开。
 *   以 DTO 与实现为准 —— **不要照那句类注释去限制前端下拉**。
 */
export const CHARGER_STATUS = createDict([
  { code: 0, label: '离线', tone: 'info', hint: '心跳超时，设备侧失联' },
  { code: 1, label: '在线空闲', tone: 'success', hint: '至少一把枪可用' },
  { code: 2, label: '充电中', tone: 'primary', hint: '至少一把枪正在充电（派生状态）' },
  { code: 3, label: '故障', tone: 'danger', hint: '设备上报的错误状态' },
  { code: 4, label: '维护中', tone: 'accent', hint: '运维主动置位，不参与派单' },
])

// ── 充电桩类型 ─────────────────────────────────────────────────────────────

/** 充电桩类型。`ChargerType`。 */
export const CHARGER_TYPE = createDict([
  { code: 1, label: '直流快充', tone: 'primary' },
  { code: 2, label: '交流慢充', tone: 'info' },
])

// ── 时段类型（尖峰平谷）─────────────────────────────────────────────────────

/**
 * 时段类型。`PeriodType`。
 *
 * ★★ 配色是**独立一组**，不复用状态色 ★★
 *   （见 `segmentColor.ts`）。尖峰也用红色，但那个红是「电价高」，
 *   和「故障」的红是两件事 —— 共用会让分段图和状态标签看起来有关联，
 *   而它们没有。
 */
export const PERIOD_TYPE = createDict([
  { code: 1, label: '尖峰', tone: 'danger', hint: '电价最高时段' },
  { code: 2, label: '高峰', tone: 'warning', hint: '电价次高时段' },
  { code: 3, label: '平段', tone: 'primary', hint: '常规电价时段' },
  { code: 4, label: '低谷', tone: 'success', hint: '电价最低时段，通常 0-8 点' },
])

// ── 套餐 ───────────────────────────────────────────────────────────────────

/** 套餐类型。`PackageType`。决定创建表单显示哪些字段。 */
export const PACKAGE_TYPE = createDict([
  { code: 1, label: '电量包', tone: 'primary', hint: '按度数，需填「电量」' },
  { code: 2, label: '时长包', tone: 'primary', hint: '按时长，需填「时长（分钟）」' },
  { code: 3, label: '服务费折扣包', tone: 'accent', hint: '按折扣，需填「服务费折扣」（0~1）' },
  { code: 4, label: '次卡', tone: 'accent', hint: '按次数，需填「次数」' },
])

/** 套餐分类类型。`CategoryType`。 */
export const CATEGORY_TYPE = createDict([
  { code: 1, label: '充电套餐分类', tone: 'primary' },
  { code: 2, label: '增值服务分类', tone: 'accent' },
])

/** 车主权益（已购套餐）状态。`MemberPackageStatus`。 */
export const MEMBER_PACKAGE_STATUS = createDict([
  { code: 1, label: '生效中', tone: 'success' },
  { code: 2, label: '已用尽', tone: 'info' },
  { code: 3, label: '已过期', tone: 'info' },
  { code: 4, label: '已作废', tone: 'danger' },
])

// ── 站点 / 车主 ────────────────────────────────────────────────────────────

/** 站点状态。`StationStatus`。注意 `2 维护中` 的站点**不会出现在车主端**。 */
export const STATION_STATUS = createDict([
  { code: 0, label: '停用', tone: 'info' },
  { code: 1, label: '启用', tone: 'success' },
  { code: 2, label: '维护中', tone: 'warning', hint: '车主端不展示维护中的站点' },
])

/** 车主账号状态。`0 禁用` 是 H5 强制登出的信号源之一。 */
export const MEMBER_STATUS = createDict([
  { code: 0, label: '禁用', tone: 'danger', hint: '禁用不会撤销 token，H5 靠 /me 探针发现' },
  { code: 1, label: '正常', tone: 'success' },
])

/** 车牌颜色。`PlateColor`。 */
export const PLATE_COLOR = createDict([
  { code: 1, label: '蓝色', tone: 'primary' },
  { code: 2, label: '黄色', tone: 'warning' },
  { code: 3, label: '绿色', tone: 'success' },
  { code: 4, label: '渐变绿色', tone: 'success' },
])

/** 性别。`Gender`。 */
export const GENDER = createDict([
  { code: 0, label: '未知', tone: 'info' },
  { code: 1, label: '男', tone: 'primary' },
  { code: 2, label: '女', tone: 'accent' },
])

// ── 订单相关 ───────────────────────────────────────────────────────────────

/** 支付方式。`PayMethod`。 */
export const PAY_METHOD = createDict([
  { code: 1, label: '余额', tone: 'primary' },
  { code: 2, label: '微信', tone: 'success' },
  { code: 3, label: '支付宝', tone: 'primary' },
])

/** 停止原因。`StopReason`。 */
export const STOP_REASON = createDict([
  { code: 1, label: '用户主动', tone: 'info' },
  { code: 2, label: '充满自停', tone: 'success' },
  { code: 3, label: '余额不足', tone: 'warning', hint: '余额耗尽导致停止，会进异常挂账' },
  { code: 4, label: '设备故障', tone: 'danger' },
  { code: 5, label: '超时', tone: 'warning' },
  { code: 6, label: '运维干预', tone: 'accent' },
])

/** 事件来源。`EventSource`。★ 注意后端**也**给了 `sourceLabel`，优先用它。 */
export const EVENT_SOURCE = createDict([
  { code: 1, label: '用户端', tone: 'primary' },
  { code: 2, label: '设备上报', tone: 'info' },
  { code: 3, label: '定时任务', tone: 'accent' },
  { code: 4, label: '运维', tone: 'warning' },
  { code: 5, label: '支付回调', tone: 'success' },
])

// ── 钱包流水 ───────────────────────────────────────────────────────────────

/**
 * 钱包流水类型。`WalletTransactionType`。
 *
 * ★★ **符号在 `amount` 上，不在类型上** ★★
 *   已核实（`WalletServiceImpl`）：`FREEZE` 和 `DEDUCT` 写进去的是
 *   `amount.negate()`（**负数**），退款/充值写正数。
 *   而这个枚举**没有 direction 字段**。
 *
 *   → 所以渲染规则是：**颜色和正负号只看 `amount` 的符号，文字标签看 `type`**。
 *     反过来做（按 type 判断收支）会在这两个类型上出错：
 *     `4 扣款` 一般是负数，但理论上没有东西保证它一定是。
 *
 * ★ `7 管理员调账` 的金额**可正可负**（同一个动作的两个方向）——
 *   这正是「符号在金额上」这条规则最直观的证据。
 */
export const WALLET_TXN_TYPE = createDict([
  { code: 1, label: '充值', tone: 'success' },
  { code: 2, label: '冻结', tone: 'warning', hint: '下单时冻结预估金额，此时仍未真正扣款' },
  { code: 3, label: '解冻', tone: 'info', hint: '取消或结算后释放冻结金额' },
  { code: 4, label: '扣款', tone: 'danger', hint: '充电结算，冻结金额转为实扣' },
  { code: 5, label: '退款', tone: 'accent', hint: '订单退款，钱回到可用余额' },
  { code: 6, label: '套餐购买', tone: 'danger', hint: '用余额购买套餐（扣的是可用余额，与充电无关）' },
  { code: 7, label: '管理员调账', tone: 'accent', hint: '人工调账，金额可正可负，必须有原因' },
  { code: 8, label: '套餐退款', tone: 'success', hint: '作废权益时退回的钱，金额取购买时的实付' },
])

/**
 * 这条流水是否「动了可用余额」。
 *
 * ★ 冻结/解冻**不动可用余额**（动的是 `frozen_amount`），
 *   在「余额明细」里应当**弱化显示**或者单独分组 ——
 *   否则用户会看到「冻结 -50、解冻 +50」，以为钱进出过两次，其实余额没变过。
 */
export function isBalanceAffecting(type: number | null | undefined): boolean {
  // 2 冻结、3 解冻 → 只动 frozen_amount
  return type !== 2 && type !== 3
}

// ── 运维工单 ───────────────────────────────────────────────────────────────

/** 工单状态。`TicketStatus`。 */
export const TICKET_STATUS = createDict([
  { code: 0, label: '待处理', tone: 'warning', hint: '刚创建，包括 AI 助手自动建的' },
  {
    code: 1,
    label: '处理中',
    tone: 'accent',
    hint: '已经有人在跟。这一档只是"占住"，不代表结论',
  },
  {
    code: 2,
    label: '已完成',
    tone: 'success',
    hint: '只有已完成工单的 content 会被当历史案例召回给 AI',
  },
])

/**
 * 工单来源。
 *
 * ★ `sourceLabel` 在 VO 上已经有，**能拿到 VO 的地方优先用它**；
 *   这里服务的是筛选下拉框（需要全量取值）。
 */
export const TICKET_SOURCE = createDict([
  { code: 1, label: '人工', tone: 'info', hint: '运维手动建的工单' },
  { code: 2, label: 'AI助手', tone: 'accent', hint: 'AI 助手在对话中建的，正文是它给出的处理建议' },
])
