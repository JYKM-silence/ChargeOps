/**
 * 金额计算（**精确十进制，禁用浮点**）。
 *
 * ★★ 为什么不能直接 `Number(a) + Number(b)` ★★
 *
 *   后端金额是 `BigDecimal`，而且有一处**明确的承诺**：
 *   「只在最后取整一次，保证 Σ分段金额 == 订单总额」。
 *   前端如果拿 `Number` 去加，会在分位上出现 0.01 的偏差，于是出现
 *   **「分段加起来 ≠ 总额」这种看起来像后端 bug、实际是前端算错的现象**，
 *   排查方向会完全跑偏。典型例子：`0.1 + 0.2 === 0.30000000000000004`。
 *
 *   所以本文件用 **BigInt 缩放整数**做加减与比较：
 *   把 `"1.0188"` 按 scale=4 变成 `10188n`，全程整数运算，输出时再插小数点。
 *
 * ★ 两个不同的 scale，**不要混用**：
 *   · 金额（totalAmount / payAmount / balance / 分段 amount） → **2 位**
 *   · 单价（电价 尖峰/高峰/平段/低谷 / 服务费）             → **4 位**
 *   后端电价确实是 4 位（种子里 `0.3188` / `1.2188`），按 2 位解析会**静默截断**。
 */

/** 金额小数位（元）。 */
export const AMOUNT_SCALE = 2
/** 单价小数位（元/度）。 */
export const PRICE_SCALE = 4

/** 后端 BigDecimal 的 JSON 形态：既可能是字符串，也可能是数字（Jackson 视配置而定）。 */
export type Decimal = string | number | null | undefined

const DECIMAL_RE = /^([+-]?)(\d*)(?:\.(\d*))?$/

/**
 * 十进制字面量 → 缩放后的 BigInt。`toScaled("1.0188", 4)` → `10188n`。
 *
 * 超出 scale 的部分按 **四舍五入**（HALF_UP）处理，与后端 `BigDecimal.setScale` 的
 * 默认行为一致；不做「截断」是因为那会让 0.005 一类的值系统性偏小。
 */
export function toScaled(value: Decimal, scale: number): bigint {
  if (value === null || value === undefined || value === '') return 0n
  const raw = typeof value === 'number' ? value.toString() : value.trim()
  const m = DECIMAL_RE.exec(raw)
  if (!m || (m[2] === '' && (m[3] === undefined || m[3] === ''))) {
    // 解析不了就归零而不是抛异常：列表里一个坏金额不该让整页渲染失败。
    // 但 NaN/Infinity 走的是 number → toString 的 'NaN'，也会落到这里。
    return 0n
  }

  const sign = m[1] === '-' ? -1n : 1n
  const intPart = m[2] === '' ? '0' : m[2]
  const fracRaw = m[3] ?? ''

  // 先按 scale 截齐小数部分
  const frac = (fracRaw + '0'.repeat(scale)).slice(0, scale)
  const digits = `${intPart}${frac}`
  let scaled = BigInt(digits) * sign

  // 处理被丢弃的那一位：决定是否进位
  const dropped = fracRaw.slice(scale)
  if (dropped.length > 0 && dropped.charCodeAt(0) >= 53 /* '5' */) {
    scaled += sign
  }
  return scaled
}

/** 缩放后 BigInt → 十进制字符串。`10188n, 4` → `"1.0188"`。 */
export function fromScaled(scaled: bigint, scale: number): string {
  const neg = scaled < 0n
  const abs = neg ? -scaled : scaled
  const s = abs.toString().padStart(scale + 1, '0')
  const intPart = s.slice(0, s.length - scale)
  const fracPart = scale > 0 ? s.slice(s.length - scale) : ''
  const body = scale > 0 ? `${intPart}.${fracPart}` : intPart
  return neg ? `-${body}` : body
}

/** 精确求和。返回 scale 位的字符串（**不是 number**）。空数组返回 `0.00`。 */
export function sumDecimal(values: readonly Decimal[], scale = AMOUNT_SCALE): string {
  let acc = 0n
  for (const v of values) acc += toScaled(v, scale)
  return fromScaled(acc, scale)
}

/** 精确减法 `a - b`。 */
export function subDecimal(a: Decimal, b: Decimal, scale = AMOUNT_SCALE): string {
  return fromScaled(toScaled(a, scale) - toScaled(b, scale), scale)
}

/** 精确加法 `a + b`。 */
export function addDecimal(a: Decimal, b: Decimal, scale = AMOUNT_SCALE): string {
  return fromScaled(toScaled(a, scale) + toScaled(b, scale), scale)
}

/** 比较：`-1` 小于 / `0` 等于 / `1` 大于。 */
export function compareDecimal(a: Decimal, b: Decimal, scale = AMOUNT_SCALE): -1 | 0 | 1 {
  const x = toScaled(a, scale)
  const y = toScaled(b, scale)
  return x < y ? -1 : x > y ? 1 : 0
}

/** 是否大于 0（金额校验用，如「退款金额必须 > 0」）。 */
export function isPositive(value: Decimal, scale = AMOUNT_SCALE): boolean {
  return toScaled(value, scale) > 0n
}

/**
 * 千分位格式化。`1234.5` → `"1,234.50"`。
 * 纯展示用，**返回值不要拿去做任何计算**。
 */
export function formatMoney(value: Decimal, scale = AMOUNT_SCALE): string {
  const text = fromScaled(toScaled(value, scale), scale)
  const neg = text.startsWith('-')
  const body = neg ? text.slice(1) : text
  const [intPart, fracPart] = body.split('.')
  const withSep = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const out = fracPart ? `${withSep}.${fracPart}` : withSep
  return neg ? `-${out}` : out
}

/**
 * 单价展示：**保留 4 位**，且不做千分位（电价从不超三位整数，加了反而啰嗦）。
 * `0.3188` → `"0.3188"`。
 */
export function formatPrice(value: Decimal): string {
  return fromScaled(toScaled(value, PRICE_SCALE), PRICE_SCALE)
}

/**
 * 电量（kWh）。后端是 `BigDecimal`，小数位不固定（充电量可能是 `12.3456`）。
 * 展示统一 2 位；**求和仍要走 `sumDecimal(..., 4)`**，不要用这个函数的输出再相加。
 */
export function formatKwh(value: Decimal): string {
  return fromScaled(toScaled(value, 4), 4)
}

/** 电量展示（带单位、2 位小数）：`12.35 度`。 */
export function formatKwhLabel(value: Decimal): string {
  return `${formatMoney(value, 2)} 度`
}

/**
 * 分段金额自检：**Σ各段金额 是否等于 订单总额**。
 *
 * ★ 后端承诺这个等式**恒成立**（只在最后取整一次）。前端在订单详情里
 *   显示分段明细时用这个做一次断言 —— 不相等说明要么 mock 数据造错了，
 *   要么后端口径变了，两者都值得在开发期立刻炸出来，而不是等用户发现。
 *   **只在开发期调用**（应用层用 `import.meta.env.DEV` 包住），生产不做这个计算。
 */
export function segmentsMatchTotal(
  segmentAmounts: readonly Decimal[],
  totalAmount: Decimal,
): boolean {
  return toScaled(sumDecimal(segmentAmounts), AMOUNT_SCALE) === toScaled(totalAmount, AMOUNT_SCALE)
}

/**
 * 进度百分比（0~100，整数）。用于「电量进度 / 权益剩余额度」。
 * `total <= 0` 时返回 0（**不要**返回 NaN，进度条会渲染成怪异宽度）。
 */
export function percentOf(used: Decimal, total: Decimal): number {
  const t = toScaled(total, AMOUNT_SCALE)
  if (t <= 0n) return 0
  const u = toScaled(used, AMOUNT_SCALE)
  const pct = Number((u * 100n) / t)
  return Math.max(0, Math.min(100, pct))
}
