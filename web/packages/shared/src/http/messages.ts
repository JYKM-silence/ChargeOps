/**
 * 需要**精确匹配**的后端文案常量。
 *
 * ★★ 只放「匹配上之后要触发一段特殊逻辑」的文案，不是文案字典。★★
 *   普通文案直接展示给用户就好，不要在这里抄一遍 —— 抄了就有两个真源，
 *   后端改一个字，这里的匹配会**静默失效**（表现为「那个提示还在弹，
 *   但功能不触发了」，最难查的一类）。
 *
 * ★ 一律用 `===` 或 `startsWith`，**禁止 `includes`**。
 *   模糊匹配会让「一句更长的话里恰好包含了这句」也命中，
 *   例如后端某天文案变成「登录状态已失效，请重新登录后重试」时，
 *   includes 仍然命中（这是好事），但「该账号已被禁用，请联系客服处理」
 *   也可能被别的句子包含而误触发强制登出。
 */

export const MSG = {
  /**
   * 401 之外的第二条登出路径。
   *
   * 拦截器只验签名、从不查库；而 Service 层发现 `BaseContext.getCurrentId()` 为 null
   * （或车主被禁用后走 `assertActive`）时会抛业务异常，形状是 **HTTP 200 + code:0**。
   * 与拦截器那条 401 是**两条完全不同的路径**，两条都要处理。
   */
  NOT_LOGGED_IN: '登录状态已失效，请重新登录',

  /**
   * 车主被禁用。资金端点（钱包 3 个 + 订单 4 个）在 Service 里每次重查 `member.status`
   * 后抛这个。H5 收到它应当强制登出 —— 这是「禁用不撤销 token」那个 7 天窗口里
   * 唯一能立刻发现的第二信号源（第一信号源是 `/me` 探针看 status=0）。
   */
  ACCOUNT_LOCKED: '该账号已被禁用，请联系客服',

  /** 充电枪乐观锁冲突，全项目唯一一处。收到后应提示「刷新并重填」而不是普通 toast。 */
  CONCURRENT_MODIFIED: '数据已被他人修改，请刷新后重试',

  /**
   * 订单列表按手机号**前缀**搜索，命中过多时后端直接拒绝。
   * 所以车主搜索框必须 ≥6 位数字才发请求（见 domain/queryDefaults.ts）。
   */
  MEMBER_PREFIX_TOO_BROAD: '该手机号前缀匹配到的车主过多',

  /** 订单状态机的 CAS 拒绝。文案较长且**以订单号开头**，所以用 startsWith 匹配。 */
  ORDER_STATUS_CONFLICT_PREFIX: '订单当前状态不允许该操作',
} as const

/** 判断一条后端 msg 是否命中某个特殊文案（前缀匹配版本）。 */
export function msgStartsWith(msg: string | null | undefined, prefix: string): boolean {
  return typeof msg === 'string' && msg.startsWith(prefix)
}

/** 判断一条后端 msg 是否**恰好**等于某个特殊文案。 */
export function msgEquals(msg: string | null | undefined, exact: string): boolean {
  return msg === exact
}
