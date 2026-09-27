/**
 * 管理端 HTTP 客户端（单例）。
 *
 * ★★ 依赖方向刻意做成**无环**的，别改：★★
 *
 *     main.ts → router → stores/session → api/auth → api/http
 *
 *   `http.ts` **不 import router、也不 import 任何 store**。
 *   原因是它需要一个「401 时跳登录」的能力，而如果直接 import router，
 *   就会形成 `http → router → store → http` 的环 ——
 *   这类环在 ESM 里表现为**某个模块在初始化时拿到 undefined**，
 *   报错信息和你改的地方通常没有关系。
 *
 *   → 所以这里留一个「注册回调」的口子（`setAuthLostHandler`），
 *     由 `main.ts` 在路由建好之后注入。依赖方向仍然是单向的。
 */

import { ElMessage } from 'element-plus'
import {
  ApiError,
  DEFAULT_PAGE_SIZE_UI,
  MAX_PAGE_SIZE,
  adminSession,
  clearAllLocalData,
  createHttp,
  invalidateAllOptions,
  MSG,
  TOKEN_HEADER,
  shouldStaySilent,
  type AuthLostReason,
  type HttpClient,
} from '@chargeops/shared'

/** 登录态丢失的处理函数。由 main.ts 注入（见文件头说明）。 */
type AuthLostHandler = (reason: AuthLostReason) => void
let authLostHandler: AuthLostHandler | null = null

export function setAuthLostHandler(handler: AuthLostHandler): void {
  authLostHandler = handler
}

/**
 * 错误提示文案。
 *
 * ★ 三条特殊文案要**说人话**：
 *   · 乐观锁冲突：文案本身已经是「请刷新后重试」，直接显示是对的，
 *     但这里把它延长成一句有动作指引的话 —— 用户需要知道**怎么做**。
 *   · 前缀匹配过多：这是**用户操作方式的问题**，要给「输入更长的前缀」这个建议，
 *     而不是把后端的原文（「该手机号前缀匹配到的车主过多」）丢出来了事。
 */
function messageOf(error: ApiError): string {
  if (error.kind === 'biz' && error.message === MSG.CONCURRENT_MODIFIED) {
    return '这条记录刚被其他人改过。请关闭弹窗后重新打开，确认最新内容再提交。'
  }
  return error.message
}

export const http: HttpClient = createHttp({
  // ★ 裸头名，无 `Bearer ` 前缀 —— 见 shared 的 session.ts
  tokenName: TOKEN_HEADER.admin,
  getToken: () => adminSession.getToken(),
  baseURL: import.meta.env.VITE_API_BASE || '',
  // 管理端没有「禁用」概念（那套是车主端的），不要误登出
  accountLockedLogsOut: false,
  onAuthLost: (reason) => {
    // ★ 幂等由 createHttp 内部保证（按 token 去重），这里可以放心做整套动作
    adminSession.clear()
    // 换账号后旧的选项缓存必须清掉，否则下拉里会短暂出现上一个账号看到的数据
    invalidateAllOptions()
    clearAllLocalData()
    authLostHandler?.(reason)
  },
  onError: (error) => {
    // 被动作锁拦下 / 请求被主动取消 → 静默，不要打断用户
    if (shouldStaySilent(error)) return
    ElMessage({ type: 'error', message: messageOf(error), duration: 4000, showClose: true })
  },
})

/** 分页请求参数的默认值，统一从一处取。 */
export const pageDefaults = {
  pageNum: 1,
  pageSize: DEFAULT_PAGE_SIZE_UI,
  maxPageSize: MAX_PAGE_SIZE,
} as const
