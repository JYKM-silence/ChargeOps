/**
 * 充电枪接口（`/api/admin/connector`）。五个接口的形状见 `api/station.ts`。
 *
 * ★★ 这是全项目**唯一**有乐观锁的实体，`updateConnector` 的 `version` 是承重的 ★★
 *
 *   后端执行的是条件更新 `UPDATE connector SET ... WHERE id = ? AND version = ?`，
 *   影响行数为 0 时抛「数据已被他人修改，请刷新后重试」。所以：
 *
 *   · `version` **必须**是打开编辑弹窗时那条记录的 `version`，
 *     **不能**在提交前重新 GET 一次拿最新值 —— 那就等于把检查变成一句恒真的
 *     装饰，两个管理员同时改同一把枪时后提交的人会静默覆盖前一个。
 *   · 冲突时**页面不要重复弹**：全局 `http` 的 `onError` 已经把这句中文文案
 *     加工成带行动指引的提示了（见 `api/http.ts` 的 `messageOf`）。
 *
 * ★★ 管理员能设的枪状态只有 4 个：`{0 离线, 1 空闲, 4 故障, 5 维护}` ★★
 *   `2 已占用` / `3 充电中` 归订单状态机所有，手工设会破坏
 *   「有订单的枪才可能是占用/充电中」这条不变量（唯一索引 `uk_connector_current_order`）。
 *   表单下拉用 `CONNECTOR_ADMIN_SETTABLE_OPTIONS`，**不要**用 `CONNECTOR_STATUS.options()`。
 *
 * ★ 新建时**不能**指定状态：`ConnectorDTO` 里没有 `status`，
 *   新枪固定为 `0 离线`（它还没和任何硬件握过手）。所以下面没有 `status` 参数。
 */

import type {
  ConnectorDTO,
  ConnectorPageQuery,
  ConnectorUpdateDTO,
  ConnectorVO,
  Paged,
} from '@chargeops/shared'
import { http } from './http'

const BASE = '/api/admin/connector'

/**
 * 分页查询。三种入口共用一个接口：
 * 某个桩下的枪（`chargerId`）、某个站下的枪（`stationId`）、
 * 以及「用户说他扫的那把枪坏了，只知道枪号」（`gunNo`，模糊匹配）。
 */
export function pageConnectors(query: ConnectorPageQuery): Promise<Paged<ConnectorVO>> {
  return http.page<ConnectorVO>(`${BASE}/page`, { ...query })
}

/** 详情。★ 返回里带 `version` —— 编辑时要原样回传的那个值就是从这里（或列表行）来的。 */
export function getConnector(id: number): Promise<ConnectorVO | null> {
  return http.get<ConnectorVO>(`${BASE}/${id}`)
}

export function createConnector(dto: ConnectorDTO): Promise<number | null> {
  return http.post<number>(BASE, dto)
}

/**
 * 修改。
 *
 * ★ 出参的 `ConnectorUpdateDTO` 把 `version` 定为**必填**（`@NotNull`），
 *   忘了传是 400 而不是「静默全量覆盖」—— 这是后端刻意留的一道保险。
 *   但真正决定成败的是**值对不对**：填一个刚查出来的新版本号，
 *   请求会成功，而乐观锁就白设了。
 */
export async function updateConnector(id: number, dto: ConnectorUpdateDTO): Promise<void> {
  await http.put<void>(`${BASE}/${id}`, dto)
}

/**
 * 删除。
 *
 * ★ `silent`：后端两道守卫**都查**（`current_order_id` 非空、或状态是 2/3），
 *   命中任一即拒绝。页面要把原因和引导合起来说，所以自己接管这条提示。
 */
export async function deleteConnector(id: number, silent = false): Promise<void> {
  await http.del<void>(`${BASE}/${id}`, silent ? { silent: true } : undefined)
}
