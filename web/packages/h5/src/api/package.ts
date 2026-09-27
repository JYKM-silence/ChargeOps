/**
 * 充电套餐（车主端）。
 */

import { http } from './http'
import type { AvailablePackageVO, MemberPackageVO } from '@chargeops/shared'

const BASE = '/api/member/package'

/**
 * 某站点可买的套餐。
 *
 * ★★ **必须带 `stationId`** ★★
 *   套餐分两类：`stationId` 为空的「通用套餐」，和绑定了某个站点的
 *   「站内套餐」。后端按站点过滤 —— 所以**没选站点就没有可用套餐**。
 *   这就是「套餐」Tab 指向「我的套餐」而不是购买页的原因
 *   （见 `layouts/TabLayout.vue` 的注释）。
 */
export function listAvailablePackages(
  stationId: number | string,
): Promise<AvailablePackageVO[] | null> {
  return http.get<AvailablePackageVO[]>(`${BASE}/available`, { stationId })
}

/**
 * 购买。
 *
 * ★★ 扣的是**钱包余额**，而且后端**没有幂等键** ★★
 *   `WalletTransactionType.PACKAGE_PURCHASE` 写流水时 `order_id` 是 `NULL`，
 *   而唯一索引 `uk_txn_order_type` 是 `(order_id, type)` ——
 *   **唯一索引不约束 NULL**，所以它对套餐购买完全不起作用。
 *   也就是说连点两次会真的扣两次钱。
 *   → 调用点**必须**套 `withActionLock`，并且要二次确认（金额要显示出来）。
 *
 * ★ 返回的是**新建的权益**（`MemberPackageVO`），可用来做「购买成功」的确认展示。
 */
export function purchasePackage(packageId: number | string): Promise<MemberPackageVO | null> {
  return http.post<MemberPackageVO>(`${BASE}/${packageId}/purchase`)
}

/**
 * 我的权益（**裸数组**，不分页）。
 *
 * ★★ 渲染时**不能只信 `status`** ★★
 *   权益过期是**定时任务**扫的，30 分钟一轮。所以存在
 *   「已经过期了但 `status` 还是 1（生效中）」最长 30 分钟的状态。
 *   → 列表**必须同时展示 `expireTime`**，并且客户端可以拿它和当前时间比，
 *     自己先标一个「已到期（待系统确认）」。
 */
export function listMyPackages(): Promise<MemberPackageVO[] | null> {
  return http.get<MemberPackageVO[]>(`${BASE}/mine`)
}
