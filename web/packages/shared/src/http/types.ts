/**
 * 后端统一响应契约。
 *
 * ★ 这三条是照后端 `result/Result.java` 与 `result/PageResult.java` 抄的，
 *   不是「常见约定」——后端是**自有形状**，改动这里之前先看那两个类：
 *
 *   1. 成功是 `code === 1`，失败是 `code === 0`。**不是 200 / 0 / true**。
 *   2. 业务失败走 **HTTP 200 + code:0 + 中文 msg**，不是 4xx/5xx。
 *      只有「未登录」是 HTTP 401，而且**响应体是空的**（拦截器直接 setStatus，
 *      不写 body）——所以 401 时读不到 msg，只能按状态码处理。
 *   3. 分页响应只有 `total` 和 `records` 两个字段。后端刻意不返回 PageHelper 的
 *      `Page` 对象（那是 ArrayList 子类，会漏出 pageNum/pages 等内部字段），
 *      所以**翻页状态由前端自己维护**，别指望响应里有 pageNum。
 */

/** 后端 `Result<T>`：成功 code=1，msg 为 null；失败 code=0，data 为 null。 */
export interface ApiResult<T> {
  code: number
  msg: string | null
  data: T | null
}

/** 后端 `PageResult`：`total` 是**总记录数**，不是当前页条数。 */
export interface PageResult<T> {
  total: number
  records: T[]
}

/**
 * 分页请求参数。字段名是 `pageNum` / `pageSize`（**不是 page / size**），
 * 且 `pageSize` 后端有 `@Max(100)` 校验，超过会得到 code:0。
 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}

/** 前端归一化后的分页结果：多带一个 `pageNum`，用于「越界校正」后回写分页器。 */
export interface Paged<T> {
  total: number
  records: T[]
  /** 实际生效的页码。后端 PageHelper 开了 `reasonable: true`，越界会被改到首/末页。 */
  pageNum: number
  pageSize: number
  /** 由 total / pageSize 算出，不依赖「返回是否为空」——后端那条配置让后者不可信。 */
  hasNext: boolean
}

export const CODE_SUCCESS = 1
export const CODE_FAIL = 0

/** 后端分页参数的默认值与上限，与各 `*PageQueryDTO` 的字段初始值一致。 */
export const DEFAULT_PAGE_NUM = 1
export const DEFAULT_PAGE_SIZE = 10
export const MAX_PAGE_SIZE = 100

/** 类型守卫：判断响应体是不是后端信封（而不是静态资源之类的裸返回）。 */
export function isApiResult(body: unknown): body is ApiResult<unknown> {
  return (
    typeof body === 'object' &&
    body !== null &&
    'code' in body &&
    typeof (body as { code: unknown }).code === 'number'
  )
}
