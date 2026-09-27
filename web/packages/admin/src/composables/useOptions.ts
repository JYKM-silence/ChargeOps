/**
 * 下拉选项的**响应式**包装。
 *
 * ★ shared 层的 `OptionLoader` 刻意不做响应式（它要保持纯 TS、不引 vue），
 *   所以 vue 这一侧由这个 composable 接。两端各包一层，逻辑不重复。
 *
 * ★ 级联下拉的写法（**别自己写 watch 竞态**）：
 *
 *     const charger$ = useOptions(() => chargerOptionsOf(form.stationId))
 *
 *   「依赖变化 → 重载 → 丢弃过期结果」都在这里做完了。
 *   自己写 `watch(stationId, async () => { options.value = await load() })`
 *   会在快速切换站点时把旧结果盖上去 —— 下拉里出现别站的桩，
 *   而用户点下去建单才会失败。这类错只在高频操作下出现，很难复现。
 */

import { onScopeDispose, ref, watch, type Ref } from 'vue'
import type { Option } from '@chargeops/shared'

/**
 * ★ 刻意用**结构类型**而不是 `OptionLoader<T, V>`：
 *   类上带着两个泛型参数，写进签名会变成 `OptionLoader<never, unknown>`
 *   这种没法用 `extends` 约束回来的形状。
 *   我们只用到 `get` / `force` 两个方法，描述清楚它们就够了 ——
 *   这也让测试里可以塞一个假对象进来。
 */
export interface LoaderLike<V> {
  get(): Promise<Option<V>[]>
  force(): Promise<Option<V>[]>
}

export interface UseOptionsResult<V = number> {
  /** 选项列表。首次加载完成前是空数组。 */
  options: Ref<Option<V>[]>
  loading: Ref<boolean>
  error: Ref<unknown>
  /** 忽略缓存重新拉取。 */
  refresh: () => Promise<void>
}

export function useOptions<V = number>(
  loader: LoaderLike<V> | null | undefined | (() => LoaderLike<V> | null | undefined),
): UseOptionsResult<V> {
  const options = ref([]) as Ref<Option<V>[]>
  const loading = ref(false)
  const error = ref<unknown>(null)

  /** ★ 竞态令牌。级联下拉快速切换时，旧请求回来必须被丢弃。 */
  let seq = 0
  let disposed = false

  function current(): LoaderLike<V> | null | undefined {
    return typeof loader === 'function' ? loader() : loader
  }

  async function load(force: boolean): Promise<void> {
    const target = current()

    if (!target) {
      // 级联的上游还没选（例如没选站点就想选桩）→ 清空而不是报错。
      // ★ 同时要作废在途请求：否则「先选了站点（请求发出）→ 又清空站点」时，
      //   那个在途结果回来会把选项填回去，而此时用户看到的是「没选站点却有桩」。
      seq += 1
      options.value = []
      loading.value = false
      return
    }

    const mine = ++seq
    loading.value = true
    error.value = null
    try {
      const list = await (force ? target.force() : target.get())
      if (mine !== seq || disposed) return
      options.value = list
    } catch (e) {
      if (mine !== seq || disposed) return
      error.value = e
      // ★ 失败时清空：留着上一次的选项会让人以为「加载成功了只是没变」，
      //   而下拉里那些选项其实来自另一个站点/分类。
      options.value = []
    } finally {
      if (mine === seq && !disposed) loading.value = false
    }
  }

  async function refresh(): Promise<void> {
    await load(true)
  }

  if (typeof loader === 'function') {
    // ★ 依赖变化 → 重新加载。`immediate: true` 让首次也走这里，
    //   避免「初始没值所以不加载、之后再也不会加载」的死角。
    watch(() => loader(), () => void load(false), { immediate: true })
  } else {
    void load(false)
  }

  onScopeDispose(() => {
    disposed = true
  })

  return { options, loading, error, refresh }
}
