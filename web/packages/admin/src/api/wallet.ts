/**
 * 车主钱包接口（管理端）。
 *
 * ★★ 这个文件只有**一个**接口，而且它读不到余额 ★★
 *
 *   管理端全项目没有任何「查车主钱包」的入口：充值和流水查询都在车主端
 *   （`/api/member/wallet/**`，那是「车主看自己的账」），管理端唯一需要的是
 *   「把一笔账改平」。所以这里只有 `adjust`。
 *
 *   直接后果：**调账前余额在管理端是不可知的**。唯一能看到余额的时机，
 *   是 `adjust` 的**响应里**带回的那个调账后的 `WalletVO`。
 *   UI 必须把这件事说出来（见 `WalletAdjustView` 的常驻说明），
 *   而不是留一个显示 `--` 的余额位。
 *
 * ★ 后端还有一个**有意留下的缺口**：没有「管理端查任意车主流水」的接口。
 *   理由是那需要先想清楚数据权限，而项目现在只有「是员工 / 不是员工」的二元判断。
 *   所以调账之后要核对，目前只能靠车主截图或直接查库 —— 这是已知限制，
 *   不要在前端「想办法」拼一个（拼不出来）。
 */

import { http } from './http'
import type { WalletAdjustDTO, WalletVO } from '@chargeops/shared'

const WALLET_BASE = '/api/admin/wallet'

/**
 * 管理员调账：加钱或扣钱。
 *
 * ★★ 金额**带符号**：正数加钱、负数扣钱 —— 调用方负责拼符号 ★★
 *   UI 上不要让人手输负号（`-` 与全角 `－` 必然混淆），
 *   而是「增加 / 扣减」两选一 + 一个正数输入框，符号由代码拼。
 *
 * ★★ 后端 `@DecimalMin/@DecimalMax` **拦不住 0** ★★
 *   （0 落在 ±100000 区间内），0 会在 Service 里被单独拒绝，
 *   但那时已经白跑一次请求。前端**必须**自己用 `isAdjustableAmount()` 拦住 0：
 *   一条金额为 0 的流水什么都不改变，却会污染对账。
 *
 * ★ `remark` **必填**（`@NotBlank`，200 字上限）。这是全项目唯一一笔
 *   「背后没有任何业务对象」的资金变动 —— 没有订单、没有权益、没有套餐，
 *   流水上只有 `type = 7` 和这行字。半年后审计的人能拿到的全部信息就是它。
 *
 * ★★ 调用方**必须**包 `withActionLock` ★★
 *   这条路径没有幂等键，同一个 payload 提交两次就是两条流水。
 *
 * @returns 调账**之后**的钱包（余额 / 冻结 / 累计充值）—— 这是运营唯一
 *          能对着念一遍、确认这次操作真的生效了的数字。
 */
export function adjustWallet(memberId: number, dto: WalletAdjustDTO): Promise<WalletVO | null> {
  return http.post<WalletVO>(`${WALLET_BASE}/${memberId}/adjust`, dto)
}
