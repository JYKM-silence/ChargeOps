/**
 * 认证接口。
 *
 * ★ 登录接口在 `/api/auth/staff/**` 下，**不在 `/api/admin/**` 里** ——
 *   这不是笔误：`WebMvcConfiguration` 的 JWT 拦截器只拦
 *   `/api/admin/**` 和 `/api/member/**`，`/api/auth/**` 是匿名可访问的。
 *   把登录路径写成 `/api/admin/auth/login` 会得到 401（还没有 token 就要求 token）。
 */

import type { StaffLoginVO, StaffVO } from './types'
import { http } from './http'

export interface StaffLoginDTO {
  username: string
  password: string
}

/** 管理端登录。成功后把 `token` 存进会话。 */
export function staffLogin(payload: StaffLoginDTO): Promise<StaffLoginVO | null> {
  return http.post<StaffLoginVO>('/api/auth/staff/login', payload)
}

/** 当前登录的运维信息（顶栏展示用）。 */
export function getStaffMe(): Promise<StaffVO | null> {
  return http.get<StaffVO>('/api/admin/staff/me')
}
