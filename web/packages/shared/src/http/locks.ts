/**
 * 防重（四层里的三层，第四层是「不做乐观更新」——那是个约定，不是代码）。
 *
 * ★★ 为什么这个文件在这个项目里特别重要 ★★
 *
 *   后端**没有任何幂等键**（全项目无 `Idempotency-Key`、无去重表、无唯一约束兜底）。
 *   所以「同一个动作被执行两次」在后端**不会被拦住**，会实实在在地产生两条记录。
 *   其中两处是真金白银：
 *
 *     · `POST /api/admin/wallet/{memberId}/adjust` —— 调账两次 = 两条资金流水
 *     · `POST /api/admin/member-package/{id}/void` —— 作废两次 = 两条作废记录
 *
 *   而这两种操作恰好都会弹一个二次确认框 —— 用户点「确定」的那一下
 *   如果因为卡顿被点了两下、或者确认按钮没及时禁用，就是重复扣款。
 *   **这类事故没法靠后端兜底，只能靠前端挡住。**
 *
 * 三层各管一段，缺一不可：
 *
 *   | 层                | 管什么                                   | 挡不住的 |
 *   |-------------------|------------------------------------------|----------|
 *   | `SubmitLock`      | 按钮级：一次提交流程内，重复点击不重发   | 关掉弹窗重开再点一次 |
 *   | `withActionLock`  | 业务级：同 payload 的动作在冷却期内拒绝  | 刷新页面后立刻重试 |
 *   | GET 合并（createHttp 里） | 读请求的并发竞态                 | — |
 *
 *   ★ 剩下的「刷新页面后重试」确实挡不住，所以**资金操作必须二次确认**，
 *     确认框里要让用户看清金额 —— 这一层是「人」，不是代码。
 */

import { ApiError } from './errors'

/** `SubmitLock.run` 的结果。用显式状态而不是 `T | undefined`，避免和业务返回值混淆。 */
export type RunOutcome<T> =
  | { status: 'done'; value: T }
  /** 被本层拦下：正在提交中，或距上次提交太近。**调用方通常什么都不用做**。 */
  | { status: 'skipped'; reason: 'pending' | 'throttled' }
  /** 执行了但失败。错误已归一化，调用方决定是 toast 还是就地显示。 */
  | { status: 'failed'; error: unknown }

export interface SubmitLockOptions {
  /**
   * 距上次提交小于这个毫秒数时直接跳过。
   *
   * ★ 为什么光有「in-flight 标志」不够：`in-flight` 只在请求往返期间有效。
   *   如果后端很快（本机几毫秒），第一次请求已经返回、标志已清，
   *   而用户的第二下点击刚好落在「标志刚清、按钮还没重绘」的缝隙里 —— 照样重发。
   *   H5 上手指连点两下的间隔可以低到 40ms，所以必须有一个**时间下限**。
   *   H5 用 300ms（触摸场景），管理端鼠标点击用 200ms 即可。
   */
  throttleMs?: number
}

/**
 * 按钮级提交锁。**非响应式**（shared 不依赖 vue），
 * 两个 app 各自用 `ref` 包一层（见 admin/h5 的 `useSubmitLock`）。
 */
export class SubmitLock {
  private pending = false
  private lastSettledAt = 0
  private readonly throttleMs: number
  private readonly listeners = new Set<() => void>()

  constructor(options: SubmitLockOptions = {}) {
    this.throttleMs = options.throttleMs ?? 200
  }

  get isPending(): boolean {
    return this.pending
  }

  /** 订阅状态变化（app 层用来同步到 `ref`）。返回取消订阅函数。 */
  onChange(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private notify(): void {
    this.listeners.forEach((l) => l())
  }

  /**
   * 执行一次提交。**不抛异常** —— 失败以 `{status:'failed'}` 返回，
   * 这样调用方不会因为漏写 try/catch 而让「提交失败」变成未捕获的 rejection。
   */
  async run<T>(fn: () => Promise<T>): Promise<RunOutcome<T>> {
    if (this.pending) return { status: 'skipped', reason: 'pending' }
    if (Date.now() - this.lastSettledAt < this.throttleMs) {
      return { status: 'skipped', reason: 'throttled' }
    }

    this.pending = true
    this.notify()
    try {
      const value = await fn()
      return { status: 'done', value }
    } catch (error) {
      return { status: 'failed', error }
    } finally {
      this.pending = false
      this.lastSettledAt = Date.now()
      this.notify()
    }
  }
}

/** 业务级动作被冷却期拦下。 */
export class ActionLockedError extends Error {
  readonly key: string
  constructor(key: string) {
    super('该操作正在处理中，请勿重复提交')
    this.name = 'ActionLockedError'
    this.key = key
  }
}

/**
 * 动作锁表：`key → 解锁时刻`。
 *
 * ★ 模块级共享（不是每个调用点一份）—— 因为要拦的正是「页面上有两处入口
 *   触发同一个动作」（列表行的按钮 + 详情页的按钮）。各自持有锁就拦不住。
 */
const actionLocks = new Map<string, number>()

/** 默认冷却 3 秒。足够覆盖「连点 / 弹窗关掉立刻重开再点」的窗口。 */
const DEFAULT_COOLDOWN_MS = 3_000

/**
 * 业务级动作锁。
 *
 * @param key 动作标识。**应当包含 payload**，例如
 *            `wallet-adjust:12:100.00:活动补偿`。
 *            包含 payload 的意义：用户先提交 +100，之后**合法地**再提交 +200，
 *            两次是不同的动作，不该被互斥。而同 payload 的第二次几乎必然是重复提交。
 * @param fn 真正要做的事
 * @throws ActionLockedError 冷却期内重复调用
 */
export async function withActionLock<T>(
  key: string,
  fn: () => Promise<T>,
  cooldownMs = DEFAULT_COOLDOWN_MS,
): Promise<T> {
  const now = Date.now()
  const unlockAt = actionLocks.get(key)

  if (unlockAt !== undefined && now < unlockAt) {
    // 提示文案走这里而不是让调用方写 —— 三处调用点写三遍必然会不一致
    throw new ActionLockedError(key)
  }

  actionLocks.set(key, now + cooldownMs)
  try {
    return await fn()
  } catch (error) {
    // ★ 失败时**缩短**冷却窗口而不是保持：失败说明这次动作没生效，
    //   用户应当能立刻重试（比如网络抖了一下）。保持 3 秒会让他以为按钮坏了。
    //   但仍然留 600ms —— 足够挡住「失败提示弹出的瞬间又点了一下」。
    actionLocks.set(key, Date.now() + Math.min(cooldownMs, 600))
    throw error
  }
  // 成功时**不清理**，让冷却窗口自然过期 —— 这正是我们要的「刚成功过就别再来一次」
}

/** 清空所有动作锁。仅测试用。 */
export function resetActionLocks(): void {
  actionLocks.clear()
}

/**
 * 从任意值生成一个稳定的短字符串，用于拼锁 key。
 *
 * ★ 键按**字典序排序**：`{a:1,b:2}` 和 `{b:2,a:1}` 必须得到同一个 key，
 *   否则「同一个 payload 换个字段顺序」会绕过锁。
 */
export function payloadKey(payload: unknown): string {
  if (payload === null || payload === undefined) return ''
  if (typeof payload !== 'object') return String(payload)
  if (Array.isArray(payload)) return `[${payload.map(payloadKey).join(',')}]`

  const entries = Object.entries(payload as Record<string, unknown>)
    .filter(([, v]) => v !== undefined && v !== null)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => `${k}:${payloadKey(v)}`)
  return `{${entries.join(',')}}`
}

/** 判断一个错误是不是被动作锁拦下的（UI 上通常静默，或给一句轻提示）。 */
export function isActionLocked(error: unknown): error is ActionLockedError {
  return error instanceof ActionLockedError
}

/** 判断一个错误要不要弹 toast：被锁静默、请求取消静默。 */
export function shouldStaySilent(error: unknown): boolean {
  if (isActionLocked(error)) return true
  if (error instanceof ApiError) return false
  // axios 的取消错误（对象上有 __CANCEL__ 或 code === 'ERR_CANCELED'）
  if (typeof error === 'object' && error !== null) {
    const e = error as { code?: string; __CANCEL__?: boolean }
    if (e.__CANCEL__ || e.code === 'ERR_CANCELED') return true
  }
  return false
}
