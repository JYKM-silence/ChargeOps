/**
 * 车主钱包。
 */

import { http } from './http'
import type { Paged, WalletRechargeDTO, WalletTransactionPageQuery, WalletTransactionVO, WalletVO } from '@chargeops/shared'

const BASE = '/api/member/wallet'

/** 我的钱包（余额 + 冻结）。 */
export function getWallet(): Promise<WalletVO | null> {
  return http.get<WalletVO>(BASE)
}

/**
 * 充值。
 *
 * ★★ 这是**模拟充值** —— 后端没接真实支付，注释里写明了 ★★
 *   演示时点一下余额就加上去，属于预期行为，不是 bug。
 *   所以 UI 上**不要**画一个微信/支付宝的支付流程，
 *   那会让人以为真的会扣钱。
 */
export function recharge(dto: WalletRechargeDTO): Promise<WalletVO | null> {
  return http.post<WalletVO>(`${BASE}/recharge`, dto)
}

/**
 * 余额明细。
 *
 * ★★ **只有 `pageNum` / `pageSize`** ★★
 *   后端 `WalletTransactionPageQueryDTO` 没有 type / 时间范围参数 ——
 *   所以**做不了「只看充值」这类筛选**。
 *   别放假筛选器，直接在页面上写明「不支持按类型筛选」。
 *
 * ★ `amount` **带符号**（冻结是负数），颜色看符号、文案看 `type`。
 */
export function pageTransactions(
  query: WalletTransactionPageQuery,
): Promise<Paged<WalletTransactionVO>> {
  return http.page<WalletTransactionVO>(`${BASE}/transactions`, query)
}
