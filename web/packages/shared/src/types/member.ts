/**
 * 车主端（H5）接口的入参 / 出参类型。
 *
 * ★ 与 `admin.ts` 的差异点，容易搞混的三处：
 *   1. 车主端 `gender` 是**数字枚举**（0/1/2），管理端 `sex` 是**字符串**
 *   2. 车主端有 `/me` 探针（管理端也有，但语义不同 —— 见 `MemberVO.status`）
 *   3. 车主端的订单接口**只能按 `status` 过滤**，没有时间范围
 */

/**
 * ★ 既要 `import type` 也要 `export type`：
 *   `export type { X } from './admin'` **不会**在本文件里建立 `X` 这个名字的绑定，
 *   而下面的 `ChargingOrderDetailVO` 要拿它们做父接口。
 *   只写 export 的话报的是 `Cannot find name 'X'` —— 看起来像没导出，其实是没导入。
 */
import type {
  ChargingOrderEventVO,
  ChargingSegmentVO,
  Decimal,
  ServerTime,
} from './admin'

/**
 * ★★ 下面这批类型两端**共用同一个后端类**，所以从 admin 转出而不是重写 ★★
 *
 *   一开始我在两个文件里各写了一份，结果 `index.ts` 的 `export type *` 直接报
 *   「已经导出过同名成员」。这其实是好事 —— 编译器逼着我承认它们本来就是同一个契约：
 *
 *     `ChargingSegmentVO` · `ChargingOrderEventVO` · `MemberPackageVO`
 *     `MemberUpdateDTO` · `WalletVO` · `WalletTransactionVO`
 *
 *   重写一份的风险不是重复，而是**将来只改一边** ——
 *   那时两端会在同一个字段上产生不同的理解，而且不会有任何报错。
 */
export type {
  ChargingOrderEventVO,
  ChargingSegmentVO,
  MemberPackageVO,
  MemberUpdateDTO,
  WalletTransactionVO,
  WalletVO,
} from './admin'

export type { Decimal, ServerTime }

// ══════════════════════════════════════════════════════════════════════════
// 认证
// ══════════════════════════════════════════════════════════════════════════

export interface MemberLoginVO {
  id: number
  phone: string
  nickname: string | null
  token: string
}

export interface MemberLoginDTO {
  phone: string
  password: string
}

/** ★ 注册即登录 —— 响应体与登录相同，直接存 token 即可，不用再登一次。 */
export interface MemberRegisterDTO {
  phone: string
  password: string
  nickname?: string | null
}

export interface MemberPasswordDTO {
  oldPassword: string
  newPassword: string
}

// ══════════════════════════════════════════════════════════════════════════
// 车主信息
// ══════════════════════════════════════════════════════════════════════════

/**
 * 车主资料。
 *
 * ★★ `status` 是**强制登出探针**的判据 ★★
 *   判定条件是「HTTP 200 **且** `status === 0`」—— 不能只看 200，
 *   因为禁用后后端**不会**撤销 token，被禁用的车主在 7 天窗口内
 *   请求照样返回 200，只是资金端点会在 Service 层返回「已被禁用」。
 *   见 H5 的 `useMemberProbe`。
 */
export interface MemberProfileVO {
  id: number
  phone: string
  nickname: string | null
  avatar: string | null
  gender: number
  genderLabel: string
  status: number
  statusLabel: string
  lastLoginTime: ServerTime | null
  createTime: ServerTime
}

// `MemberUpdateDTO` 见文件头的转出说明。

// ══════════════════════════════════════════════════════════════════════════
// 车辆
// ══════════════════════════════════════════════════════════════════════════

export interface VehicleVO {
  id: number
  plateNo: string
  /** 1 蓝 / 2 黄 / 3 绿 / 4 渐变绿 */
  plateColor: number
  plateColorLabel: string
  brand: string | null
  model: string | null
  batteryCapacityKwh: Decimal | null
  /** ★ **数字**：1 是默认车，0 不是。判默认车要用 `=== 1`，别用真值判断。 */
  isDefault: number
  remark: string | null
  createTime: ServerTime
}

export interface VehicleDTO {
  plateNo: string
  plateColor: number
  brand?: string | null
  model?: string | null
  batteryCapacityKwh?: Decimal | null
  isDefault?: number
  remark?: string | null
}

/** ★ 无 `isDefault` —— 改默认车有专门的 `PUT /{id}/default`。 */
export interface VehicleUpdateDTO {
  plateNo?: string
  plateColor?: number
  brand?: string | null
  model?: string | null
  batteryCapacityKwh?: Decimal | null
  remark?: string | null
}

// ══════════════════════════════════════════════════════════════════════════
// 站点 / 充电枪浏览  ★ Phase 0.5 新增的两个接口
// ══════════════════════════════════════════════════════════════════════════

/**
 * 车主端站点卡片。
 *
 * ★ 后端 SQL 里**写死只返回 `status = 1`（启用）** ——
 *   维护中（2）和停用（0）的站点根本不会出现在列表里，前端不用再过滤一遍。
 * ★ 但**没有 `status` 字段** —— 因为能返回的必然是启用的，后端没序列化它。
 */
export interface MemberStationVO {
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
  openTime: string | null
  closeTime: string | null
  parkingFee: Decimal | null
  connectorCount: number
  idleConnectorCount: number
}

export interface MemberStationPageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  city?: string
}

/**
 * 车主端充电枪。
 *
 * ★★ `available` 是**快照，不是预订** ★★
 *   它只是「查的这一刻这把枪空闲」。查到 `available = true` 之后
 *   到用户点「开始充电」之间，别人完全可能先占了。
 *   所以**必须处理「`available=true` 但建单失败（枪被占用）」这个分支** ——
 *   表现是建单接口返回 `code:0`，UI 要能优雅地提示并刷新列表，
 *   而不是弹一个原始错误就完事。
 *
 * ★★ 后端 `listByStation` **不排序** ★★
 *   因为 `gun_no` 是字符串，SQL 排出来是 `1, 10, 2` 这种反直觉顺序。
 *   后端注释明确要求**前端重排**：先按可用排，再按桩分组。
 *   见 H5 的 `sortConnectors()`。
 */
export interface MemberConnectorVO {
  id: number
  stationId: number
  chargerId: number
  chargerDeviceNo: string | null
  chargerName: string | null
  /** ★ 后端直接给的**标签**，不是数字码（车主端没给 `chargerType` 数字）。 */
  chargerTypeLabel: string | null
  gunNo: string
  connectorType: string | null
  ratedPowerKw: Decimal | null
  status: number
  statusLabel: string
  available: boolean
}

// ══════════════════════════════════════════════════════════════════════════
// 钱包
// ══════════════════════════════════════════════════════════════════════════

/*
 * `WalletVO` / `WalletTransactionVO` 见文件头的转出说明。两处使用注意：
 *
 * ★★ 流水接口**只有** `pageNum` / `pageSize` ★★
 *   后端 `WalletTransactionPageQueryDTO` 没有 type / 时间范围参数 ——
 *   所以流水页**做不了「只看充值」这类筛选**。
 *   别在 UI 上放一个点了没反应的筛选器，直接在页面上写明。
 *
 * ★ `amount` 带符号（冻结是负数），颜色看符号，文案看 `type`。
 */

export interface WalletTransactionPageQuery {
  pageNum?: number
  pageSize?: number
}

/**
 * 充值。
 *
 * ★ 这是**模拟充值**接口 —— 没有接真实支付。后端的注释里写明了。
 *   演示时点一下余额就加上去了，属于预期行为，不是 bug。
 */
export interface WalletRechargeDTO {
  amount: Decimal
}

// ══════════════════════════════════════════════════════════════════════════
// 套餐
// ══════════════════════════════════════════════════════════════════════════

/** 可购买套餐（`GET /package/available?stationId=`）。 */
export interface AvailablePackageVO {
  id: number
  name: string
  categoryId: number
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

/*
 * `MemberPackageVO`（我的权益）见文件头的转出说明。
 *
 * ★★ 渲染时**不能只信 `status`** ★★
 *   权益过期是**定时任务**扫的，30 分钟一轮。
 *   所以存在「已经过期了但 `status` 还是 1（生效中）」最长 30 分钟的状态。
 *   列表**必须同时展示 `expireTime`**，让用户自己也能看出到期时间；
 *   客户端还可以拿 `expireTime` 和当前时间比，自己先标一个「已到期」。
 */

// ══════════════════════════════════════════════════════════════════════════
// 订单
// ══════════════════════════════════════════════════════════════════════════

/** 车主端订单列表行。 */
export interface ChargingOrderVO {
  id: number
  orderNo: string
  status: number
  statusLabel: string
  stationId: number
  plateNo: string | null
  createTime: ServerTime
  startTime: ServerTime | null
  endTime: ServerTime | null
  totalEnergyKwh: Decimal | null
  totalAmount: Decimal | null
  payAmount: Decimal | null
}

/**
 * 车主端订单详情。
 *
 * ★ 相比管理端少了车主信息与冻结金额 —— 车主端看不到 `frozenAmount`。
 * ★ 但**同样带 `segments` 与 `events`**，所以详情页可以画分段明细与时间轴。
 */
export interface ChargingOrderDetailVO extends ChargingOrderVO {
  chargerId: number
  connectorId: number
  durationSeconds: number | null
  startMeterKwh: Decimal | null
  endMeterKwh: Decimal | null
  startSoc: number | null
  endSoc: number | null
  energyFee: Decimal | null
  serviceFee: Decimal | null
  discountAmount: Decimal | null
  payMethod: number | null
  payMethodLabel: string | null
  payTime: ServerTime | null
  refundTime: ServerTime | null
  refundAmount: Decimal | null
  stopReason: number | null
  stopReasonLabel: string | null
  settleTime: ServerTime | null
  segments: ChargingSegmentVO[] | null
  events: ChargingOrderEventVO[] | null
}

/*
 * `ChargingSegmentVO` / `ChargingOrderEventVO` 见文件头的转出说明 ——
 * 与管理端是**同一个后端类**，所以分段明细与事件流的渲染逻辑两端完全一致。
 */

/**
 * ★★ 车主端订单列表**只有分页 + status** ★★
 *   没有任何时间范围 / 站点 / 关键词过滤 ——
 *   所以「查上个月的充电记录」这件事在 H5 上做不到，
 *   页面要给一个「继续下滑加载」而不是一个假的日期筛选器。
 */
export interface OrderPageQuery {
  pageNum?: number
  pageSize?: number
  status?: number
}

/**
 * 建单。
 *
 * ★ `vehicleId` 可空 —— 车主可以不下挂车辆直接充。
 * ★ `payMethod` 必填（1 余额 / 2 微信 / 3 支付宝），但注意
 *   **余额支付会在建单时校验余额并按预估价冻结**；
 *   微信/支付宝在当前实现里是模拟的，不会真的拉起支付。
 */
export interface OrderCreateDTO {
  stationId: number
  chargerId: number
  connectorId: number
  vehicleId?: number | null
  payMethod: number
}
