/**
 * 时间解析与格式化。
 *
 * ★★ 本文件存在的唯一理由：**绝对不要对后端的字符串直接 `new Date(str)`。** ★★
 *
 *   后端（Jackson 3 + `application.yml` 里的 date-format）输出的时间形如
 *   `2026-01-01 00:00:00` —— 注意是**空格分隔、无时区、非 ISO 8601**。
 *   这在各浏览器里的行为**不一致**：
 *     · Chrome/Edge  会当成**本地时间**解析（能跑，但依赖实现细节）
 *     · Safari       直接得到 `Invalid Date`（不报错，页面显示 NaN/空白）
 *   而 `Date.parse` 在 Safari 上返回 NaN 之后，任何 `toLocaleString()` 都只会
 *   渲染出「Invalid Date」—— 这类问题在开发机（Chrome）上**永远复现不了**。
 *
 *   所以统一走手写正则解析，逐字段取数后 `new Date(y, m-1, d, ...)`（本地时间构造）。
 *   后端全链路无时区概念（DB 是 datetime，无 tz），所以「本地时间」就是正确语义。
 *
 * ★ 反向：发给后端的时间**必须**格式化成 `yyyy-MM-dd HH:mm:ss`。
 *   后端 DTO 的时间字段标注了 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")`，
 *   格式不符会在**反序列化阶段**就 4xx/报错，而不是进 Service 再校验。
 */

/** 后端时间格式，两端唯一真源。带 `-` 分隔、24 小时制、**无毫秒无时区**。 */
export const SERVER_DATETIME_PATTERN = 'yyyy-MM-dd HH:mm:ss'

/** 宽容的解析正则：允许 `T` 分隔、允许省略秒、允许带毫秒（后端偶尔来自 DB 的裸值）。 */
const PARSE_RE = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?(?:\.(\d{1,3}))?)?$/

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/**
 * 解析后端时间字符串 → Date（本地时间语义）。
 * 解析不出来返回 `null`，**不抛异常** —— 调用方通常只想「显示个东西」，
 * 为了一个坏值把整个列表渲染炸掉不值得。
 */
export function parseServerTime(input: string | null | undefined): Date | null {
  if (!input) return null
  const m = PARSE_RE.exec(input.trim())
  if (!m) return null

  const year = Number(m[1])
  const month = Number(m[2])
  const day = Number(m[3])
  const hour = m[4] === undefined ? 0 : Number(m[4])
  const minute = m[5] === undefined ? 0 : Number(m[5])
  const second = m[6] === undefined ? 0 : Number(m[6])
  const ms = m[7] === undefined ? 0 : Number(m[7].padEnd(3, '0'))

  if (month < 1 || month > 12 || day < 1 || day > 31) return null
  if (hour > 23 || minute > 59 || second > 59) return null

  const date = new Date(year, month - 1, day, hour, minute, second, ms)
  // 反查一遍，挡掉 2026-02-31 这种「字段都在范围内但日期不存在」的值
  // （Date 构造函数会把它滚到 3 月 3 日，静默给出错误结果）
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return null
  }
  return date
}

/** 格式化成本后端格式 `yyyy-MM-dd HH:mm:ss`。用于**所有**发给后端的时间参数。 */
export function formatServerTime(input: Date | string | null | undefined): string | null {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return null
  return (
    `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
  )
}

/** `yyyy-MM-dd`。用于日期选择器（后端接受 `date` 参数时）。 */
export function formatDate(input: Date | string | null | undefined): string | null {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return null
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

/** `HH:mm`。用于时间轴、分段起止。 */
export function formatTime(input: Date | string | null | undefined): string | null {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return null
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** `MM-dd HH:mm`。列表里省宽度的常用形态。 */
export function formatShort(input: Date | string | null | undefined): string | null {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return null
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/** `MM月dd日 HH:mm`。H5 里比 `MM-dd` 更口语。 */
export function formatCn(input: Date | string | null | undefined): string | null {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return null
  return `${d.getMonth() + 1}月${d.getDate()}日 ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/**
 * 相对时间。列表里比绝对时间更容易读，但**超过一定跨度必须回落到绝对时间** ——
 * 「37天前」这种表达没有意义。
 */
export function formatRelative(input: Date | string | null | undefined, now = new Date()): string {
  const d = input instanceof Date ? input : parseServerTime(input)
  if (!d || Number.isNaN(d.getTime())) return '-'

  const diff = now.getTime() - d.getTime()
  // 未来时间（时钟偏差）不显示「-3分钟前」这种，直接给绝对时间
  if (diff < 0) return formatShort(d) ?? '-'
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`

  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  if (sameDay) return `今天 ${formatTime(d)}`

  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (
    d.getFullYear() === yesterday.getFullYear() &&
    d.getMonth() === yesterday.getMonth() &&
    d.getDate() === yesterday.getDate()
  ) {
    return `昨天 ${formatTime(d)}`
  }

  if (diff < 7 * 86_400_000) return formatShort(d) ?? '-'
  return formatDate(d) ?? '-'
}

/** 两个时间之间的时长（毫秒）。任一不可解析返回 null。 */
export function durationBetween(
  from: string | Date | null | undefined,
  to: string | Date | null | undefined,
): number | null {
  const a = from instanceof Date ? from : parseServerTime(from)
  const b = to instanceof Date ? to : parseServerTime(to)
  if (!a || !b) return null
  const ms = b.getTime() - a.getTime()
  return ms < 0 ? null : ms
}

/** 毫秒 → `1小时23分` / `23分10秒` / `8秒`。用于充电时长展示。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || ms < 0 || !Number.isFinite(ms)) return '-'
  const totalSeconds = Math.floor(ms / 1000)
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

/** 毫秒 → `01:23:45` / `23:45`。用于**待支付倒计时**（事实 4）。 */
export function formatCountdown(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms <= 0) return '00:00'
  const totalSeconds = Math.floor(ms / 1000)
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  return h > 0 ? `${pad2(h)}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`
}

/** 待支付订单的存活窗口：**15 分钟**，与后端定时任务口径一致（事实 4）。 */
export const PENDING_PAY_TIMEOUT_MS = 15 * 60 * 1000

/**
 * 从「订单创建时间 + 15 分钟」算出剩余毫秒。
 *
 * ★ 为什么用 `createTime` 而不是某个 `expireTime` 字段：后端**没有**返回过期时间，
 *   它的超时是定时任务按 `create_time < now() - 15min` 扫出来的（5 分钟一轮），
 *   所以「过期时刻」在前端只能由创建时间推算。也因此**到点不等于立刻变状态** ——
 *   最长可能晚 5 分钟才被扫到，UI 文案要说「预计」，不能说「已超时，正在关闭」。
 */
export function pendingPayRemainMs(
  createTime: string | null | undefined,
  now = Date.now(),
): number | null {
  const created = parseServerTime(createTime)
  if (!created) return null
  return created.getTime() + PENDING_PAY_TIMEOUT_MS - now
}

/** 今天 00:00:00（本地）。订单列表默认「近 7 天」的右端点。 */
export function startOfToday(now = new Date()): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate())
}

/**
 * 近 N 天区间。用于订单列表的**默认筛选**。
 *
 * ★ 为什么订单列表要有默认区间：后端订单列表在**无任何过滤条件**时是对全表
 *   `ORDER BY create_time DESC` + PageHelper 分页 —— 表一大就是 filesort 全表扫。
 *   这不是前端的性能问题，但会让演示时的第一个请求就很慢，所以默认带上近 7 天。
 *   区间边界用 `00:00:00` ~ `23:59:59`（含端点），与后端 `BETWEEN` 语义对齐。
 */
export function lastNDaysRange(days: number, now = new Date()): { begin: string; end: string } {
  const begin = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (days - 1))
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59)
  return {
    begin: formatServerTime(begin) as string,
    end: formatServerTime(end) as string,
  }
}
