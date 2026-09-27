/**
 * 把 shared 的 `SubmitLock` 包成响应式（车主端）。
 *
 * ★ 与管理端是同一份实现，**唯一差异是默认节流 300ms 而不是 200ms** ——
 *   手指连点两下的间隔可以低到 40ms（鼠标双击没这么快），
 *   而且 H5 上「点击 → 按钮变 loading」这一帧更容易被跳过。
 *   300ms 是触摸场景的经验值，不是随手写的。
 */

import { onScopeDispose, ref, type Ref } from 'vue'
import { SubmitLock, type RunOutcome, type SubmitLockOptions } from '@chargeops/shared'

export interface UseSubmitLock {
  pending: Ref<boolean>
  run: <T>(fn: () => Promise<T>) => Promise<RunOutcome<T>>
  reset: () => void
}

export function useSubmitLock(options: SubmitLockOptions = {}): UseSubmitLock {
  const lock = new SubmitLock({ throttleMs: 300, ...options })
  const pending = ref(lock.isPending)

  const off = lock.onChange(() => {
    pending.value = lock.isPending
  })
  onScopeDispose(off)

  return {
    pending,
    run: (fn) => lock.run(fn),
    reset: () => {
      pending.value = false
    },
  }
}
