/**
 * 一次性的异步数据加载（详情页、资料页这类）。
 *
 * ★ 列表页**不用**这个 —— 列表用 `usePagedList`（带无限滚动）。
 *
 * ★★ 为什么带 `seq` 竞态保护 ★★
 *   H5 上「点 A → 返回 → 点 B」的间隔可以很短（手指连点）。
 *   没有 seq 的话，A 的响应可能**晚于** B 返回，于是页面上显示的是 A 的数据，
 *   而用户以为自己打开的是 B —— 这种错配在订单详情里会让人看到**别人的充电记录**，
 *   比单纯的"加载慢"严重得多。
 */

import { onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'

export interface UseAsyncDataOptions {
  /** 立即执行一次。默认 true。 */
  immediate?: boolean
}

export interface UseAsyncData<T> {
  data: Ref<T | null>
  loading: Ref<boolean>
  /** 是否失败过（用于区分「真的没数据」和「加载挂了」）。 */
  failed: Ref<boolean>
  /** 重新拉取。 */
  reload: () => Promise<void>
}

/**
 * @param fetcher 拉到数据。**必须**是纯读取，不要在里面改状态。
 */
export function useAsyncData<T>(
  fetcher: () => Promise<T | null>,
  options: UseAsyncDataOptions = {},
): UseAsyncData<T> {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const failed = ref(false)

  let seq = 0
  let disposed = false

  async function reload(): Promise<void> {
    const my = ++seq
    loading.value = true
    failed.value = false
    try {
      const result = await fetcher()
      // ★ 过期的响应直接丢掉，不写进 data
      if (disposed || my !== seq) return
      data.value = result
    } catch {
      if (disposed || my !== seq) return
      // ★ 失败时**保留上一次的数据**、只置 failed ——
      //   刷新失败把整页清空（变成空白错误页）是更差的体验：
      //   用户宁可看到稍旧的数字，也不要看着一片空白不知道该干什么。
      failed.value = true
    } finally {
      if (!disposed && my === seq) loading.value = false
    }
  }

  if (options.immediate !== false) {
    void reload()
  }

  onScopeDispose(() => {
    disposed = true
  })

  return { data, loading, failed, reload }
}

/**
 * 按某个响应式来源变化自动重拉。
 *
 * ★ 用 `immediate: false` 的 `useAsyncData` + 这里的 watch，
 *   而不是在组件里手写 watch + 手动比对 seq。
 */
export function useAsyncDataOn<T, S>(
  source: Ref<S>,
  fetcher: (value: S) => Promise<T | null>,
): UseAsyncData<T> {
  const result = useAsyncData<T>(() => fetcher(source.value), { immediate: false })

  watch(
    source,
    () => {
      void result.reload()
    },
    { immediate: true },
  )

  return result
}
