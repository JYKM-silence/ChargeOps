/**
 * 上传接口。
 *
 * ★★ 三条后端约定，错一条就传不上去 ★★
 *   1. 字段名固定 **`file`** —— 不是 `upload`、不是 `image`
 *   2. 白名单只有 `jpg / jpeg / png / gif / webp`；单文件 10MB、整个请求 20MB
 *   3. 返回的是**绝对 URL**（`http://localhost:8081/img/xxx.png`）
 *
 * 第 3 点最容易被忽略：把绝对 URL 直接塞进 `<img :src>` 在当前开发环境
 * **看起来是好的**（本机 8081 确实能访问），但一旦换域名/加 HTTPS 就会挂。
 * 所以统一走 `resolveAssetUrl()` 剥掉 origin，改用同源相对路径。
 */

import { ElMessage } from 'element-plus'
import { checkUploadFile, resolveAssetUrl, uploadFile } from '@chargeops/shared'
import { http } from './http'

const UPLOAD_URL = '/api/admin/upload'

/** 上传前的本地校验结果。与后端文案逐字一致，避免同一件事两种说法。 */
export function validateFile(file: File): string | null {
  const check = checkUploadFile(file)
  return check.ok ? null : (check.message ?? '文件不符合要求')
}

/**
 * 上传一个文件，返回**同源化之后**的 URL。
 *
 * ★ 失败时返回 `null` 而**不抛异常** —— 这个函数被 `el-upload` 的
 *   `http-request` 钩子调用，钩子里抛异常只会让组件卡在 loading 状态，
 *   错误提示反而出不来。这里自己 toast 完直接返回。
 */
export async function uploadImage(file: File): Promise<string | null> {
  const invalid = validateFile(file)
  if (invalid) {
    ElMessage({ type: 'warning', message: invalid })
    return null
  }

  // ★ `uploadFile` 内部已经把 `{url}` 信封解成字符串了（见 shared/http/upload.ts）
  const url = await uploadFile(http, UPLOAD_URL, file).catch(() => '')

  if (!url) {
    ElMessage({ type: 'error', message: '上传失败，请重试' })
    return null
  }

  return resolveAssetUrl(url)
}

export { resolveAssetUrl }
