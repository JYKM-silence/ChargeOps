/**
 * 前端校验规则 —— **逐条对应后端 DTO 上的 `jakarta.validation` 注解**。
 *
 * ★★ 本文件不是「我们自己觉得合理的规则」，是把后端的注解抄一遍。★★
 *   理由：后端校验失败返回的是 **HTTP 200 + code:0 + 中文 msg**（见 http/types.ts），
 *   用户点「保存」→ 转一圈 → 弹一句「站点编码只能是字母、数字和连字符」——
 *   体验上等同于「先让你错一次」。前端先行校验是为了**不让他错**，
 *   所以规则必须**与后端完全一致**：松了 → 后端打回，白跑一次；
 *   紧了 → **合法的值被前端拦住，功能直接不可用**（后者严重得多）。
 *
 *   所以每条规则旁边都标了出处（`StationDTO:80`），改的时候去对一眼。
 *   后端改了注解而这里没跟着改，表现是「前端不让填合法值」，
 *   而不是报错 —— 这类问题最难查，所以注释里留下精确坐标。
 *
 * ★ 另一个坑：**登录接口刻意没有格式校验**。注册的 `phone` 有严格 `@Pattern`，
 *   而登录的 `phone` **只有 `@NotBlank` + `@Size(max=20)`**（后端注释里写明了）。
 *   所以登录表单**不能**套用手机号正则 —— 一旦套上，任何历史遗留的、
 *   不符合 `1[3-9]xxxxxxxxx` 的账号就再也登不进来了。这是「前端比后端严」
 *   直接造成功能不可用的活例子。
 */

/** 手机号：`MemberRegisterDTO:57` 的 `@Pattern`。**只用于注册/搜索，不用于登录**。 */
export const MOBILE_RE = /^1[3-9]\d{9}$/

/** 站点编码 / 模板编码：`StationDTO:80`、`TariffTemplateDTO:68`。 */
export const CODE_RE = /^[A-Za-z0-9-]+$/

/**
 * 时刻 `HH:mm` 或 `HH:mm:ss`，**不允许 24:00:00**。
 * 用于 `openTime` / `TariffRuleDTO.startTime`。
 */
export const TIME_HM_RE = /^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/

/**
 * 时刻 `HH:mm` / `HH:mm:ss`，**额外允许 `24:00:00`**。
 * 用于 `closeTime` / `TariffRuleDTO.endTime`。
 *
 * ★ 为什么关门时间和开始时间规则不同：一天 24 小时的营业时间必须用
 *   `24:00:00` 表示「到当天结束」，写成 `00:00:00` 会被理解成「从 0 点开始」。
 *   而**只允许精确的 `24:00:00`**（不能是 `24:30`），所以正则里 `24` 后面
 *   硬写了 `:00:00`。
 */
export const TIME_HM_END_RE = /^(([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?|24:00:00)$/

export function isMobile(v: unknown): boolean {
  return typeof v === 'string' && MOBILE_RE.test(v)
}

/** 密码：`MemberRegisterDTO:67`、`MemberPasswordDTO:91` 的 `@Size(6,32)`。 */
export function isPassword(v: unknown): boolean {
  return typeof v === 'string' && v.length >= 6 && v.length <= 32
}

export function isCode(v: unknown): boolean {
  return typeof v === 'string' && CODE_RE.test(v)
}

export function isTimeHM(v: unknown): boolean {
  return typeof v === 'string' && TIME_HM_RE.test(v)
}

export function isTimeHMEnd(v: unknown): boolean {
  return typeof v === 'string' && TIME_HM_END_RE.test(v)
}

/**
 * 各文本字段的长度上限。**键名刻意与后端字段对齐**，从后端注解抄来。
 * 表单里 `maxlength` 直接用这些值，保证「打满了也提交得上去」。
 */
export const MAX_LEN = {
  /** `StationDTO:79` / `StationUpdateDTO:57` / `TariffTemplateDTO:67` */
  code: 32,
  /** `StationDTO:85` */
  stationName: 64,
  /** `CategoryDTO:72` */
  categoryName: 32,
  /** `ChargePackageDTO:101` */
  packageName: 64,
  /** `ChargePackageDTO:202` */
  packageDescription: 512,
  /** `ChargerDTO:74` */
  deviceNo: 32,
  /** `ChargerDTO:79` */
  chargerName: 64,
  /** `ChargerDTO:90` */
  model: 64,
  /** `ConnectorDTO:91` —— 枪口号是字符串，注意 `gunNo` 排序问题 */
  gunNo: 16,
  /** `ConnectorDTO:104` */
  interfaceType: 32,
  /** `TariffTemplateDTO:73` */
  templateName: 64,
  /** `TariffTemplateDTO:83` */
  region: 64,
  /** `TariffTemplateDTO:87` */
  templateRemark: 512,
  /** `StationDTO:89` */
  operator: 64,
  /** `StationDTO:106` */
  address: 255,
  /** `StationDTO:128` */
  servicePhone: 20,
  /** `VehicleDTO:66` */
  plateNo: 16,
  /** `VehicleDTO:76` / `VehicleDTO:80` */
  vehicleBrand: 50,
  /** `VehicleDTO:101` */
  vehicleRemark: 255,
  /** `MemberUpdateDTO:72` */
  nickname: 50,
  /** `MemberUpdateDTO:85` */
  avatar: 255,
  /** `WalletAdjustDTO:83` —— 单条 200 字 */
  adjustReason: 200,
  /** `MemberPackageVoidDTO:61` */
  voidReason: 200,
  /** `MemberLoginDTO:51` —— 登录手机号只限长，**不限格式** */
  loginPhone: 20,
} as const

/** 金额/数值上下限，同样抄自后端注解。 */
export const LIMITS = {
  /** `WalletRechargeDTO:30-31` 充值：0.01 ~ 50000.00 */
  rechargeMin: '0.01',
  rechargeMax: '50000.00',
  /** `WalletAdjustDTO:65-66` 调账：-100000.00 ~ 100000.00 */
  adjustMin: '-100000.00',
  adjustMax: '100000.00',
  /**
   * `OrderRefundDTO:40-41` 退款：0.01 ~ 100000.00。
   *
   * ★ 这里有个容易搞反的点：后端**有绝对上限**（10 万），
   *   但**没有「不得超过实付金额」这个相对上限**（`OrderRefundDTO` 的
   *   注释里没做这个校验）。所以前端要补的是**相对**那一条
   *   （`退款金额 ≤ payAmount`），绝对区间照抄即可 —— 别以为后端什么都没校验。
   */
  refundMin: '0.01',
  refundMax: '100000.00',
  /** 服务费折扣必须落在 (0, 1]，`ChargePackageDTO:184-185`。 */
  discountMax: '1',
  /** 电量必须 > 0，`ChargePackageDTO:151`。 */
  kwhMinExclusive: '0',
  /** `ChargerDTO` / `ConnectorDTO` 额定功率必须 > 0。 */
  powerMinExclusive: '0',
} as const

/** 通用范围判断（闭区间）。`inclusive=false` 时开区间。 */
export function inRange(value: number, min: number, max: number, inclusive = true): boolean {
  if (!Number.isFinite(value)) return false
  return inclusive ? value >= min && value <= max : value > min && value < max
}

/** 枚举值判断：`oneOf(status, 0, 1, 2)`。比手写 `||` 链更难写错。 */
export function oneOf<T extends number | string>(value: T | null | undefined, ...allowed: T[]): boolean {
  return value !== null && value !== undefined && allowed.includes(value)
}

/**
 * 调账金额的业务校验 —— **后端拦不住 0**。
 *
 * ★★ 出处：`WalletAdjustDTO:55` 的原注释「0 由 `@DecimalMin/@DecimalMax` 拦不住
 *   （0 落在区间内）」。也就是说 `adjustAmount = 0` 能一路通过参数校验，
 *   然后在 Service 里产生一条**金额为 0 的资金流水** —— 一条脏数据，
 *   而且这个动作后端**没有幂等键**（见 domain/orderRules.ts 的说明）。
 *
 *   所以这条**必须**由前端拦住，是前端唯一不可省的金额校验。
 */
export function isAdjustableAmount(value: string | number | null | undefined): boolean {
  if (value === null || value === undefined || value === '') return false
  const n = Number(value)
  if (!Number.isFinite(n)) return false
  if (n === 0) return false
  return n >= -100000 && n <= 100000
}

/** 退款金额：绝对区间照抄后端，**相对上限**由调用方传入 `payAmount` 一起判。 */
export function isRefundableAmount(
  value: string | number | null | undefined,
  payAmount?: string | number | null,
): boolean {
  if (value === null || value === undefined || value === '') return false
  const n = Number(value)
  if (!Number.isFinite(n)) return false
  if (n < 0.01 || n > 100000) return false
  if (payAmount === null || payAmount === undefined) return true
  const paid = Number(payAmount)
  // 浮点比较只用来「拦住明显越界」，真正相等与否交给后端 BigDecimal 判
  return Number.isFinite(paid) ? n <= paid + 1e-9 : true
}

/**
 * 银行卡/金额输入框的「合法中间态」判断。
 *
 * ★ 为什么需要它：输入 `1.` 或 `.` 时 `Number()` 得到的是 `NaN`，
 *   但用户**还在输入**，此时把输入框标红是错的。输入框用这个过滤按键，
 *   提交时再用上面那些**严格**的函数校验。两者不能混用。
 */
export function isPartialAmountInput(v: string): boolean {
  return /^\d*\.?\d*$/.test(v)
}
