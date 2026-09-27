/**
 * 站点接口（`/api/admin/station`）。
 *
 * ★ 五个接口的形状是全项目统一的（正本是 `StationController` 的类注释）：
 *
 *     POST   /api/admin/station        → 新 id
 *     PUT    /api/admin/station/{id}   → 无返回体
 *     DELETE /api/admin/station/{id}   → 无返回体
 *     GET    /api/admin/station/{id}
 *     GET    /api/admin/station/page
 *
 * ★★ 这一层只做**类型收口**，不做参数清洗 ★★
 *   「丢掉 `''` / `null` / `undefined`」是 `http` 层的职责（`cleanParams`）。
 *   在这里再洗一遍会让「空值算不算没传」有两个真源，而两者迟早不一致，
 *   表现是「筛选条件清空了，结果还是被筛过的」这种没人会怀疑到参数上的错。
 *
 * ★★ 站点**没有乐观锁** ★★
 *   `StationUpdateDTO` 里没有 `version` —— 全项目只有充电枪有。
 *   所以 `updateStation` 只能由调用方拿 `updateTime` 做客户端比对
 *   （见 StationListView 的 `assertFresh`），那种比对挡不住真并发。
 */

import type {
  Paged,
  StationDTO,
  StationPageQuery,
  StationTariffVO,
  StationUpdateDTO,
  StationVO,
} from '@chargeops/shared'
import { http } from './http'

const BASE = '/api/admin/station'

/** 分页查询。`code` 是**前缀匹配**、`city` 是**精确匹配**（后端 XML 决定，前端不要自己截断）。 */
export function pageStations(query: StationPageQuery): Promise<Paged<StationVO>> {
  return http.page<StationVO>(`${BASE}/page`, { ...query })
}

/** 详情。返回 null 表示信封里没有 data（正常不会发生，交给调用方兜底）。 */
export function getStation(id: number): Promise<StationVO | null> {
  return http.get<StationVO>(`${BASE}/${id}`)
}

/** 新建。返回后端回填的新 id，可用于「建完直接跳详情」。 */
export function createStation(dto: StationDTO): Promise<number | null> {
  return http.post<number>(BASE, dto)
}

/** 修改。★ 局部更新：**只传改动过的键**（`diffUpdate` 的输出），不要提交整份表单。 */
export async function updateStation(id: number, dto: StationUpdateDTO): Promise<void> {
  await http.put<void>(`${BASE}/${id}`, dto)
}

/**
 * 删除。
 *
 * ★ `silent` 为 true 时**不走全局错误提示**：删除会被后端两道守卫挡下
 *   （站点下还有桩 / 还有未了结的订单），页面需要把后端那句原因**和一句引导**
 *   合起来说。让全局先弹一条、页面再弹一条，用户往往只看到其中一条，
 *   而恰好看到的那条可能正是没有行动指引的那条。
 */
export async function deleteStation(id: number, silent = false): Promise<void> {
  await http.del<void>(`${BASE}/${id}`, silent ? { silent: true } : undefined)
}

/**
 * 某站点的全部计费绑定（按生效时刻倒序）。
 *
 * ★★ 这个接口**不分页** —— 与另外两个 `by-station` 形状的接口不同 ★★
 *   后端注释写明了：绑定永远按站点查，一个站点的绑定数量以「个」计，
 *   所以返回的是**裸数组**（`Result<List<StationTariffVO>>`），没有分页信封。
 *   用 `http.page()` 去接会从数组上读 `records`/`total` 两个 `undefined`，
 *   表现为「这个 Tab 永远是空的，而且不报错」。
 */
export function listStationTariffs(stationId: number): Promise<StationTariffVO[]> {
  return http.get<StationTariffVO[]>(`/api/admin/station-tariff/by-station/${stationId}`).then(
    (list) => list ?? [],
  )
}
