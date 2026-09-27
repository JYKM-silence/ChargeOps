/**
 * 车主资料 + 头像上传。
 *
 * ★ 路径是 `/api/member/me`，而**登录注册在** `/api/auth/member/...`。
 *   两组路径分属两个 Controller，别混。
 */

import { http } from './http'
import { resolveAssetUrl, uploadFile } from '@chargeops/shared'
import type { MemberPasswordDTO, MemberProfileVO, MemberUpdateDTO } from '@chargeops/shared'

/** 当前车主资料。★ 也是**禁用探针**用的那个接口（见 stores/session）。 */
export function getProfile(): Promise<MemberProfileVO | null> {
  return http.get<MemberProfileVO>('/api/member/me')
}

/**
 * 改资料。
 *
 * ★ 这是 `UpdateDTO` 家族的成员：**不传的字段 = 不修改**。
 *   所以「把昵称清空」在这种契约下**做不到**（传空串会被当成"要改成空"还是
 *   "不传"取决于后端实现，而后果不可预期）。
 *   真要清空得走后端特判 —— 前端不要假装能做到。
 */
export function updateProfile(dto: MemberUpdateDTO): Promise<null> {
  return http.put<null>('/api/member/me', dto)
}

/**
 * 改密码。
 *
 * ★ 后端通常要求**旧密码**（见 `MemberPasswordDTO`），所以这不是
 *   「忘记密码」流程 —— H5 上不要把它做成「重置密码」的入口。
 * ★ 改完密码后旧 token 是否失效由后端决定，前端不做假设。
 */
export function changePassword(dto: MemberPasswordDTO): Promise<null> {
  return http.put<null>('/api/member/me/password', dto)
}

/**
 * 上传头像，返回**可直接放进 `<img src>` 的同源路径**。
 *
 * ★ 字段名固定 `file`、白名单 `jpg/jpeg/png/gif/webp`（**没有 svg**）、
 *   单文件 10MB —— 校验都在 `shared/http/upload.ts` 里，与后端逐字一致。
 *
 * ★ 后端这个接口返回 `Result<String>`（**裸字符串，不是 `{url}` 对象**），
 *   `uploadFile()` 已经按字符串收并做了同源归一化 —— 直接用返回值即可。
 *
 * ★ 失败时**抛异常**（不是返回空串）：调用方要让用户看到原因，
 *   而空串会变成「上传成功了但图片不显示」这种不报错的故障。
 */
export async function uploadAvatar(file: File): Promise<string> {
  const url = await uploadFile(http, '/api/member/upload', file)
  return resolveAssetUrl(url)
}
