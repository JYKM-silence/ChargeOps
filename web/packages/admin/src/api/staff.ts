/**
 * 运维人员接口（`/api/admin/staff/**`）。
 *
 * ★★ 这个路径段下**只有一个端点**：`GET /me` ★★
 *   后端没有 `PUT /api/admin/staff/me`、也没有运维列表接口 ——
 *   所以「我的」页面是**只读**的，那不是本次没做完，是后端没有这个能力。
 *   放一个点了报错的「编辑」按钮比不放更糟。
 *
 * ★ 与 `api/auth.ts` 的关系：那边有一个同名同 URL 的 `getStaffMe`（当年作为
 *   登录态探针写的，目前无人调用）。这里保留一份是刻意的分工 ——
 *   `auth.ts` 管 `/api/auth/**`（匿名可访问的登录），
 *   `staff.ts` 管 `/api/admin/staff/**`（需要 token 的运维资料）。
 *   **新的调用一律从这里引**；两处 URL 重复是暂时的，
 *   而 `http.get` 的 GET 去重保证并发时也只会真的发一次请求。
 */

import type { StaffVO } from '@chargeops/shared'
import { http } from './http'

/**
 * 当前登录的运维信息。
 *
 * ★ **不收 id 参数**：后端从 token 解出「当前登录者是谁」（`BaseContext`），
 *   所以「查别人的资料」在接口形状上就无法表达 —— 前端也不该去拼一个 id 出来。
 */
export function getStaffMe(): Promise<StaffVO | null> {
  return http.get<StaffVO>('/api/admin/staff/me')
}
