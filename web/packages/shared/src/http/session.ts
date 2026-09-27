/**
 * 登录态存储。
 *
 * ★★ 两端是**两套完全独立的凭证**，不要试图合并 ★★
 *
 *   | 端     | 请求头名          | localStorage key             | 后端 TTL |
 *   |--------|-------------------|------------------------------|----------|
 *   | 管理端 | `token`           | `chargeops.admin.session`    | 2 小时   |
 *   | 车主端 | `authentication`  | `chargeops.h5.session`       | 7 天     |
 *
 *   两个 key 不同不是洁癖：同一个浏览器里同时开管理端和车主端是**常态**
 *   （演示时就是一个窗口管后台、一个窗口开 H5），共用 key 会互相顶掉登录态。
 *
 * ★★ 请求头名是**裸 token，不加 `Bearer ` 前缀**。★★
 *   后端拦截器读的就是 `request.getHeader("token")` / `("authentication")`，
 *   原样取值去 parse。加了前缀后 parse 失败 → 401，且**401 的响应体是空的**，
 *   前端只能看到「登录已失效」，排查时根本不会想到是前缀问题。
 *   所以这里用常量固化，并在 http/createHttp.ts 里也只从常量取。
 */

/** 后端 TTL：管理端 2 小时 = 7200000ms（`RedisConstant` / JWT 配置）。 */
export const ADMIN_TOKEN_TTL_MS = 2 * 60 * 60 * 1000

/** 后端 TTL：车主端 7 天 = 604800000ms。禁用车主不撤销 token，就是这个窗口。 */
export const MEMBER_TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000

/** localStorage key —— 两端各一份。 */
export const SESSION_KEY = {
  admin: 'chargeops.admin.session',
  h5: 'chargeops.h5.session',
} as const

/** 请求头名 —— **裸值，无前缀**。 */
export const TOKEN_HEADER = {
  admin: 'token',
  h5: 'authentication',
} as const

export interface Session {
  token: string
  /** 客户端推算的过期时刻（毫秒时间戳）。**仅用于省一次注定失败的请求**。 */
  expiresAt: number
  /** 主体标识：管理端是 staffId，车主端是 memberId。用于换账号时清理缓存。 */
  subject: string
  /** 展示用（顶栏昵称）。不是凭证，可以过时。 */
  displayName: string
  loginAt: number
}

/**
 * 本地时钟可能比服务端快/慢，直接信 `expiresAt` 会「token 还有 20 分钟却已登出」
 * 或「早就过期还在发请求」。留 60 秒余量，把边界情况全部推给服务端判定。
 */
const CLOCK_SKEW_MS = 60_000

/**
 * 会话存储。localStorage 不可用时（无痕模式、被策略禁用、配额满）
 * **自动退化为内存存储**，而不是抛异常 —— 退化的后果只是「刷新页面要重新登录」，
 * 比整个应用起不来轻得多。
 */
export class SessionStore {
  private readonly key: string
  private memory: Session | null = null
  private readonly persistent: boolean

  constructor(key: string) {
    this.key = key
    this.persistent = SessionStore.probe()
  }

  /** 探测 localStorage 是否真的**可写**（存在 ≠ 可写：Safari 无痕下 setItem 会抛）。 */
  private static probe(): boolean {
    try {
      const probeKey = '__chargeops_probe__'
      window.localStorage.setItem(probeKey, '1')
      window.localStorage.removeItem(probeKey)
      return true
    } catch {
      return false
    }
  }

  get(): Session | null {
    if (!this.persistent) return this.memory
    try {
      const raw = window.localStorage.getItem(this.key)
      if (!raw) return null
      const parsed = JSON.parse(raw) as Partial<Session>
      if (typeof parsed?.token !== 'string' || !parsed.token) return null
      return {
        token: parsed.token,
        expiresAt: typeof parsed.expiresAt === 'number' ? parsed.expiresAt : 0,
        subject: typeof parsed.subject === 'string' ? parsed.subject : '',
        displayName: typeof parsed.displayName === 'string' ? parsed.displayName : '',
        loginAt: typeof parsed.loginAt === 'number' ? parsed.loginAt : 0,
      }
    } catch {
      // 存的东西坏了（手工改过 / 上个版本的结构）→ 当作没登录，并清掉
      this.clear()
      return null
    }
  }

  set(session: Session): void {
    if (!this.persistent) {
      this.memory = session
      return
    }
    try {
      window.localStorage.setItem(this.key, JSON.stringify(session))
    } catch {
      // 配额满 / 隐私策略 → 退化为内存，本次会话仍可用
      this.memory = session
    }
  }

  clear(): void {
    this.memory = null
    if (!this.persistent) return
    try {
      window.localStorage.removeItem(this.key)
    } catch {
      /* 清不掉也没办法，至少内存里已经没了 */
    }
  }

  /** 取 token；没有或**客户端推算已过期**则返回 null。 */
  getToken(): string | null {
    const s = this.get()
    if (!s) return null
    if (this.isExpired(s)) return null
    return s.token
  }

  /**
   * 是否已过期（按客户端推算）。
   *
   * ★ `expiresAt === 0` 视为**不过期**：老版本存下来的会话没有这个字段，
   *   与其把人踢出去，不如让它去请求一次，由服务端的 401 说了算。
   */
  isExpired(session: Session, now = Date.now()): boolean {
    if (!session.expiresAt) return false
    return now + CLOCK_SKEW_MS >= session.expiresAt
  }

  /** 用后端返回的 token 造一条会话记录。 */
  static create(
    token: string,
    ttlMs: number,
    subject: string | number,
    displayName: string,
    now = Date.now(),
  ): Session {
    return {
      token,
      expiresAt: now + ttlMs,
      subject: String(subject),
      displayName,
      loginAt: now,
    }
  }
}

/** 管理端会话存储（单例）。 */
export const adminSession = new SessionStore(SESSION_KEY.admin)

/** 车主端会话存储（单例）。 */
export const h5Session = new SessionStore(SESSION_KEY.h5)

/**
 * 清空**所有**本地业务缓存。
 *
 * ★ 登出时必须调用，不能只清 session：否则换个账号登录后，
 *   下拉选项缓存、上一次的列表数据还会短暂显示**上一个账号的**数据。
 *   这类串号在演示时非常刺眼（刚登出的车主信息还挂在页面上）。
 */
export function clearAllLocalData(): void {
  try {
    // 只清本前缀的 key，不动同一域名下可能存在的其他应用数据
    const doomed: string[] = []
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const k = window.localStorage.key(i)
      if (k && k.startsWith('chargeops.')) doomed.push(k)
    }
    doomed.forEach((k) => window.localStorage.removeItem(k))
  } catch {
    /* 清不掉就清不掉，session 已经清过了 */
  }
}
