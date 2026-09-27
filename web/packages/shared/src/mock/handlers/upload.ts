/**
 * 文件上传：`/api/admin/upload` 与 `/api/member/upload`，外加一个 `/img/**` 的**取图**替身。
 *
 * ★★ 两个入口的**业务规则完全相同**，只有鉴权不同 ★★
 *   后端 `FileUploadService` 是全项目唯一一份实现，两个 Controller 各自只有
 *   一行转发。而拦截路径 `/api/admin/**` 与 `/api/member/**` 是分开的，
 *   所以管理端要 `token`、车主端要 `authentication` —— mock 里唯一的差别
 *   就是调 `guardStaff` 还是 `guardMember`。校验逻辑共用一个函数，
 *   复制一份的话，两份里只有一份会被改。
 *
 * ★★ 返回的是**绝对 URL 字符串**，不是对象 ★★
 *   两个 Controller 的签名都是 `Result<String>`（data 就是那个 URL），
 *   全仓库**没有** `UploadVO` 这个类。写成 `{url: ...}` 在类型上能过
 *   （那个类型是前端自己造的），运行时 `"http://...".url` 是 `undefined`，
 *   表现是「上传成功但图片不显示」—— 不报错、不进 catch。
 *
 * ★★ 为什么还要拦 `/img/**` ★★
 *   它**不在**任何 `api/*.ts` 里（它是 `<img src>` 发出的请求，不是接口调用），
 *   但缺了它，「上传头像 → 看一眼」这条最直观的演示是走不通的：
 *   上传成功、URL 也有了，图片却是一个 404 —— 演示的人会以为上传坏了。
 *   （真后端把存储目录挂在 `/img/**` 上**匿名**对外提供，见 `WebMvcConfiguration`。）
 *   ★ 存在内存里而不是写文件：mock 的寿命就是一次页面加载，刷新即清空，
 *     和内存库的语义一致 —— 绝不会留下一个第二天还在的假图片。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import { envelope, fail, guardMember, guardStaff, ok } from './core'
import type { MockContext } from './core'
import { TEXT } from './texts'
import { ALLOWED_EXTENSIONS, UPLOAD_FIELD_NAME } from '../../http/upload'

/**
 * 上传成功后返回的 URL 前缀。
 *
 * ★ 逐字抄 `application.yml` 的 `chargeops.storage.url-prefix`
 *   （`http://localhost:8081`）+ `url-path`（`/img`）。
 *   这个值会被**写进数据库**（`member.avatar` / `charge_package.image`），
 *   所以它必须长得和真后端一样 —— 否则演示时 `resolveAssetUrl` 剥 origin
 *   那一步看起来是对的，而真实数据里存的是另一个东西。
 *
 * ★ `application-prod.yml` 里这两项是别的值（换环境要改配置**并且刷历史数据**）。
 *   mock 固定用 dev 的那一份：演示环境就是 dev。
 */
const URL_PREFIX = 'http://localhost:8081'
const URL_PATH = '/img'

/**
 * 本次会话里上传过的字节：`对象名 → { 内容, MIME }`。
 *
 * ★ 模块级 `Map` 而不是塞进 `MockDb`：它**不是业务数据**，没有任何一条
 *   业务规则读它（订单、钱包、权益都不关心图片）。放进 `MockDb` 会让
 *   「内存库里有什么」这个问题混进一堆二进制。
 *   ★ 它也因此**不随 `createHandlers()` 重置**，但热更新时旧的对象名
 *     不会再被任何页面引用，等同于垃圾 —— 代价可以忽略。
 */
const uploadedFiles = new Map<string, { body: Blob; contentType: string }>()

/** 上传校验的失败文案 —— **逐字**复刻 `FileUploadService` 的三条。 */
function validateUpload(file: File | null): string | null {
  // ① 空文件。★ 表单里选了一个 0 字节的文件、或者压根没选，都落在这里。
  //    不拦的话后面是「读了 0 个字节、然后写了一个空文件」—— 它会**成功**
  //    并返回一个能打开的 URL，用户得到一张永远加载不出来的图，而接口一声不吭。
  if (!file || file.size === 0) return TEXT.UPLOAD_FILE_EMPTY

  const name = file.name
  const dot = name.lastIndexOf('.')
  // ② 没有后缀，或点在最后一位（`avatar.` 取出来是空串，会拼出 `uuid.`）。
  //    ★ 它和「不支持的类型」刻意分成两句：前者是「看不出这是什么」，
  //      后者是「看得出，但不在允许范围内」，下一步该做什么不一样。
  if (name === '' || dot < 0 || dot === name.length - 1) return TEXT.UPLOAD_FILE_NAME_INVALID

  const rawExtension = name.slice(dot + 1)
  // ③ 白名单。★ 报错里填的是**原始后缀**而不是小写后的那一份：
  //    这句话是给上传的人看的，他手上那个文件叫 `a.JSP`，
  //    回一句「不支持的文件类型：jsp」会让他再去核对一遍自己到底传了什么。
  //    对象名（机器读的）用小写规范形式，提示语（人读的）用原始形式。
  if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(rawExtension.toLowerCase())) {
    return `不支持的文件类型：${rawExtension}`
  }
  return null
}

/**
 * 读出表单里名为 `file` 的那一项。
 *
 * ★ 字段名固定 `file`（两个 Controller 都显式写了 `@RequestPart("file")`）——
 *   改成 `upload` 之类的名字，真后端会 400，而 mock 这里会「照常成功」。
 *   ★ 用 `UPLOAD_FIELD_NAME` 常量而不是字面量：前端拼表单时用的是同一个常量，
 *     两处不可能漂移。
 * ★ 不是 `File` 就当成「没传文件」：后端 `MultipartFile` 为空时也是这一条。
 */
async function readUploadedFile(request: Request): Promise<File | null> {
  const form = await request.formData().catch(() => null)
  if (!form) return null
  const value = form.get(UPLOAD_FIELD_NAME)
  return value instanceof File ? value : null
}

/**
 * 保存并返回访问 URL。
 *
 * ★ 对象名 = 随机串 + 小写后缀，**没有任何一个字符来自用户**：
 *   用户给的文件名可以带 `../`、可以带脚本后缀，用它做对象名是一个
 *   目录穿越 + 存储型 XSS。真后端用 UUID，这里用同样的形状。
 *   ★ 不用 `crypto.randomUUID()`：它在非安全上下文（http 的局域网 IP）
 *     下不存在，而 mock 恰恰经常在那样的地址上被打开。取时间戳 + 随机数，
 *     形状够用、且到处都能跑。
 */
async function storeUpload(file: File): Promise<string> {
  const dot = file.name.lastIndexOf('.')
  const extension = file.name.slice(dot + 1).toLowerCase()
  const objectName = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}.${extension}`

  uploadedFiles.set(objectName, {
    body: file.slice(0, file.size, file.type),
    contentType: file.type || 'application/octet-stream',
  })
  return `${URL_PREFIX}${URL_PATH}/${objectName}`
}

export function uploadHandlers(ctx: MockContext): RequestHandler[] {
  /**
   * 两个入口的唯一差别就是这一层鉴权，所以只写一份逻辑。
   *
   * ★ `guard` 用参数传进来而不是在函数体里判「路径里有没有 member」：
   *   那种判法会让「哪一端用哪张令牌表」变成一个**运行时字符串比较**，
   *   而它本该在写代码时就定死（粘贴错了会在演示时表现成一次莫名其妙的 401）。
   */
  // ★ 返回类型只写「成败 + 一个响应」这一半：两端的身份类型
  //   （`AdminCtx` / `MemberCtx`）在这里根本用不到（上传不读身份，只判在不在），
  //   而写成 `ReturnType<typeof guardStaff>` 会让车主端那一份**类型上不成立**
  //   （`Guarded<MemberCtx>` 不是 `Guarded<AdminCtx>`）—— 那会诱使人去写 as，
  //   于是把「两端各自只能用自己的令牌」这条约束一起绕过去。
  const upload = (
    guard: (
      context: MockContext,
      request: Request,
    ) => { ok: true; ctx: unknown } | { ok: false; response: Response },
  ) =>
    ({ request }: { request: Request }) =>
      envelope(ctx, async () => {
        const g = guard(ctx, request)
        if (!g.ok) return g.response

        const file = await readUploadedFile(request)
        const invalid = validateUpload(file)
        if (invalid) return fail(invalid)

        // ★ 走到这里一定非空（校验的第一条就是它），断言只是让类型收窄。
        return ok(await storeUpload(file as File))
      })

  return [
    http.post('/api/admin/upload', upload(guardStaff)),
    http.post('/api/member/upload', upload(guardMember)),

    /**
     * `GET /img/:name` —— 把刚上传的那张图**原样**吐回去。
     *
     * ★ 不加鉴权，和真后端一致：静态资源在拦截器范围之外
     *   （`url-path` 的注释里写明了「不要配成 /api 开头，图片会变成必须登录才能看」）。
     * ★ 找不到时返回 404 空体，而不是一张占位图：一张「看起来正常」的假图
     *   会让人以为对象名对上了，而真正的问题（对象名不一致）依然在。
     */
    http.get('/img/:name', ({ params }) => {
      const stored = uploadedFiles.get(params.name as string)
      if (!stored) return new Response(null, { status: 404 })
      return new Response(stored.body, {
        status: 200,
        headers: { 'Content-Type': stored.contentType },
      })
    }),
  ]
}
