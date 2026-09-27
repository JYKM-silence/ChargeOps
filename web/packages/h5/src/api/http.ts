/**
 * 车主端 HTTP 客户端（单例）。
 *
 * ★ 与管理端是**两份独立实例**，只有参数不同 —— 逻辑全在
 *   `shared/http/createHttp.ts`，这里不重复实现。
 *
 * ★★ 与管理端的**三处实质差异**，每一处都不是偏好问题：★★
 *
 *   1. **请求头名是 `authentication`，不是 `token`**
 *      后端两个拦截器读的就是不同的头。写错了 → 401 且**响应体是空的**，
 *      前端只能看到「登录已失效」，根本猜不到是头名的问题。
 *      所以这里从 `TOKEN_HEADER.h5` 常量取，不手写字符串。
 *
 *   2. **TTL 是 7 天，不是 2 小时**
 *      而这也是「禁用车主不撤销 token」的窗口：拦截器**不查库**，
 *      被禁用的车主最长还能用 7 天。所以才有下面第 3 条。
 *
 *   3. **`accountLockedLogsOut: true`**（管理端是 `false`）
 *      车主端有「禁用」概念：任何请求返回 `AccountLocked` 文案就立刻登出。
 *      这是主动探针之外的**第二信号源** —— 两个都要有，因为
 *      禁用后用户可能不主动做任何操作，探针要等下次进页面才跑。
 *
 * ★ 依赖方向：`http.ts` **不 import router、也不 import store**（会成环）。
 *   401 的跳转由 `main.ts` 通过 `setAuthLostHandler` 注入。
 */

import { showFailToast } from 'vant'
import {
  MSG,
  TOKEN_HEADER,
  clearAllLocalData,
  createHttp,
  h5Session,
  invalidateAllOptions,
  shouldStaySilent,
  type ApiError,
  type AuthLostReason,
  type HttpClient,
} from '@chargeops/shared'

type AuthLostHandler = (reason: AuthLostReason) => void
let authLostHandler: AuthLostHandler | null = null

export function setAuthLostHandler(handler: AuthLostHandler): void {
  authLostHandler = handler
}

/**
 * ★ 车主端**不做**「技术性文案」的翻译。
 *   后端给车主的文案本来就是面向用户的（「余额不足，请先充值」），
 *   前端重写一遍只会制造两套说法。这里只加一条管理端没有的：
 *   账号被禁用时要说清楚「不是你操作错了」。
 */
function messageOf(error: ApiError): string {
  if (error.kind === 'biz' && error.message === MSG.ACCOUNT_LOCKED) {
    return '该账号已被禁用，请联系客服'
  }
  return error.message
}

export const http: HttpClient = createHttp({
  // ★ 裸头名，无 `Bearer ` 前缀 —— 加了前缀后端 parse 失败，且 401 空体
  tokenName: TOKEN_HEADER.h5,
  getToken: () => h5Session.getToken(),
  baseURL: import.meta.env.VITE_API_BASE || '',
  // ★ 车主端有「禁用」概念，命中该文案就登出（管理端是 false）
  accountLockedLogsOut: true,
  onAuthLost: (reason) => {
    // 幂等由 createHttp 内部按 token 去重，这里放心做整套动作
    h5Session.clear()
    invalidateAllOptions()
    clearAllLocalData()
    authLostHandler?.(reason)
  },
  onError: (error) => {
    // 被动作锁拦下 / 请求被主动取消 → 静默
    if (shouldStaySilent(error)) return
    // ★ 这里**必须**用 Vant 的 toast，不能用 Element Plus ——
    //   H5 包里没有 element-plus（它只在 admin 的依赖里），
    //   引了会在构建时报「模块不存在」，或者更糟：装成依赖后打进
    //   一个 200KB 的、与 Vant 视觉完全打架的组件库。
    //   ★ 也不要手动引 `vant/es/toast/style` —— 我们用 `@vant/auto-import-resolver`
    //     之外的**全量引入**（见 main.ts），样式已经全局在了。
    showFailToast({ message: messageOf(error), duration: 3000 })
  },
})
