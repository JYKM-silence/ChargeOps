/**
 * 语义色调 —— **前端自己的概念，后端没有这个字段**。
 *
 * ★ 为什么不让组件直接写颜色：同一个语义（「成功」「危险」）在管理端
 *   （Element Plus）和车主端（Vant）要用**不同的 CSS 变量**表达，
 *   而且浅色底/深色字的搭配也不一样。如果每个组件各写各的
 *   `type="success"` / `color="#00B42A"`，两端的观感一定会慢慢分叉，
 *   最后变成「管理端的成功是 #00B42A、H5 的是 #07C160」。
 *
 *   所以字典只给**语义**（tone），由两端各自的 `StatusTag` 决定怎么渲染。
 *   一次走查改一处，两端同时变。
 */
export type Tone = 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'accent'

/**
 * 色调 → CSS 变量名。
 *
 * ★ 变量定义在 `tokens.css`（两端各自 import 同一份），**不在这里写死颜色值** ——
 *   否则主题一变，这个文件就成了第二个真源。
 * ★ `-weak` 是浅底色，`-strong` 是同色深字。状态标签一律「浅底 + 同色深字」，
 *   不用纯色填充 —— 一整屏纯色块是「廉价」最主要的来源。
 */
export const TONE_VAR: Record<Tone, { base: string; weak: string; strong: string }> = {
  primary: { base: 'var(--co-primary)', weak: 'var(--co-primary-weak)', strong: 'var(--co-primary-strong)' },
  success: { base: 'var(--co-success)', weak: 'var(--co-success-weak)', strong: 'var(--co-success-strong)' },
  warning: { base: 'var(--co-warning)', weak: 'var(--co-warning-weak)', strong: 'var(--co-warning-strong)' },
  danger: { base: 'var(--co-danger)', weak: 'var(--co-danger-weak)', strong: 'var(--co-danger-strong)' },
  info: { base: 'var(--co-info)', weak: 'var(--co-info-weak)', strong: 'var(--co-info-strong)' },
  accent: { base: 'var(--co-accent)', weak: 'var(--co-accent-weak)', strong: 'var(--co-accent-strong)' },
}

/** Element Plus 的 `type` 取值（`el-tag` / `el-alert` / `el-button`）。 */
export function toneToElementType(tone: Tone): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  return tone === 'accent' ? 'primary' : tone
}

/** 金额正负的语义色。**只看符号，不看类型** —— 见 constants/dicts.ts 的说明。 */
export function signedTone(scaled: bigint): Tone {
  if (scaled > 0n) return 'success'
  if (scaled < 0n) return 'danger'
  return 'info'
}
