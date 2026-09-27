/**
 * 车主端登录态 + **禁用探针**。
 *
 * ★ 凭证存在 `h5Session`（localStorage）里，**不在 pinia 里** ——
 *   `http.ts` 需要一个同步取 token 的函数（拦截器是同步的）。
 *   pinia 只镜像一份给界面看的数据。
 *
 * ★★ 禁用探针为什么必须存在 ★★
 *
 *   后端的鉴权拦截器**不查库** —— 它只验 JWT 签名和有效期。
 *   所以「禁用车主」这个操作**不会撤销已发出的 token**，
 *   被禁用的车主最长还能用满 **7 天**（车主端 TTL）。
 *
 *   后端的补救是在**资金端点**上拦住（那些会查库），但**只读接口不拦** ——
 *   被禁用的人还能一直看自己的订单和余额。这个窗口客观存在，前端关不掉，
 *   只能把它压到「不主动操作就发现不了」：
 *
 *     ① **探针**：`GET /api/member/me`，判定条件是
 *        **HTTP 200 且 `status === 0`** —— ★ 不能只看 200。
 *        后端禁用是「返回成功，但 body 里的 status 是 0」，
 *        只看 HTTP 状态码会 100% 漏判。
 *     ② **任何请求**返回 `AccountLocked` 文案 → 由 `http.ts` 的
 *        `accountLockedLogsOut: true` 兜底。
 *
 *   ★ 两条都要有：探针要等用户进页面才跑，而兜底要等用户发请求才触发，
 *     单独任何一条都会留下「什么都没做所以一直没发现」的空档。
 *
 * ★★ 探针必须**软失败** ★★
 *   探针本身网络错误/超时**绝不登出**。否则在地铁里断网一次，
 *   用户就会被踢出登录 —— 而他的登录态其实完全有效。
 *   只有「明确拿到了 status === 0」才登出。
 */

import { defineStore } from 'pinia'
import {
  MEMBER_TOKEN_TTL_MS,
  SessionStore,
  h5Session,
  invalidateAllOptions,
  type MemberLoginVO,
  type MemberProfileVO,
} from '@chargeops/shared'

/**
 * ★ 用常量而不是裸 `0`：`MEMBER_STATUS` 是个 `Dict`（有 `.list`/`.label()`），
 *   **没有** `MEMBER_STATUS.NORMAL` 这种具名键 —— 写成那样会静默拿到
 *   `undefined`，于是 `status === undefined` 恒为 false，**禁用永远发现不了**。
 *   所以这里显式取判据码，并让它在两处引用点共用。
 */
const MEMBER_STATUS_DISABLED = 0
import { getProfile } from '@/api/member'
import { memberLogin, memberRegister, type MemberLoginDTO, type MemberRegisterDTO } from '@/api/auth'

/**
 * 探针节流窗口。
 *
 * ★ 为什么是 30 秒：探针是一次真实请求。如果在路由守卫里不做节流，
 *   用户在 Tab 之间来回切 5 次就是 5 个请求。30 秒足够覆盖
 *   「被禁用后用户继续操作」的真实场景，又不会把 /me 变成轮询。
 */
const PROBE_THROTTLE_MS = 30_000

export const useSessionStore = defineStore('h5-session', {
  state: () => ({
    /** 与服务端同步过的资料（刷新后从 localStorage 恢复成 null，等探针填）。 */
    profile: null as MemberProfileVO | null,
    /** 上次探针的时刻（毫秒）。0 = 从没跑过 → 第一次进页面必跑。 */
    lastProbeAt: 0,
    /** 探针正在进行中，避免并发重复发。 */
    probing: false,
    /**
     * ★★ 镜像一份 token，**唯一的作用是给 `isLoggedIn` 一个响应式依赖** ★★
     *
     *   凭证本身仍然只存在 `h5Session`（localStorage）里 —— 这条没变，
     *   `http.ts` 依旧同步从 `h5Session.getToken()` 取，不经过 pinia。
     *
     *   ★ 为什么不能直接写 `isLoggedIn: () => h5Session.getToken() !== null`：
     *     pinia 的 getter 就是 Vue 的 `computed`，而 computed **会把结果缓存住**，
     *     只在它的**响应式依赖**变化时才重算。`getToken()` 读的是 localStorage，
     *     不是响应式数据 —— 那个 getter **一个依赖都没有**，
     *     首次求值之后**永远返回那一刻的结果**。
     *
     *   ★★ 后果不是「慢一拍」，是**登录整个失效** ★★
     *     首屏算得 `false` 并缓存；`_applyLogin()` 把会话写进 localStorage 后
     *     **没有任何响应式依赖变化**，computed 不失效，守卫读到的还是缓存的 `false`
     *     → 登录后当场被打回 `/login`。用户看到的就是「提示登录成功，但界面不动」。
     *
     *   ★ 所以每次会话变化都必须同步这里：`restore()` / `_applyLogin()` / `logout()`
     *     三处一个都不能漏。`probe()` 里那句 `if (!this.isLoggedIn)` 也吃这个值。
     */
    token: null as string | null,
  }),

  getters: {
    // ★ 读镜像字段（响应式），**不要**改回 `h5Session.getToken()` —— 理由见 state 里的注释
    isLoggedIn: (state) => state.token !== null,
    displayName: (state) => state.profile?.nickname || state.profile?.phone || '车主',
    avatar: (state) => state.profile?.avatar ?? '',
  },

  actions: {
    /** 从 localStorage 恢复一个「占位资料」，让首屏不至于显示空白。 */
    restore(): void {
      // ★★ 镜像 token 必须**无条件**同步，而且必须放在下面两个 early-return **之前** ★★
      //   放在后面的话，「已经恢复过 profile」和「压根没登录」这两条路径都会跳过它，
      //   于是刷新后 token 一直是 null —— 又变回「登录了却进不去」。
      //   ★ 用 getToken()（会判过期）而不是 session.token：过期的凭证不该算已登录。
      this.token = h5Session.getToken()

      if (this.profile) return
      const session = h5Session.get()
      if (!session) return
      // ★ 这里造的是一个**只有 id/phone 的占位对象**，不是真资料 ——
      //   真资料由探针填。字段缺的用合理默认值，`status` 给 1（正常），
      //   因为「从本地恢复」这件事本身不能得出任何关于禁用的结论。
      this.profile = {
        id: Number(session.subject) || 0,
        phone: session.displayName,
        nickname: session.displayName,
        avatar: null,
        gender: 0,
        genderLabel: '',
        // 给「正常」：从本地恢复这件事本身推不出任何关于禁用的结论，
        // 真判据由探针填（见 probe()）。给 0 会让首屏先闪一下「已禁用」。
        status: 1,
        statusLabel: '',
        lastLoginTime: null,
        createTime: '',
      }
    },

    /**
     * 写入登录结果。注册也走这里（后端注册即登录，返回同样的 VO）。
     *
     * ★ 参数收 `MemberLoginVO | null`：`http.post` 的返回类型允许 null
     *   （后端 code:1 但 data 为空时会拿到 null），所以这里必须显式处理 ——
     *   **不能靠 `!` 断言掉**，那正是「登录成功但进不去」这类问题的来源。
     */
    _applyLogin(vo: MemberLoginVO | null): void {
      if (!vo?.token) {
        // 后端成功时一定带 token；没有就是契约变了，别静默地「登录成功但进不去」
        throw new Error('登录响应缺少 token')
      }
      h5Session.set(
        SessionStore.create(vo.token, MEMBER_TOKEN_TTL_MS, vo.id, vo.nickname || vo.phone, Date.now()),
      )
      // ★ 换账号必须清下拉缓存，否则会短暂显示上一个账号的数据
      invalidateAllOptions()
      this.lastProbeAt = 0
      this.profile = null
      // ★★ 这一行是「登录之后能跳转」的关键 ★★
      //   少了它，localStorage 里就算写进去了，`isLoggedIn` 也还是缓存的旧值 —— 见 state 里 token 的注释。
      this.token = vo.token
      this.restore()
    },

    async login(payload: MemberLoginDTO): Promise<void> {
      this._applyLogin(await memberLogin(payload))
    },

    async register(payload: MemberRegisterDTO): Promise<void> {
      this._applyLogin(await memberRegister(payload))
    },

    async logout(): Promise<void> {
      h5Session.clear()
      invalidateAllOptions()
      this.profile = null
      this.lastProbeAt = 0
      this.probing = false
      // ★ 同理，不清这里的话登出后 isLoggedIn 还是 true，守卫会把人留在需要登录的页面上
      this.token = null
    },

    /**
     * 拉一次 /me 并判定禁用。**软失败** —— 网络错误不登出。
     * @returns 'ok' 正常 / 'locked' 已禁用 / 'unknown' 没拿到结论（网络问题）
     */
    async probe(force = false): Promise<'ok' | 'locked' | 'unknown'> {
      if (!this.isLoggedIn) return 'unknown'
      if (this.probing) return 'unknown'
      if (!force && Date.now() - this.lastProbeAt < PROBE_THROTTLE_MS) return 'unknown'

      this.probing = true
      try {
        const vo = await getProfile()
        this.lastProbeAt = Date.now()

        if (!vo) return 'unknown'
        this.profile = vo

        // ★★ 判定条件是「拿到了资料 且 status 是 0」★★
        //   不能只看请求成功 —— 禁用是 HTTP 200 + status:0。
        if (vo.status === MEMBER_STATUS_DISABLED) {
          await this.logout()
          return 'locked'
        }
        return 'ok'
      } catch {
        // ★ **软失败**：断网/超时/5xx 一律不登出。
        //   只有明确拿到 status===0 才算禁用 —— 见文件头。
        return 'unknown'
      } finally {
        this.probing = false
      }
    },

    /** 路由守卫里的节流版探针（不 await，不阻塞导航）。 */
    probeIfStale(): void {
      void this.probe(false)
    },
  },
})
