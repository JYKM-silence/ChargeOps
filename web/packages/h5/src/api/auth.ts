/**
 * 车主端认证。
 *
 * ★ 路径是 `/api/auth/member/...`，**不是** `/api/member/auth/...` ——
 *   已核对 `MemberAuthController` 的 `@RequestMapping("/api/auth/member")`。
 *   这对路径很像，写错了得到的是 404 而不是 401，很容易被当成「后端没起」。
 */

import { http } from './http'
import type { MemberLoginDTO, MemberLoginVO, MemberRegisterDTO } from '@chargeops/shared'

/** 注册。★ 后端**注册即登录**，返回值与登录完全一样（含 token）。 */
export function memberRegister(dto: MemberRegisterDTO): Promise<MemberLoginVO | null> {
  return http.post<MemberLoginVO>('/api/auth/member/register', dto)
}

export function memberLogin(dto: MemberLoginDTO): Promise<MemberLoginVO | null> {
  return http.post<MemberLoginVO>('/api/auth/member/login', dto)
}

export type { MemberLoginDTO, MemberRegisterDTO }
