/**
 * 管理端认证：登录 + 当前登录者资料。
 *
 * ★★ 这两条**只有其中一条需要 token**，而划分的依据不是「哪个文件好看」★★
 *
 *   · `POST /api/auth/staff/login` 在 `/api/auth/**` 下 ——
 *     后端的 `WebMvcConfiguration` 只注册 `/api/admin/**` 与 `/api/member/**`
 *     两个拦截路径，`/api/auth/**` **不在其中**。
 *     所以这里刻意**不写 `guardStaff`** —— 写了的话，mock 上「登录」会要求
 *     先有 token，而真后端不会，于是这个 mock 会挡住唯一一条能拿到 token 的路。
 *
 *   · `GET /api/admin/staff/me` 反过来，是**必须**走 `guardStaff` 的，
 *     因为它的 id 就是从 token 里解出来的（`BaseContext.getCurrentId()`）。
 *     它也是整个管理端最常用的「登录态探针」。
 *
 * ★ 登录接口**没有验证码、没有失败次数限制** —— 这不全是简化：
 *   后端的 `StaffServiceImpl.login` 里确实只有「存在 / 未禁用 / 密码对」三道。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type { StaffLoginVO } from '../../types/admin'
import { envelope, fail, guardStaff, newToken, ok } from './core'
import type { MockContext } from './core'
import { TEXT } from './texts'
import { toStaffVo } from './vo'

/** 登录请求体。★ `password` 是**明文** —— 后端 `@Data` 的 DTO 字段就是明文。 */
interface StaffLoginBody {
  username?: string
  password?: string
}

export function adminAuthHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.post('/api/auth/staff/login', ({ request }) =>
      envelope(ctx, async () => {
        const body = (await request.json().catch(() => ({}))) as StaffLoginBody
        const username = body.username ?? ''
        const password = body.password ?? ''

        // ★ 三道校验的**顺序照抄** `StaffServiceImpl.login`（存在 → 未禁用 → 密码），
        //   因为顺序决定了用户看到哪一句，而这三句的处置完全不同：
        //   「账号被禁用」要找管理员，「密码错误」是自己再想想。
        //   顺序反过来的话，一个被禁用的账号输错密码会收到「密码错误」——
        //   用户会一直重试到一个永远不可能成功的动作上。
        const staff = ctx.db.staff.find((s) => s.username === username)
        if (!staff) return fail(TEXT.ACCOUNT_NOT_FOUND)
        if (staff.status === 0) return fail(TEXT.ACCOUNT_LOCKED)
        if (staff.password !== password) return fail(TEXT.PASSWORD_ERROR)

        const token = newToken('admin')
        // ★ 把 token 记进 `adminTokens` 才是「登录成功」这件事的全部后果 ——
        //   后端的 token 是自包含的 JWT，服务端不存；mock 没有签名能力，
        //   只能用一张表代替「验签」。两者对外表现一致：
        //   一个没登录过的 token 一定换不到身份。
        ctx.db.adminTokens.set(token, staff.id)

        const vo: StaffLoginVO = {
          id: staff.id,
          username: staff.username,
          name: staff.name,
          role: staff.role,
          stationId: staff.stationId,
          token,
        }
        return ok(vo)
      }),
    ),

    http.get('/api/admin/staff/me', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        // ★ 找不到时返回 401 而不是 `STAFF_NOT_FOUND`：
        //   能走到这里说明 token 是认得的，而 token 表指着一条不存在的员工行 ——
        //   这只可能是「登录后被删号」。后端的 `requireStaff` 会抛 `账号不存在`，
        //   但前端在 `getStaffMe` 上对 401 的处理（清会话 + 跳登录）才是正确处置：
        //   一个查不到身份的管理端页面没有任何可做的事。
        const staff = ctx.db.staff.find((s) => s.id === g.ctx.staffId)
        if (!staff) return fail(TEXT.ACCOUNT_NOT_FOUND)

        return ok(toStaffVo(staff))
      }),
    ),
  ]
}
