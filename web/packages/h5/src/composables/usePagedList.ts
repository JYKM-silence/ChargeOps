/**
 * 分页列表（**无限滚动版**，配 `van-list`）。
 *
 * ★★ 与管理端的 `usePagedList` 是两份不同的实现，不是重复 ★★
 *   管理端是「页码 + 上一页/下一页」的表格，H5 是「下滑加载更多」。
 *   两者的状态机不一样：管理端关心「当前第几页」，H5 关心
 *   「已加载到第几页 + 还有没有更多」。硬合成一个必然两边都用不舒服。
 *
 * ★★ 这里最容易出的 bug 是「空列表仍然触底加载」★★
 *   后端 `PageHelper` 的 `reasonable: true` 会把越界的 `pageNum`
 *   **静默修正成最后一页** —— 所以「加载完最后一页后再请求下一页」
 *   拿到的是**最后一页的数据重复**，而不是空数组。
 *   如果只按「返回了数据就继续」判断，列表会**无限重复最后一页**。
 *   → 必须用 `total` 判断是否到底（见 `hasMore`），不能只看本次返回条数。
 */

import { computed, ref, shallowRef, type Ref } from 'vue'
import { DEFAULT_PAGE_SIZE_UI, type Paged } from '@chargeops/shared'

export interface UsePagedListOptions<T> {
  fetch: (query: { pageNum: number; pageSize: number }) => Promise<Paged<T>>
  pageSize?: number
  /** 额外的查询条件（status 之类）。变了要调 `reset()`。 */
  params?: () => Record<string, unknown>
}

export interface UsePagedList<T> {
  rows: Ref<T[]>
  total: Ref<number>
  pageNum: Ref<number>
  /** `van-list` 的 `loading`：正在加载**下一页**。 */
  loading: Ref<boolean>
  /** 首屏加载（列表还是空的）。用于区分骨架屏与「加载更多」。 */
  firstLoading: Ref<boolean>
  /** `van-list` 的 `finished`：没有更多了。 */
  finished: Ref<boolean>
  /** `van-list` 的 `error`：这一页加载失败了，允许重试。 */
  error: Ref<boolean>
  hasMore: Ref<boolean>
  /** `van-list` 的 `@load` 回调。 */
  loadMore: () => Promise<void>
  /** 下拉刷新：清空重来。 */
  refresh: () => Promise<void>
  /** 改动筛选条件/删除后重建列表。 */
  reset: () => Promise<void>
  /** 就地替换某一行（比如支付成功后改状态），不重拉整页。 */
  patchRow: (predicate: (row: T) => boolean, patch: Partial<T>) => void
}

export function usePagedList<T>(options: UsePagedListOptions<T>): UsePagedList<T> {
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE_UI

  const rows = shallowRef<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const pageNum = ref(1)
  const loading = ref(false)
  const firstLoading = ref(false)
  const finished = ref(false)
  const error = ref(false)

  const hasMore = computed(() => rows.value.length < total.value)

  /** 竞态保护：`reset()` 之后，在飞的旧请求不能把数据写回来。 */
  let seq = 0
  /** 防止 `van-list` 在一次请求还没回来时重复触发 `@load`。 */
  let inFlight = false

  async function fetchPage(target: number, replace: boolean): Promise<void> {
    const my = seq
    if (inFlight) return
    inFlight = true
    loading.value = true
    error.value = false

    try {
      const result = await options.fetch({
        pageNum: target,
        pageSize,
        ...(options.params?.() ?? {}),
      })

      if (my !== seq) return

      const list = result?.records ?? []
      total.value = result?.total ?? 0
      rows.value = replace ? list : [...rows.value, ...list]
      pageNum.value = target

      // ★★ 用 `total` 判断到底，**不能**用「这一页返回了几条」★★
      //   后端 `PageHelper` 的 `reasonable: true` 会把越界的 pageNum
      //   静默修正回最后一页 —— 于是「最后一页之后再请求」拿到的是
      //   最后一页的**重复数据**，而不是空数组。
      //   只看条数的话，列表会无限重复最后一页（而且看起来像加载成功）。
      finished.value = rows.value.length >= total.value || list.length === 0
    } catch {
      if (my !== seq) return
      error.value = true
      // ★ 失败时**不置 finished** —— 否则用户就再也加载不了下一页了，
      //   而 `van-list` 的 error 态本来就是为了「可以重试」而存在的。
    } finally {
      // ★★ 这三行**都必须**在 `if (my === seq)` 里面 ★★
      //
      //   被 `refresh()` / `reset()` 抢占的旧请求，它的 `finally` 迟早会跑到 ——
      //   而那时**新请求已经开始了**。原先把 `firstLoading` 和 `inFlight`
      //   写在守卫外面，于是旧请求会去改新请求的状态：
      //
      //     · 无条件清 `inFlight` → 新请求还在飞，守卫却被清掉了。
      //       此时 `van-list` 触发 `load` → `loadMore()` 以 `pageNum + 1` 追加写入，
      //       而 `refresh()` 已经把 `pageNum` 置成了 0 → **又拉一次第 1 页并追加**，
      //       表现为列表里**首页数据出现两遍**。
      //     · 无条件清 `firstLoading` → 新请求的首屏骨架屏提前消失，
      //       用户先看到空列表再看到数据。
      //
      //   ★ 这类 bug 只在「用户快速切筛选条件 / 下拉刷新的同时列表还在触底加载」
      //     时才复现，所以它不是靠点几下就能发现的 —— 必须靠守卫本身正确。
      if (my === seq) {
        loading.value = false
        firstLoading.value = false
        inFlight = false
      }
    }
  }

  async function loadMore(): Promise<void> {
    if (finished.value || error.value) return
    await fetchPage(pageNum.value + 1, false)
  }

  async function refresh(): Promise<void> {
    seq += 1
    inFlight = false
    finished.value = false
    error.value = false
    rows.value = []
    total.value = 0
    pageNum.value = 0
    firstLoading.value = rows.value.length === 0
    await fetchPage(1, true)
  }

  async function reset(): Promise<void> {
    await refresh()
  }

  function patchRow(predicate: (row: T) => boolean, patch: Partial<T>): void {
    // ★ 只用于「改一个已知行的已知字段」（如支付成功后 status 变 5）——
    //   刻意不做整页重拉：H5 上重拉会滚动位置跳动。
    //   但要清楚这**不是**数据源，刷新后仍以服务端为准。
    rows.value = rows.value.map((row) => (predicate(row) ? { ...row, ...patch } : row))
  }

  return {
    rows,
    total,
    pageNum,
    loading,
    firstLoading,
    finished,
    error,
    hasMore,
    loadMore,
    refresh,
    reset,
    patchRow,
  }
}
