/**
 * 管理端登录态。
 *
 * ★ 凭证存在 `SessionStore`（localStorage）里，**不在 pinia 里** ——
 *   因为 `http.ts` 需要一个「同步取 token」的函数（拦截器是同步的），
 *   而 pinia 的 state 在没有 app 实例时取不到。
 *   pinia 这里只镜像一份**给界面看**的数据（昵称、角色）。
 */

import { defineStore } from 'pinia'
import { ADMIN_TOKEN_TTL_MS, SessionStore, adminSession, invalidateAllOptions } from '@chargeops/shared'
import { staffLogin, type StaffLoginDTO } from '@/api/auth'
import type { StaffLoginVO } from '@/api/types'

export const useSessionStore = defineStore('admin-session', {
  state: () => ({
    /** 与服务端同步过的最新登录信息（刷新后从 localStorage 恢复）。 */
    staff: null as StaffLoginVO | null,
    /** 已恢复过一次，避免每次路由守卫都读 localStorage。 */
    restored: false,
    /**
     * ★★ 镜像一份 token，**唯一的作用是给 `isLoggedIn` 一个响应式依赖** ★★
     *
     *   凭证本身仍然只存在 `SessionStore`（localStorage）里 —— 这条没变，
     *   `http.ts` 依旧同步从 `adminSession.getToken()` 取，不经过 pinia。
     *
     *   ★ 为什么不能直接写 `isLoggedIn: () => adminSession.getToken() !== null`：
     *     pinia 的 getter 就是 Vue 的 `computed`，而 computed **会把结果缓存住**，
     *     只在它的**响应式依赖**变化时才重算。`getToken()` 读的是 localStorage，
     *     不是响应式数据 —— 于是那个 getter **一个依赖都没有**，
     *     首次求值之后**永远返回那一刻的结果**。
     *
     *   ★★ 后果不是「慢一拍」，是**登录整个失效** ★★
     *     首屏导航时还没登录，算得 `false` 并缓存；等 `login()` 把会话写进
     *     localStorage 之后，**没有任何响应式依赖发生变化**，computed 不失效，
     *     守卫读到的仍然是缓存的 `false` → `router.replace('/dashboard')`
     *     当场被打回 `/login`。用户看到的正是「提示了登录成功，但界面跳不过去」。
     *     实测轨迹里可以直接看到：`setItem` 写入会话之后，守卫**根本没有再读 localStorage**。
     *
     *   ★ 所以每次会话变化都必须同步这里：`restore()` / `login()` / `logout()`
     *     三处一个都不能漏，漏了就会退回上面那个 bug。
     */
    token: null as string | null,
  }),

  getters: {
    // ★ 读镜像字段（响应式），**不要**改回 `adminSession.getToken()` —— 理由见 state 里的注释
    isLoggedIn: (state) => state.token !== null,
    displayName: (state) => state.staff?.name || state.staff?.username || '未登录',
  },

  actions: {
    /** 从 localStorage 恢复（应用启动时调一次）。 */
    restore(): void {
      if (this.restored) return
      const session = adminSession.get()
      if (session) {
        // ★ 只有 token 是可信的；`subject`/`displayName` 是上次登录时顺手存的快照，
        //   可能已经过时（改了名字）。所以**不**把它们当作权限依据，只用于首屏显示。
        this.staff = {
          id: Number(session.subject) || 0,
          username: session.displayName,
          name: session.displayName,
          role: 0,
          stationId: null,
          token: session.token,
        }
      }
      // ★ 会话变化 → 同步镜像。★ 用 getToken()（会判过期）而不是 session.token：
      //   过期的凭证不该让 isLoggedIn 变成 true，否则又会「进得去页面、每个请求都 401」。
      this.token = adminSession.getToken()
      this.restored = true
    },

    async login(payload: StaffLoginDTO): Promise<void> {
      const vo = await staffLogin(payload)
      if (!vo?.token) {
        // 后端成功时一定带 token；没有就是契约变了，别静默地「登录成功但进不去」
        throw new Error('登录响应缺少 token')
      }

      adminSession.set(
        SessionStore.create(vo.token, ADMIN_TOKEN_TTL_MS, vo.id, vo.name || vo.username, Date.now()),
      )
      // ★ 换账号必须清下拉缓存，否则会短暂显示上一个账号的数据
      invalidateAllOptions()

      this.staff = vo
      // ★★ 这一行是「登录之后能跳转」的关键 ★★
      //   少了它，localStorage 里就算写进去了，`isLoggedIn` 也还是缓存的旧值 —— 见 state 里 token 的注释。
      this.token = vo.token
      this.restored = true
    },

    logout(): void {
      adminSession.clear()
      invalidateAllOptions()
      this.staff = null
      // ★ 同理，不清这里的话登出后 isLoggedIn 还是 true，守卫会把人留在需要登录的页面上
      this.token = null
      // restored 保持 true：已经恢复过了，不必再读
    },
  },
})
