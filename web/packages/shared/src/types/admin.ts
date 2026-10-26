/**
 * 管理端接口的入参 / 出参类型。
 *
 * ★★ 这一层必须**逐字段手写**，不许偷懒用 `any`。★★
 *   后端 Jackson 配了 `FAIL_ON_UNKNOWN_PROPERTIES = false`，
 *   字段名拼错**不会报错**，只会被静默丢弃 —— 表现为「参数改了没反应」。
 *   有了这一层，拼错在 `vue-tsc` 阶段就被拦下；没有它，排查方向每次都跑偏。
 *
 * ★ 所有 `LocalDateTime` 在后端序列化成 `yyyy-MM-dd HH:mm:ss` 的**字符串**，
 *   不是 ISO 8601。所以这里一律 `string`，且**禁止** `new Date(str)` 直接用
 *   （Safari 会 Invalid Date）—— 走 `parseServerTime()`。
 *
 * ★ 所有 `BigDecimal` 都是**字符串化的数字**。JSON 里到前端是 number 还是 string
 *   取决于后端序列化配置，但**必须按字符串处理**：浮点求和对不上后端的
 *   「只在最后取整一次、保证 Σ分段 == 总额」。所以类型写成 `string | number`，
 *   运算一律走 `money.ts` 的 `sumDecimal` 等函数。
 */

/** 后端时间字符串。格式 `yyyy-MM-dd HH:mm:ss`。 */
export type ServerTime = string
/** 后端金额/数值。按字符串处理，运算走 money.ts。 */
export type Decimal = string | number

// ══════════════════════════════════════════════════════════════════════════
// 通用
// ══════════════════════════════════════════════════════════════════════════

/**
 * 上传接口的返回。
 *
 * ★★ 这里**刻意是 `string` 而不是 `{url}` 对象** ★★
 *   已核对两个 Controller：`AdminUploadController` 与 `MemberUploadController`
 *   的签名**都是** `public Result<String> upload(...)`，data 就是那个 URL 字符串。
 *   后端**没有 `UploadVO` 这个类**（全仓库 grep 0 处）。
 *
 *   ★ 曾经这里写过一个 `interface UploadVO { url: string }`，是凭「一般上传接口
 *     都长这样」猜的。它的代价不是编译错误，而是 `"http://x".url === undefined`
 *     → 上传永远返回空串 → 「上传成功但图片不显示」，不报错也不进 catch。
 *     **前端不该给后端发明类型。**
 *
 * ★ 后端返回的是**绝对 URL**（`http://localhost:8081/img/xxx.jpg`）。
 *   直接塞进 `<img :src>` 会打到 8081 —— 而后端零 CORS，图片能显示但
 *   换域名就挂。统一走 `resolveAssetUrl()` 剥掉 origin，改走同源代理。
 *   （`uploadFile()` 已经做掉了这步，调用方拿到的是 `/img/xxx.jpg`。）
 */
export type UploadUrl = string

// ══════════════════════════════════════════════════════════════════════════
// 认证 / 运维
// ══════════════════════════════════════════════════════════════════════════

export interface StaffLoginVO {
  id: number
  username: string
  name: string
  role: number
  stationId: number | null
  token: string
}

export interface StaffVO {
  id: number
  username: string
  name: string
  phone: string | null
  /**
   * ★ 注意这里 `sex` 是**字符串**，而车主端的 `gender` 是**数字枚举**。
   *   两端不一样，别顺手统一。
   */
  sex: string | null
  role: number
  stationId: number | null
  status: number
  createTime: ServerTime
}

// ══════════════════════════════════════════════════════════════════════════
// 站点
// ══════════════════════════════════════════════════════════════════════════

export interface StationVO {
  id: number
  code: string
  name: string
  operator: string | null
  province: string | null
  city: string | null
  district: string | null
  address: string | null
  longitude: Decimal | null
  latitude: Decimal | null
  servicePhone: string | null
  /** `HH:mm` 或 `HH:mm:ss`，后端当字符串存。 */
  openTime: string | null
  closeTime: string | null
  parkingFee: Decimal | null
  status: number
  statusLabel: string
  createTime: ServerTime
  updateTime: ServerTime
}

export interface StationDTO {
  code: string
  name: string
  operator?: string | null
  province?: string | null
  city?: string | null
  district?: string | null
  address?: string | null
  longitude?: Decimal | null
  latitude?: Decimal | null
  servicePhone?: string | null
  openTime?: string
  closeTime?: string
  parkingFee?: Decimal | null
  status?: number
}

/** ★ 没有 `version` —— 站点和桩上都没有乐观锁，只有充电枪有。 */
export interface StationUpdateDTO {
  code?: string
  name?: string
  operator?: string | null
  province?: string | null
  city?: string | null
  district?: string | null
  address?: string | null
  longitude?: Decimal | null
  latitude?: Decimal | null
  servicePhone?: string | null
  openTime?: string
  closeTime?: string
  parkingFee?: Decimal | null
  status?: number
}

export interface StationPageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  code?: string
  city?: string
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 充电桩
// ══════════════════════════════════════════════════════════════════════════

export interface ChargerVO {
  id: number
  stationId: number
  stationName: string | null
  deviceNo: string
  name: string
  /** 1 直流快充 / 2 交流慢充 */
  type: number
  typeLabel: string
  model: string | null
  ratedPowerKw: Decimal | null
  gunCount: number
  /** ★ 0-4，**没有白名单** —— 类注释里「只能改故障/维护中」是过时的。 */
  status: number
  statusLabel: string
  lastHeartbeatTime: ServerTime | null
  firmwareVersion: string | null
  createTime: ServerTime
  updateTime: ServerTime
}

export interface ChargerDTO {
  stationId: number
  deviceNo: string
  name: string
  type: number
  model?: string | null
  ratedPowerKw?: Decimal | null
  firmwareVersion?: string | null
  lastHeartbeatTime?: ServerTime | null
}

/** ★ 无 `version`。 */
export interface ChargerUpdateDTO {
  deviceNo?: string
  name?: string
  type?: number
  model?: string | null
  ratedPowerKw?: Decimal | null
  status?: number
  firmwareVersion?: string | null
}

export interface ChargerPageQuery {
  pageNum?: number
  pageSize?: number
  stationId?: number
  deviceNo?: string
  name?: string
  type?: number
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 充电枪  ★ 唯一带乐观锁的实体
// ══════════════════════════════════════════════════════════════════════════

export interface ConnectorVO {
  id: number
  chargerId: number
  stationId: number
  gunNo: string
  connectorType: string | null
  ratedPowerKw: Decimal | null
  /** 0 离线 / 1 空闲 / 2 已占用 / 3 充电中 / 4 故障 / 5 维护 */
  status: number
  statusLabel: string
  currentOrderId: number | null
  totalChargeCount: number
  /** ★★ 编辑时必须**原样回传**，否则后端 CAS 失败。 */
  version: number
  createTime: ServerTime
  updateTime: ServerTime
}

export interface ConnectorDTO {
  chargerId: number
  gunNo: string
  connectorType?: string | null
  ratedPowerKw?: Decimal | null
}

export interface ConnectorUpdateDTO {
  /** ★★ 必填。漏传就是乐观锁失效或直接报错。 */
  version: number
  gunNo?: string
  connectorType?: string | null
  ratedPowerKw?: Decimal | null
  /** ★ 只接受 {0,1,4,5} —— 见 `CONNECTOR_ADMIN_SETTABLE`。传 2/3 会被后端拒绝。 */
  status?: number
}

export interface ConnectorPageQuery {
  pageNum?: number
  pageSize?: number
  stationId?: number
  chargerId?: number
  gunNo?: string
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 计费模板 / 规则
// ══════════════════════════════════════════════════════════════════════════

export interface TariffRuleVO {
  id: number
  templateId: number
  /** 1 尖峰 / 2 高峰 / 3 平段 / 4 低谷 */
  periodType: number
  periodTypeLabel: string
  /** `HH:mm:ss` */
  startTime: string
  /** `HH:mm:ss`，可能是 `24:00:00` */
  endTime: string
  energyPrice: Decimal
  servicePrice: Decimal
  seq: number
  createTime: ServerTime
  updateTime: ServerTime
}

export interface TariffRuleDTO {
  periodType: number
  startTime: string
  endTime: string
  energyPrice: Decimal
  servicePrice: Decimal
  seq?: number
}

export interface TariffTemplateVO {
  id: number
  code: string
  name: string
  region: string | null
  description: string | null
  status: number
  statusLabel: string
  /** ★ 详情/列表都可能带规则；列表页若为 null 说明后端没装配。 */
  rules: TariffRuleVO[] | null
  createTime: ServerTime
  updateTime: ServerTime
}

export interface TariffTemplateDTO {
  code: string
  name: string
  region?: string | null
  description?: string | null
  status?: number
  rules: TariffRuleDTO[]
}

export interface TariffTemplateUpdateDTO {
  code?: string
  name?: string
  region?: string | null
  description?: string | null
  status?: number
  rules?: TariffRuleDTO[]
}

export interface TariffTemplatePageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  code?: string
  region?: string
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 站点计费绑定
// ══════════════════════════════════════════════════════════════════════════

export interface StationTariffVO {
  id: number
  stationId: number
  templateId: number
  templateName: string | null
  templateCode: string | null
  effectiveFrom: ServerTime | null
  effectiveTo: ServerTime | null
  priority: number
  status: number
  statusLabel: string
  createTime: ServerTime
  updateTime: ServerTime
}

export interface StationTariffDTO {
  stationId: number
  templateId: number
  effectiveFrom?: ServerTime | null
  effectiveTo?: ServerTime | null
  priority?: number
  status?: number
}

/**
 * ★★ 更新只能改这三个字段 ★★
 *   `stationId` / `templateId` 不在 UpdateDTO 里 —— 绑定关系不可改，
 *   要换模板必须删了重建。编辑弹窗里那两个下拉必须是**只读展示**，
 *   做成可编辑的话用户改完提交，后端静默忽略（FAIL_ON_UNKNOWN 关闭），
 *   用户会以为改成功了。
 */
export interface StationTariffUpdateDTO {
  effectiveTo?: ServerTime | null
  priority?: number
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 套餐分类
// ══════════════════════════════════════════════════════════════════════════

export interface CategoryVO {
  id: number
  name: string
  /** 1 充电套餐分类 / 2 增值服务分类 */
  type: number
  typeLabel: string
  sort: number
  status: number
  statusLabel: string
  createTime: ServerTime
}

export interface CategoryDTO {
  name: string
  type: number
  sort?: number
  status?: number
}

/** ★ 无 `type` —— 分类的归属类型建立后不可改。 */
export interface CategoryUpdateDTO {
  name?: string
  sort?: number
  status?: number
}

export interface CategoryPageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  type?: number
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 充电套餐
// ══════════════════════════════════════════════════════════════════════════

export interface ChargePackageVO {
  id: number
  name: string
  categoryId: number
  /** 1 电量包 / 2 时长包 / 3 服务费折扣包 / 4 次卡 */
  packageType: number
  packageTypeLabel: string
  stationId: number | null
  price: Decimal
  originPrice: Decimal | null
  energyKwh: Decimal | null
  durationMinutes: number | null
  times: number | null
  serviceDiscount: Decimal | null
  validDays: number
  description: string | null
  image: string | null
  status: number
  statusLabel: string
  sort: number
  createTime: ServerTime
  updateTime: ServerTime
}

/**
 * ★★ 四种 `packageType` 各自只用到**一部分**额度字段 ★★
 *   电量包 → `energyKwh`；时长包 → `durationMinutes`；
 *   服务费折扣包 → `serviceDiscount`；次卡 → `times`。
 *
 *   后端**不校验**「你填了不相关的字段」—— 多填的会被静默存下但永不生效。
 *   所以新建表单必须**按 packageType 动态显示相关字段**，
 *   而不是把 5 个额度字段全摆出来（那必然导致运营填错）。
 */
export interface ChargePackageDTO {
  name: string
  categoryId: number
  packageType: number
  stationId?: number | null
  price: Decimal
  originPrice?: Decimal | null
  energyKwh?: Decimal | null
  durationMinutes?: number | null
  times?: number | null
  serviceDiscount?: Decimal | null
  validDays?: number
  description?: string | null
  image?: string | null
  status?: number
  sort?: number
}

/** ★ 无 `packageType` / `stationId` / `categoryId` 可改（categoryId 在，但类型不可改）。 */
export interface ChargePackageUpdateDTO {
  name?: string
  categoryId?: number
  price?: Decimal
  originPrice?: Decimal | null
  energyKwh?: Decimal | null
  durationMinutes?: number | null
  times?: number | null
  serviceDiscount?: Decimal | null
  validDays?: number
  description?: string | null
  image?: string | null
  status?: number
  sort?: number
}

export interface ChargePackagePageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  categoryId?: number
  packageType?: number
  stationId?: number
  status?: number
}

// ══════════════════════════════════════════════════════════════════════════
// 车主
// ══════════════════════════════════════════════════════════════════════════

/**
 * ★★ `MemberVO` 里**没有 `balance`** ★★
 *   管理端没有任何接口能读到车主钱包余额 —— 只有 `POST /wallet/{id}/adjust`
 *   会**在响应里**返回调账后的 `WalletVO`。
 *   所以「调账前余额是多少」在管理端是**看不到的**，UI 必须写明这一点，
 *   不要留一个永远显示「--」的余额列让用户以为是 bug。
 */
export interface MemberVO {
  id: number
  phone: string
  nickname: string | null
  avatar: string | null
  gender: number
  genderLabel: string
  /** 1 正常 / 0 禁用 */
  status: number
  statusLabel: string
  lastLoginTime: ServerTime | null
  createTime: ServerTime
}

export interface MemberPageQuery {
  pageNum?: number
  pageSize?: number
  /**
   * ★★ 前缀匹配，不是模糊匹配 ★★
   *   命中过多时后端直接返回 `code:0`「该手机号前缀匹配到的车主过多…」。
   *   所以搜索框**必须 ≥6 位数字才发请求**（见 `PHONE_SEARCH_MIN_LENGTH`）。
   */
  phone?: string
  nickname?: string
  status?: number
}

export interface MemberUpdateDTO {
  nickname?: string
  avatar?: string | null
  gender?: number
}

export interface MemberStatusDTO {
  status: number
}

// ══════════════════════════════════════════════════════════════════════════
// 车主权益（会员套餐）
// ══════════════════════════════════════════════════════════════════════════

export interface MemberPackageVO {
  id: number
  packageId: number
  packageType: number
  packageTypeLabel: string
  packageName: string
  payAmount: Decimal | null
  totalKwh: Decimal | null
  remainingKwh: Decimal | null
  totalMinutes: number | null
  remainingMinutes: number | null
  totalTimes: number | null
  remainingTimes: number | null
  serviceDiscount: Decimal | null
  effectiveFrom: ServerTime | null
  expireTime: ServerTime | null
  /** 1 生效中 / 2 已用尽 / 3 已过期 / 4 已作废 */
  status: number
  statusLabel: string
  remark: string | null
  createTime: ServerTime
}

/** `POST /{id}/void` 的响应。★ 会真的退钱，所以必须二次确认 + 防重。 */
export interface MemberPackageVoidVO {
  memberPackageId: number
  memberId: number
  packageName: string
  refundAmount: Decimal
}

export interface MemberPackageVoidDTO {
  remark?: string
}

// ══════════════════════════════════════════════════════════════════════════
// 钱包
// ══════════════════════════════════════════════════════════════════════════

export interface WalletVO {
  balance: Decimal
  frozenAmount: Decimal
  totalRecharge: Decimal
}

export interface WalletTransactionVO {
  transactionNo: string
  orderId: number | null
  /** 1 充值 / 2 冻结 / 3 解冻 / 4 扣款 / 5 退款 / 6 套餐购买 / 7 管理员调账 / 8 套餐退款 */
  type: number
  typeLabel: string
  /**
   * ★★ **带符号** ★★
   *   冻结写的是 `amount.negate()`，也就是**负数**。
   *   所以渲染时：颜色看**符号**，文案看 `type` ——
   *   不能从 `type` 反推方向（冻结是负、解冻是正，但两者都是「非消费」）。
   */
  amount: Decimal
  balanceAfter: Decimal
  remark: string | null
  createTime: ServerTime
}

/**
 * 管理员调账。
 *
 * ★★ `amount` 是**带符号**的：正数加钱、负数扣钱 ★★
 * ★★ 后端注释明确写了 `@DecimalMin/@DecimalMax` **拦不住 0** ★★
 *   所以前端必须自己拒绝 0（见 `isAdjustableAmount()`），
 *   否则会写出一条金额为 0 的流水，污染对账。
 */
export interface WalletAdjustDTO {
  amount: Decimal
  remark: string
}

// ══════════════════════════════════════════════════════════════════════════
// 订单  ★ 状态机最复杂的一块
// ══════════════════════════════════════════════════════════════════════════

export interface ChargingSegmentVO {
  seq: number
  periodType: number
  periodTypeLabel: string
  startTime: ServerTime
  endTime: ServerTime
  durationSeconds: number
  energyKwh: Decimal
  energyPrice: Decimal
  servicePrice: Decimal
  energyFee: Decimal
  serviceFee: Decimal
  /** ★ 后端保证 Σ`amount` == 订单 `totalAmount`（只在最后取整一次）。前端只读，不重算。 */
  amount: Decimal
}

/**
 * 订单事件。
 *
 * ★★ 两处后端没给标签，前端必须自建映射 ★★
 *   1. `eventType` 是**字符串枚举**（`create`/`start`/`end`/…），不是数字
 *   2. `fromStatus` / `toStatus` 是**裸 Integer，没有 label**
 *   见 `constants/orderEvent.ts` 与 `orderStatusLabel()`。
 *
 * ★★ `abnormal` 有两种含义 ★★
 *   支付时余额不足，后端自己把订单 4→6，但写的 `eventType` 是 `abnormal`。
 *   所以渲染时**必须结合 `toStatus`**：`toStatus === 6` 是「余额不足」，
 *   不是真的设备异常。见 `describeOrderEvent()`。
 */
export interface ChargingOrderEventVO {
  fromStatus: number | null
  toStatus: number | null
  eventType: string
  /** 1 用户端 / 2 设备上报 / 3 定时任务 / 4 运维 / 5 支付回调 */
  source: number
  sourceLabel: string
  detail: string | null
  createTime: ServerTime
}

/** 管理端订单列表行。 */
export interface AdminOrderVO {
  id: number
  orderNo: string
  status: number
  statusLabel: string
  memberId: number
  memberPhone: string | null
  memberNickname: string | null
  stationId: number
  stationName: string | null
  chargerId: number
  connectorId: number
  plateNo: string | null
  createTime: ServerTime
  startTime: ServerTime | null
  endTime: ServerTime | null
  settleTime: ServerTime | null
  totalEnergyKwh: Decimal | null
  totalAmount: Decimal | null
  discountAmount: Decimal | null
  payAmount: Decimal | null
  /** ★ 冻结中金额。订单终结前会一直占着，UI 要能看出「这笔钱被锁着」。 */
  frozenAmount: Decimal | null
}

export interface AdminOrderDetailVO extends AdminOrderVO {
  tariffTemplateId: number | null
  memberPackageId: number | null
  durationSeconds: number | null
  startMeterKwh: Decimal | null
  endMeterKwh: Decimal | null
  startSoc: number | null
  endSoc: number | null
  energyFee: Decimal | null
  serviceFee: Decimal | null
  payMethod: number | null
  payMethodLabel: string | null
  payTime: ServerTime | null
  refundTime: ServerTime | null
  refundAmount: Decimal | null
  stopReason: number | null
  stopReasonLabel: string | null
  segments: ChargingSegmentVO[] | null
  events: ChargingOrderEventVO[] | null
}

export interface AdminOrderPageQuery {
  pageNum?: number
  pageSize?: number
  orderNo?: string
  status?: number
  stationId?: number
  /** ★ 前缀匹配，≥6 位数字。 */
  memberPhone?: string
  /**
   * ★★ 默认**必须**带近 7 天范围 ★★
   *   无任何过滤条件时后端是全表 filesort，慢到超时。
   *   见 `defaultOrderRange()`。
   */
  createTimeFrom?: ServerTime
  createTimeTo?: ServerTime
}

export interface OrderStartDTO {
  /**
   * ★★ **可空**，且缺数据时**不要填 0** ★★
   *   已核实后端 `OrderStartDTO.startMeterKwh` **没有** `@NotNull`。
   *   它是**累计读数**，填 0 会让 `totalEnergyKwh = end - 0 = 整个电表读数`，
   *   算出一笔几百度的订单 —— **而且不会报错**。
   *   留空会走「缺数据 → 结算进异常挂账」，那是一个有人会去看的失败。
   *
   * ★ 类型写 `?` 而不是 `Decimal`：这里的可空性是后端契约的一部分，
   *   写成必填会逼调用点用 `as` 绕过类型 —— 而 `as` 一旦用上，
   *   **字段名拼错也会一起被绕过**（后端关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`，
   *   表现为「参数改了没反应」而不是报错，排查方向每次跑偏）。
   */
  startMeterKwh?: Decimal | null
  startSoc?: number | null
}

export interface OrderEndDTO {
  /** ★ **必填**（与 start 不同）：`OrderEndDTO.endMeterKwh` 上有 `@NotNull`。 */
  endMeterKwh: Decimal
  endSoc?: number | null
  /**
   * 1 用户主动 / 2 充满自停 / 3 余额不足 / 4 设备故障 / 5 超时 / 6 运维干预
   *
   * ★ 可空，而且 **NULL 有信息量**：只有「充电真的开始过」的订单才有停止原因。
   *   后端**没有** `@NotNull`，所以类型也不能写成必填。
   */
  stopReason?: number | null
  /**
   * 设备上报的结束时刻。
   *
   * ★ 可空时服务端取 `now()`，那是**降级不是等价** ——
   *   计费窗口用哪个时间会直接改变金额（跨尖峰/平谷边界差一个电价档）。
   */
  endTime?: ServerTime | null
}

/**
 * 退款。
 *
 * ★★ 只有绝对上限，**没有相对上限** ★★
 *   后端有 `@DecimalMin("0.01")` + `@DecimalMax("100000.00")`，
 *   但**没有**校验 `amount <= payAmount` —— 也就是说可以退超过实付的金额。
 *   前端必须自己挡（`isRefundableAmount(value, payAmount)`）。
 */
export interface OrderRefundDTO {
  amount: Decimal
}

// ── AI 运维助手 ────────────────────────────────────────────────────────────

/**
 * 向 AI 运维助手提问。
 *
 * `sessionId` 为空时后端会新开一个会话，并把生成的 id 回传；**下一轮要把它带回来**，
 * 否则助手不记得上一句聊了什么。它存在内存里，重启即失效，所以不能当长期标识用。
 */
export interface AiChatDTO {
  /** 后端 `@Size(max = 500)`。 */
  question: string
  sessionId?: string | null
}

/**
 * 助手的一次回答。
 *
 * `references` 是这一轮**实际召回**的知识片段，用来回答「它为什么这么说」。
 * 它可能为空数组 —— 知识库里没有相关内容时助手应当直说，而不是编一个依据出来。
 */
export interface AiChatVO {
  sessionId: string
  answer: string
  /**
   * 这一轮**实际拿去检索**的问题。
   *
   * 追问会被后端用会话历史改写后再检索（「那第二点具体怎么做」→ 一句能独立检索的
   * 完整问题），这时它和用户问的那句不一样：召回结果取决于它，不显示的话
   * 「问的是 A、召回的却是别的东西」看起来像检索坏了。
   */
  retrievalQuery: string | null
  references: KnowledgeChunkVO[] | null
}

/** 一条召回的知识片段。`score` 是余弦相似度，越大越相关。 */
export interface KnowledgeChunkVO {
  source: string
  section: string
  score: number | null
  snippet: string
}

/**
 * 运维工单。
 *
 * `status` 的三档见 `TICKET_STATUS`；`source` 区分人工建单和 AI 建单，
 * 排查「AI 建的单内容不对」时先看这一列。
 */
export interface MaintenanceTicketVO {
  id: number
  ticketNo: string
  chargerId: number | null
  /** 由后端批量补上；桩已被删除时为 null。 */
  chargerName: string | null
  stationId: number | null
  alarmId: number | null
  title: string
  content: string | null
  source: number | null
  sourceLabel: string | null
  status: number | null
  statusLabel: string | null
  createTime: ServerTime | null
  updateTime: ServerTime | null
}

export interface MaintenanceTicketPageQuery {
  pageNum?: number
  pageSize?: number
  stationId?: number
  chargerId?: number
  status?: number
  source?: number
}

/** 改工单状态。后端只收 0 / 1 / 2，越界是 400。 */
export interface MaintenanceTicketStatusDTO {
  status: number
}
