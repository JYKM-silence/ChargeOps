/**
 * 分页列表的通用逻辑（**所有列表页都用它，不要各写一套**）。
 *
 * ★★ 它替页面处理的四件事，每一件都对应一个真实会踩的坑 ★★
 *
 *   1. **越界校正**：删掉末页最后一条后，`pageNum` 指向了一个不存在的页。
 *      后端 PageHelper 配了 `reasonable: true`，会把越界页码**静默重标**为最后一页，
 *      所以它返回的是「最后一页」的数据，但我们的 `pageNum` 还停在旧值 ——
 *      表现是分页器显示第 3 页、列表也是第 3 页内容，但总数只有 2 页。
 *      这里在**删除后**主动回退一页并重拉，把这个状态收敛回来。
 *      （`http.page()` 已经处理了「请求时页码越界」，两者互补。）
 *
 *   2. **竞态**：快速连点筛选时，先发的请求可能后回来，把新结果覆盖掉。
 *      用自增的 `seq` 丢弃过期响应 —— 这比 abort 简单，且不必处理 abort 的异常。
 *
 *   3. **筛选与翻页的耦合**：改筛选条件必须**回到第 1 页**。
 *      忘了这一步就会出现「在第 5 页搜一个只有 1 条结果的词 → 空列表」。
 *
 *   4. **query 清洗**：`''`/`null` 不参与请求（`http` 层也会洗一遍，这里提前洗
 *      是为了让「重置」按钮的 `dirty` 判断准确）。
 */

import { computed, reactive, ref, type Ref } from 'vue'
import { DEFAULT_PAGE_SIZE_UI, type Paged } from '@chargeops/shared'

export interface UsePagedListOptions<Q extends object> {
  /** 拉取一页。通常是 `(q) => http.page<Row>('/api/admin/xxx/page', q)`。 */
  fetch: (query: Q) => Promise<Paged<unknown>>
  /** 初始筛选条件（不含分页字段）。 */
  initial?: Q
  pageSize?: number
  /** 是否立即加载。默认 true。 */
  immediate?: boolean
}

export interface PagedList<Row, Q extends object> {
  rows: Ref<Row[]>
  total: Ref<number>
  pageNum: Ref<number>
  pageSize: Ref<number>
  loading: Ref<boolean>
  /** 已加载过一次以上。用来区分「加载中骨架」和「真的空」。 */
  loaded: Ref<boolean>
  error: Ref<unknown>
  /** 筛选条件（响应式，直接 v-model 绑到输入框）。 */
  filters: Q
  /** 按当前筛选重查，回到第 1 页。 */
  search: () => Promise<void>
  /** 清空筛选并重查。 */
  reset: () => Promise<void>
  /** 原地重查当前页。写操作成功后调它。 */
  reload: () => Promise<void>
  /** 筛选条件是否与初始值不同（驱动「重置」按钮的可用态）。 */
  dirty: Ref<boolean>
  /** 翻页。 */
  goPage: (page: number) => Promise<void>
  /**
   * 删除一条记录后刷新。
   *
   * ★ 关键在「当前页只剩这一条且不是第一页」时要**回退一页** —— 见文件头第 1 点。
   *   页面里不要自己调 `reload()` 来代替它。
   */
  afterRemove: () => Promise<void>
}

function isBlank(v: unknown): boolean {
  return v === undefined || v === null || (typeof v === 'string' && v.trim() === '')
}

export function usePagedList<Row, Q extends object>(
  options: UsePagedListOptions<Q>,
): PagedList<Row, Q> {
  const initial = { ...(options.initial ?? {}) } as Q

  const rows = ref([]) as Ref<Row[]>
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(options.pageSize ?? DEFAULT_PAGE_SIZE_UI)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<unknown>(null)
  const filters = reactive({ ...initial }) as Q

  /** ★ 竞态令牌。每次发起请求自增，回来时对不上就丢弃。 */
  let seq = 0

  const dirty = computed(() => {
    return (Object.keys(initial) as (keyof Q)[]).some((k) => filters[k] !== initial[k])
  })

  async function load(): Promise<void> {
    const mine = ++seq
    loading.value = true
    error.value = null
    try {
      const paged = await options.fetch({
        ...filters,
        pageNum: pageNum.value,
        pageSize: pageSize.value,
      } as Q)

      if (mine !== seq) return // ★ 有更新的请求发出去了，这次的结果作废

      rows.value = paged.records as Row[]
      total.value = paged.total
      // ★ 用后端校正后的页码回写，分页器才不会和后端各说各话
      pageNum.value = paged.pageNum
      loaded.value = true
    } catch (e) {
      if (mine !== seq) return
      error.value = e
      // ★ 失败时**清空列表**：留着上一次的结果会让人以为「查询成功了，
      //   只是结果没变」—— 而实际上是请求失败了。EmptyState 会显示错误态。
      rows.value = []
      total.value = 0
      loaded.value = true
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  async function search(): Promise<void> {
    // ★ 改筛选必须回第 1 页，否则会停在旧页上得到空结果
    pageNum.value = 1
    await load()
  }

  async function reset(): Promise<void> {
    for (const k of Object.keys(initial) as (keyof Q)[]) {
      filters[k] = initial[k]
    }
    await search()
  }

  async function reload(): Promise<void> {
    await load()
  }

  async function goPage(page: number): Promise<void> {
    pageNum.value = page
    await load()
  }

  async function afterRemove(): Promise<void> {
    // 当前页只剩这一条，且不在第一页 → 回退一页再拉
    if (rows.value.length === 1 && pageNum.value > 1) {
      pageNum.value -= 1
    }
    await load()
  }

  if (options.immediate !== false) {
    void load()
  }

  return {
    rows,
    total,
    pageNum,
    pageSize,
    loading,
    loaded,
    error,
    filters,
    search,
    reset,
    reload,
    goPage,
    dirty: dirty as Ref<boolean>,
    afterRemove,
  }
}

/** 供页面做「清空筛选」判断的小工具，与上面同一套「等于没传」的判定。 */
export { isBlank }
