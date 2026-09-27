/**
 * 把 shared 的 `SubmitLock` 包成响应式。
 *
 * ★ shared 层刻意不依赖 vue（见 shared/src/index.ts 的说明），
 *   所以锁本身是纯 TS 类；响应式的那一层在 app 里，就是这么几行。
 *   两端各一份，差异只有节流时长（H5 触摸场景更长）。
 */

import { onScopeDispose, ref, type Ref } from 'vue'
import { SubmitLock, type RunOutcome, type SubmitLockOptions } from '@chargeops/shared'

export interface UseSubmitLock {
  /** 绑定到按钮的 `:loading`。 */
  pending: Ref<boolean>
  /** 执行提交。被锁时返回 `{status:'skipped'}`，**不抛异常**。 */
  run: <T>(fn: () => Promise<T>) => Promise<RunOutcome<T>>
  /** 手动释放（例如弹窗关闭时）。正常流程不需要调。 */
  reset: () => void
}

/**
 * @param options.throttleMs 连点保护窗口。管理端鼠标 200ms 足够；
 *                           H5 手指连点会更快，用 300ms（见各自的调用处）。
 */
export function useSubmitLock(options: SubmitLockOptions = {}): UseSubmitLock {
  const lock = new SubmitLock(options)
  const pending = ref(lock.isPending)

  // 锁内部状态变化 → 同步到 ref。返回的取消订阅函数交给 onScopeDispose，
  // 避免组件卸载后 listener 还挂在那个 lock 上（lock 是组件级对象，
  // 正常情况下会一起被回收，但显式解绑更稳妥）。
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
