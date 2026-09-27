/**
 * 行 → VO 的映射层。
 *
 * ★★ 每个 `*Label` 字段都来自 `constants/dicts.ts`，**不新写一份中文** ★★
 *   字典里的标签是逐条对齐后端 `getLabel()` 的，而 handler 里再抄一遍
 *   就多了一个真源 —— 后端改了一个字，这一份不会跟着变，
 *   表现是「同一个状态在两个页面显示不同的字」。走字典则不可能分叉。
 *
 * ★★ 金额一律 `yuan()` 除以 100 ★★
 *   库里是整数分（见 `db.ts` 文件头）。VO 上的 `Decimal` 就是后端
 *   `BigDecimal` 序列化出来的那个 JSON number，所以这里「分 → 元」是**出库的唯一出口**。
 *   ★ 电量（`kwh`）已经在库里对齐过 4 位小数了，这里不再二次舍入 ——
 *     舍入两次和舍入一次的结果可以不同（`0.12345 → 0.1235 → 0.124` vs `0.123`），
 *     而前端有一条「Σ分段 == 订单总额」的断言会把它当成后端算错了。
 *
 * ★★ 时间一律 `ts()` / `tsRequired()` ★★ 库里是 `Date`，出口才格式化。
 *   直接 `date.toISOString()` 会得到带 `T` 和 `Z` 的串 —— Safari 之外也许能跑，
 *   但和后端 `yyyy-MM-dd HH:mm:ss` 的格式不符，前端解析正则碰巧容错（允许 T），
 *   于是这个错误会一直藏着，直到某个地方做了字符串比较（`createTime >= from`）才炸。
 */

import type {
  AdminOrderDetailVO,
  AdminOrderVO,
  CategoryVO,
  ChargePackageVO,
  ChargerVO,
  ChargingOrderEventVO,
  ChargingSegmentVO,
  ConnectorVO,
  MemberPackageVO,
  MemberVO,
  StaffVO,
  StationTariffVO,
  StationVO,
  TariffRuleVO,
  TariffTemplateVO,
  WalletTransactionVO,
  WalletVO,
} from '../../types/admin'
import type {
  AvailablePackageVO,
  ChargingOrderDetailVO,
  ChargingOrderVO,
  MemberConnectorVO,
  MemberStationVO,
  VehicleVO,
} from '../../types/member'
import type {
  DbCategory,
  DbCharger,
  DbConnector,
  DbMember,
  DbMemberPackage,
  DbOrder,
  DbOrderEvent,
  DbOrderSegment,
  DbPackage,
  DbStaff,
  DbStation,
  DbStationTariff,
  DbTariffRule,
  DbTariffTemplate,
  DbVehicle,
  DbWallet,
  DbWalletTxn,
  MockDb,
} from '../db'
import { yuan, ts, tsRequired } from '../db'
import {
  CATEGORY_TYPE,
  CHARGER_STATUS,
  CHARGER_TYPE,
  CONNECTOR_STATUS,
  EVENT_SOURCE,
  GENDER,
  isConnectorAvailable,
  MEMBER_PACKAGE_STATUS,
  MEMBER_STATUS,
  PACKAGE_TYPE,
  PAY_METHOD,
  PERIOD_TYPE,
  PLATE_COLOR,
  STATION_STATUS,
  STOP_REASON,
  WALLET_TXN_TYPE,
} from '../../constants/dicts'
import { orderStatusLabel } from '../../constants/orderStatus'

/** 上架 / 下架。后端在 `ChargePackageVO` / `CategoryVO` 等里都写成这两个词。 */
function enableLabel(status: number): string {
  return status === 1 ? '启用' : '停用'
}

// ══════════════════════════════════════════════════════════════════════════
// 员工
// ══════════════════════════════════════════════════════════════════════════

/**
 * 员工（`/api/admin/staff/me`）。
 *
 * ★★ 这里**没有** `password`，而且必须是「有意的没写」而不是「忘了」★★
 *   映射层是过滤敏感字段的最后一道闸 —— handler 如果直接把 `DbStaff`
 *   原样 `ok()` 出去，密码就随着 `/me` 一起回到浏览器了。
 *   所以在类型上做：`StaffVO` 根本没有这个字段（`strict` + 对象字面量
 *   的多余属性检查会在编译期拦下「顺手多写一个」）。
 *
 * ★ `sex` 是**字符串**（`'男'` / `'女'`），和车主端数字枚举的 `gender`
 *   不是一回事 —— 两端不一致是后端的现状，不是笔误。
 * ★ 没有 `statusLabel`：后端 `StaffVO` 里就只有 `status` 数字。
 */
export function toStaffVo(row: DbStaff): StaffVO {
  return {
    id: row.id,
    username: row.username,
    name: row.name,
    phone: row.phone,
    sex: row.sex,
    role: row.role,
    stationId: row.stationId,
    status: row.status,
    createTime: tsRequired(row.createTime),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 站点 / 充电桩 / 充电枪
// ══════════════════════════════════════════════════════════════════════════

export function toStationVo(row: DbStation): StationVO {
  return {
    id: row.id,
    code: row.code,
    name: row.name,
    operator: row.operator,
    province: row.province,
    city: row.city,
    district: row.district,
    address: row.address,
    longitude: row.longitude,
    latitude: row.latitude,
    servicePhone: row.servicePhone,
    openTime: row.openTime,
    closeTime: row.closeTime,
    parkingFee: row.parkingFee === null ? null : yuan(row.parkingFee),
    status: row.status,
    statusLabel: STATION_STATUS.label(row.status),
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

/**
 * ★ `stationName` 是**富化字段**：查不到站点时给 `null` 而不是报错。
 *   后端的取舍是「只读路径上的派生字段失败要降级」——「这一行的站名显示不出来」
 *   不该让整个列表接口挂掉。站点被并发删掉时走到这里，行为与后端一致。
 */
export function toChargerVo(db: MockDb, row: DbCharger): ChargerVO {
  const station = db.stationById(row.stationId)
  return {
    id: row.id,
    stationId: row.stationId,
    stationName: station?.name ?? null,
    deviceNo: row.deviceNo,
    name: row.name,
    type: row.type,
    typeLabel: CHARGER_TYPE.label(row.type),
    model: row.model,
    ratedPowerKw: row.ratedPowerKw,
    gunCount: row.gunCount,
    status: row.status,
    statusLabel: CHARGER_STATUS.label(row.status),
    lastHeartbeatTime: ts(row.lastHeartbeatTime),
    firmwareVersion: row.firmwareVersion,
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

export function toConnectorVo(row: DbConnector): ConnectorVO {
  return {
    id: row.id,
    chargerId: row.chargerId,
    stationId: row.stationId,
    gunNo: row.gunNo,
    connectorType: row.connectorType,
    ratedPowerKw: row.ratedPowerKw,
    status: row.status,
    statusLabel: CONNECTOR_STATUS.label(row.status),
    currentOrderId: row.currentOrderId,
    totalChargeCount: row.totalChargeCount,
    version: row.version,
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 计费模板 / 规则 / 站点绑定
// ══════════════════════════════════════════════════════════════════════════

export function toTariffRuleVo(row: DbTariffRule): TariffRuleVO {
  return {
    id: row.id,
    templateId: row.templateId,
    periodType: row.periodType,
    periodTypeLabel: PERIOD_TYPE.label(row.periodType),
    startTime: row.startTime,
    endTime: row.endTime,
    energyPrice: row.energyPrice,
    servicePrice: row.servicePrice,
    seq: row.seq,
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

/**
 * ★ `rules` 只有详情才装配（`withRules`），列表一律 `null`。
 *   后端接口注释写明「详情才带规则」——列表页带 8 条规则 × 10 行 = 80 个对象，
 *   而列表根本不渲染它们。mock 照抄这个分工，顺带让前端的
 *   「列表页拿不到 rules」这条假设真的被验证一次。
 */
export function toTariffTemplateVo(
  db: MockDb,
  row: DbTariffTemplate,
  withRules: boolean,
): TariffTemplateVO {
  return {
    id: row.id,
    code: row.code,
    name: row.name,
    region: row.region,
    description: row.description,
    status: row.status,
    statusLabel: enableLabel(row.status),
    rules: withRules ? db.rulesOf(row.id).map(toTariffRuleVo) : null,
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

/**
 * ★ `templateName` / `templateCode` 是富化的，缺了给 `null`。
 *   后端注释点明了理由：「没有人记得模板 7 是哪个价」—— 这两个字段
 *   是这个页面上唯一的可读信息。
 */
export function toStationTariffVo(db: MockDb, row: DbStationTariff): StationTariffVO {
  const template = db.templateById(row.templateId)
  return {
    id: row.id,
    stationId: row.stationId,
    templateId: row.templateId,
    templateName: template?.name ?? null,
    templateCode: template?.code ?? null,
    effectiveFrom: ts(row.effectiveFrom),
    effectiveTo: ts(row.effectiveTo),
    priority: row.priority,
    status: row.status,
    statusLabel: enableLabel(row.status),
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 分类 / 套餐
// ══════════════════════════════════════════════════════════════════════════

export function toCategoryVo(row: DbCategory): CategoryVO {
  return {
    id: row.id,
    name: row.name,
    type: row.type,
    typeLabel: CATEGORY_TYPE.label(row.type),
    sort: row.sort,
    status: row.status,
    statusLabel: enableLabel(row.status),
    createTime: tsRequired(row.createTime),
  }
}

/**
 * ★ 一个映射同时服务 `ChargePackageVO` 与 `AvailablePackageVO` ——
 *   两个接口的字段**逐字相同**（后端 `ChargePackageVO` 是一个类，
 *   车主端的 `available` 返回的也是它）。写成两个函数等于给同一份数据
 *   造了两个可能分叉的形状。
 *
 * ★ 注意 `status` / `statusLabel` 也一并返回：车主端接口**不发** `status = 0` 的行，
 *   所以它拿到的一定是「上架」，但这个字段仍然在 VO 上（后端没排除它）。
 */
export function toPackageVo(row: DbPackage): ChargePackageVO & AvailablePackageVO {
  return {
    id: row.id,
    name: row.name,
    categoryId: row.categoryId,
    packageType: row.packageType,
    packageTypeLabel: PACKAGE_TYPE.label(row.packageType),
    stationId: row.stationId,
    price: yuan(row.price),
    originPrice: row.originPrice === null ? null : yuan(row.originPrice),
    energyKwh: row.energyKwh,
    durationMinutes: row.durationMinutes,
    times: row.times,
    serviceDiscount: row.serviceDiscount,
    validDays: row.validDays,
    description: row.description,
    image: row.image,
    status: row.status,
    statusLabel: enableLabel(row.status),
    sort: row.sort,
    createTime: tsRequired(row.createTime),
    updateTime: tsRequired(row.updateTime),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 车主 / 权益 / 钱包 / 车辆
// ══════════════════════════════════════════════════════════════════════════

/**
 * ★ 一个映射同时服务管理端 `MemberVO` 与车主端 `MemberProfileVO` ——
 *   字段逐字相同（后端是同一个 `MemberVO`）。
 *   ★★ 其中 `status` 是 H5 **强制登出探针**的判据 ★★（HTTP 200 且 `status === 0`），
 *   所以这个字段**必须**是真值，不能像别的富化字段那样在拿不准时降级成 null。
 */
export function toMemberVo(row: DbMember): MemberVO {
  return {
    id: row.id,
    phone: row.phone,
    nickname: row.nickname,
    avatar: row.avatar,
    gender: row.gender,
    genderLabel: GENDER.label(row.gender),
    status: row.status,
    statusLabel: MEMBER_STATUS.label(row.status),
    lastLoginTime: ts(row.lastLoginTime),
    createTime: tsRequired(row.createTime),
  }
}

export function toMemberPackageVo(row: DbMemberPackage): MemberPackageVO {
  return {
    id: row.id,
    packageId: row.packageId,
    packageType: row.packageType,
    packageTypeLabel: PACKAGE_TYPE.label(row.packageType),
    packageName: row.packageName,
    payAmount: row.payAmount === null ? null : yuan(row.payAmount),
    totalKwh: row.totalKwh,
    remainingKwh: row.remainingKwh,
    totalMinutes: row.totalMinutes,
    remainingMinutes: row.remainingMinutes,
    totalTimes: row.totalTimes,
    remainingTimes: row.remainingTimes,
    serviceDiscount: row.serviceDiscount,
    effectiveFrom: ts(row.effectiveFrom),
    expireTime: ts(row.expireTime),
    status: row.status,
    statusLabel: MEMBER_PACKAGE_STATUS.label(row.status),
    remark: row.remark,
    createTime: tsRequired(row.createTime),
  }
}

/**
 * 「我的一份份权益」的排序 —— `MemberPackageMapper.listByMember` 的 `ORDER BY status, expire_time, id`。
 *
 * ★★ 这不是「顺手排一下」，而是两个接口返回值的一部分 ★★
 *   「我的权益」和「某车主的权益」用的是**同一条 SQL**（同一个 mapper 方法），
 *   顺序因此是接口契约的一部分，而不是各页面自己决定的表现层选择。
 *
 * ★★ 三个键为什么各自必要，缺一个就有一个具体的坏症状 ★★
 *   · `status`：生效中的必须排在最前 —— 列表第一屏就是「现在能用哪几张」。
 *     缺它的话，一个刚买的新包（id 最大）后面恰好跟着一堆已作废的旧包时，
 *     新包会被排到中间，车主以为没买上。
 *   · `expireTime`：同级里先到期的排前面 —— 这是**使用顺序**（永久有效那份排同级最后，
 *     这是「先花会过期的」）。缺它的话，先到期的埋在下面，车主会眼睁睁看着一张券过期。
 *   · `id`：**必须有**。前两个键都可能并列（同状态、同到期时刻 —— 同一天买的两份），
 *     不补第三个键时顺序由存储引擎决定，刷新一次就换一个位置。
 *     mock 是内存数组，天然稳定，正因如此**写错了也看不出来** —— 而真库上会跳。
 *
 * ★ `null` 的 `expireTime`（V3 之前的老行，「不知道」）排在最前：MySQL 的 `ASC`
 *   把 NULL 放最前，这里显式复刻，免得与真库的先后顺序对不上。
 */
export function sortMemberPackages(rows: readonly DbMemberPackage[]): DbMemberPackage[] {
  const time = (d: Date | null): number => (d === null ? -Infinity : d.getTime())
  return [...rows].sort(
    (a, b) => a.status - b.status || time(a.expireTime) - time(b.expireTime) || a.id - b.id,
  )
}

export function toWalletVo(row: DbWallet): WalletVO {
  return {
    balance: yuan(row.balance),
    frozenAmount: yuan(row.frozenAmount),
    totalRecharge: yuan(row.totalRecharge),
  }
}

export function toWalletTxnVo(row: DbWalletTxn): WalletTransactionVO {
  return {
    transactionNo: row.transactionNo,
    orderId: row.orderId,
    type: row.type,
    typeLabel: WALLET_TXN_TYPE.label(row.type),
    // ★ 符号原样带出去：`2 冻结` 存的就是负数，前端靠符号而不是靠 type 判方向。
    amount: yuan(row.amount),
    balanceAfter: yuan(row.balanceAfter),
    remark: row.remark,
    createTime: tsRequired(row.createTime),
  }
}

export function toVehicleVo(row: DbVehicle): VehicleVO {
  return {
    id: row.id,
    plateNo: row.plateNo,
    plateColor: row.plateColor,
    plateColorLabel: PLATE_COLOR.label(row.plateColor),
    brand: row.brand,
    model: row.model,
    batteryCapacityKwh: row.batteryCapacityKwh,
    // ★ 是数字不是布尔 —— 前端要用 `=== 1` 判默认车，改成 true/false 会让它永远为假。
    isDefault: row.isDefault,
    remark: row.remark,
    createTime: tsRequired(row.createTime),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 订单：分段 / 事件 / 列表行 / 详情
// ══════════════════════════════════════════════════════════════════════════

export function toSegmentVo(row: DbOrderSegment): ChargingSegmentVO {
  return {
    seq: row.seq,
    periodType: row.periodType,
    periodTypeLabel: PERIOD_TYPE.label(row.periodType),
    startTime: tsRequired(row.startTime),
    endTime: tsRequired(row.endTime),
    durationSeconds: row.durationSeconds,
    energyKwh: row.energyKwh,
    energyPrice: row.energyPrice,
    servicePrice: row.servicePrice,
    energyFee: yuan(row.energyFee),
    serviceFee: yuan(row.serviceFee),
    amount: yuan(row.amount),
  }
}

export function toEventVo(row: DbOrderEvent): ChargingOrderEventVO {
  return {
    fromStatus: row.fromStatus,
    toStatus: row.toStatus,
    eventType: row.eventType,
    source: row.source,
    sourceLabel: EVENT_SOURCE.label(row.source),
    detail: row.detail,
    createTime: tsRequired(row.createTime),
  }
}

/** 管理端订单列表行。`memberPhone` / `stationName` 是富化字段，缺了给 null。 */
export function toAdminOrderVo(db: MockDb, row: DbOrder): AdminOrderVO {
  const member = db.memberById(row.memberId)
  const station = db.stationById(row.stationId)
  return {
    id: row.id,
    orderNo: row.orderNo,
    status: row.status,
    statusLabel: orderStatusLabel(row.status),
    memberId: row.memberId,
    memberPhone: member?.phone ?? null,
    memberNickname: member?.nickname ?? null,
    stationId: row.stationId,
    stationName: station?.name ?? null,
    chargerId: row.chargerId,
    connectorId: row.connectorId,
    plateNo: row.plateNo,
    createTime: tsRequired(row.createTime),
    startTime: ts(row.startTime),
    endTime: ts(row.endTime),
    settleTime: ts(row.settleTime),
    totalEnergyKwh: row.totalEnergyKwh,
    totalAmount: row.totalAmount === null ? null : yuan(row.totalAmount),
    discountAmount: row.discountAmount === null ? null : yuan(row.discountAmount),
    payAmount: row.payAmount === null ? null : yuan(row.payAmount),
    frozenAmount: row.frozenAmount === null ? null : yuan(row.frozenAmount),
  }
}

/**
 * 管理端订单详情。
 *
 * ★ 主体**复用**列表行的映射再展开：详情和列表对同一批字段（状态、金额、时间）
 *   必须是同一套口径。分开写两份，改一处忘另一处的表现是
 *   「列表显示的金额和详情不一样」，而那时没人会怀疑是映射分叉。
 *
 * ★ `payMethodLabel` / `stopReasonLabel` 是**可空**的：未支付的订单没有支付方式、
 *   没开始过的订单没有停止原因。字典的 `label(null)` 会返回 `'-'`，
 *   所以这里必须先判空 —— 把 `'-'` 发出去，前端的分支（`if (label)`）会以为有值。
 */
export function toAdminOrderDetailVo(db: MockDb, row: DbOrder): AdminOrderDetailVO {
  return {
    ...toAdminOrderVo(db, row),
    tariffTemplateId: row.tariffTemplateId,
    memberPackageId: row.memberPackageId,
    durationSeconds: row.durationSeconds,
    startMeterKwh: row.startMeterKwh,
    endMeterKwh: row.endMeterKwh,
    startSoc: row.startSoc,
    endSoc: row.endSoc,
    energyFee: row.energyFee === null ? null : yuan(row.energyFee),
    serviceFee: row.serviceFee === null ? null : yuan(row.serviceFee),
    payMethod: row.payMethod,
    payMethodLabel: row.payMethod === null ? null : PAY_METHOD.label(row.payMethod),
    payTime: ts(row.payTime),
    refundTime: ts(row.refundTime),
    refundAmount: row.refundAmount === null ? null : yuan(row.refundAmount),
    stopReason: row.stopReason,
    stopReasonLabel: row.stopReason === null ? null : STOP_REASON.label(row.stopReason),
    // ★ 没结算过的订单 `segments` 是**空数组**而不是 null：后端返回的是
    //   一个已经初始化好的 List（空表就是空 List）。给 null 会让前端的
    //   `v-for="s in detail.segments"` 报错，而 `[]` 是安全的。
    segments: db.segmentsOf(row.id).map(toSegmentVo),
    events: db.eventsOf(row.id).map(toEventVo),
  }
}

/** 车主端订单列表行。★ **没有** `frozenAmount` 与车主信息 —— 车主端看不到那两样。 */
export function toMemberOrderVo(row: DbOrder): ChargingOrderVO {
  return {
    id: row.id,
    orderNo: row.orderNo,
    status: row.status,
    statusLabel: orderStatusLabel(row.status),
    stationId: row.stationId,
    plateNo: row.plateNo,
    createTime: tsRequired(row.createTime),
    startTime: ts(row.startTime),
    endTime: ts(row.endTime),
    totalEnergyKwh: row.totalEnergyKwh,
    totalAmount: row.totalAmount === null ? null : yuan(row.totalAmount),
    payAmount: row.payAmount === null ? null : yuan(row.payAmount),
  }
}

export function toMemberOrderDetailVo(db: MockDb, row: DbOrder): ChargingOrderDetailVO {
  return {
    ...toMemberOrderVo(row),
    chargerId: row.chargerId,
    connectorId: row.connectorId,
    durationSeconds: row.durationSeconds,
    startMeterKwh: row.startMeterKwh,
    endMeterKwh: row.endMeterKwh,
    startSoc: row.startSoc,
    endSoc: row.endSoc,
    energyFee: row.energyFee === null ? null : yuan(row.energyFee),
    serviceFee: row.serviceFee === null ? null : yuan(row.serviceFee),
    discountAmount: row.discountAmount === null ? null : yuan(row.discountAmount),
    payMethod: row.payMethod,
    payMethodLabel: row.payMethod === null ? null : PAY_METHOD.label(row.payMethod),
    payTime: ts(row.payTime),
    refundTime: ts(row.refundTime),
    refundAmount: row.refundAmount === null ? null : yuan(row.refundAmount),
    stopReason: row.stopReason,
    stopReasonLabel: row.stopReason === null ? null : STOP_REASON.label(row.stopReason),
    settleTime: ts(row.settleTime),
    segments: db.segmentsOf(row.id).map(toSegmentVo),
    events: db.eventsOf(row.id).map(toEventVo),
  }
}

// ══════════════════════════════════════════════════════════════════════════
// 车主端：站点 / 充电枪浏览
// ══════════════════════════════════════════════════════════════════════════

/**
 * 车主端站点卡片。
 *
 * ★★ **没有 `status`** ★★ 后端 `pageForMember` 的 SQL 里写死了 `WHERE status = 1`，
 *   能出现在列表里的必然是启用的，所以后端干脆不序列化它。
 *   mock 也照做 —— 多发一个 `status` 看起来无害，但它会让前端悄悄长出
 *   「用 status 再过滤一遍」的代码，而那段代码在真后端上是死代码。
 *
 * ★★ 两个枪数是**聚合**出来的，不是站点自己的列 ★★
 *   后端是 `GROUP BY station_id` 的计数查询，站下没有枪时**整行都不出现**
 *   （不是给一个 0 行）—— 所以取值必须是「查不到就是 0」。
 *   这里用 `filter().length` 天然满足，不需要那个坑。
 */
export function toMemberStationVo(db: MockDb, row: DbStation): MemberStationVO {
  const guns = db.connectors.filter((c) => c.stationId === row.id)
  return {
    id: row.id,
    code: row.code,
    name: row.name,
    operator: row.operator,
    province: row.province,
    city: row.city,
    district: row.district,
    address: row.address,
    longitude: row.longitude,
    latitude: row.latitude,
    servicePhone: row.servicePhone,
    openTime: row.openTime,
    closeTime: row.closeTime,
    parkingFee: row.parkingFee === null ? null : yuan(row.parkingFee),
    connectorCount: guns.length,
    idleConnectorCount: guns.filter((c) => isConnectorAvailable(c.status)).length,
  }
}

/**
 * 车主端充电枪。
 *
 * ★★ `available` 是**快照，不是预订** ★★ 它只说明「查的这一刻空闲」，
 *   到用户点「开始充电」之间别人完全可能先占了 —— 所以前端必须处理
 *   「`available=true` 但建单失败」这个分支。mock 里也一样：
 *   建单 handler 会重新判一次枪状态，两者的结果可以不同（这正是要点）。
 *
 * ★ 桩信息（编号 / 名称 / 类型标签）是**富化**的，桩查不到时给 `null`（不报错）——
 *   与后端 `MemberStationServiceImpl.toConnectorVos` 的取舍一致。
 */
export function toMemberConnectorVo(db: MockDb, row: DbConnector): MemberConnectorVO {
  const charger = db.chargerById(row.chargerId)
  return {
    id: row.id,
    stationId: row.stationId,
    chargerId: row.chargerId,
    chargerDeviceNo: charger?.deviceNo ?? null,
    chargerName: charger?.name ?? null,
    // ★ 车主端拿到的是**标签**而不是 `chargerType` 数字码 —— 后端没给它数字。
    chargerTypeLabel: charger ? CHARGER_TYPE.label(charger.type) : null,
    gunNo: row.gunNo,
    connectorType: row.connectorType,
    ratedPowerKw: row.ratedPowerKw,
    status: row.status,
    statusLabel: CONNECTOR_STATUS.label(row.status),
    available: isConnectorAvailable(row.status),
  }
}
