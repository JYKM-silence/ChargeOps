/**
 * 充电桩接口（`/api/admin/charger`）。五个接口的形状见 `api/station.ts`。
 *
 * ★★ 三个「不在 DTO 里」的字段，前端千万不要试图绕过去 ★★
 *
 *   · `stationId` —— 只在 `ChargerDTO`（新建）里有，`ChargerUpdateDTO` 里**没有**。
 *     桩建好后不能换站：换站会让它每把枪的冗余列 `station_id` 全部失真。
 *     所以编辑弹窗里「所属站点」只能是**只读展示** —— 做成可编辑的话，
 *     用户改完提交，后端因为关了 `FAIL_ON_UNKNOWN_PROPERTIES` 会**静默忽略**，
 *     用户以为改成功了。
 *   · `gunCount` —— 冗余列，事实来源是 `connector` 表，由枪的增删在同一事务里重算。
 *     任何 DTO 里都没有它。
 *   · `lastHeartbeatTime` —— 只在新建时可以传（给旧平台数据迁移用），
 *     改的时候不能。管理端能设置它就等于能伪造「这台桩刚刚还在线」，
 *     而运维恰恰靠这个字段判断桩是不是掉线了。所以两个表单里都不出现它。
 *
 * ★★ 桩的状态**没有白名单** ★★
 *   `ChargerUpdateDTO` 是 `@Min(0) @Max(4)`，0~4 全部可设。
 *   `ChargerStatus` 类注释里那句「管理员只能改故障/维护中」是**过时注释**，
 *   与 DTO 和 Service 实现都不符，别照它去限制前端下拉。
 */

import type { ChargerDTO, ChargerPageQuery, ChargerUpdateDTO, ChargerVO, Paged } from '@chargeops/shared'
import { http } from './http'

const BASE = '/api/admin/charger'

/** 分页查询。不传 `stationId` 就是**跨站点**查（运维按设备号全局搜的主要入口）。 */
export function pageChargers(query: ChargerPageQuery): Promise<Paged<ChargerVO>> {
  return http.page<ChargerVO>(`${BASE}/page`, { ...query })
}

export function getCharger(id: number): Promise<ChargerVO | null> {
  return http.get<ChargerVO>(`${BASE}/${id}`)
}

export function createCharger(dto: ChargerDTO): Promise<number | null> {
  return http.post<number>(BASE, dto)
}

/**
 * 修改。★ 局部更新，且**没有 `version`** —— 桩上没有乐观锁，
 * 并发覆盖只能靠调用方比对 `updateTime`（挡不住真并发，见列表页注释）。
 */
export async function updateCharger(id: number, dto: ChargerUpdateDTO): Promise<void> {
  await http.put<void>(`${BASE}/${id}`, dto)
}

/**
 * 删除。
 *
 * ★ `silent`：桩下还有枪、或还有未了结的订单时后端会拒绝，
 *   页面要把那句原因和一句引导合起来说，所以自己接管这条提示。
 *   （前端**不预判能不能删** —— 拿不到订单数，预判只会把可删的也挡住。）
 */
export async function deleteCharger(id: number, silent = false): Promise<void> {
  await http.del<void>(`${BASE}/${id}`, silent ? { silent: true } : undefined)
}
