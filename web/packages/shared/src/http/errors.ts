/**
 * 错误归一化。
 *
 * ★ 为什么要一个 `kind` 而不是只看 `msg`：
 *   后端的业务失败文案**就是给用户看的**（README 明确要求前端把 msg 显示出来，
 *   而不是当异常吞掉），但「401 空体」「超时」「断网」「5xx」这几种在前端
 *   需要**不同的动作**（跳登录 / 提示重试 / 保留页面状态），而不是不同的文案。
 *   所以失败分两类：`biz` 用后端文案，其余用前端固定文案 + 各自的处理分支。
 */

export type ApiErrorKind = 'biz' | 'auth' | 'network' | 'timeout' | 'server' | 'unknown'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  /** 后端的 code（业务失败时为 0）。非信封失败没有这个值。 */
  readonly code?: number
  readonly httpStatus?: number
  /** 排查用：与服务端日志对齐的请求标识。 */
  readonly requestId?: string

  constructor(
    kind: ApiErrorKind,
    message: string,
    extra: { code?: number; httpStatus?: number; requestId?: string } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.code = extra.code
    this.httpStatus = extra.httpStatus
    this.requestId = extra.requestId
    // 让 instanceof 在 ES2022 target 下正常工作
    Object.setPrototypeOf(this, ApiError.prototype)
  }

  get isAuth(): boolean {
    return this.kind === 'auth'
  }
}

/**
 * 前端固定文案。
 *
 * ★ 这几条**刻意不走后端**：401 的响应体是空的，超时/断网根本没有响应体。
 *   其余一切能拿到 `msg` 的地方，一律用后端的原文。
 */
export const FALLBACK_MESSAGE = {
  auth: '登录状态已失效，请重新登录',
  timeout: '请求超时，请检查网络后重试',
  network: '网络连接失败，请检查网络',
  server: '服务异常，请稍后重试',
  unknown: '操作失败',
} as const

/** HTTP 状态码 → 错误种类。 */
export function kindOfHttpStatus(status: number): ApiErrorKind {
  if (status === 401) return 'auth'
  if (status >= 500) return 'server'
  return 'unknown'
}
