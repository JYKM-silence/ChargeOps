/**
 * `TimeCodec` 的等价实现 —— 把 `HH:mm` / `HH:mm:ss` 与「当日分钟数」互相转换。
 *
 * ★★ 为什么单独一个文件，而不是各自写在需要它的 handler 里 ★★
 *   这个转换有**两个**消费方，而且它们对它的要求**不完全一样**：
 *   · 站点的营业时间（`admin-station.ts`）—— 转完还要比大小，报错消息是
 *     `TimeCodec` 那四条；
 *   · 计费规则的时段（`admin-tariff.ts`）—— 转完要**两两相邻比较**找缺口和重叠，
 *     报错消息来自 `TariffValidator` / `TariffRuleSnapshot`。
 *
 *   两边共用的那部分恰好是最容易写错的那部分：**`24:00:00` 等于 1440 分钟，
 *   而它是合法的**。任何一处漏掉这条，就会得到「全天营业的站点总是报错」
 *   或者「跨零点的计费规则永远缺一段」—— 两个症状都很难从消息上看出来。
 *   复制一份的话，两份里只有一份会被改。
 *
 * ★ 解析失败返回**结构化结果**而不是 `null` 或者抛异常：
 *   两个消费方要的失败消息不一样（一个用 `TimeCodec` 的原文，
 *   一个是 `TariffRuleSnapshot` 的构造器消息），所以这里只判定「成不成立」，
 *   文案交给调用方 —— 但也因此，这里必须把**四种不同的失败**分开，
 *   否则调用方就只剩「格式不对」一句话可说了。
 */

/** `TimeCodec.MINUTES_PER_DAY`。 */
export const MINUTES_PER_DAY = 1440

/**
 * 解析失败的原因。
 *
 * ★ 四种分开而不是一个 `'invalid'`：它们对应的用户动作完全不同。
 *   `EMPTY` → 有字段没填；`FORMAT` → 格式不对（可能是没零填充）；
 *   `HOUR24` → 写了 `24:30` 这种不存在的时刻；`RANGE` → 数值越界。
 */
export type ClockError = 'EMPTY' | 'FORMAT' | 'HOUR24' | 'RANGE'

export type ClockParsed = { ok: true; minutes: number } | { ok: false; error: ClockError; raw: string }

/**
 * `HH:mm` / `HH:mm:ss` → 当日分钟数，`24:00:00` → 1440。
 *
 * ★★ 秒是**可选**的 ★★ HTML 的 `<input type="time">` 在秒为 0 时提交的是
 *   `"08:00"` 而不是 `"08:00:00"` —— 只认带秒的格式，表现是
 *   「页面上填得好好的时间，一保存就说格式不对」。
 *
 * ★★ 为什么用分钟数而不是字符串比较 ★★
 *   字符串比较在零填充的 `HH:mm:ss` 上**恰好**是对的，但那只在格式统一时成立，
 *   而 `<input type="time">` 正好会给出 `"8:00"` 这种不零填充的串 ——
 *   `"8:00" > "24:00:00"` 与 `"08:00" < "24:00:00"` 会给出相反答案。
 */
export function parseClock(raw: string | null | undefined): ClockParsed {
  const text = raw ?? ''
  if (text.trim() === '') return { ok: false, error: 'EMPTY', raw: text }

  const matched = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(text.trim())
  if (!matched) return { ok: false, error: 'FORMAT', raw: text }

  const hour = Number(matched[1])
  const minute = Number(matched[2])
  const second = matched[3] === undefined ? 0 : Number(matched[3])

  // ★ 上界只放行 `24:00:00` 这**一个**值，不是「小时 <= 24」——
  //   那样 `24:30:00` 也会过，而「24 点之后」只能是次日 0 点。
  //   MySQL 的 TIME 能存到 838 小时是它的**时长**语义，不是这几列想要的。
  if (hour === 24 && (minute !== 0 || second !== 0)) {
    return { ok: false, error: 'HOUR24', raw: text }
  }
  if (hour > 24 || minute > 59 || second > 59) {
    return { ok: false, error: 'RANGE', raw: text }
  }
  return { ok: true, minutes: hour * 60 + minute }
}

/**
 * 当日分钟数 → `HH:mm:ss`。
 *
 * ★ 1440 **不用特判**：`1440 / 60 = 24`、`1440 % 60 = 0`，拼出来正好是
 *   `"24:00:00"`。加一个 `if (minute === 1440) return '24:00:00'` 是多余的分支，
 *   而多余的分支就是将来会被改错的那个。
 */
export function clockOf(minuteOfDay: number): string {
  const h = Math.floor(minuteOfDay / 60)
  const m = minuteOfDay % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`
}

/**
 * `"08:00"` → `"08:00:00"`，解析失败返回 `null`。
 *
 * ★★ 入库格式必须统一 ★★ 库里的 `ORDER BY start_time` 以及
 *   `uk_tariff_rule_tpl_start (template_id, start_time)` 这条唯一索引
 *   都**依赖零填充**这一性质 —— 混合格式下 MySQL 里 `"8:00"` 和 `"08:00"`
 *   是同一个 TIME 值，唯一索引判它们重复，而字符串比较判它们不同。
 */
export function normalizeClock(raw: string): string | null {
  const parsed = parseClock(raw)
  return parsed.ok ? clockOf(parsed.minutes) : null
}
