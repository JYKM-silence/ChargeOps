/**
 * 车主端认证：注册 + 登录。
 *
 * ★★ 这两条**都不经过 `guardMember`**，而且这是对的 ★★
 *   路径在 `/api/auth/member/**` 下，而后端的拦截器只挂
 *   `/api/admin/**` 与 `/api/member/**` 两个模式 —— `/api/auth/**` 不在其中。
 *   写代码时的划分和拦截器一致：谁写了 `guardMember`，谁的请求就自动带上
 *   鉴权语义。登录接口当然不能要求先有 token，否则唯一能拿到 token 的路被自己堵死。
 *
 * ★★ 注册接口**不预先查手机号是否存在** ★★
 *   后端 `register` 直接 insert，重复由**唯一索引**拒绝，再被
 *   `GlobalExceptionHandler` 从 MySQL 报错里抠出冲突的值，拼成 `<手机号>已存在`。
 *   所以那句提示里是**用户刚提交的号码**，不是「该手机号已注册」这种人话 ——
 *   抄成人话虽然更好读，但对不上，而演示时核对的就是这些字。
 *
 * ★ 一个刻意的偏离，只在一个场景下生效：见 `login` 里 `locked` 那一段。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type { MemberLoginDTO, MemberLoginVO, MemberRegisterDTO } from '../../types/member'
import { envelope, fail, newToken, ok } from './core'
import type { MockContext } from './core'
import { alreadyExists, TEXT } from './texts'

/** `StatusConstant.ENABLE` —— 1 正常。 */
const MEMBER_ENABLE = 1
/** `StatusConstant.DISABLE` —— 0 禁用。 */
const MEMBER_DISABLE = 0

/** 注册时手机号的格式。与 `MemberRegisterDTO` 的 `@Pattern` 逐字一致。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/
/** 注册 / 改密的密码长度区间。与 `@Size(min = 6, max = 32)` 一致。 */
const PASSWORD_MIN = 6
const PASSWORD_MAX = 32

/**
 * 注册请求的字段校验。
 *
 * ★★ 为什么这里**破例**复刻了 `@Pattern` / `@Size`（core 层的政策只覆盖
 *   `@NotBlank` / `@NotNull`）★★
 *   这两条是**唯一**挡着「注册一个格式不对的手机号 / 一个字符的密码」的东西，
 *   Service 里没有任何复查。跳过它们，mock 就会让一个真后端会拒绝的注册「成功」，
 *   而那会真的写进内存库 —— 后面「用这个号码登录」还会成功。
 *
 * ★★ 一条字段可以**同时**报两句话，这是后端的真实行为，不是抄错了 ★★
 *   `phone = ""` 时 `@NotBlank` 和 `@Pattern` **都会失败**（`@Pattern` 只对
 *   `null` 放行），于是用户看到的是「手机号不能为空；手机号格式不正确」。
 *   `password = ""` 同理（长度 0 也不在 6-32 内）。逐字段、按注解顺序收集再拼，
 *   才是 Spring 的行为；写成「空就报一句、否则再判格式」会少掉后半句。
 */
function validateRegister(body: MemberRegisterDTO): string | null {
  const problems: string[] = []
  const phone = body.phone
  const password = body.password
  const nickname = body.nickname

  if (phone === undefined || phone === null || phone.trim() === '') {
    problems.push('手机号不能为空')
  }
  if (phone === undefined || phone === null || !PHONE_PATTERN.test(phone)) {
    problems.push('手机号格式不正确')
  }
  if (password === undefined || password === null || password.trim() === '') {
    problems.push('密码不能为空')
  }
  if (
    password === undefined ||
    password === null ||
    password.length < PASSWORD_MIN ||
    password.length > PASSWORD_MAX
  ) {
    problems.push(`密码长度必须在 ${PASSWORD_MIN}-${PASSWORD_MAX} 位之间`)
  }
  if (nickname !== undefined && nickname !== null && nickname.length > 50) {
    problems.push('昵称不能超过 50 个字符')
  }

  return problems.length > 0 ? problems.join('；') : null
}

/** 登录请求的字段校验。★ 只有 `@NotBlank`（+ 手机号那条 20 字上限）。 */
function validateLogin(body: MemberLoginDTO): string | null {
  const problems: string[] = []
  if (body.phone === undefined || body.phone === null || body.phone.trim() === '') {
    problems.push('手机号不能为空')
  } else if (body.phone.length > 20) {
    problems.push('手机号长度不合法')
  }
  if (body.password === undefined || body.password === null || body.password.trim() === '') {
    problems.push('密码不能为空')
  }
  return problems.length > 0 ? problems.join('；') : null
}

export function memberAuthHandlers(ctx: MockContext): RequestHandler[] {
  return [
    /**
     * 注册（**注册即登录**）。
     *
     * ★ 响应体与登录相同，`token` 直接可用 —— 所以前端注册完不用再登一次。
     * ★ 注册的车主一律是「1 正常」，并且 `lastLoginTime` 就是此刻
     *   （后端也是这么写的：注册成功 = 已经登录过一次）。
     */
    http.post('/api/auth/member/register', ({ request }) =>
      envelope(ctx, async () => {
        const body = (await request.json().catch(() => ({}))) as MemberRegisterDTO
        const invalid = validateRegister(body)
        if (invalid) return fail(invalid)

        const phone = body.phone
        // ★ 唯一索引：`uk_member_phone`。冲突时后端从驱动报错里抠出那个值，
        //   所以这里报的也是**用户刚提交的号码**。
        if (ctx.db.members.some((m) => m.phone === phone)) return fail(alreadyExists(phone))

        const at = ctx.db.now()
        const member = {
          id: ctx.db.nextId('member'),
          phone,
          // ★ 明文存。真后端是 BCrypt 密文，但 mock 模拟「密码校验」这件事
          //   不需要哈希 —— 而且哈希是**故意慢**的，在演示里只会让每次登录卡一下。
          password: body.password,
          nickname: body.nickname ?? null,
          avatar: null,
          gender: 0,
          status: MEMBER_ENABLE,
          lastLoginTime: at,
          createTime: at,
          updateTime: at,
        }
        ctx.db.members.push(member)

        return ok(toLoginVo(ctx, member.id))
      }),
    ),

    /**
     * 登录。
     *
     * ★★ 四道检查的顺序**照抄** `MemberServiceImpl.login`（存在 → 未禁用 →
     *   设过密码 → 密码对）★★ 顺序决定了用户看到哪一句，而这四句的处置完全不同：
     *   「手机号或密码错误」是自己再想想，「账号被禁用」要找客服，
     *   「未设置密码」是**重试永远不会成功**的那种（验证码注册的用户）。
     *   ★ 「没注册」和「密码错」**合并**成同一句是有意的（后端就是这么抛的）：
     *     分开会让接口变成一个「这个手机号注册过吗」的探测器。
     */
    http.post('/api/auth/member/login', ({ request }) =>
      envelope(ctx, async () => {
        const body = (await request.json().catch(() => ({}))) as MemberLoginDTO
        const invalid = validateLogin(body)
        if (invalid) return fail(invalid)

        const member = ctx.db.members.find((m) => m.phone === body.phone)
        if (!member) return fail(TEXT.MEMBER_LOGIN_FAILED)

        // ★★ 唯一的一处**刻意偏离后端**，而且只在 `locked` 场景下 ★★
        //   后端在这里会拒绝被禁用的账号（「账号被禁用」）。但 `locked` 场景要演示的
        //   是**另一条链路**：「已经登录着的人被禁用了，探针发现 `status: 0`
        //   然后强制登出」。而种子里那个车主**一开始就是禁用的** ——
        //   登录也拦的话，就永远拿不到那张 token，`/me` 这条路根本走不到，
        //   演示就变成了「禁用之后连登录都进不去」，那是另一件事。
        //   ★ 其余场景一律照抄后端。要演示真实的「登录被拒」，把场景切回 `default`
        //     再用一个被禁用的号码登录即可（admin 那边禁用一下就有）。
        if (member.status === MEMBER_DISABLE && ctx.scenario !== 'locked') {
          return fail(TEXT.MEMBER_DISABLED)
        }

        if (member.password === null) return fail(TEXT.MEMBER_PASSWORD_NOT_SET)
        if (member.password !== body.password) return fail(TEXT.MEMBER_LOGIN_FAILED)

        // ★ 记账，不是鉴权：更新失败不影响这次登录（后端也是 try-catch 包住的）。
        member.lastLoginTime = ctx.db.now()

        return ok(toLoginVo(ctx, member.id))
      }),
    ),
  ]
}

/**
 * 签发 token 并拼出登录 VO。
 *
 * ★ token 同时写进 `memberTokens` —— 那一步才是「登录成功」这件事的全部后果。
 *   后端的 token 是自包含的 JWT（服务端不存），mock 没有签名能力，
 *   只能用一张表代替「验签」。两者对外表现一致：一个没登录过的 token
 *   一定换不到身份。
 * ★ 登录 VO 里**没有** `status`（那个字段在 `/me` 的 `MemberProfileVO` 上）——
 *   所以「登录成功」和「能用」是两件事，中间隔着一次探针。
 */
function toLoginVo(ctx: MockContext, memberId: number): MemberLoginVO {
  const member = ctx.db.memberById(memberId)
  const token = newToken('member')
  ctx.db.memberTokens.set(token, memberId)
  return {
    id: memberId,
    phone: member?.phone ?? '',
    nickname: member?.nickname ?? null,
    token,
  }
}
