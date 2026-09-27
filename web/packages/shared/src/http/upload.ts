/**
 * 文件上传。
 *
 * 后端 `FileUploadService` 是全项目**唯一**一份上传校验实现（管理端/车主端两个
 * Controller 各自只有一句转发），规则抄在这里：
 *
 *   | 规则             | 值                                        | 出处 |
 *   |------------------|-------------------------------------------|------|
 *   | 表单字段名       | **`file`**（写死，不是 `upload`/`img`）    | 两个 Controller 的 `@RequestPart` |
 *   | 后缀白名单       | `jpg` `jpeg` `png` `gif` `webp`           | `FileUploadService:108` |
 *   | 单文件大小       | 10MB                                      | `application.yml:42` |
 *   | 整个请求大小     | 20MB（`max-request-size`）                | `application.yml:43` |
 *   | 后缀取法         | 从**文件名**取，大小写不敏感              | `FileUploadService:200` |
 *
 * ★ 白名单里**没有 svg**。这是刻意的：SVG 是 XML，能内嵌 `<script>`，
 *   而后端把上传目录用 `/img/**` **匿名**对外提供 —— 一个 svg 就是一个
 *   存储型 XSS。所以前端选择器上也要挡住 svg，不能只在后端拦。
 *
 * ★ 前端这些校验是**为了不让你白等一次上传**（10MB 传完再被拒绝很难受），
 *   不是安全边界。安全边界在后端，前端永远可以被绕过 —— 这条不用改。
 */

import type { HttpClient, RequestOptions } from './createHttp'

/** 与 `FileUploadService.ALLOWED_EXTENSIONS` 逐字一致。 */
export const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp'] as const

/** `<input accept>` 用的字符串。 */
export const ACCEPT_ATTR = '.jpg,.jpeg,.png,.gif,.webp'

/** 单文件 10MB。`application.yml` 的 `max-file-size`。 */
export const MAX_FILE_BYTES = 10 * 1024 * 1024

/** 整个 multipart 请求 20MB。当前接口都是单文件，留作说明与将来多文件用。 */
export const MAX_REQUEST_BYTES = 20 * 1024 * 1024

/** 上传表单字段名。**不能改**，后端写死了 `@RequestPart("file")`。 */
export const UPLOAD_FIELD_NAME = 'file'

export interface UploadCheck {
  ok: boolean
  /** 不通过时的提示文案，**逐字复刻后端**（含全角冒号）。 */
  message?: string
}

/**
 * 上传前置校验。返回的文案与后端 `MessageConstant` 的三条**逐字一致** ——
 * 这样「前端拦下」和「后端拒绝」在用户眼里是同一句话，不会出现两套说法。
 */
export function checkUploadFile(file: File | null | undefined): UploadCheck {
  if (!file) return { ok: false, message: '上传文件为空' }

  // ★ 0 字节文件后端也拒（UPLOAD_FILE_EMPTY），前端先拦
  if (file.size === 0) return { ok: false, message: '上传文件为空' }

  if (file.size > MAX_FILE_BYTES) {
    // 后端这条是被 Spring 的 multipart 限制拦下的，文案不在 MessageConstant 里，
    // 所以这句由前端自己组织（说明白具体上限，比「文件过大」有用）
    return { ok: false, message: `文件大小不能超过 ${MAX_FILE_BYTES / 1024 / 1024}MB` }
  }

  const name = file.name
  const dot = name.lastIndexOf('.')
  // 与后端一致：没有点、或点在最后一位（`a.`），都算「缺少扩展名」
  if (dot < 0 || dot === name.length - 1) {
    return { ok: false, message: '文件名不合法，缺少扩展名' }
  }

  const ext = name.slice(dot + 1).toLowerCase()
  if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(ext)) {
    return { ok: false, message: `不支持的文件类型：${ext}` }
  }

  return { ok: true }
}

/**
 * 上传并返回**可直接放进 `<img src>` 的地址**。
 *
 * @param http   对应端的客户端
 * @param path   `/api/admin/upload` 或 `/api/member/upload`
 */
export async function uploadFile(
  http: HttpClient,
  path: string,
  file: File,
  options?: RequestOptions,
): Promise<string> {
  const check = checkUploadFile(file)
  if (!check.ok) throw new Error(check.message)

  const form = new FormData()
  // ★ 字段名固定 `file`。改成别的 → 后端 `@RequestPart("file")` 拿不到 → 400。
  form.append(UPLOAD_FIELD_NAME, file)

  // ★ 不要手动设 `Content-Type: multipart/form-data` —— 那样会**丢掉
  //   boundary**，后端解析失败。交给浏览器/axios 自动带上（含 boundary）。
  //
  // ★★ 返回的是**裸字符串**，不是对象 ★★
  //   已核对两个 Controller 的签名，**都是**：
  //     `AdminUploadController.upload(...)`  → `public Result<String> upload(...)`
  //     `MemberUploadController.upload(...)` → `public Result<String> upload(...)`
  //   也就是 `Result.success(fileUploadService.upload(file))`，data 就是那个 URL 字符串。
  //
  //   ★ 后端**根本没有 `UploadVO` 这个类**（全仓库 grep 为 0 处）。
  //     之前这里写成解 `{url}` 信封是错的，而且错得很隐蔽：
  //     类型上能过（`UploadVO` 是前端自己造的），运行时 `"http://...".url` 是
  //     `undefined` → 返回 `''` → 表现是「上传成功但图片不显示」，
  //     不报错、不进 catch，排查时根本不会想到是这一行。
  //     → 所以这里按**裸字符串**收，并顺手归一化成同源路径。
  const url = await http.post<string>(path, form, options)
  return typeof url === 'string' ? resolveAssetUrl(url) : ''
}

/**
 * 把后端返回的 URL 归一成**同源相对路径**。
 *
 * ★★ 为什么必须做这一步：后端的返回是 `url-prefix` + `url-path` 拼出来的
 *   **绝对地址**，而 `url-prefix` 是配置项（本机是 `http://localhost:8081`）。
 *   直接把这个绝对地址塞进 `<img src>` 会带来两个问题：
 *
 *     1. **换后端地址就全挂**：文档里写的是 `localhost:8081`，一旦同学把后端
 *        起在别的机器/端口（或者以后有了正式域名），所有**新上传**的图能用，
 *        但页面上所有图片都会去请求一个不存在的 `localhost:8081`。
 *     2. **绕过开发代理**：我们刻意让前端走 Vite 代理（后端零 CORS，见
 *        `vite.config.ts`），而绝对地址会跳过代理直连 8081，等于绕开了
 *        那条本来用于统一入口的链路。
 *
 *   转成 `/img/xxx.png` 之后，开发期由 Vite 代理转发、将来同源部署由 Nginx
 *   转发，**两种情况都不用改代码**。
 *
 * ★ 非绝对地址、或解析失败的，原样返回（不做无谓的猜测）。
 */
export function resolveAssetUrl(url: string | null | undefined): string {
  if (!url) return ''
  const trimmed = url.trim()
  if (!trimmed) return ''
  // 已经是相对路径（后端换配置后可能直接返回 `/img/xxx.png`）→ 直接用
  if (trimmed.startsWith('/')) return trimmed
  // data: / blob: 直接用
  if (trimmed.startsWith('data:') || trimmed.startsWith('blob:')) return trimmed
  try {
    const parsed = new URL(trimmed)
    return `${parsed.pathname}${parsed.search}`
  } catch {
    return trimmed
  }
}
