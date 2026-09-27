/**
 * 后端文案表 —— **逐字**抄自 `com.chargeops.constant.MessageConstant` 与各 ServiceImpl。
 *
 * ★★ 为什么要单独一个文件，而不是就地写在 handler 里 ★★
 *   这是「哪些文案和真后端对齐过」的**唯一清单**。演示时如果某句提示和后端
 *   对不上，只需要看这一个文件就能逐条核对；散在 12 个 handler 里的话，
 *   没人能回答「到底抄全了没有」。
 *
 * ★ `MSG`（`http/messages.ts`）与这个文件的分工**不是重复**：
 *   · `MSG` 是「前端要按内容做分支」的**匹配用**常量，所以它可能只是一个
 *     前缀（`MEMBER_PREFIX_TOO_BROAD` 就刻意短于后端原句）；
 *   · 这里是 mock **要发出去**的完整原句。
 *   凡是两边都有的（登录失效、账号被禁用、乐观锁冲突），下面**直接从 `MSG` 取**，
 *   这样两处不可能漂移；只有 `MSG` 里没有的才写字面量。
 *
 * ★ 带 `%d` 的后端模板（`STATION_HAS_CHARGER` 那五条）在这里是**函数**而不是字符串：
 *   `String.format` 只是拼串，写成模板变量的话每个调用点都要自己拼，
 *   迟早有人漏掉数量 —— 而那句话的全部价值就在于那个数字（它是给管理员的行动指引）。
 */

import { MSG } from '../../http/messages'

export const TEXT = {
  // ── 账号与登录 ──────────────────────────────────────────────────────────
  /** `MessageConstant.ACCOUNT_NOT_FOUND`。管理端登录**分开**报「账号不存在」。 */
  ACCOUNT_NOT_FOUND: '账号不存在',
  /** `MessageConstant.PASSWORD_ERROR`。管理端登录分开报「密码错误」。 */
  PASSWORD_ERROR: '密码错误',
  /** `MessageConstant.ACCOUNT_LOCKED`。★ 管理端专用，和车主端的 MEMBER_DISABLED 不是一句。 */
  ACCOUNT_LOCKED: '账号被禁用',
  /** `MessageConstant.USER_NOT_LOGIN`。★ 与 `MSG.NOT_LOGGED_IN` 同句，直接复用。 */
  USER_NOT_LOGIN: MSG.NOT_LOGGED_IN,
  /** `MessageConstant.PASSWORD_EDIT_FAILED`。 */
  PASSWORD_EDIT_FAILED: '密码修改失败',

  // ── 通用 ────────────────────────────────────────────────────────────────
  /** `MessageConstant.UNKNOWN_ERROR`。`GlobalExceptionHandler` 的兜底。 */
  UNKNOWN_ERROR: '未知错误',
  /** `MessageConstant.NOT_FOUND`。 */
  NOT_FOUND: '数据不存在',
  /** `MessageConstant.UPLOAD_FAILED`。 */
  UPLOAD_FAILED: '文件上传失败',

  // ── 站点 / 充电桩 / 充电枪 ──────────────────────────────────────────────
  STATION_NOT_FOUND: '充电站不存在',
  CHARGER_NOT_FOUND: '充电桩不存在',
  CONNECTOR_NOT_FOUND: '充电枪不存在',
  /** 建单时枪被别人占了。★ 后端实际会拼上「（当前：空闲/已占用…）」，见 `connectorBusy`。 */
  CONNECTOR_OCCUPIED: '该充电枪正在使用中，请换一把',
  /** 管理员想删一把还有订单的枪。★ 和上面那句**刻意**分开，见 MessageConstant 的长注释。 */
  CONNECTOR_IN_USE: '该充电枪当前有订单占用，不能删除',
  /** `ConnectorStatus.isAdminSettable()` 拒绝 2/3 时的文案。 */
  CONNECTOR_STATUS_NOT_EDITABLE:
    '「已占用」和「充电中」由充电流程自动维护，不能手工设置',

  // ── 套餐 / 分类 ─────────────────────────────────────────────────────────
  PACKAGE_NOT_FOUND: '套餐不存在',
  PACKAGE_OFF_SHELF: '该套餐已下架',
  /** ★ `assertTypeFields` 的两条消息都**以它开头**，后缀逐字段变化。见下面两个函数。 */
  PACKAGE_TYPE_FIELD_MISMATCH: '套餐的类型专属字段不匹配',
  /**
   * `MessageConstant.PACKAGE_FIELD_MISSING`。
   *
   * ★★ 它和上面那条**不是**同一件事，别合并 ★★
   *   · `PACKAGE_TYPE_FIELD_MISMATCH` 出现在**管理员保存套餐**时：他填错了字段，
   *     改掉再存就行；
   *   · 这一条出现在**车主购买**时：库里的 SKU 缺了它自己那个类型该有的列
   *     （`remaining_* IS NULL` 会让这份权益永远选不中），管理员根本没在填表，
   *     是数据坏了。对车主是「这个包买不了」，去改表单解决不了问题。
   *   而且**以它开头的每一句后缀都不一样**（缺少 / 必须大于 0 / 售价非法 /
   *   折扣区间），见下面三个函数。
   */
  PACKAGE_FIELD_MISSING: '该套餐的数据不完整，无法购买',
  CATEGORY_NOT_FOUND: '分类不存在',
  /** `assertTypeFields` 报「分类类型不匹配」时的前缀，后缀拼具体两个类型名。 */
  CATEGORY_TYPE_MISMATCH: '分类类型不匹配',

  // ── 计费模板 / 站点计费绑定 ─────────────────────────────────────────────
  TEMPLATE_NOT_FOUND: '计费模板不存在',
  /** ★ 完整原句，含「请新建一个模板版本」—— 只写「不能修改」等于把管理员堵死。 */
  TEMPLATE_IN_USE: '该模板已被站点绑定，不能修改计费规则；请新建一个模板版本',
  TEMPLATE_DISABLED: '该计费模板已停用，不能用于新建绑定',
  /**
   * `MessageConstant.TARIFF_OVERLAP`。★ 完整句是「这一句 + `：` + 各重叠处」，
   * 由 `admin-tariff` 的 `validateTariffRules` 拼出（相邻两条各自的时段描述）。
   */
  TARIFF_OVERLAP: '计费时段存在重叠',
  /**
   * `MessageConstant.TARIFF_NOT_COVER_24H`。★ 完整句是「这一句 + `，缺失：` + 各缺口」。
   * 注意分隔符和上面那句**不一样**（逗号 vs 冒号）—— 抄自 `TariffValidator` 的两处
   * `String.join`，两类的语气不同：「重叠」是并列的事实，「缺失」是补充说明。
   */
  TARIFF_NOT_COVER_24H: '计费时段未完整覆盖 24 小时',
  STATION_TARIFF_NOT_FOUND: '站点计费绑定不存在',
  /** ★ 站点没配计费方案时建单会**失败**，不是回退到默认价（那是个静默的资金错误）。 */
  STATION_TARIFF_NOT_BOUND: '该站点当前没有配置计费方案，请联系运营',
  /** `assertEffectiveRange` 的前缀，后缀是 `from -> to` 两个时刻。 */
  EFFECTIVE_RANGE_INVALID: '失效时刻必须晚于生效时刻，否则这条绑定永远不会生效',

  // ── 并发 / 车主 ─────────────────────────────────────────────────────────
  /** `MessageConstant.CONCURRENT_MODIFIED`。★ 与 `MSG.CONCURRENT_MODIFIED` 同句，复用。 */
  CONCURRENT_MODIFIED: MSG.CONCURRENT_MODIFIED,
  /** `MessageConstant.MEMBER_NOT_FOUND`。 */
  MEMBER_NOT_FOUND: '车主不存在',
  /** `MessageConstant.MEMBER_DISABLED`。★ 与 `MSG.ACCOUNT_LOCKED` 同句 —— 车主端强制登出的信号。 */
  MEMBER_DISABLED: MSG.ACCOUNT_LOCKED,
  /** `MessageConstant.MEMBER_LOGIN_FAILED`。★ 车主端「没注册」和「密码错」**合并**成这一句。 */
  MEMBER_LOGIN_FAILED: '手机号或密码错误',
  /** `MessageConstant.MEMBER_PASSWORD_NOT_SET`。★ 不并进上面那句：那类用户重试密码永远不会成功。 */
  MEMBER_PASSWORD_NOT_SET: '该账号未设置密码，无法使用密码登录',
  OLD_PASSWORD_ERROR: '原密码错误',
  /**
   * ★ **当前没有任何调用点，而且注册路径发不出这句话。**
   *   它对应后端 `MessageConstant.PHONE_ALREADY_EXISTS`，但那个常量在注册流程里
   *   **永远不会被用到**：`register` 直接 insert，重复由唯一索引拒绝，
   *   再由 `GlobalExceptionHandler` 从 `Duplicate entry '...'` 里抠出那个手机号
   *   拼成 `<手机号>已存在`（见 `alreadyExists` / `member-auth.ts`）。
   *   保留在这里是为了让「后端有这个常量」这件事在 mock 里也留个痕 ——
   *   但它**不是**注册接口会发出的文案，别拿它去替换 `alreadyExists(phone)`。
   */
  PHONE_ALREADY_EXISTS: '该手机号已注册',
  VEHICLE_NOT_FOUND: '车辆不存在',

  // ── 订单 ────────────────────────────────────────────────────────────────
  ORDER_NOT_FOUND: '订单不存在',
  /** ★ 前缀，完整句由 `db.ts` 的 `statusConflict()` 拼出（带订单号与前后状态）。 */
  ORDER_STATUS_CONFLICT: '订单当前状态不允许该操作',
  ORDER_VEHICLE_BUSY: '该车辆有进行中的充电订单，请先结束',
  ORDER_CONNECTOR_STATE_INVALID: '该充电枪上的订单状态异常，请联系运维',
  ORDER_PAY_METHOD_UNSUPPORTED: '该支付方式暂未支持，请使用余额支付',
  /** `estimateFreeze` 失败时（模板无规则）的兜底。 */
  ORDER_NO_TARIFF: '该站点当前没有配置计费方案，请联系运营',
  /** 结算时可用余额不够。★ 后端会拼上金额和 orderId，见 `settleNotEnough`。 */
  BALANCE_NOT_ENOUGH: '余额不足',

  // ── 权益（管理端作废）────────────────────────────────────────────────────
  MEMBER_PACKAGE_NOT_FOUND: '权益不存在',
  /** ★ 前缀，完整句由 `member-package` 的 VOID handler 拼（带当前状态）。 */
  MEMBER_PACKAGE_STATUS_CONFLICT: '权益当前状态不允许该操作',
  MEMBER_PACKAGE_IN_USE: '该权益被一笔进行中的订单占用，请先处理那笔订单',
  MEMBER_PACKAGE_PAY_AMOUNT_MISSING:
    '该权益没有购买金额快照（建于本功能上线之前），无法计算退款金额，请人工处理',

  // ── 钱包调账 ────────────────────────────────────────────────────────────
  WALLET_ADJUST_AMOUNT_ZERO: '调账金额不能为 0',
  WALLET_ADJUST_REMARK_REQUIRED: '调账必须填写原因',
  WALLET_ADJUST_BALANCE_NOT_ENOUGH: '该车主可用余额不足，无法扣除这笔金额',

  // ── 上传 ────────────────────────────────────────────────────────────────
  UPLOAD_FILE_EMPTY: '上传文件为空',
  UPLOAD_FILE_NAME_INVALID: '文件名不合法，缺少扩展名',
} as const

/**
 * 手机号前缀命中过多。
 *
 * ★★ 这里**不能**用 `MSG.MEMBER_PREFIX_TOO_BROAD` ★★
 *   那个常量只有前半句（`该手机号前缀匹配到的车主过多`），是前端用来做**前缀匹配**
 *   的；后端实际发出去的是带行动的完整句子。mock 必须发**完整句**，
 *   否则 `msgStartsWith` 虽然仍然命中，但演示时看到的提示会少掉
 *   「请多输入几位数字后重试」—— 而那半句才是让管理员知道下一步该干什么的部分。
 */
export const MEMBER_PREFIX_TOO_BROAD_FULL =
  '该手机号前缀匹配到的车主过多，请多输入几位数字后重试'

// ══════════════════════════════════════════════════════════════════════════
// 唯一索引冲突
// ══════════════════════════════════════════════════════════════════════════

/**
 * 唯一索引冲突的文案 —— `<冲突的值>已存在`。
 *
 * ★★ 这个形状不是「设计」出来的，是 `GlobalExceptionHandler` 从 MySQL 的
 *   报错里**抠**出来的 ★★
 *   它拿 `Duplicate entry '(.*?)' for key` 去 match 驱动抛出的 message，
 *   取第 1 组再拼上 `MessageConstant.ALREADY_EXISTS = "已存在"`。
 *   所以最终用户看到的是 `DEV-2026-0001已存在`（值在前、没人话分隔），
 *   而不是「设备编号已存在」—— 抄成后者虽然更好读，但**对不上**，
 *   而前端在演示里核对的就是这些字。
 *
 * ★ 冲突的值一律是**用户刚提交的那个**（MySQL 报的是引起冲突的索引值），
 *   所以调用点传的是入参，不是库里那行的值 —— 两者在正常情形下相等，
 *   但当大小写不敏感（`utf8mb4_general_ci`）时可能不等，
 *   那时用户看到的必须是他自己敲的那个串，否则他会以为系统在说别人的数据。
 */
export function alreadyExists(value: string): string {
  return `${value}已存在`
}

// ══════════════════════════════════════════════════════════════════════════
// 带数量的删除守卫（后端是 `String.format` 模板，这里做成函数）
// ══════════════════════════════════════════════════════════════════════════

export function stationHasCharger(count: number): string {
  return `该站点下还有 ${count} 个充电桩，请先删除或转移`
}

export function chargerHasConnector(count: number): string {
  return `该充电桩下还有 ${count} 把充电枪，请先删除`
}

export function stationHasOrder(count: number): string {
  return `该站点下还有 ${count} 笔未完结的充电订单，请先处理`
}

export function chargerHasOrder(count: number): string {
  return `该充电桩下还有 ${count} 笔未完结的充电订单，请先处理`
}

export function categoryHasPackage(count: number): string {
  return `该分类下还有 ${count} 个充电套餐，请先删除或调整分类`
}

export function memberPackageAlreadyUsed(count: number): string {
  return `该权益已经被 ${count} 笔订单用过，不能退款`
}

// ══════════════════════════════════════════════════════════════════════════
// 带拼串的完整消息
// ══════════════════════════════════════════════════════════════════════════

/**
 * 建单时枪不空闲。
 *
 * ★ 后端 `assertConnectorUsable` 会把**当前状态**拼进括号 —— 那句括号是用户
 *   唯一的线索：显示「已占用」说明有人在充、显示「故障」说明该换站点。
 *   ★ 这里不引用字典的 `label()` 而是直接收一个标签参数：调用方手里就是
 *   `CONNECTOR_STATUS.label(row.status)` 的结果，多传一层不会更清楚。
 */
export function connectorBusy(statusLabel: string): string {
  return `${TEXT.CONNECTOR_OCCUPIED}（当前：${statusLabel}）`
}

/** 建单时充电桩不可用。后端 `assertChargerUsable` 会带上桩 id。 */
export function chargerUnavailable(statusLabel: string, chargerId: number): string {
  return `充电桩当前不可用（${statusLabel}）：chargerId=${chargerId}`
}

/**
 * 建单时「桩不属于这个站」。
 *
 * ★ 它和下面的「枪不属于」都是为了拦**前端的一个 bug**（把 A 站的订单发到 B 站的枪上）：
 *   只查枪的话会建出一笔「站点字段是 A、枪是 B」的订单 —— 结算按 A 的电价算，
 *   而实际用的是 B 的电。两张表各自都自洽，只有放一起看才发现对不上。
 */
export function chargerNotInStation(chargerId: number, stationId: number): string {
  return `充电桩不属于该站点：chargerId=${chargerId}，stationId=${stationId}`
}

/** 建单时「枪不属于这个桩或这个站」。★ 两个关系合成一句，后端就是一行 `||`。 */
export function connectorNotInChargerOrStation(connectorId: number): string {
  return `充电枪不属于该充电桩或站点：connectorId=${connectorId}`
}

/** 一把枪上有超过一笔进行中的订单 —— 脏数据，必须停下来人工处理。 */
export function connectorStateInvalid(connectorId: number, count: number): string {
  return `${TEXT.ORDER_CONNECTOR_STATE_INVALID}：connectorId=${connectorId} 上有 ${count} 笔进行中的订单`
}

/** 枪已被某笔订单占着（`assertConnectorFree` 的第二条分支）。 */
export function connectorHeldByOrder(orderNo: string): string {
  return `${TEXT.CONNECTOR_OCCUPIED}：已有订单 ${orderNo}`
}

/** 车辆有进行中的订单。 */
export function vehicleBusy(orderNo: string): string {
  return `${TEXT.ORDER_VEHICLE_BUSY}：已有订单 ${orderNo}`
}

/**
 * 支付时余额不够。
 *
 * ★ 逐字复刻 `WalletServiceImpl.settle` 抛的那句：`结算失败：可用余额不足以支付 X，orderId=N`。
 *   金额是 `BigDecimal` 直接拼进去的，所以**没有千分位、也没有强制两位小数**
 *   （`45` 就是 `45`，不是 `45.00`）。这里用 `yuan()` 之后 `toString()` 得到的
 *   正是 JS 对同一个数的默认渲染，两边一致。
 */
export function settleNotEnough(amountYuan: number, orderId: number): string {
  return `结算失败：可用余额不足以支付 ${amountYuan}，orderId=${orderId}`
}

/**
 * `ChargePackageServiceImpl.assertTypeFields` 的两条消息。
 *
 * ★ `requiredField` 是**英文列名**（`energyKwh` …），不要「友好化」成中文：
 *   那句话是给改库的人看的，列名才是他要去改的东西。
 *   两条的 `typeLabel` 都来自 `PackageType.getLabel()`。
 */
export function packageFieldRequired(typeLabel: string, field: string): string {
  return `${TEXT.PACKAGE_TYPE_FIELD_MISMATCH}：${typeLabel}必须填写 ${field}`
}

export function packageFieldLeftover(typeLabel: string, leftovers: string[]): string {
  return (
    `${TEXT.PACKAGE_TYPE_FIELD_MISMATCH}：${typeLabel}不能设置 ${leftovers.join('、')}` +
    `（这些字段属于别的套餐类型，残留值会被计费逻辑读走）`
  )
}

/**
 * `MemberPackageServiceImpl.requireBenefit` 的三条（购买路径上唯一的「校验」，见该方法注释）。
 *
 * ★★ `column` 是**英文列名**（`energy_kwh` …），不要「友好化」成中文 ★★
 *   这句话是给**改库的人**看的 —— 他要去 `charge_package` 里补那一列，
 *   列名才是他要找的东西。`typeLabel` 则是 `PackageType.getLabel()` 的中文，
 *   用来说明「是哪一种套餐的 SKU 坏了」。两者都在句子里，缺一不可。
 *
 * ★ 三条的后缀刻意不同（缺少 / 必须大于 0 / 售价非法 / 折扣区间）：
 *   它们对应四种完全不同的修法，合并成一句「数据不完整」等于让运维自己猜。
 */
export function packageBenefitMissing(typeLabel: string, column: string): string {
  return (
    `${TEXT.PACKAGE_FIELD_MISSING}：${typeLabel} 缺少 ${column}` +
    `（该列在 charge_package 里为空，正常路径下不会有这种数据）`
  )
}

export function packageBenefitNotPositive(typeLabel: string, column: string, actual: number): string {
  return `${TEXT.PACKAGE_FIELD_MISSING}：${typeLabel} 的 ${column} 必须大于 0，实际 ${actual}`
}

/** ★ 括号是**开闭不配对**的 `(0, 1]` —— 逐字抄后端，别「修正」成 `[0, 1]`。 */
export function packageDiscountInvalid(actual: number): string {
  return `${TEXT.PACKAGE_FIELD_MISSING}：服务费折扣必须在 (0, 1] 之间，实际 ${actual}`
}

/** 售价为负（只可能来自直接改库；0 是合法的「赠送套餐」）。 */
export function packagePriceInvalid(actualPrice: number): string {
  return `${TEXT.PACKAGE_FIELD_MISSING}：售价非法（${actualPrice}）`
}

/**
 * `ChargePackageServiceImpl.requireCategory` 的类型校验。
 *
 * ★ 后半句的**形状**是「而分类「X」是「Y」」—— 两个都是中文标签，
 *   一个是**这一行**分类的名字、一个是它的类型。抄的时候别把 `name` 丢掉：
 *   只报类型的话，管理员得回去列表里自己找哪个分类是「增值服务分类」。
 */
export function categoryTypeMismatch(categoryName: string, typeLabel: string): string {
  return (
    `${TEXT.CATEGORY_TYPE_MISMATCH}：充电套餐只能挂在「充电套餐分类」下，` +
    `而分类「${categoryName}」是「${typeLabel}」`
  )
}

/** 站点计费绑定的失效时刻不晚于生效时刻。后缀是两个时刻（`yyyy-MM-dd HH:mm:ss`）。 */
export function effectiveRangeInvalid(from: string, to: string): string {
  return `${TEXT.EFFECTIVE_RANGE_INVALID}：${from} -> ${to}`
}

/** 权益作废时的状态拒绝。后缀说明当前是什么状态、只有什么状态可以作废。 */
export function memberPackageStatusConflict(currentLabel: string): string {
  return `${TEXT.MEMBER_PACKAGE_STATUS_CONFLICT}：这份权益当前是「${currentLabel}」，只有「生效中」的可以作废`
}

/**
 * 权益作废的 CAS 失败（两个人同时点，或者期间被订单锁上/被结算扣光）。
 *
 * ★ 和上面那句**必须分开**：那一句是「你打开页面时状态就不对」，
 *   这一句是「你确认的那一瞬间被别的操作改了」—— 后者才是要用户**刷新重试**的。
 *   合成一句会让「刷新一下就能成功」的情况看起来像数据坏了。
 */
export function memberPackageStatusChanged(): string {
  return '权益当前状态不允许该操作：这份权益在你确认期间被别的操作改动了（另一笔作废、一笔订单锁上了它、或一次结算扣光了余量），请刷新后重试'
}

/** 权益被某笔进行中的订单占着，不能作废。★ 把订单 id 报出来，运营才能去查那笔单。 */
export function memberPackageLockedByOrder(orderId: number): string {
  return `${TEXT.MEMBER_PACKAGE_IN_USE}：占用它的订单 id = ${orderId}`
}
