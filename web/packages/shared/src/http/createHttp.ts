/**
 * axios 工厂 —— 两端唯一的出网口。
 *
 * 管理端和车主端**只差参数**（请求头名、token 存储、登录页路径），
 * 其余行为完全一致，所以收在一个工厂里，避免两端各写一套拦截器后慢慢跑偏。
 *
 * 这个文件承担四件事，每一件都对应后端的一个具体行为：
 *
 *   1. **注入裸 token**（无 `Bearer ` 前缀）—— 见 session.ts
 *   2. **清洗 query**：丢掉 `undefined/null/''`
 *      → 后端的 `Integer` 字段绑定空串会落进 4xx 兜底分支，返回一句
 *        「未知错误」，而**参数名和文案都对不上**，是最难排查的一类；
 *        另外订单列表不带时间范围时是全表 filesort，所以空值必须真的不发出去。
 *   3. **解信封**：`code===1` 直接把 `data` 交给调用方；`code===0` 抛 ApiError
 *      并**带着后端的中文 msg**。
 *   4. **401 幂等**：并发 5 个请求同时 401 时，只跳一次登录、只弹一次提示。
 */

import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
} from 'axios'
import { ApiError, FALLBACK_MESSAGE, kindOfHttpStatus } from './errors'
import { MSG, msgEquals } from './messages'
import {
  CODE_FAIL,
  CODE_SUCCESS,
  DEFAULT_PAGE_NUM,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  isApiResult,
  type PageQuery,
  type PageResult,
  type Paged,
} from './types'
import { formatServerTime } from '../utils/datetime'

/** 自有请求选项。额外字段 axios 会原样透传给 adapter，不影响请求本身。 */
export interface RequestOptions extends AxiosRequestConfig {
  /**
   * 静默请求：失败时**不**触发全局 `onError`，错误仍会抛给调用方。
   *
   * 用于：登录态探针（失败要静默处理）、后台轮询、可选的级联下拉
   * —— 这些失败不该弹 toast 打断用户正在做的事。
   */
  silent?: boolean
  /** 关闭 GET 去重。默认开启，见 dedupe()。 */
  noDedupe?: boolean
}

/** 登录态丢失的原因。两端提示文案不同，所以交给调用方决定。 */
export type AuthLostReason = 'expired' | 'locked'

export interface HttpClientOptions {
  /** 请求头名。管理端 `token`，车主端 `authentication`。**裸值**。 */
  tokenName: string
  /** 取当前 token。返回 null 表示未登录 —— 此时**不发 token 头**，让后端返回 401。 */
  getToken: () => string | null
  /**
   * 登录态丢失回调。**已保证幂等**：同一个 token 只会触发一次，
   * 所以可以放心在里面做「跳登录页 + toast + 清缓存」这一整套。
   */
  onAuthLost: (reason: AuthLostReason) => void
  /** 业务/网络失败的统一出口（接 toast）。静默请求不会走到这里。 */
  onError?: (error: ApiError) => void
  /**
   * 是否把「该账号已被禁用」也当作登录态丢失。
   *
   * ★ 仅车主端为 true。后端资金端点在 Service 里每次重查 `member.status`，
   *   禁用后返回这句 code:0 —— 而**禁用并不会撤销 token**（7 天窗口内
   *   拦截器照样放行），所以这是被动发现「已被禁用」的主要信号。
   *   管理端没有这条路径，保持 false 以免误登出。
   */
  accountLockedLogsOut?: boolean
  /** 相对路径前缀，默认空串（走 Vite 代理，同源）。 */
  baseURL?: string
  timeoutMs?: number
}

export interface HttpClient {
  readonly axios: AxiosInstance
  request<T>(config: RequestOptions & { url: string }): Promise<T | null>
  get<T>(url: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T | null>
  post<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T | null>
  put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T | null>
  /** 后端只有 PUT 用于更新，没有 PATCH —— 这里保留是为了语义清晰，暂无人用。 */
  patch<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T | null>
  /** 后端**所有**删除都是 `DELETE /xxx/{id}`，无请求体。 */
  del<T>(url: string, options?: RequestOptions): Promise<T | null>
  /**
   * 分页查询，**带越界校正**。所有列表页都应该用它，不要裸调 get。
   * 「删掉末页最后一条后列表显示空、其实上一页有数据」这个 bug 由它兜住。
   */
  /**
   * ★ 约束用 `object` 而不是 `Record<string, unknown>`：
   *   各接口的分页查询类型都是 `interface`（如 `OrderPageQuery`），
   *   **`interface` 没有隐式索引签名**，写成 `Record` 会导致每个调用点
   *   都得 `as never` / `as Record<...>` 才能过 —— 而 `as` 一旦用上，
   *   **字段名拼错也会一起被绕过**（后端关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`，
   *   表现是「筛选条件改了没反应」而不是报错，排查方向每次跑偏）。
   *   实现里只读 `pageNum` / `pageSize` 再原样展开，用 `object` 完全安全。
   */
  page<T>(url: string, query?: PageQuery & object, options?: RequestOptions): Promise<Paged<T>>
}

/** 请求超时。后端订单列表无过滤条件时是全表 filesort，给宽一点。 */
const DEFAULT_TIMEOUT_MS = 20_000

/**
 * 清洗 query：丢掉「等于没传」的值。
 *
 * ★ `false` 和 `0` **必须保留** —— 它们是有效业务值
 *   （`status=0` 是「停用」、`isDefault=0` 是「非默认车」）。
 *   只丢 `undefined` / `null` / 空白字符串。
 * ★ 空数组也丢掉：后端接 `List` 参数时拿到空数组和拿到 null 行为不一致，
 *   而「没选任何筛选项」的语义就是「不筛」。
 */
function cleanParams(params: Record<string, unknown> | undefined): Record<string, unknown> | undefined {
  if (!params) return undefined
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue
    if (typeof value === 'string' && value.trim() === '') continue
    if (Array.isArray(value) && value.length === 0) continue
    if (value instanceof Date) {
      const formatted = formatServerTime(value)
      if (formatted) out[key] = formatted
      continue
    }
    out[key] = value
  }
  return out
}

/** 拼去重键。params 顺序不同视为同一请求，所以按键排序后再序列化。 */
function dedupeKey(method: string, url: string, params: unknown): string {
  if (!params) return `${method} ${url}`
  const sorted = Object.keys(params as Record<string, unknown>)
    .sort()
    .map((k) => `${k}=${JSON.stringify((params as Record<string, unknown>)[k])}`)
    .join('&')
  return `${method} ${url}?${sorted}`
}

export function createHttp(options: HttpClientOptions): HttpClient {
  const {
    tokenName,
    getToken,
    onAuthLost,
    onError,
    accountLockedLogsOut = false,
    baseURL = '',
    timeoutMs = DEFAULT_TIMEOUT_MS,
  } = options

  const instance = axios.create({
    baseURL,
    timeout: timeoutMs,
    // ★ 不要设 `withCredentials`：后端没有 CORS 配置，走同源代理，
    //   cookie 也用不上（鉴权全靠自定义头）。设了反而在某些浏览器上
    //   触发额外的预检要求。
  })

  /**
   * 登录态丢失的**幂等闸门**。
   *
   * ★ 记的是「触发丢失的那个 token」而不是一个时间戳或布尔量：
   *   并发 5 个请求同时 401 → 5 个回调带着**同一个旧 token** → 只放行第一个。
   *   用户重新登录后 token 变了 → 闸门自动失效，不需要任何人手动重置。
   *   如果用布尔量，就得在登录成功时记得调一次 reset，**那种约定迟早会被漏掉**。
   */
  let lastAuthLostToken: string | null = null

  function fireAuthLost(reason: AuthLostReason, token: string | null): void {
    const key = token ?? ''
    if (lastAuthLostToken === key) return
    lastAuthLostToken = key
    onAuthLost(reason)
  }

  // ── 请求拦截：注入 token ────────────────────────────────────────────────
  instance.interceptors.request.use((config) => {
    const token = getToken()
    if (token) {
      // ★ 裸 token，**不加 `Bearer `**。后端原样 parse，加了前缀必然 401。
      config.headers.set(tokenName, token)
    }
    // 清洗 query（axios 也会序列化 Date，但格式会是 ISO，后端不认）
    if (config.params) {
      config.params = cleanParams(config.params as Record<string, unknown>)
    }
    return config
  })

  // ── 响应拦截：解信封 + 错误归一 ─────────────────────────────────────────
  instance.interceptors.response.use(
    (response: AxiosResponse) => {
      // 非信封响应（图片、纯文本、第三方）原样放行，不做 code 判定
      if (!isApiResult(response.data)) return response

      const body = response.data
      const token = getToken()

      if (body.code === CODE_SUCCESS) {
        // 解信封：调用方直接拿到业务数据，而不是 {code,msg,data}
        response.data = body.data
        return response
      }

      // code === 0（业务失败）—— 后端 msg 就是给用户看的文案
      const msg = body.msg ?? FALLBACK_MESSAGE.unknown

      // ★ 这是 401 之外的第二条登出路径：Service 层发现 BaseContext 为空时
      //   抛的是业务异常（HTTP 200 + code:0），形状与拦截器那条 401 完全不同。
      if (msgEquals(msg, MSG.NOT_LOGGED_IN)) {
        fireAuthLost('expired', token)
        throw new ApiError('auth', msg, { code: body.code, httpStatus: response.status })
      }

      // ★ 车主被禁用。禁用不撤销 token，所以这条是「被动发现」的主要来源。
      if (accountLockedLogsOut && msgEquals(msg, MSG.ACCOUNT_LOCKED)) {
        fireAuthLost('locked', token)
        throw new ApiError('biz', msg, { code: body.code, httpStatus: response.status })
      }

      throw new ApiError('biz', msg, { code: body.code ?? CODE_FAIL, httpStatus: response.status })
    },
    (error: unknown) => {
      const err = error as AxiosError

      // 主动取消（切路由 abort）——原样抛，**不要**弹 toast、不要当失败处理
      if (axios.isCancel(err)) return Promise.reject(err)

      const status = err.response?.status
      const token = getToken()

      if (status === 401) {
        // ★ 后端的 401 是**空响应体**（拦截器只 setStatus 不写 body），
        //   所以这里读不到任何 msg，只能按状态码走固定文案。
        fireAuthLost('expired', token)
        return Promise.reject(new ApiError('auth', FALLBACK_MESSAGE.auth, { httpStatus: 401 }))
      }

      // 超时：axios 用 ECONNABORTED / ETIMEDOUT 两种码，都算超时
      if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
        return Promise.reject(new ApiError('timeout', FALLBACK_MESSAGE.timeout))
      }

      // 有响应但状态码异常（且响应体不是信封 —— 否则会在上面那个分支处理掉）
      if (status !== undefined) {
        const kind = kindOfHttpStatus(status)
        const msg = kind === 'server' ? FALLBACK_MESSAGE.server : FALLBACK_MESSAGE.unknown
        return Promise.reject(new ApiError(kind, msg, { httpStatus: status }))
      }

      // 完全没有响应：断网、DNS、代理挂掉
      return Promise.reject(new ApiError('network', FALLBACK_MESSAGE.network))
    },
  )

  /** 把 ApiError 送进全局出口（静默请求除外）。取消不提示。 */
  function report(error: ApiError, silent: boolean | undefined): void {
    if (!silent && onError) onError(error)
  }

  /**
   * GET 进行中请求合并。
   *
   * ★ 目的不是省流量，是**修掉一类竞态**：页面初始化时父组件和子组件
   *   各拉一次同样的下拉数据，两个请求时序交错，后回来的那个覆盖先回来的，
   *   于是「筛选条件已经变了，列表还是旧数据」。
   *
   * ★ 只有**没有传 `signal`** 的请求才合并：带 signal 的调用方要能独立取消，
   *   如果共享了 promise，一个取消会连带另一个也失败。
   */
  const inflight = new Map<string, Promise<unknown>>()

  /**
   * 真正的发送。`silent` 单独传而不是从 config 里读 —— 因为错误归一分支里
   * config 已经不可用了，而 `silent` 必须活到 catch 里。
   */
  async function send<T>(config: AxiosRequestConfig, silent?: boolean): Promise<T | null> {
    try {
      const response = await instance.request<T>(config)
      return (response.data ?? null) as T | null
    } catch (raw) {
      if (axios.isCancel(raw)) throw raw
      const error = raw instanceof ApiError ? raw : new ApiError('unknown', FALLBACK_MESSAGE.unknown)
      report(error, silent)
      throw error
    }
  }

  function get<T>(url: string, params?: Record<string, unknown>, opts: RequestOptions = {}) {
    const cleaned = cleanParams(params)
    // 不写显式类型注解：`opts` 里的 `url?: string` 会把推断结果放宽成可选，
    // 于是 `send` 拿到的 url 变成 `string | undefined`（TS 7 会直接报错）
    const config = { ...opts, url, method: 'GET', params: cleaned }

    // 带 signal 或显式关闭去重 → 直接发
    if (opts.noDedupe || opts.signal) return send<T>(config, opts.silent)

    const key = dedupeKey('GET', url, cleaned)
    const existing = inflight.get(key)
    if (existing) return existing as Promise<T | null>

    const promise = send<T>(config, opts.silent).finally(() => {
      inflight.delete(key)
    })
    inflight.set(key, promise)
    return promise
  }

  /**
   * 分页查询 —— **带越界校正**。
   *
   * ★★ 为什么需要校正：后端 PageHelper 开了 `reasonable: true`。★★
   *   它把越界页码**改到**首/末页，并且**不告诉你改过**
   *   （响应里只有 `{total, records}`，没有 pageNum）。
   *   于是「在第 3 页删掉最后一条」之后：前端还拿着 pageNum=3 去请求，
   *   后端默默地返回第 2 页的数据 —— 前端把它当第 3 页渲染，
   *   **页码显示 3、内容却是第 2 页**，而且下一页按钮的状态也是错的。
   *
   * ★ 校正方式：拿到 `total` 自己算 `lastPage`，把实际生效的页码**回写**给调用方
   *   （返回的 `pageNum`），调用方拿它更新分页器即可。
   *   这里**不需要重发请求** —— 后端返回的已经是最后一页的数据了，
   *   只是它没告诉我们页码，所以由我们标注。这一点很关键：
   *   如果按「发现越界 → 用新页码重发一次」去做，每次删末页数据都会多一个请求。
   *
   * ★ `hasNext` 用 `total/pageSize` 算，**不要用 `records.length < pageSize`** ——
   *   后者在「总数正好是 pageSize 整数倍」时会把最后一页误判成还有下一页。
   */
  async function page<T>(
    url: string,
    query: PageQuery & object = {},
    opts: RequestOptions = {},
  ): Promise<Paged<T>> {
    const pageSize = Math.min(Math.max(query.pageSize ?? DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE)
    const requested = Math.max(query.pageNum ?? DEFAULT_PAGE_NUM, 1)

    const raw = await get<PageResult<T>>(url, { ...query, pageNum: requested, pageSize }, opts)

    const total = typeof raw?.total === 'number' ? raw.total : 0
    const records = Array.isArray(raw?.records) ? raw.records : []
    const lastPage = total > 0 ? Math.ceil(total / pageSize) : 1
    const effective = Math.min(requested, lastPage)

    return {
      total,
      records,
      pageNum: effective,
      pageSize,
      hasNext: effective < lastPage,
    }
  }

  return {
    axios: instance,
    request: <T>(config: RequestOptions & { url: string }) => send<T>(config, config.silent),
    get,
    page,
    post: <T>(url: string, body?: unknown, opts: RequestOptions = {}) =>
      send<T>({ ...opts, url, method: 'POST', data: body }, opts.silent),
    put: <T>(url: string, body?: unknown, opts: RequestOptions = {}) =>
      send<T>({ ...opts, url, method: 'PUT', data: body }, opts.silent),
    patch: <T>(url: string, body?: unknown, opts: RequestOptions = {}) =>
      send<T>({ ...opts, url, method: 'PATCH', data: body }, opts.silent),
    del: <T>(url: string, opts: RequestOptions = {}) =>
      send<T>({ ...opts, url, method: 'DELETE' }, opts.silent),
  }
}
