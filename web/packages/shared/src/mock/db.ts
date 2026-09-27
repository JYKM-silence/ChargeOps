/**
 * Mock 的**可变**内存库。
 *
 * ★★ 它必须真的会变，否则整条演示链路是假的 ★★
 *   `start` / `end` / `settle` / `pay` / `refund` / `cancel` 不是「返回一个成功信封」，
 *   而是真的按后端的状态机改行：订单状态、充电枪状态与 `currentOrderId`、钱包余额与
 *   冻结额、订单事件流。前端要验证的恰恰是**第二次点同一个按钮会被拒绝**（CAS），
 *   一个只回 `code:1` 的 mock 永远演示不出那件事。
 *
 * ★★ 金额一律用**整数分**（`Cents`）存 ★★
 *   后端是 `decimal(12,2)`，而 JS 的 `0.1 + 0.2 = 0.30000000000000004`。
 *   分段金额是**逐段舍入后相加**得到总额的（见 BillingEngine 的「Σ分段 == 总额 是
 *   恒等式」），中间只要掺一次浮点噪声，那条不变式就会差一分 ——
 *   而前端 `segmentsMatchTotal()` 会把它当成后端算错了。
 *   所以：库里只存整数分，出口（VO 映射）才除以 100。
 *
 * ★ 时间一律存 `Date`，出口才格式化成 `yyyy-MM-dd HH:mm:ss`。
 *   反过来的话（库里就存后端字符串）每次比较都要解析，而解析在 Safari 上是坑。
 *
 * ★ 所有状态机操作都带一个可选的 `at`（操作时刻）。
 *   生产代码里没人会传它，它的唯一用途是 **fixtures 造历史数据**：
 *   让种子订单走的是**同一套** CAS 逻辑，从而「造出来的假数据」与
 *   「用户点出来的真数据」在结构上不可能不一致。
 */

import { orderStatusLabel } from '../constants/orderStatus'
import { formatServerTime } from '../utils/datetime'
import type { Decimal } from '../types/admin'

// ══════════════════════════════════════════════════════════════════════════
// 基础类型
// ══════════════════════════════════════════════════════════════════════════

/** 整数分。见文件头：金额在库里**只有**这一种表示。 */
export type Cents = number

/** 分 → 元（后端 `BigDecimal` 序列化出来就是这样一个 JSON number）。 */
export function yuan(cents: Cents): number {
  return Math.round(cents) / 100
}

/** 元（来自请求体 / fixtures 的字面量）→ 分。 */
export function toCents(value: Decimal | null | undefined): Cents {
  if (value === null || value === undefined || value === '') return 0
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? Math.round(n * 100) : 0
}

/** 电量对齐到 4 位小数（后端 `decimal(12,4)`）。 */
export function kwh(value: number): number {
  return Math.round(value * 10000) / 10000
}

/** 后端时间字符串。**唯一的出库格式**。 */
export function ts(at: Date | null | undefined): string | null {
  return at ? formatServerTime(at) : null
}

/** 非空时间字段的出库格式（后端那些 `NOT NULL` 的时间列）。 */
export function tsRequired(at: Date): string {
  return formatServerTime(at) ?? ''
}

/**
 * CAS 的结果。
 *
 * ★ 用「返回值」而不是「抛异常」表达失败：handler 层要把它翻成
 *   `HTTP 200 + code:0`，而抛异常会把控制流和「真的出错了」混在一起 ——
 *   后端也是这个形状（Service 抛业务异常 → GlobalExceptionHandler 翻信封）。
 *
 * ★★ `insufficientBalance` 是**给调用方**的标记，不是给用户看的文案 ★★
 *   `markPaid` 在余额不足时只回滚（订单退回 4、钱退回冻结），而
 *   「4 → 6 支付失败 + 一条 abnormal 事件」必须由**调用方**再写一次 ——
 *   后端就是这么分的（`OrderController.pay` 的 catch，理由见那边的注释：
 *   抛异常的瞬间事务已被标记 rollback-only，只有 Controller 没有事务）。
 *   ★ 不靠 `msg` 文本去认这件事：文案改一个字，这条路径就会**静默**退化成
 *     「订单停在 4、没有事件、没有任何报错」—— 那正是最难查的一类问题。
 */
export type CasResult = { ok: true } | { ok: false; msg: string; insufficientBalance?: true }

export const CAS_OK: CasResult = { ok: true }

// ══════════════════════════════════════════════════════════════════════════
// 行类型（对应后端 entity）
// ══════════════════════════════════════════════════════════════════════════

export interface DbStaff {
  id: number
  username: string
  /** 明文。后端是 BCrypt 密文，这里没必要 —— mock 不模拟哈希这件事。 */
  password: string
  name: string
  phone: string | null
  sex: string | null
  role: number
  stationId: number | null
  status: number
  createTime: Date
}

export interface DbMember {
  id: number
  phone: string
  /** ★ `null` 是**合法且有意的状态**（验证码登录的用户），用它登录必须失败。 */
  password: string | null
  nickname: string | null
  avatar: string | null
  gender: number
  status: number
  lastLoginTime: Date | null
  createTime: Date
  updateTime: Date
}

export interface DbVehicle {
  id: number
  memberId: number
  plateNo: string
  plateColor: number
  brand: string | null
  model: string | null
  batteryCapacityKwh: number | null
  /** 1 是默认车 / 0 不是。**数字**，不是布尔。 */
  isDefault: number
  remark: string | null
  createTime: Date
  updateTime: Date
}

export interface DbWallet {
  memberId: number
  balance: Cents
  frozenAmount: Cents
  totalRecharge: Cents
  createTime: Date
  updateTime: Date
}

/** 后端 `WalletTransactionType` 的码值。 */
export type WalletTxnType = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8

export interface DbWalletTxn {
  id: number
  transactionNo: string
  memberId: number
  /** 充值和套餐买卖都是 null（直购模型没有购买单）。 */
  orderId: number | null
  type: WalletTxnType
  /** **带符号**：正入账、负出账。冻结/扣款写的是负数。 */
  amount: Cents
  balanceAfter: Cents
  remark: string | null
  createTime: Date
}

export interface DbStation {
  id: number
  code: string
  name: string
  operator: string | null
  province: string | null
  city: string | null
  district: string | null
  address: string | null
  longitude: number | null
  latitude: number | null
  servicePhone: string | null
  openTime: string | null
  closeTime: string | null
  parkingFee: Cents | null
  status: number
  createTime: Date
  updateTime: Date
}

export interface DbCharger {
  id: number
  stationId: number
  deviceNo: string
  name: string
  type: number
  model: string | null
  ratedPowerKw: number | null
  /** 冗余列，由 connector 的增删重算 —— 后端也是重算而不是自增。 */
  gunCount: number
  status: number
  lastHeartbeatTime: Date | null
  firmwareVersion: string | null
  createTime: Date
  updateTime: Date
}

export interface DbConnector {
  id: number
  chargerId: number
  stationId: number
  gunNo: string
  connectorType: string | null
  ratedPowerKw: number | null
  status: number
  currentOrderId: number | null
  totalChargeCount: number
  /** ★ 全项目**唯一**带乐观锁的实体，编辑时必须原样回传。 */
  version: number
  /**
   * ★★ 演示用标记：这条枪在库里已经是「被别人改过」的状态。★★
   *   下一次 PUT 一定返回 `@CONCURRENT_MODIFIED`，并把标记清掉 ——
   *   于是「刷新后重试成功」这条链路才能被走第二遍。
   *   真后端里这个冲突来自另一个事务，mock 单线程造不出来，只能显式标记。
   */
  pendingConflict?: boolean
  createTime: Date
  updateTime: Date
}

export interface DbTariffRule {
  id: number
  templateId: number
  periodType: number
  /** `HH:mm:ss`，末段是 `24:00:00`。 */
  startTime: string
  endTime: string
  energyPrice: number
  servicePrice: number
  seq: number
  createTime: Date
  updateTime: Date
}

export interface DbTariffTemplate {
  id: number
  code: string
  name: string
  region: string | null
  description: string | null
  status: number
  createTime: Date
  updateTime: Date
}

export interface DbStationTariff {
  id: number
  stationId: number
  templateId: number
  effectiveFrom: Date | null
  effectiveTo: Date | null
  priority: number
  status: number
  createTime: Date
  updateTime: Date
}

export interface DbCategory {
  id: number
  name: string
  type: number
  sort: number
  status: number
  createTime: Date
}

export interface DbPackage {
  id: number
  name: string
  categoryId: number
  packageType: number
  /** `0` 表示全平台通用（后端 SQL 里的 `station_id IN (0, ?)`）。 */
  stationId: number | null
  price: Cents
  originPrice: Cents | null
  energyKwh: number | null
  durationMinutes: number | null
  times: number | null
  serviceDiscount: number | null
  validDays: number
  description: string | null
  image: string | null
  status: number
  sort: number
  createTime: Date
  updateTime: Date
}

export interface DbMemberPackage {
  id: number
  memberId: number
  packageId: number
  packageType: number
  /** 名称快照，SKU 改名不影响已售出的这一份。 */
  packageName: string
  /** ★ `null` = 「不知道」（建于本功能上线之前的行），**不是** 0。 */
  payAmount: Cents | null
  totalKwh: number | null
  remainingKwh: number | null
  totalMinutes: number | null
  remainingMinutes: number | null
  totalTimes: number | null
  remainingTimes: number | null
  serviceDiscount: number | null
  effectiveFrom: Date | null
  expireTime: Date | null
  status: number
  /** 被哪笔进行中的订单锁着。有值 = 这份权益当前不可用、也不可退款。 */
  lockedOrderId: number | null
  remark: string | null
  createTime: Date
  updateTime: Date
}

export interface DbOrder {
  id: number
  orderNo: string
  memberId: number
  vehicleId: number | null
  /** 车牌**快照**，不是 JOIN 车辆拿的实时值。 */
  plateNo: string | null
  stationId: number
  chargerId: number
  connectorId: number
  tariffTemplateId: number | null
  memberPackageId: number | null
  status: number
  createTime: Date
  startTime: Date | null
  endTime: Date | null
  settleTime: Date | null
  durationSeconds: number | null
  startMeterKwh: number | null
  endMeterKwh: number | null
  startSoc: number | null
  endSoc: number | null
  totalEnergyKwh: number | null
  energyFee: Cents | null
  serviceFee: Cents | null
  totalAmount: Cents | null
  discountAmount: Cents | null
  payAmount: Cents | null
  frozenAmount: Cents | null
  refundAmount: Cents | null
  payMethod: number | null
  payTime: Date | null
  refundTime: Date | null
  stopReason: number | null
  remark: string | null
  updateTime: Date
}

export interface DbOrderSegment {
  id: number
  orderId: number
  seq: number
  periodType: number
  startTime: Date
  endTime: Date
  durationSeconds: number
  energyKwh: number
  energyPrice: number
  servicePrice: number
  energyFee: Cents
  serviceFee: Cents
  /** ★ 后端保证 `Σ amount === order.totalAmount`（逐段舍入后求和）。 */
  amount: Cents
  createTime: Date
}

export interface DbOrderEvent {
  id: number
  orderId: number
  fromStatus: number | null
  toStatus: number | null
  /** 字符串枚举：`create` / `start` / `end` / `settle` / `pay` / `refund` / `cancel` / `abnormal`。 */
  eventType: string
  /** 1 用户端 / 2 设备上报 / 3 定时任务 / 4 运维 / 5 支付回调 */
  source: number
  operatorId: number | null
  detail: string | null
  createTime: Date
}

// ══════════════════════════════════════════════════════════════════════════
// 内存库
// ══════════════════════════════════════════════════════════════════════════

export interface MockDb {
  // ── 表 ────────────────────────────────────────────────────────────────
  staff: DbStaff[]
  members: DbMember[]
  vehicles: DbVehicle[]
  wallets: DbWallet[]
  walletTxns: DbWalletTxn[]
  stations: DbStation[]
  chargers: DbCharger[]
  connectors: DbConnector[]
  tariffTemplates: DbTariffTemplate[]
  tariffRules: DbTariffRule[]
  stationTariffs: DbStationTariff[]
  categories: DbCategory[]
  packages: DbPackage[]
  memberPackages: DbMemberPackage[]
  orders: DbOrder[]
  segments: DbOrderSegment[]
  events: DbOrderEvent[]
  /** 会话：token → 主体 id。两端各一张表，因为两套 token 不能互相冒充。 */
  adminTokens: Map<string, number>
  memberTokens: Map<string, number>

  // ── 工具 ──────────────────────────────────────────────────────────────
  nextId(table: string): number
  now(): Date
  /** 订单号 / 流水号生成（后端是 `BizNoGenerator`）。 */
  nextNo(prefix: string, at: Date): string

  // ── 查询 ──────────────────────────────────────────────────────────────
  orderById(id: number): DbOrder | undefined
  connectorById(id: number): DbConnector | undefined
  chargerById(id: number): DbCharger | undefined
  stationById(id: number): DbStation | undefined
  memberById(id: number): DbMember | undefined
  walletOf(memberId: number): DbWallet
  templateById(id: number): DbTariffTemplate | undefined
  rulesOf(templateId: number): DbTariffRule[]
  packagesOf(memberId: number): DbMemberPackage[]
  segmentsOf(orderId: number): DbOrderSegment[]
  eventsOf(orderId: number): DbOrderEvent[]

  // ── 订单状态机（CAS）──────────────────────────────────────────────────
  createOrder(input: CreateOrderInput): CasResult & { order?: DbOrder }
  /**
   * `1 待启动` → `2 充电中`。
   *
   * ★ `startMeterKwh` **可空**：后端 `OrderStartDTO.startMeterKwh` 明确写着
   *   「可空；缺数据结算会挂账而不是算错」，所以这是**一条合法路径**，
   *   不是错用。收 `number` 的话 handler 只能填 0 —— 那会变成一句谎话
   *   （电表读数 0 和「没读到」在结算时是两个完全不同的结论：
   *   前者算出满电量、后者挂账）。
   */
  markStarted(
    orderId: number,
    startMeterKwh: number | null,
    startSoc: number | null,
    at?: Date,
  ): CasResult
  markEnded(orderId: number, input: EndInput, at?: Date): CasResult
  markSettled(orderId: number, at?: Date): CasResult
  markPaid(orderId: number, at?: Date): CasResult
  /** ★ 影响 0 行是**正常**的（订单已不在 4），所以它不返回失败。 */
  markPayFailed(orderId: number, reason: string, at?: Date): void
  markRefunded(orderId: number, amount: Decimal, at?: Date): CasResult
  markCancelled(orderId: number, at?: Date): CasResult
  markAbnormal(orderId: number, reason: string, at?: Date): CasResult
  /** 取消 / 挂账时按 `currentOrderId` 放枪，**幂等**。 */
  releaseConnector(orderId: number, at: Date): void
  releasePackageLock(orderId: number, at: Date): void

  // ── 钱包 ──────────────────────────────────────────────────────────────
  recharge(memberId: number, amount: Decimal, at?: Date): CasResult
  adjust(memberId: number, amount: Decimal, remark: string, at?: Date): CasResult
  freeze(memberId: number, orderId: number, amount: Cents, at: Date): CasResult
  /**
   * 写一条钱包流水（**只写流水，不动余额**）。
   *
   * ★★ 这个方法是 handler 层加进来的，理由和它为什么在这里而不是在 handler 里 ★★
   *   `6 套餐购买` 和 `8 套餐退款` 这两笔钱是**在 handler 里**从余额上扣/加的
   *   （它们不由订单状态机驱动，`db.ts` 里没有对应的 CAS 方法）。
   *   而「写流水」这件事带一条**唯一索引语义**（`uk_txn_order_type`：同一订单的
   *   同一类型资金动作只能有一条）—— 在 handler 里重写一遍等于让那条约束有两个实现，
   *   迟早会分叉。所以这里只是把**已经存在的**那个私有函数挂到接口上，
   *   没有新增任何逻辑（原来它就在 `recharge` / `adjust` / `freeze` / `markPaid` 里用）。
   */
  recordTxn(input: {
    memberId: number
    orderId: number | null
    type: WalletTxnType
    amount: Cents
    remark: string | null
    at: Date
  }): void

  // ── 定时任务替身 ──────────────────────────────────────────────────────
  /** 后端 5 分钟一轮：把超过 15 分钟的 `4 待支付` 标成 `6 支付失败`。 */
  sweepStalePendingPay(at?: Date): number
  /** 后端 30 分钟一轮：把过期但仍写着 `1 生效中` 的权益落库成 `3 已过期`。 */
  expireOverduePackages(at?: Date): number
}

export interface CreateOrderInput {
  memberId: number
  vehicleId: number | null
  stationId: number
  chargerId: number
  connectorId: number
  tariffTemplateId: number | null
  payMethod: number
  /** 预估冻结额（分）。后端是「电池容量 × 最贵单价」，见 estimateFreeze。 */
  frozenAmount: Cents
  memberPackageId?: number | null
  at: Date
}

export interface EndInput {
  endMeterKwh: number
  endSoc: number | null
  /**
   * 1 用户主动 / 2 充满自停 / 3 余额不足 / 4 设备故障 / 5 超时 / 6 运维干预。
   *
   * ★ **可空**（设备没上报停止原因时就是 null）。后端 `OrderEndDTO.stopReason`
   *   没有 `@NotNull`，而 `describeStopReason(null)` 会渲染成「未上报」——
   *   这里必须能表达 null，否则 handler 只能瞎填一个数字，
   *   事件详情里就会出现一条**并不存在**的停止原因。
   */
  stopReason: number | null
  endTime?: Date | null
}

/** 后端 `OrderStatus.holdingConnectorCodes()` → {1,2,3}。 */
const HOLDING_CONNECTOR = [1, 2, 3]
/** 后端 `OrderStatus.holdingMoneyCodes()` → {1,2,3,4,6}。 */
const HOLDING_MONEY = [1, 2, 3, 4, 6]

/** 后端 `ChargingOrderSweepJob.STALE_REASON`，逐字。 */
export const STALE_PENDING_PAY_REASON =
  '超过支付时限仍未支付，系统标记为支付失败；充值后可在订单详情重新支付'

/** 后端 `SchedulingProperties` 的两个默认口径。 */
export const PAY_TIMEOUT_MINUTES = 15
export const PAY_SWEEP_INTERVAL_MS = 5 * 60 * 1000
export const PACKAGE_EXPIRE_INTERVAL_MS = 30 * 60 * 1000

export function createDb(): MockDb {
  const counters = new Map<string, number>()
  const noCounters = new Map<string, number>()

  const db: MockDb = {
    staff: [],
    members: [],
    vehicles: [],
    wallets: [],
    walletTxns: [],
    stations: [],
    chargers: [],
    connectors: [],
    tariffTemplates: [],
    tariffRules: [],
    stationTariffs: [],
    categories: [],
    packages: [],
    memberPackages: [],
    orders: [],
    segments: [],
    events: [],
    adminTokens: new Map<string, number>(),
    memberTokens: new Map<string, number>(),

    nextId(table) {
      const next = (counters.get(table) ?? 0) + 1
      counters.set(table, next)
      return next
    },

    now: () => new Date(),

    /**
     * 单号生成。
     *
     * ★ 带上**时分秒**而不是纯自增：前端演示时会连点建单，
     *   纯自增的话两张单号只差最后一位，肉眼分不出「哪笔是哪笔」。
     * ★ 后端单号里带日期，同一天内递增；这里保留那个形状但把日期换成
     *   当前时刻的 `yyyyMMddHHmmss`，保证同一秒内建的两笔也不会撞。
     */
    nextNo(prefix, at) {
      const stamp = formatServerTime(at)?.replace(/[-: ]/g, '') ?? ''
      const n = (noCounters.get(prefix) ?? 0) + 1
      noCounters.set(prefix, n)
      return `${prefix}${stamp}${String(n).padStart(3, '0')}`
    },

    orderById: (id) => db.orders.find((o) => o.id === id),
    connectorById: (id) => db.connectors.find((c) => c.id === id),
    chargerById: (id) => db.chargers.find((c) => c.id === id),
    stationById: (id) => db.stations.find((s) => s.id === id),
    memberById: (id) => db.members.find((m) => m.id === id),
    templateById: (id) => db.tariffTemplates.find((t) => t.id === id),
    rulesOf: (templateId) =>
      db.tariffRules.filter((r) => r.templateId === templateId).sort((a, b) => a.seq - b.seq),
    packagesOf: (memberId) =>
      db.memberPackages.filter((p) => p.memberId === memberId).sort((a, b) => b.id - a.id),
    segmentsOf: (orderId) =>
      db.segments.filter((s) => s.orderId === orderId).sort((a, b) => a.seq - b.seq),
    eventsOf: (orderId) =>
      db.events.filter((e) => e.orderId === orderId).sort((a, b) => a.id - b.id),

    /**
     * 取钱包，**没有就开一张空卡**。
     *
     * ★ 后端 `ensureWallet` 就是这个行为，而且只在「要动钱」的路径上调 ——
     *   纯查询路径（`GET /wallet`）读不到行时会返回 `WalletVO.empty()`
     *   （三个 0），不是报错。两者都对：一个没充过值的车主余额就是 0。
     */
    walletOf(memberId) {
      let w = db.wallets.find((x) => x.memberId === memberId)
      if (!w) {
        w = {
          memberId,
          balance: 0,
          frozenAmount: 0,
          totalRecharge: 0,
          createTime: db.now(),
          updateTime: db.now(),
        }
        db.wallets.push(w)
      }
      return w
    },

    // ── 钱包 ────────────────────────────────────────────────────────────

    recharge(memberId, amount, at = db.now()) {
      const cents = toCents(amount)
      if (cents <= 0) return { ok: false, msg: '充值金额必须大于 0' }
      const wallet = db.walletOf(memberId)
      wallet.balance += cents
      wallet.totalRecharge += cents
      wallet.updateTime = at
      recordTxn({ memberId, orderId: null, type: 1, amount: cents, remark: '充值', at })
      return CAS_OK
    },

    adjust(memberId, amount, remark, at = db.now()) {
      const cents = toCents(amount)
      // ★ 逐条复刻 WalletServiceImpl.adjust 的三道校验，顺序也一样 ——
      //   「0」和「null」分开是因为下一步动作不同（一个填错了、一个没填）。
      if (cents === 0) return { ok: false, msg: '调账金额不能为 0' }
      if (!remark || !remark.trim()) return { ok: false, msg: '调账必须填写原因' }

      const wallet = db.walletOf(memberId)
      if (cents > 0) {
        wallet.balance += cents
      } else {
        // 后端把判断放在 SQL 的 WHERE 里（`balance >= ?`），影响 0 行 = 余额不足
        if (wallet.balance < -cents) {
          return { ok: false, msg: '该车主可用余额不足，无法扣除这笔金额' }
        }
        wallet.balance += cents
      }
      wallet.updateTime = at
      recordTxn({ memberId, orderId: null, type: 7, amount: cents, remark, at })
      return CAS_OK
    },

    freeze(memberId, orderId, amount, at) {
      // ★ 0 元不冻也不写流水 —— 后端对 0 元刻意宽容，理由见 WalletServiceImpl.freeze
      if (amount <= 0) return CAS_OK
      const wallet = db.walletOf(memberId)
      if (wallet.balance < amount) return { ok: false, msg: '余额不足' }
      wallet.balance -= amount
      wallet.frozenAmount += amount
      wallet.updateTime = at
      recordTxn({ memberId, orderId, type: 2, amount: -amount, remark: '下单冻结', at })
      return CAS_OK
    },

    // ── 订单状态机 ──────────────────────────────────────────────────────

    createOrder(input) {
      const { at } = input
      const connector = db.connectorById(input.connectorId)
      if (!connector) return { ok: false, msg: '充电枪不存在' }

      const order: DbOrder = {
        id: db.nextId('order'),
        orderNo: db.nextNo('SO', at),
        memberId: input.memberId,
        vehicleId: input.vehicleId,
        plateNo: input.vehicleId
          ? (db.vehicles.find((v) => v.id === input.vehicleId)?.plateNo ?? null)
          : null,
        stationId: input.stationId,
        chargerId: input.chargerId,
        connectorId: input.connectorId,
        tariffTemplateId: input.tariffTemplateId,
        memberPackageId: null,
        status: 1,
        createTime: at,
        startTime: null,
        endTime: null,
        settleTime: null,
        durationSeconds: null,
        startMeterKwh: null,
        endMeterKwh: null,
        startSoc: null,
        endSoc: null,
        totalEnergyKwh: null,
        energyFee: null,
        serviceFee: null,
        totalAmount: null,
        discountAmount: 0,
        payAmount: null,
        frozenAmount: input.frozenAmount,
        refundAmount: 0,
        payMethod: input.payMethod,
        payTime: null,
        refundTime: null,
        stopReason: null,
        remark: null,
        updateTime: at,
      }
      db.orders.push(order)

      // ★ 抢枪：后端是 `WHERE status = 1` 的条件更新，0 行 = 被别人抢走了。
      //   这条判断在 mock 里同样**必须**存在，否则「两个人同时扫码」的演示里
      //   第二个人会建单成功，而真后端会拒绝他。
      if (connector.status !== 1 || connector.currentOrderId !== null) {
        db.orders.pop()
        return { ok: false, msg: '该充电枪正在使用中，请换一把' }
      }
      connector.status = 2
      connector.currentOrderId = order.id
      connector.version += 1
      connector.updateTime = at

      if (input.memberPackageId) {
        const mp = db.memberPackages.find((p) => p.id === input.memberPackageId)
        if (mp && mp.lockedOrderId === null && mp.status === 1) {
          mp.lockedOrderId = order.id
          mp.updateTime = at
          order.memberPackageId = mp.id
        }
      }

      const frozen = db.freeze(input.memberId, order.id, input.frozenAmount, at)
      if (!frozen.ok) {
        // 冻钱失败 = 整笔回滚。后端是一个事务，这里手动撤销前面几步。
        connector.status = 1
        connector.currentOrderId = null
        db.orders.pop()
        return frozen
      }

      recordEvent({
        orderId: order.id,
        fromStatus: null,
        toStatus: 1,
        eventType: 'create',
        source: 1,
        operatorId: input.memberId,
        detail: `枪 ${connector.id}，计费模板 ${input.tariffTemplateId}，预估冻结 ${yuan(input.frozenAmount)}`,
        at,
      })
      return { ok: true, order }
    },

    markStarted(orderId, startMeterKwh, startSoc, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      // ★★ CAS：`WHERE id = ? AND status = 1`。不匹配就是「有人先动了这一行」★★
      if (order.status !== 1) return { ok: false, msg: statusConflict(order, 1, 2) }

      order.status = 2
      order.startTime = at
      order.startMeterKwh = startMeterKwh === null ? null : kwh(startMeterKwh)
      order.startSoc = startSoc
      order.updateTime = at

      // 枪跟着进「3 充电中」。★ 后端对 0 行只告警不抛异常（订单才是真相），
      //   这里同样不因为枪不对而让启动失败。
      const connector = db.connectorById(order.connectorId)
      if (connector) {
        connector.status = 3
        connector.currentOrderId = order.id
        connector.version += 1
        connector.updateTime = at
      }

      recordEvent({
        orderId,
        fromStatus: 1,
        toStatus: 2,
        eventType: 'start',
        source: 2,
        operatorId: null,
        // ★ 读数为空时这里渲染成字符串 `null` —— 这是**后端的原样**：
        //   `"起始电表读数 " + dto.getStartMeterKwh()` 是 Java 的字符串拼接，
        //   `BigDecimal` 为 null 时拼出来就是这四个字母。
        //   同一份代码里 `describeStopReason(null)` 却体贴地写成了「未上报」——
        //   这个不对称是真实存在的，mock 不替后端「修」它：
        //   演示时看到的每个字都应该和真后端一致，哪怕其中一个是漏网之鱼。
        detail: `起始电表读数 ${startMeterKwh === null ? 'null' : kwh(startMeterKwh)}`,
        at,
      })
      return CAS_OK
    },

    markEnded(orderId, input, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      if (order.status !== 2) return { ok: false, msg: statusConflict(order, 2, 3) }

      const endTime = input.endTime ?? at
      // ★ 电量 = 电表差。电表是累计值，不会因为换了辆车就归零。
      const meterProblem =
        order.startMeterKwh === null
          ? `缺少电表读数，无法计算用电量，已转入异常挂账：订单缺少起始读数，结束读数 ${kwh(input.endMeterKwh)}`
          : input.endMeterKwh < order.startMeterKwh
            ? `结束电表读数小于起始读数，已转入异常挂账：起始 ${order.startMeterKwh}，结束 ${kwh(input.endMeterKwh)}`
            : null

      const durationSeconds = order.startTime
        ? Math.round((endTime.getTime() - order.startTime.getTime()) / 1000)
        : null

      // ★ ① 先把「充电结束了」这个物理事实落库 —— 不可回滚的事实
      order.status = 3
      order.endTime = endTime
      order.durationSeconds = durationSeconds
      order.endMeterKwh = kwh(input.endMeterKwh)
      order.endSoc = input.endSoc
      order.stopReason = input.stopReason
      order.totalEnergyKwh =
        meterProblem === null ? kwh(input.endMeterKwh - (order.startMeterKwh ?? 0)) : null
      order.updateTime = at

      // ★ 车拔枪了，但订单还没结算 → 枪回到「2 已占用」，**不释放**。
      //   这是后端的真实行为：结算期间别人不能拿这把枪下第二笔单。
      const connector = db.connectorById(order.connectorId)
      if (connector) {
        connector.status = 2
        connector.currentOrderId = order.id
        connector.version += 1
        connector.updateTime = at
      }

      recordEvent({
        orderId,
        fromStatus: 2,
        toStatus: 3,
        eventType: 'end',
        source: 2,
        operatorId: null,
        detail:
          `结束电表读数 ${kwh(input.endMeterKwh)}，电量 ${order.totalEnergyKwh ?? '-'}，` +
          `停止原因 ${input.stopReason === null ? '未上报' : (STOP_REASON_LABEL[input.stopReason] ?? input.stopReason)}`,
        at,
      })

      // ★ ② 电量算不出来时**紧接着**转异常挂账。两步而不是一步：
      //   `endTime` 已经保住了，异常状态也拿到了，合成一步必然丢掉一个。
      if (meterProblem !== null) db.markAbnormal(orderId, meterProblem, at)
      return CAS_OK
    },

    markSettled(orderId, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      if (order.status !== 3) return { ok: false, msg: statusConflict(order, 3, 4) }
      if (order.totalEnergyKwh === null || order.startTime === null || order.endTime === null) {
        return {
          ok: false,
          msg: `缺少电表读数，无法计算用电量，已转入异常挂账：orderId=${order.id}`,
        }
      }

      const templateId = order.tariffTemplateId
      // ★ 用的是订单**冻结的**模板，不是「这个站点现在用哪个模板」——
      //   这就是 station_tariff 整张表存在的意义。
      const rules = templateId === null ? [] : db.rulesOf(templateId)
      const billed = billSegments(order.startTime, order.endTime, order.totalEnergyKwh, rules)
      if ('msg' in billed) return { ok: false, msg: billed.msg }

      const discount = applyPackage(order, billed.totalAmount, at)
      const payAmount = billed.totalAmount - discount

      order.status = 4
      order.energyFee = billed.energyFee
      order.serviceFee = billed.serviceFee
      order.totalAmount = billed.totalAmount
      order.discountAmount = discount
      order.payAmount = payAmount
      order.settleTime = at
      order.updateTime = at

      let seq = 1
      for (const s of billed.segments) {
        db.segments.push({
          id: db.nextId('segment'),
          orderId,
          seq: seq++,
          periodType: s.periodType,
          startTime: s.startTime,
          endTime: s.endTime,
          durationSeconds: s.durationSeconds,
          energyKwh: s.energyKwh,
          energyPrice: s.energyPrice,
          servicePrice: s.servicePrice,
          energyFee: s.energyFee,
          serviceFee: s.serviceFee,
          amount: s.amount,
          createTime: at,
        })
      }

      // ★ 释放枪口 + 累计充电次数。两者都在 CAS **之后**，所以提交当且仅当 CAS 提交。
      db.releaseConnector(orderId, at)
      const connector = db.connectorById(order.connectorId)
      if (connector) {
        connector.totalChargeCount += 1
        connector.version += 1
      }
      db.releasePackageLock(orderId, at)

      recordEvent({
        orderId,
        fromStatus: 3,
        toStatus: 4,
        eventType: 'settle',
        source: 3,
        operatorId: null,
        detail: `分段 ${billed.segments.length} 段，应付 ${yuan(billed.totalAmount)}，优惠 ${yuan(discount)}，实付 ${yuan(payAmount)}`,
        at,
      })
      return CAS_OK
    },

    markPaid(orderId, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      // ★★ `markPaid` 的谓词是 `status IN (4, 6)` —— 支付失败的单充值后能重付 ★★
      if (order.status !== 4 && order.status !== 6) {
        return { ok: false, msg: statusConflict(order, 4, 5) }
      }
      const from = order.status

      order.status = 5
      order.payTime = at
      order.updateTime = at

      // ★ 先解冻、再扣款，顺序不能反：先扣的话钱还在冻结里，可用余额可能不够，
      //   一笔本该成功的结算会被拒。
      const wallet = db.walletOf(order.memberId)
      const frozen = order.frozenAmount ?? 0
      const actual = order.payAmount ?? 0
      if (frozen > 0) {
        wallet.frozenAmount -= frozen
        wallet.balance += frozen
        wallet.updateTime = at
        recordTxn({ memberId: order.memberId, orderId, type: 3, amount: frozen, remark: '结算解冻', at })
      }
      if (actual > 0) {
        if (wallet.balance < actual) {
          // ★ 余额不足 → 后端抛 BalanceNotEnoughException，**整个事务回滚**，
          //   订单退回 4 待支付、钱退回冻结。这里手动撤销上面那两步。
          wallet.balance -= frozen
          wallet.frozenAmount += frozen
          removeTxn(orderId, 3)
          // ★★ 订单也必须一起退回去 ★★
          //   只退钱不退状态的话，订单会停在 `5 已支付` 而账上一分没扣 ——
          //   一个「已支付但没付钱」的中间态，而且它**不报错**：
          //   对账时才会发现这笔单子既没有扣款流水、也没有 `pay` 事件。
          //   （`payTime` 同理：它是「这笔单付过钱」的证据，退款/对账都要看它。）
          order.status = from
          order.payTime = null
          order.updateTime = at
          // ★ 文案逐字抄 `WalletServiceImpl.settle` 的那句 —— 注意**不是**
          //   `MessageConstant.BALANCE_NOT_ENOUGH`（「余额不足」）：
          //   那一句是建单冻结（`freeze`）和调账（`deduct`）用的。
          //   金额按 Java 的 `BigDecimal.toString()` 渲染（`decimal(10,2)`
          //   → 恒两位小数），所以这里 `toFixed(2)` 而不是 `yuan()` 的裸数字。
          return {
            ok: false,
            msg: `结算失败：可用余额不足以支付 ${(actual / 100).toFixed(2)}，orderId=${orderId}`,
            insufficientBalance: true,
          }
        }
        wallet.balance -= actual
        wallet.updateTime = at
        recordTxn({
          memberId: order.memberId,
          orderId,
          type: 4,
          amount: -actual,
          remark: '充电结算',
          at,
        })
      }

      recordEvent({
        orderId,
        fromStatus: from,
        toStatus: 5,
        eventType: 'pay',
        source: 1,
        operatorId: order.memberId,
        detail: `实付 ${yuan(actual)}，解冻 ${yuan(frozen)}`,
        at,
      })
      return CAS_OK
    },

    markPayFailed(orderId, reason, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order || order.status !== 4) return
      order.status = 6
      order.remark = reason
      order.updateTime = at
      // ★★ 事件类型仍然是 `abnormal`（不是 `pay_failed`）★★
      //   这是后端的真实行为，也是前端 `describeOrderEvent()` 必须结合
      //   `toStatus` 判断的原因：`toStatus === 6` 是「余额不足」，不是设备异常。
      recordEvent({
        orderId,
        fromStatus: 4,
        toStatus: 6,
        eventType: 'abnormal',
        source: 3,
        operatorId: null,
        detail: reason,
        at,
      })
    },

    markRefunded(orderId, amount, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      if (order.status !== 5) return { ok: false, msg: statusConflict(order, 5, 7) }

      const cents = toCents(amount)
      if (cents <= 0) return { ok: false, msg: `退款金额必须大于 0，实际：${cents / 100}` }

      order.status = 7
      order.refundAmount = cents
      order.refundTime = at
      order.updateTime = at

      const wallet = db.walletOf(order.memberId)
      wallet.balance += cents
      wallet.updateTime = at
      recordTxn({ memberId: order.memberId, orderId, type: 5, amount: cents, remark: '订单退款', at })

      recordEvent({
        orderId,
        fromStatus: 5,
        toStatus: 7,
        eventType: 'refund',
        source: 4,
        operatorId: null,
        detail: `退款 ${yuan(cents)}`,
        at,
      })
      return CAS_OK
    },

    markCancelled(orderId, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      // ★★ 只有 `1 待启动` 能取消 ★★
      //   让充电中也走这条路等于给了一条「充完电把订单作废」的路径 ——
      //   那不是取消，是偷电。
      if (order.status !== 1) return { ok: false, msg: statusConflict(order, 1, 8) }

      order.status = 8
      order.updateTime = at

      // ① 解冻（0 元不写流水，避免对账噪音）
      const frozen = order.frozenAmount ?? 0
      if (frozen > 0) {
        const wallet = db.walletOf(order.memberId)
        wallet.frozenAmount -= frozen
        wallet.balance += frozen
        wallet.updateTime = at
        recordTxn({
          memberId: order.memberId,
          orderId,
          type: 3,
          amount: frozen,
          remark: '订单取消解冻',
          at,
        })
      }
      // ② 放枪 + 放权益锁。取消是「1 → 8」，那把枪从头到尾没充过电，
      //    不放的话它会永远停在「2 已占用」上再也点不动。
      db.releaseConnector(orderId, at)
      db.releasePackageLock(orderId, at)

      recordEvent({
        orderId,
        fromStatus: 1,
        toStatus: 8,
        eventType: 'cancel',
        source: 1,
        operatorId: order.memberId,
        detail: null,
        at,
      })
      return CAS_OK
    },

    markAbnormal(orderId, reason, at = db.now()) {
      const order = db.orderById(orderId)
      if (!order) return { ok: false, msg: '订单不存在' }
      // ★ 来源集合是 holdingMoney（钱还冻着但账结不掉），**不是** holdingConnector ——
      //   挂账最常见的两个场景是 `4 余额不够` 和 `6 重试仍失败`，它们都不占枪。
      if (!HOLDING_MONEY.includes(order.status)) {
        return { ok: false, msg: statusConflict(order, order.status, 9) }
      }
      const from = order.status

      order.status = 9
      order.remark = reason
      order.updateTime = at

      // ★ 放枪和放权益锁，但**绝不解冻** —— 挂账是「钱还得冻着等人工」的语义，
      //   解冻会把一笔本该被扣掉或退回的钱提前放走。
      db.releaseConnector(orderId, at)
      db.releasePackageLock(orderId, at)

      recordEvent({
        orderId,
        fromStatus: from,
        toStatus: 9,
        eventType: 'abnormal',
        source: 3,
        operatorId: null,
        detail: reason,
        at,
      })
      return CAS_OK
    },

    releaseConnector(orderId, at) {
      // ★ 谓词只有 `currentOrderId === orderId`，所以**幂等**：
      //   重复调、对一把从未被本单占用的枪调，都是无副作用。
      const connector = db.connectors.find((c) => c.currentOrderId === orderId)
      if (!connector) return
      connector.status = connector.status === 3 || connector.status === 2 ? 1 : connector.status
      connector.currentOrderId = null
      connector.version += 1
      connector.updateTime = at
    },

    releasePackageLock(orderId, at) {
      const mp = db.memberPackages.find((p) => p.lockedOrderId === orderId)
      if (!mp) return
      mp.lockedOrderId = null
      mp.updateTime = at
    },

    // ★ 只是把下面那个私有函数挂上来，实现一行没动 —— 见接口上的注释。
    recordTxn,

    sweepStalePendingPay(at = db.now()) {
      const before = at.getTime() - PAY_TIMEOUT_MINUTES * 60 * 1000
      let n = 0
      for (const order of db.orders) {
        // ★ 判据是 `settle_time`（账单生成时刻）而不是 `create_time` ——
        //   「他有多少时间可以慢慢付」的起点是账单生成，不是扫码。
        if (order.status !== 4 || !order.settleTime) continue
        if (order.settleTime.getTime() > before) continue
        db.markPayFailed(order.id, STALE_PENDING_PAY_REASON, at)
        n += 1
      }
      return n
    },

    expireOverduePackages(at = db.now()) {
      let n = 0
      for (const mp of db.memberPackages) {
        // ★ 只动「status = 1 但已经过期」的行。这个任务是**给缓存纠偏**，
        //   不是正确性的一环 —— 判断「这份权益还能不能用」的权威判据
        //   是 `expire_time > now`，不是 status。
        if (mp.status !== 1 || !mp.expireTime) continue
        if (mp.expireTime.getTime() > at.getTime()) continue
        mp.status = 3
        mp.updateTime = at
        n += 1
      }
      return n
    },
  }

  // ── 内部：流水与事件 ──────────────────────────────────────────────────

  /**
   * 写一条钱包流水。
   *
   * ★ 复刻 `uk_txn_order_type(order_id, type)` 这条唯一索引：同一订单的同一类型
   *   资金动作**永远只能有一条**。它挡的是「结算重试 / 重复回调 / 定时补偿」
   *   三条路径各自尝试扣款 —— 在数据库层，重复扣钱不可能发生。
   *   `order_id` 为 NULL 的流水不参与这条约束（充值、套餐买卖）。
   */
  function recordTxn(input: {
    memberId: number
    orderId: number | null
    type: WalletTxnType
    amount: Cents
    remark: string | null
    at: Date
  }): void {
    if (input.orderId !== null && db.walletTxns.some((t) => t.orderId === input.orderId && t.type === input.type)) {
      return
    }
    const wallet = db.walletOf(input.memberId)
    db.walletTxns.push({
      id: db.nextId('walletTxn'),
      transactionNo: db.nextNo('WT', input.at),
      memberId: input.memberId,
      orderId: input.orderId,
      type: input.type,
      amount: input.amount,
      balanceAfter: wallet.balance,
      remark: input.remark,
      createTime: input.at,
    })
  }

  /** 回滚一条流水。**只在「事务回滚」时调用**（见 markPaid 的余额不足分支）。 */
  function removeTxn(orderId: number, type: WalletTxnType): void {
    const idx = db.walletTxns.findIndex((t) => t.orderId === orderId && t.type === type)
    if (idx >= 0) db.walletTxns.splice(idx, 1)
  }

  function recordEvent(input: {
    orderId: number
    fromStatus: number | null
    toStatus: number | null
    eventType: string
    source: number
    operatorId: number | null
    detail: string | null
    at: Date
  }): void {
    db.events.push({
      id: db.nextId('event'),
      orderId: input.orderId,
      fromStatus: input.fromStatus,
      toStatus: input.toStatus,
      eventType: input.eventType,
      source: input.source,
      operatorId: input.operatorId,
      detail: input.detail,
      createTime: input.at,
    })
  }

  /**
   * 权益抵扣，返回**抵扣金额（分）**。
   *
   * ★ 产品假设：**权益抵扣的是电费，服务费照收**。这个规则没有被任何上游文档
   *   规定过，是实现时定下来的 —— 电费是平台代收后要付给电网的钱，
   *   服务费才是平台的收入。
   * ★ 覆盖率封顶 1：剩余量大于实际用量时抵扣不能超过电费本身，
   *   否则 `payAmount` 会变成负数，也就是**充电反而给用户钱**。
   * ★ 「抵扣多少就扣减多少」—— 两个数必须来自同一个量（`covered`），
   *   否则一份只剩 4 度的包充了 10 度之后余量会变成 -6，而用户没多付，
   *   平台在账面上却「欠用户 6 度」。
   */
  function applyPackage(order: DbOrder, totalAmount: Cents, at: Date): Cents {
    if (order.memberPackageId === null) return 0
    const mp = db.memberPackages.find((p) => p.id === order.memberPackageId)
    if (!mp || mp.status !== 1) return 0

    const energyFee = order.energyFee ?? 0
    const serviceFee = order.serviceFee ?? 0
    let covered = 0

    if (mp.packageType === 3) {
      // 折扣包不按覆盖率：抵扣 = round2(服务费 × (1 − 折扣系数))
      const discount = mp.serviceDiscount ?? 0
      const off = Math.round(serviceFee * (1 - discount))
      mp.updateTime = at
      return Math.max(0, Math.min(off, totalAmount))
    }

    if (mp.packageType === 1) {
      const actual = order.totalEnergyKwh ?? 0
      const remain = mp.remainingKwh ?? 0
      covered = Math.min(actual, remain)
      if (actual > 0) {
        mp.remainingKwh = kwh(remain - covered)
        if (mp.remainingKwh <= 0) mp.status = 2
      }
    } else if (mp.packageType === 2) {
      const actual = (order.durationSeconds ?? 0) / 60
      const remain = mp.remainingMinutes ?? 0
      covered = Math.min(actual, remain)
      if (actual > 0) {
        mp.remainingMinutes = Math.round(remain - covered)
        if ((mp.remainingMinutes ?? 0) <= 0) mp.status = 2
      }
    } else if (mp.packageType === 4) {
      // 次卡：一次充电就是一整笔，覆盖率恒为 1
      covered = 1
      mp.remainingTimes = Math.max(0, (mp.remainingTimes ?? 1) - 1)
      if (mp.remainingTimes <= 0) mp.status = 2
    }
    mp.updateTime = at

    if (covered <= 0) return 0
    const ratio =
      mp.packageType === 1
        ? covered / Math.max(order.totalEnergyKwh ?? 1, 0.0001)
        : mp.packageType === 2
          ? covered / Math.max((order.durationSeconds ?? 1) / 60, 0.0001)
          : 1
    const off = Math.round(energyFee * Math.min(ratio, 1))
    return Math.max(0, Math.min(off, energyFee))
  }

  return db
}

// ══════════════════════════════════════════════════════════════════════════
// 计费引擎（BillingEngine 的等价实现，只为 fixtures / settle 产出分段）
// ══════════════════════════════════════════════════════════════════════════

interface SegmentOut {
  periodType: number
  startTime: Date
  endTime: Date
  durationSeconds: number
  energyKwh: number
  energyPrice: number
  servicePrice: number
  energyFee: Cents
  serviceFee: Cents
  amount: Cents
}

interface BilledOut {
  segments: SegmentOut[]
  energyFee: Cents
  serviceFee: Cents
  totalAmount: Cents
}

/** `HH:mm:ss`（可能是 `24:00:00`）→ 从零点起的分钟数。 */
function minuteOfClock(clock: string): number {
  const [h, m] = clock.split(':')
  return Number(h) * 60 + Number(m)
}

/**
 * 把充电窗口按电价时段切开、逐段计价。
 *
 * ★★ 与后端 `BillingEngine` 的**唯一**约定是那条不变式：
 *   `Σ segments[].amount === totalAmount`，而 `totalAmount` 的定义**就是**
 *   已舍入分段的和（不是另外算一遍再对账）。
 *   所以这里全程用整数分，且总额是加出来的，不是乘出来的 ——
 *   前端 `segmentsMatchTotal()` 在演示里永远成立。
 *
 * ★ 电量按**时间正比**分摊（明写的假设：全程恒功率），用望远镜式累进
 *   `alloc_i = round4(E × cum_i / S) − round4(E × cum_{i-1} / S)`
 *   保证两个端点相消、`Σ alloc == E` 精确成立，且每段都非负。
 *
 * ★ 切点是「每条规则在每一天的边界 ∩ (S, E)」，`plusDays(1)` 天然落到次日零点，
 *   所以**跨天不需要任何特判** —— 这是选这个算法的全部理由。
 */
export function billSegments(
  start: Date,
  end: Date,
  totalEnergyKwh: number,
  rules: DbTariffRule[],
): BilledOut | { msg: string } {
  if (rules.length === 0) {
    return { msg: '该站点当前没有配置计费方案，请联系运营' }
  }

  const cuts: number[] = [start.getTime(), end.getTime()]
  const dayMs = 24 * 60 * 60 * 1000
  const firstDay = new Date(start.getFullYear(), start.getMonth(), start.getDate()).getTime()
  for (let day = firstDay; day <= end.getTime(); day += dayMs) {
    for (const rule of rules) {
      for (const clock of [rule.startTime, rule.endTime]) {
        const at = day + minuteOfClock(clock) * 60 * 1000
        if (at > start.getTime() && at < end.getTime()) cuts.push(at)
      }
    }
  }
  const sorted = [...new Set(cuts)].sort((a, b) => a - b)

  const windows: { start: Date; end: Date; rule: DbTariffRule }[] = []
  for (let i = 0; i < sorted.length - 1; i += 1) {
    const s = new Date(sorted[i])
    const e = new Date(sorted[i + 1])
    // 半开区间 [start, end)：用**区间起点**反查规则，恰好命中一条
    const minute = s.getHours() * 60 + s.getMinutes()
    const matched = rules.filter((r) => minute >= minuteOfClock(r.startTime) && minute < minuteOfClock(r.endTime))
    if (matched.length === 0) {
      return { msg: `计费时段存在缺口，${formatServerTime(s)} 没有任何计费规则覆盖它` }
    }
    if (matched.length > 1) {
      return { msg: `计费时段重叠，${formatServerTime(s)} 同时被两条规则覆盖` }
    }
    windows.push({ start: s, end: e, rule: matched[0] })
  }
  if (windows.length === 0) return { msg: '计费引擎未产出任何分段' }

  // 望远镜式电量分摊（对齐 4 位小数）
  const cumSeconds: number[] = []
  let running = 0
  for (const w of windows) {
    running += Math.round((w.end.getTime() - w.start.getTime()) / 1000)
    cumSeconds.push(running)
  }
  const totalSeconds = running
  const energies: number[] = []
  let previous = 0
  for (const cum of cumSeconds) {
    const current = kwh((totalEnergyKwh * cum) / totalSeconds)
    energies.push(kwh(current - previous))
    previous = current
  }

  const segments: SegmentOut[] = []
  let energyFeeSum = 0
  let serviceFeeSum = 0
  let totalAmount = 0

  windows.forEach((w, i) => {
    const energy = energies[i]
    // ★ 两个分量**各自**舍入到分，绝不是 round2(电量 × (电费 + 服务费) ) ——
    //   库里三列都存，对账 SQL `WHERE amount <> energy_fee + service_fee` 必须返回 0 行。
    const energyFee = Math.round(energy * w.rule.energyPrice * 100)
    const serviceFee = Math.round(energy * w.rule.servicePrice * 100)
    const amount = energyFee + serviceFee

    segments.push({
      periodType: w.rule.periodType,
      startTime: w.start,
      endTime: w.end,
      durationSeconds: cumSeconds[i] - (cumSeconds[i - 1] ?? 0),
      energyKwh: energy,
      energyPrice: w.rule.energyPrice,
      servicePrice: w.rule.servicePrice,
      energyFee,
      serviceFee,
      amount,
    })
    energyFeeSum += energyFee
    serviceFeeSum += serviceFee
    totalAmount += amount
  })

  return { segments, energyFee: energyFeeSum, serviceFee: serviceFeeSum, totalAmount }
}

/**
 * 后端 `statusConflict()` 的**逐字复刻**。
 *
 * ★ 消息里带上当前状态和期望状态：只写「操作失败」的话，用户和客服都无法判断
 *   这是并发（刷新重试即可）还是用错了流程（想取消一笔正在充电的订单）。
 * ★ 前端按**前缀**匹配它（`MSG.ORDER_STATUS_CONFLICT_PREFIX`），所以
 *   「订单当前状态不允许该操作」这 11 个字一个字都不能改。
 */
function statusConflict(order: DbOrder, expected: number, target: number): string {
  return (
    '订单当前状态不允许该操作：订单 ' +
    order.orderNo +
    ' 当前是「' +
    orderStatusLabel(order.status) +
    '」，该操作要求「' +
    orderStatusLabel(expected) +
    '」并转入「' +
    orderStatusLabel(target) +
    '」。若刚刚重复提交过，刷新后重试即可'
  )
}

/**
 * `StopReason` 的标签，只用于拼事件 detail。
 *
 * ★ 查不到时回退成**数字**、`null` 回退成「未上报」—— 逐字对应后端
 *   `StopReason.of(code).getLabel()` 与 `describeStopReason(null)`。
 *   「未上报」这四个字是有意义的（设备没告诉我们为什么停），
 *   而「null」不是 —— 所以那一条不能也走数字回退。
 */
const STOP_REASON_LABEL: Record<number, string> = {
  1: '用户主动',
  2: '充满自停',
  3: '余额不足',
  4: '设备故障',
  5: '超时',
  6: '运维干预',
}

export { HOLDING_CONNECTOR, HOLDING_MONEY }
