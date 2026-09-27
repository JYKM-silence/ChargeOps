/**
 * 尖峰平谷配色 —— **独立于状态色的一组**。
 *
 * ★★ 为什么不复用 `Tone`：尖峰用的也是红色，但那个红的含义是「电价高」，
 *   和「故障 / 支付失败」的红是**两件无关的事**。共用一套变量会让
 *   分段图和状态标签看起来有语义关联（「这段是红色的，所以出问题了？」），
 *   而它们没有。
 *
 *   → 所以这组颜色有自己的 CSS 变量前缀 `--co-period-*`，
 *     改电价配色不会影响任何状态标签。
 *
 * ★ 色相顺序刻意与电价的「高低」对齐：红 → 橙 → 蓝 → 绿，
 *   而**平段用蓝色**（不是灰色）—— 灰色在图表里会被读成「无数据」，
 *   但平段是一个真实存在的时段，通常还占了一天里最长的时间。
 */

import type { Tone } from '../constants/tone'
import { parseServerTime } from '../utils/datetime'

/** 时段类型的 CSS 变量名。 */
export const PERIOD_COLOR_VAR: Record<number, { base: string; weak: string; strong: string }> = {
  // 1 尖峰
  1: { base: 'var(--co-period-sharp)', weak: 'var(--co-period-sharp-weak)', strong: 'var(--co-period-sharp-strong)' },
  // 2 高峰
  2: { base: 'var(--co-period-peak)', weak: 'var(--co-period-peak-weak)', strong: 'var(--co-period-peak-strong)' },
  // 3 平段
  3: { base: 'var(--co-period-flat)', weak: 'var(--co-period-flat-weak)', strong: 'var(--co-period-flat-strong)' },
  // 4 低谷
  4: { base: 'var(--co-period-valley)', weak: 'var(--co-period-valley-weak)', strong: 'var(--co-period-valley-strong)' },
}

/** 时段 → 图表色。未知时段给一个中性灰（**不要**落到尖峰的红色上）。 */
export function periodColor(periodType: number | null | undefined): string {
  if (periodType === null || periodType === undefined) return 'var(--co-text-3)'
  return PERIOD_COLOR_VAR[periodType]?.base ?? 'var(--co-text-3)'
}

export function periodWeakColor(periodType: number | null | undefined): string {
  if (periodType === null || periodType === undefined) return 'var(--co-bg-2)'
  return PERIOD_COLOR_VAR[periodType]?.weak ?? 'var(--co-bg-2)'
}

/**
 * 分段在总时长里的宽度百分比。
 *
 * ★ 用**时间跨度**算，不用金额、不用电量：
 *   分段图的横轴是时间（00:00 → 24:00），宽度必须和横轴一致，
 *   否则图形和坐标轴对不上，看的人会把「宽的那段」理解成「贵的那段」。
 *   金额已经在表格里了，图只负责「时间上怎么分布」。
 */
export function segmentWidthPercent(
  startTime: string | Date | null | undefined,
  endTime: string | Date | null | undefined,
  dayStart: Date,
): number {
  const s = startTime instanceof Date ? startTime : parseServerTime(startTime)
  const e = endTime instanceof Date ? endTime : parseServerTime(endTime)
  if (!s || !e) return 0

  const DAY_MS = 24 * 60 * 60 * 1000
  const start = (s.getTime() - dayStart.getTime()) / DAY_MS
  const end = (e.getTime() - dayStart.getTime()) / DAY_MS
  const clamped = Math.min(Math.max(end, 0), 1) - Math.min(Math.max(start, 0), 1)
  return Math.max(0, clamped) * 100
}

/** 时段色调（与 `PERIOD_TYPE` 的 tone 保持一致）。 */
export const PERIOD_TONE: Record<number, Tone> = {
  1: 'danger',
  2: 'warning',
  3: 'primary',
  4: 'success',
}
