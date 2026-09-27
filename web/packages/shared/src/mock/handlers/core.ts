/**
 * handler 层的地基：信封、分页、鉴权、场景闸门、参数读取。
 *
 * ★★ 这里是整个 mock 里唯一「可以被所有域信任」的一层，理由有两条 ★★
 *
 *   1. **信封形状只有这一份实现。** 后端的 `Result` 是自有形状 ——
 *      成功是 `code: 1` 且 `msg` **保持 null**（`Result.success` 只 setCode，
 *      不写 msg），失败是 `**HTTP 200** + code: 0 + 中文 msg`，
 *      未登录是 **HTTP 401 且响应体为空**。三处只要有一处写成「常见约定」
 *      （比如 `{code: 200, message: ''}`），前端就会走进完全不同的分支，
 *      而且是**静默**的：`isApiResult` 仍然为真、`code === 1` 仍然不成立，
 *      于是每个接口都变成「业务失败」，msg 是 undefined。
 *
 *   2. **分页的越界语义只有这一份实现。** 见 `paginate`，它不是「切片」那么简单。
 *
 * ★ 这一层**不碰任何业务规则**：状态机在 `db.ts`，文案在 `MSG` / 各域的常量里。
 */

import { delay, HttpResponse } from 'msw'
import type { MockDb } from '../db'
import type { MockScenario } from '../scenario'
import { SLOW_DELAY_MS } from '../scenario'
import { TEXT } from './texts'
import { TOKEN_HEADER } from '../../http/session'
import { DEFAULT_PAGE_NUM, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from '../../http/types'
import type { PageResult } from '../../http/types'
import type { Decimal } from '../../types/admin'
import { parseServerTime } from '../../utils/datetime'

/**
 * 每个 handler 工厂都收这个。
 *
 * ★ 把 `db` 显式传进去而不是让各域 `import` 一份模块级单例：
 *   内存库是**有状态**的，而 `createHandlers()` 每次调用都必须拿到新的一份
 *   （否则热更新后数据会串 —— 见 `index.ts` 的说明）。模块级单例做不到这件事。
 */
export interface MockContext {
  db: MockDb
  scenario: MockScenario
  /**
   * 每次响应的固定延迟（毫秒）。
   *
   * ★ 在 `createHandlers` 里就已经算好了（`slow` 场景的默认值也在那里落定），
   *   所以这一层不用再判场景。
   */
  delayMs: number
}

// ══════════════════════════════════════════════════════════════════════════
// 一、信封
// ══════════════════════════════════════════════════════════════════════════

/**
 * 成功。
 *
 * ★ `msg` 写 `null` 而不是 `''` 或 `'success'`：已核对 `Result.java` ——
 *   `success(T data)` 只做 `setCode(CODE_SUCCESS)` 和 `setData(data)`，
 *   `msg` 保持字段初始值 `null`。写成 `'success'` 会让「成功时 msg 是空的」
 *   这条前端假设在某些地方失效（比如「拿 msg 当提示语」的兜底分支）。
 */
export function ok(data: unknown = null): Response {
  return HttpResponse.json({ code: 1, msg: null, data })
}

/** `Result<Void>` 的接口（start / end / settle / refund / cancel / update / delete）用这个。 */
export function okVoid(): Response {
  return ok(null)
}

/**
 * 业务失败。
 *
 * ★★ **HTTP 200** ★★ 后端所有业务异常都由 `GlobalExceptionHandler` 翻成
 *   `Result.error(msg)`，状态码是 200。把它们写成 4xx 的话，
 *   前端的 `onError` 会走到「HTTP 状态码异常」那条分支，
 *   **后端的中文文案会被丢掉**，用户看到的是「操作失败」。
 */
export function fail(msg: string): Response {
  return HttpResponse.json({ code: 0, msg, data: null })
}

/**
 * 未鉴权。
 *
 * ★★ 响应体必须是**空的** ★★ 后端的拦截器只做 `response.setStatus(401)`
 *   然后 `return false`，一个字节都不写。前端的判定是
 *   「401 **且** body 为空 → 登录态失效」，并且读不到 msg 时用固定文案
 *   `FALLBACK_MESSAGE.auth`。往这里塞一个 `{code: 401, msg: ...}` 看起来更友好，
 *   实际是**把这条路径测没了**：`isApiResult` 会吃掉它、走成业务失败分支，
 *   于是「401 空体」这条真实链路永远没有被验证过。
 */
export function unauthorized(): Response {
  return new HttpResponse(null, { status: 401 })
}

/**
 * 5xx。
 *
 * ★ 体也是空的（`kindOfHttpStatus(500)` → `'server'` →
 *   `FALLBACK_MESSAGE.server`）。这里和后端的真实形态一致：
 *   未捕获异常在那个兜底 handler 里其实返回的是 `code:0`，
 *   但**网关 / 后端进程挂掉**时前端拿到的确实是一个空体 5xx ——
 *   而 `error` 场景要演示的正是那一种。
 */
export function serverError(): Response {
  return new HttpResponse(null, { status: 500 })
}

// ══════════════════════════════════════════════════════════════════════════
// 二、场景闸门
// ══════════════════════════════════════════════════════════════════════════

/**
 * handler 的统一外壳：**延迟 → 故障注入 → 真正干活**。
 *
 * ★ 顺序是有意的：延迟排在最前，所以 `slow + error` 也能看见「转圈之后才报错」——
 *   那正是真实网络里最难受的一种形态，也是最容易被前端漏掉的状态组合。
 *
 * ★ `error` 覆盖**全部**端点（含登录）。切成这个场景之后整个应用都进不去，
 *   听起来很粗暴，但它就是要点 —— 首屏、列表、表单、上传，每一处都该有自己的
 *   错误态，而不是一个白屏。要只演示局部失败，用 `insufficient` / `conflict`。
 */
export async function envelope(
  ctx: MockContext,
  run: () => Response | Promise<Response>,
): Promise<Response> {
  runScheduledJobs(ctx)
  if (ctx.delayMs > 0) await delay(ctx.delayMs)
  if (ctx.scenario === 'error') return serverError()
  return run()
}

/**
 * 定时任务替身 —— **每个请求进来先跑一轮**。
 *
 * ★★ 为什么必须存在，以及为什么挂在这里而不是另起一个 `setInterval` ★★
 *
 *   后端有两个 `@Scheduled` 任务在改数据：
 *     ① 5 分钟一轮：把「4 待支付」超 15 分钟的单标成「6 支付失败」；
 *     ② 30 分钟一轮：把过期但还写着「1 生效中」的权益落库成「3 已过期」。
 *   它们的产物**会出现在列表里**（订单列表里的 `6`、权益列表里的 `3`），
 *   不模拟的话，种子里那些「刚好卡在时限边上」的行会永远停在一个
 *   真后端上不可能出现的状态。
 *
 *   `setInterval` 是不行的：内存库的生命周期是「一次 `createHandlers()`」，
 *   而定时器会跨热更新活着，改到已经没人引用的那份库上（还会阻止 GC）。
 *   挂在请求上则**天然跟着库走**，也天然是「打开页面时才推进」——
 *   和真人演示的节奏一致，不会出现「放了一晚上，第二天打开全变成支付失败」。
 *
 * ★ 跑在 `delay` 和故障注入**之前**：它是「时间流逝」而不是「这次请求的响应」，
 *   所以 `error` 场景下也该照常推进（那一轮请求本来就该看到推进后的数据）。
 */
export function runScheduledJobs(ctx: MockContext): void {
  ctx.db.sweepStalePendingPay()
  ctx.db.expireOverduePackages()
}

/** `slow` 场景在没有显式 `delayMs` 时的默认延迟，落定在 `createHandlers` 里。 */
export { SLOW_DELAY_MS }

// ══════════════════════════════════════════════════════════════════════════
// 二·五、令牌
// ══════════════════════════════════════════════════════════════════════════

let tokenSeq = 0

/**
 * 签发一个会话令牌。
 *
 * ★★ 刻意**不做成 JWT 的形状** ★★
 *   后端的 token 是 JWT（`JwtBuilder` 签的），但这里没有密钥也没有签名 ——
 *   伪造一个 `header.payload.signature` 的三段串只会带来一种**更坏**的误解：
 *   有人会以为 devtools 里那个 `eyJ...` 能被解出 payload，于是照着改一改
 *   去调真后端。一个一眼看出是假的串（`mock-admin-3`）不会引诱任何人这么做。
 *
 * ★ 前缀区分两端，是因为两张 token 表**不通用**（`adminTokens` / `memberTokens`）：
 *   把管理端的 token 贴到车主端请求头上必须失败。前缀让「贴错了」这件事
 *   在 devtools 里一眼可见，而不是变成一次莫名其妙的 401 排查。
 *
 * ★ `tokenSeq` 单调递增而不是随机：演示时反复登录，令牌在 devtools 里
 *   是 `mock-admin-1` / `mock-admin-2` 这样可数的，比一串随机十六进制好认得多。
 *   跨会话撞车不可能 —— 内存库的寿命就是一次页面加载。
 */
export function newToken(kind: 'admin' | 'member'): string {
  tokenSeq += 1
  return `mock-${kind}-${tokenSeq}`
}

// ══════════════════════════════════════════════════════════════════════════
// 三、分页
// ══════════════════════════════════════════════════════════════════════════

/**
 * 分页切片 —— **复刻 PageHelper `reasonable: true`**。
 *
 * ★★ 这不是「切一刀」那么简单，三个行为都必须一致 ★★
 *
 *   1. **页码越界时返回的是「最后一页的数据」，不是空数组。**
 *      所以在第 3 页删掉最后一条之后，前端还拿着 `pageNum=3` 请求，
 *      拿回来的是第 2 页的内容 —— 页面上「页码显示 3、内容却是第 2 页」。
 *      这正是前端 `http.page()` 里那段「越界校正」要修的东西，
 *      而它之所以修得了，是因为**这里真的按 reasonable 返回了数据**。
 *      如果 mock 返回空数组，那条校正逻辑永远不会被触发，
 *      演示时看到的反而是「mock 有问题」。
 *   2. **`pageNum < 1` 被改到第 1 页**（`reasonable` 的另一半）。
 *   3. **只有 `total` 和 `records`**。后端刻意不返回 PageHelper 的 `Page` 对象，
 *      所以响应里没有 `pageNum` —— 实际生效的页码由前端拿 `total` 自己算。
 *
 * ★ `total` 一律是**过滤之后**的总数：后端是 `PageHelper` 对同一条 SQL
 *   跑一次 `COUNT(*)`，条件和列表完全一致。用「切片前的数组长度」表达它是对的。
 */
export function paginate<T>(rows: readonly T[], pageNum: number, pageSize: number): PageResult<T> {
  const total = rows.length
  // ★ total 为 0 时 lastPage 取 1 而不是 0：否则 `Math.min(pageNum, 0)` 会得到
  //   一个 0 或负的起始下标，`slice(-10, ...)` 会从**数组末尾**开始切 ——
  //   那会凭空返回最后 10 条本该不存在的记录。
  const lastPage = total > 0 ? Math.ceil(total / pageSize) : 1
  const effective = Math.min(Math.max(Math.trunc(pageNum), 1), lastPage)
  const start = (effective - 1) * pageSize
  return { total, records: rows.slice(start, start + pageSize) }
}

/**
 * 取本次请求生效的 `pageNum` / `pageSize`。
 *
 * ★ 默认值是 `1` / `10`，来源是各 `*PageQueryDTO` 的**字段初始值** ——
 *   Spring 绑定不到参数时字段保留初始值，所以「不传」等价于「传了 1 / 10」。
 */
export function pageParams(query: URLSearchParams): { pageNum: number; pageSize: number } {
  return {
    pageNum: numParam(query, 'pageNum') ?? DEFAULT_PAGE_NUM,
    pageSize: numParam(query, 'pageSize') ?? DEFAULT_PAGE_SIZE,
  }
}

/**
 * 分页参数的范围校验，返回失败信封或 `null`。
 *
 * ★ 文案**逐字**取自各 `*PageQueryDTO` 的注解 `message`，例如
 *   `@Max(value = 100, message = "每页条数不能超过 100")`。
 *   多条错误用 `；` 连接 —— `GlobalExceptionHandler` 是把
 *   `getFieldErrors()` 逐个 `getDefaultMessage()` 再 `Collectors.joining("；")`。
 *
 * ★ 这一层能生效，靠的是 Controller 上的 `@Valid`（各 `*PageQueryDTO` 的注释
 *   都点明了「`@Max` 是唯一挡住 `?pageSize=1000000` 的东西」）。
 *   不过实际业务走不到这里：前端 `http.page()` 已经把 `pageSize` 夹进
 *   `[1, 100]`。它存在是为了「手工构造一个越界请求」时能和真后端对上话。
 *
 * ★ **顺序照 DTO 的字段声明顺序**（`pageNum` 在前）。Spring 按校验遍历顺序
 *   收集 `FieldError`，而那是字段声明顺序 —— 这是一条**假设**，
 *   写在这里以便将来发现顺序对不上时知道去哪改。
 */
export function checkPageParams(query: URLSearchParams): Response | null {
  const pageNum = numParam(query, 'pageNum')
  const pageSize = numParam(query, 'pageSize')
  const problems: string[] = []

  if (pageNum !== undefined && pageNum < 1) problems.push('页码不能小于 1')
  if (pageSize !== undefined) {
    if (pageSize < 1) problems.push('每页条数不能小于 1')
    else if (pageSize > MAX_PAGE_SIZE) problems.push('每页条数不能超过 100')
  }

  return problems.length > 0 ? fail(problems.join('；')) : null
}

// ══════════════════════════════════════════════════════════════════════════
// 四、query 参数读取
// ══════════════════════════════════════════════════════════════════════════

/**
 * 读一个字符串参数。
 *
 * ★★ 「没传」和「传了空串」在这里**必须等价** ★★ 前端的 `cleanParams`
 *   会把 `undefined` / `null` / 纯空白串一起丢掉，所以空串根本到不了 worker。
 *   但 handler 仍然把它当「没传」处理，是为了让**手工在地址栏敲**
 *   `?name=` 的请求也得到同一个结果 —— 否则会出现「页面上不筛、地址栏里筛」
 *   这种只在演示时才会出现的差异。
 */
export function strParam(query: URLSearchParams, key: string): string | undefined {
  const raw = query.get(key)
  if (raw === null) return undefined
  const trimmed = raw.trim()
  return trimmed === '' ? undefined : trimmed
}

/** 读一个数字参数。解析不出数字时当「没传」—— 后果是**不筛**，而不是报错。 */
export function numParam(query: URLSearchParams, key: string): number | undefined {
  const raw = strParam(query, key)
  if (raw === undefined) return undefined
  const value = Number(raw)
  return Number.isFinite(value) ? value : undefined
}

/**
 * 请求体里的 `Decimal` → `number`。
 *
 * ★★ 为什么必须有这么一个函数，而不是直接赋值 ★★
 *   接口上的 `Decimal` 是 `string | number`（后端 `BigDecimal` 的 JSON 形态
 *   两种都可能，见 `utils/money.ts`），而**库里的列全是 `number`**。
 *   直接赋值在类型上就不成立 —— 这不是类型系统的刁难，是真的会出问题：
 *   `"1.5" + 0` 得到 `"1.50"`，而 `"1.5" * 2` 得到 `3`。同一个字段在
 *   加法里是字符串拼接、在乘法里是数字运算，这种 bug 只会在某个特定入口出现。
 *
 * ★ 与 `toCents` 的分工：**金额**一律走 `toCents`（它会乘 100 并取整到分），
 *   这个函数服务的是**非金额的小数** —— 额定功率、电量度数、折扣系数、电价单价。
 *   用 `toCents` 处理它们会得到放大 100 倍的功率。
 *
 * ★ `null` / `undefined` / 空串 / 解析不出数字 → `null`（不是 0）：
 *   库里这些列全都是可空的，`null` 表示「不知道」，而 `0` 表示「是零」。
 *   `0 kW` 的充电桩和「没填额定功率的充电桩」是两件事。
 */
export function toNumber(value: Decimal | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : null
}

/**
 * 必填字段校验 —— **只复刻 `@NotBlank` / `@NotNull` 这一类**，返回失败信封或 `null`。
 *
 * ★★ 为什么值得单独做一个（而不是让每个 create 自己 `if (!x) return fail(...)`）★★
 *   后端的必填校验是**在 Controller 上一次性跑完**的：Spring 收集**全部**
 *   `FieldError`，`GlobalExceptionHandler` 用 `；` 拼成**一句**返回。
 *   逐个 early-return 会让「同时漏了名称和类型」只报出第一个，
 *   管理员改一次、再被拒一次 —— 而真后端是一次报全的。
 *
 * ★ 顺序 = **DTO 字段声明顺序**（Spring 按校验遍历顺序收集，那是字段声明顺序）。
 *   这是一条**假设**，写在参数说明里以便将来发现顺序对不上时知道去哪改。
 *
 * ★ 「空」的口径：`@NotBlank` 对**纯空白串**也算空（`name: "   "` 会被拒），
 *   所以字符串先 `trim()`；`@NotNull` 只判 null（数字 `0` 是合法值，
 *   用 `=== undefined || === null` 而不是真值判断）。
 *
 * ★ 这一层**不覆盖** `@Size` / `@Min` / `@Max` / `@Pattern` / `@DecimalMin`：
 *   它们要么在业务层有更具体的检查（如 `assertTypeFields`、`TariffValidator`），
 *   要么只是长度上限。这是**有意留的口子**，别以为是抄漏了。
 */
export function missingRequired(
  checks: readonly (readonly [unknown, string])[],
): Response | null {
  const problems: string[] = []
  for (const [value, message] of checks) {
    const empty =
      value === undefined ||
      value === null ||
      (typeof value === 'string' && value.trim() === '')
    if (empty) problems.push(message)
  }
  return problems.length > 0 ? fail(problems.join('；')) : null
}

/**
 * 读一个 `yyyy-MM-dd HH:mm:ss` 时间参数。
 *
 * ★★ **禁止** `new Date(后端字符串)` ★★ 后端给的是空格分隔的非 ISO 格式，
 *   在 Safari 上会得到 `Invalid Date`，而 `Invalid Date` 参与比较时**恒为 false** ——
 *   表现是「加了时间范围，一条都没筛掉，看起来像筛选没生效」。
 *   统一走 `parseServerTime`（shared 里唯一的时间解析入口）。
 */
export function timeParam(query: URLSearchParams, key: string): Date | undefined {
  const raw = strParam(query, key)
  if (raw === undefined) return undefined
  return parseServerTime(raw) ?? undefined
}

// ══════════════════════════════════════════════════════════════════════════
// 五、鉴权
// ══════════════════════════════════════════════════════════════════════════

/** 管理端身份。只放 id —— 需要整行时用 `db.staff.find(...)` 自己取。 */
export interface AdminCtx {
  db: MockDb
  staffId: number
}

/** 车主端身份。 */
export interface MemberCtx {
  db: MockDb
  memberId: number
}

/**
 * 鉴权结果。
 *
 * ★ 用「返回值」而不是「抛异常」表达未登录：handler 的每一行都可能是
 *   `const g = guardStaff(...); if (!g.ok) return g.response`，
 *   而抛异常会把「未登录」和「代码写错了」混在同一条 catch 里 ——
 *   后者会被误报成 401，排查时方向完全跑偏。
 */
export type Guarded<T> = { ok: true; ctx: T } | { ok: false; response: Response }

/**
 * 管理端鉴权。
 *
 * ★ 请求头名 `token`、**裸值不加 `Bearer `** —— 取自 `TOKEN_HEADER.admin`，
 *   和前端注入时用的是同一个常量，所以两处不可能漂移。
 *   ★ 拦截器只挂 `/api/admin/**`，所以 `/api/auth/staff/login` **不经过这里**
 *     （登录时当然还没有 token）。mock 里也是同样的划分：谁写了 `guardStaff`
 *     谁就自动带上了鉴权语义。
 */
export function guardStaff(
  ctx: MockContext,
  request: Request,
): Guarded<AdminCtx> {
  // ★ `token-expired` 场景：不管请求带没带 token，一律 401 空体。
  //   它演示的是「并发 5 个请求同时 401 → 只跳一次登录、只弹一次提示」
  //   这条幂等链路（见 createHttp 的 fireAuthLost）。所以这里**不能**
  //   把 token 判断放在前面 —— 那样大部分请求会正常返回，就看不出幂等了。
  if (ctx.scenario === 'token-expired') return { ok: false, response: unauthorized() }

  const token = request.headers.get(TOKEN_HEADER.admin)
  const staffId = token ? ctx.db.adminTokens.get(token) : undefined
  // ★ 「没带头」和「头里的 token 不认识」返回**同一个** 401 空体。
  //   后端的拦截器不区分「token 过期」和「签名不对」（注释里写明了理由：
  //   区分它们等于免费告诉攻击者「签名猜对了但过期了」），mock 也不区分。
  if (staffId === undefined) return { ok: false, response: unauthorized() }

  return { ok: true, ctx: { db: ctx.db, staffId } }
}

/** 车主端鉴权。请求头名 `authentication`，同样**裸值**。 */
export function guardMember(
  ctx: MockContext,
  request: Request,
): Guarded<MemberCtx> {
  if (ctx.scenario === 'token-expired') return { ok: false, response: unauthorized() }

  const token = request.headers.get(TOKEN_HEADER.h5)
  const memberId = token ? ctx.db.memberTokens.get(token) : undefined
  if (memberId === undefined) return { ok: false, response: unauthorized() }

  return { ok: true, ctx: { db: ctx.db, memberId } }
}

/**
 * 复刻 `MemberServiceImpl.assertActive`：资金端点的**每次**入口都重查账号状态。
 *
 * ★★ 为什么这条链路必须单独存在（它和 401 是两回事）★★
 *   **禁用车主不会撤销 token。** 7 天窗口内拦截器照样放行，请求返回 HTTP 200 ——
 *   所以前端拿不到任何状态码层面的信号。后端唯一会说话的地方就是这里：
 *   在 Service 层重查一遍 `member.status`，返回一句 `code:0` 的
 *   「该账号已被禁用，请联系客服」。H5 的 `accountLockedLogsOut: true`
 *   就是冲着这句话配的，命中即强制登出。
 *
 * ★ 调用范围**逐字照抄**后端，不多不少：
 *   订单 create / pageMine / getMine / cancel、套餐 available / mine / purchase、
 *   钱包 getMine / recharge / transactions —— 共 10 条。
 *   **`pay` 刻意不在其中**（后端注释：补上它会制造一个解不开的死结 ——
 *   被禁用 + 有 4 待支付订单的车主，钱就永远出不来了）。
 *   `/me` 探针也不在（它本来就要把 `status: 0` 原样返回，那是另一条登出路径）。
 *
 * ★ 返回的是「失败信封或 null」，因为所有调用点都是
 *   `const denied = assertActive(...); if (denied) return denied`。
 */
export function assertActive(ctx: MockContext, memberId: number): Response | null {
  const member = ctx.db.memberById(memberId)
  if (!member) return fail(TEXT.MEMBER_NOT_FOUND)
  if (member.status === 0) return fail(TEXT.MEMBER_DISABLED)
  return null
}
