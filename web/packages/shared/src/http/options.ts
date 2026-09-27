/**
 * 下拉选项加载器。
 *
 * ★★ 为什么需要专门一层：后端**没有**站点 / 充电桩 / 充电枪的下拉列表接口 ★★
 *
 *   管理端只有 `GET /api/admin/station/page`、`charger/page`、`connector/page`
 *   这类**分页**接口，没有 `/list`（只有 `category/list`、`tariff-template/list-enabled`
 *   这两个例外）。所以任何「选择所属站点」的下拉框都只能：
 *
 *     1. 用 `pageSize=100` 拿一页，**循环翻页**直到取完；
 *     2. 自己缓存结果 —— 否则打开一个弹窗就把整张表拉一遍，
 *        而「站点」这种下拉在一个表单页里可能出现好几次。
 *
 *   → 于是有了这个文件。业务代码只描述「哪个接口、怎么映射成选项」，
 *     翻页、缓存、失效都在这里。
 *
 * ★ 缓存带来的必然问题：**新建了一个站点，下拉里没有它**。
 *   所以每次写成功之后必须调 `invalidateOptionsByTag('station')`
 *   （见 domain/ 里的写后刷新约定）。标签机制就是为了让调用方
 *   不需要知道「哪些 loader 依赖站点」。
 */

import type { HttpClient, RequestOptions } from './createHttp'
import type { Paged } from './types'

/**
 * 按字段名取值。
 *
 * ★★ 为什么不直接写 `item[valueKey]` 并给 `T` 加
 *   `extends Record<string, unknown>` 约束 ★★
 *   因为 `interface` 声明的类型**没有隐式索引签名**（`type` 别名才有）。
 *   我们的接口类型（`StationVO`、`ConnectorVO`…）全是 `interface`，
 *   加了那个约束会导致**所有接口类型都传不进来**，报
 *   「Index signature for type 'string' is missing」。
 *   而把接口改成 `type` 别名只是为了迁就一个泛型约束，那是本末倒置。
 *   → 约束放宽到 `object`，取值时在这里收窄一次。
 */
function pick(item: object, key: string): unknown {
  return (item as Record<string, unknown>)[key]
}

/** 一个下拉项。 */
export interface Option<V = number> {
  value: V
  label: string
  /** 置灰不可选（例如「已停用」的套餐），但仍显示 —— 比直接隐藏更有解释力。 */
  disabled?: boolean
  /** 选项右侧的次要说明（站点地址、充电桩型号）。 */
  hint?: string
}

export interface OptionSource<T, V = number> {
  /** 唯一键。同 key 的 loader 共享缓存。 */
  key: string
  /** 依赖标签。写操作后按标签批量失效。 */
  tags?: readonly string[]
  /** 拉一页。通常是 `(pageNum) => http.page(url, {pageNum, pageSize: 100, ...})`。 */
  fetchPage: (pageNum: number) => Promise<Paged<T>>
  /** 单条映射。返回 null 表示**这一条不进下拉**（例如已删除的数据）。 */
  map: (item: T) => Option<V> | null
  /** 缓存有效期。默认 5 分钟。 */
  ttlMs?: number
}

/**
 * 单次加载最多翻多少页。
 *
 * ★ 100 × 20 = 2000 个选项。超过这个量级说明这个字段**不该用下拉**
 *   （应当换成远程搜索的 select）。硬上限的作用是防止把一个写错的
 *   `total`（或者后端 `reasonable:true` 的异常行为）变成无限循环。
 */
const MAX_OPTION_PAGES = 20

const DEFAULT_TTL_MS = 5 * 60 * 1000

interface CacheEntry {
  promise: Promise<unknown>
  at: number
}

/** 全局缓存。key → 条目。 */
const cache = new Map<string, CacheEntry>()
/** key → tags，用于按标签失效（一个 loader 可能被打上多个标签）。 */
const keyTags = new Map<string, readonly string[]>()

/**
 * 下拉选项加载器。**非响应式** —— app 层用 `ref` 接。
 */
export class OptionLoader<T, V = number> {
  private readonly source: OptionSource<T, V>

  constructor(source: OptionSource<T, V>) {
    this.source = source
    keyTags.set(source.key, source.tags ?? [])
  }

  get key(): string {
    return this.source.key
  }

  /** 取选项（命中缓存则直接用）。 */
  get(): Promise<Option<V>[]> {
    const ttl = this.source.ttlMs ?? DEFAULT_TTL_MS
    const hit = cache.get(this.source.key)
    if (hit && Date.now() - hit.at < ttl) {
      return hit.promise as Promise<Option<V>[]>
    }
    return this.force()
  }

  /** 忽略缓存重新加载。`get()` 发现缓存过期时也走这里。 */
  force(): Promise<Option<V>[]> {
    const promise = this.loadAll()

    cache.set(this.source.key, { promise, at: Date.now() })

    // ★ 失败时**必须把缓存删掉**：否则一次网络抖动会让这个下拉
    //   在 TTL 内（5 分钟）永远返回那个 rejected promise，页面怎么点都不出来，
    //   而用户看到的只是「下拉是空的」—— 一个会因为缓存而**自愈失败**的 bug。
    promise.catch(() => {
      if (cache.get(this.source.key)?.promise === promise) {
        cache.delete(this.source.key)
      }
    })

    return promise
  }

  /** 只清这个 loader 的缓存。 */
  invalidate(): void {
    cache.delete(this.source.key)
  }

  private async loadAll(): Promise<Option<V>[]> {
    const { fetchPage, map } = this.source
    const out: Option<V>[] = []
    let pageNum = 1

    for (;;) {
      const paged = await fetchPage(pageNum)

      for (const item of paged.records) {
        const option = map(item)
        if (option) out.push(option)
      }

      // ★ 用 total/pageSize 算总页数，**不要用 `records.length < pageSize`**：
      //   后者在「总数正好是 pageSize 整数倍」时会把最后一页当成还有下一页，
      //   于是多请求一次拿到空数组 —— 不致命，但会让「要不要再翻一页」这件事
      //   变得依赖运气。而 total 是后端一定返回的。
      const lastPage = paged.total > 0 ? Math.ceil(paged.total / paged.pageSize) : 1
      if (pageNum >= lastPage) break

      pageNum += 1
      if (pageNum > MAX_OPTION_PAGES) {
        // ★ 不静默截断：数据超量是要被发现的（说明该改成远程搜索了），
        //   所以留一条明确的控制台痕迹。
        console.warn(
          `[options] ${this.source.key} 超过 ${MAX_OPTION_PAGES} 页（${MAX_OPTION_PAGES * 100} 条），已截断。` +
            '这个字段应该改用远程搜索。',
        )
        break
      }
    }

    return out
  }
}

/** 按标签失效。写操作成功后调用。 */
export function invalidateOptionsByTag(...tags: string[]): void {
  const wanted = new Set(tags)
  for (const [key, tags0] of keyTags) {
    if (tags0.some((t) => wanted.has(t))) cache.delete(key)
  }
}

/** 全部失效。不确定依赖关系时的兜底（例如「导入」这类大动作之后）。 */
export function invalidateAllOptions(): void {
  cache.clear()
}

/** 仅测试用。 */
export function resetOptionCache(): void {
  cache.clear()
  keyTags.clear()
}

/** 便捷构造：最常见的「分页接口 + id/名称」形态。 */
export function createOptionLoader<T extends object, V = number>(
  http: HttpClient,
  config: {
    key: string
    tags?: readonly string[]
    url: string
    /** 附加查询条件（如 `{stationId: 3}`，级联下拉用）。**会进缓存键**。 */
    params?: Record<string, unknown>
    valueKey?: string
    labelKey: string
    hintKey?: string
    /** 返回 false 的条目不进下拉。 */
    filter?: (item: T) => boolean
    ttlMs?: number
  },
): OptionLoader<T, V> {
  const {
    key,
    tags,
    url,
    params,
    valueKey = 'id',
    labelKey,
    hintKey,
    filter,
    ttlMs,
  } = config

  // ★ 级联下拉（如「选完站点再选桩」）必须把 params 编进缓存键，
  //   否则选了 A 站点的桩、再切到 B 站点，拿到的还是 A 的缓存 ——
  //   一个非常隐蔽、且只在切换站点时出现的错。
  const paramKey = params
    ? Object.entries(params)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(',')
    : ''
  const cacheKey = paramKey ? `${key}?${paramKey}` : key

  return new OptionLoader<T, V>({
    key: cacheKey,
    tags,
    ttlMs,
    fetchPage: (pageNum) => http.page<T>(url, { ...params, pageNum, pageSize: 100 }),
    map: (item) => {
      if (filter && !filter(item)) return null
      const value = pick(item, valueKey) as V | undefined
      const label = pick(item, labelKey)
      if (value === undefined || value === null) return null
      if (label === undefined || label === null) return null
      const option: Option<V> = { value, label: String(label) }
      if (hintKey) {
        const hint = pick(item, hintKey)
        if (hint !== undefined && hint !== null && String(hint) !== '') {
          option.hint = String(hint)
        }
      }
      return option
    },
  })
}

/**
 * 数组型接口的选项加载器 —— 对应后端**少数不走分页**的两个接口：
 *
 *   · `GET /api/admin/category/list`          → `List<CategoryVO>`
 *   · `GET /api/admin/tariff-template/list-enabled` → `List<TariffTemplateVO>`
 *
 * ★★ 这两个接口**必须**用这个函数，不能用 `createOptionLoader` ★★
 *   它们返回的是裸数组（`Result<List<T>>`），**没有分页信封**。
 *   拿 `createOptionLoader` 去接，`http.page()` 会从数组上读
 *   `records` 和 `total` —— 读到两个 `undefined`，表现为
 *   「下拉永远是空的，而且不报错」。这种错最难查，因为请求本身是 200。
 *
 * ★ 也与「不能给这两个接口加分页参数」这条后端注释一致：
 *   给 `list-enabled` 传 `pageSize` 只会被忽略，但会让人误以为它是分页接口。
 */
export function createListOptionLoader<T extends object, V = number>(
  http: HttpClient,
  config: {
    key: string
    tags?: readonly string[]
    url: string
    params?: Record<string, unknown>
    valueKey?: string
    labelKey: string
    hintKey?: string
    filter?: (item: T) => boolean
    ttlMs?: number
  },
): OptionLoader<T, V> {
  const { key, tags, url, params, valueKey = 'id', labelKey, hintKey, filter, ttlMs } = config

  const paramKey = params
    ? Object.entries(params)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(',')
    : ''
  const cacheKey = paramKey ? `${key}?${paramKey}` : key

  return new OptionLoader<T, V>({
    key: cacheKey,
    tags,
    ttlMs,
    // ★ 包成「只有一页的分页」交给 OptionLoader —— 这样缓存、失效、
    //   失败清缓存这些逻辑全都不用重写。total 就是数组长度，lastPage 恒为 1。
    fetchPage: async (pageNum) => {
      if (pageNum > 1) {
        return { total: 0, records: [], pageNum, pageSize: 0, hasNext: false }
      }
      // `http.get` 在 `data` 为 null 时返回 null（后端返回空列表时不会为 null，
      // 但 Result.success(null) 会）—— 兜底成空数组，让下拉是空的而不是崩的。
      const list = (await http.get<T[]>(url, params)) ?? []
      return {
        total: list.length,
        records: list,
        pageNum: 1,
        pageSize: list.length,
        hasNext: false,
      }
    },
    map: (item) => {
      if (filter && !filter(item)) return null
      const value = pick(item, valueKey) as V | undefined
      const label = pick(item, labelKey)
      if (value === undefined || value === null) return null
      if (label === undefined || label === null) return null
      const option: Option<V> = { value, label: String(label) }
      if (hintKey) {
        const hint = pick(item, hintKey)
        if (hint !== undefined && hint !== null && String(hint) !== '') {
          option.hint = String(hint)
        }
      }
      return option
    },
  })
}

/**
 * 把 `Option[]` 反查成 label。列表页把 `stationId` 渲染成站点名时用。
 * 找不到返回原值（**不要返回空串或 `-`**：看不到值会让人以为数据丢了）。
 */
export function labelOf<V>(options: readonly Option<V>[], value: V | null | undefined): string {
  if (value === null || value === undefined) return '-'
  const hit = options.find((o) => o.value === value)
  return hit ? hit.label : String(value)
}

/** 供 `<select>` / `<el-select>` 直接吃的 `{label, value}` 形态。 */
export function toSelectItems<V>(
  options: readonly Option<V>[],
): { label: string; value: V; disabled?: boolean }[] {
  return options.map((o) => ({ label: o.label, value: o.value, disabled: o.disabled }))
}

/** 被 `RequestOptions` 用到的类型再导出，方便 app 层写 loader 时不用二次 import。 */
export type { HttpClient, RequestOptions }
