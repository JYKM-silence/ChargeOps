/**
 * 我的车辆。
 */

import { http } from './http'
import type { VehicleDTO, VehicleUpdateDTO, VehicleVO } from '@chargeops/shared'

const BASE = '/api/member/vehicle'

/** 我的车辆列表（**裸数组**，不分页 —— 一个车主的车不会多到需要分页）。 */
export function listVehicles(): Promise<VehicleVO[] | null> {
  return http.get<VehicleVO[]>(BASE)
}

export function getVehicle(id: number | string): Promise<VehicleVO | null> {
  return http.get<VehicleVO>(`${BASE}/${id}`)
}

/** 新增。★ 返回的是**新车的 id**（`Result<Long>`），不是 VO。 */
export function createVehicle(dto: VehicleDTO): Promise<number | null> {
  return http.post<number>(BASE, dto)
}

/**
 * 编辑。
 *
 * ★ `VehicleUpdateDTO` **没有 `isDefault`** —— 改默认车走
 *   `setDefaultVehicle()`（那是独立的一次动作，不是编辑表单里的一个勾）。
 *   表单里放一个"设为默认"的开关然后塞进 update 是**不会生效的**，
 *   而且因为后端关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`，它**不会报错**。
 */
export function updateVehicle(id: number | string, dto: VehicleUpdateDTO): Promise<null> {
  return http.put<null>(`${BASE}/${id}`, dto)
}

/**
 * 删除。
 *
 * ★★ 删除**没有任何守卫** —— 已核对 `VehicleServiceImpl.delete()`：★★
 *   `requireMine()`（只确认「存在且是我的」）→ `deleteByIdAndMember()`（硬删）
 *   → 若删的是默认车再把剩下的第一辆提升为默认。**全程不查订单**。
 *
 *   `charging_order.vehicle_id` **没有外键**（本项目的约定就是不用外键，
 *   理由见根 README），而 `plate_no` 是**快照列**（DDL 原话：「车牌快照：
 *   车辆可被删除/改牌，订单必须保留当时值」）。
 *
 *   → 所以「车辆被进行中的订单引用时会被拒绝」是**错的**，
 *     不要照着写提示文案。真实行为是：**删得掉**，订单靠 `plate_no` 快照
 *     继续成立（金额、车牌都不受影响），只是订单上的 `vehicle_id` 变成悬空值。
 *
 * ★ 唯一仍会被拒绝的是**并发**：`deleteByIdAndMember` 影响 0 行 →
 *   后端按「不是我的车」处理。但那种情况用户刷新一下就看不到这辆车了。
 *
 * ★ 调用的顺序后果：删掉默认车后端会**自动把剩下的第一辆提升为默认**，
 *   所以删完必须**重拉列表**，不要在本地把那一行去掉就完事 ——
 *   本地剔除会让「哪辆是默认车」显示错。
 */
export function deleteVehicle(id: number | string): Promise<null> {
  return http.del<null>(`${BASE}/${id}`)
}

/**
 * 设为默认车。
 *
 * ★★ 判「哪辆是默认车」要用 `isDefault === 1`，**不能写真值判断** ★★
 *   后端这个字段是**数字**（1/0），不是布尔。`if (vehicle.isDefault)` 在
 *   `0` 上恰好也对，但如果哪天后端改成返回字符串 `"0"`，
 *   `"0"` 是 **truthy** —— 所有车都会显示成默认车。用 `=== 1` 才稳。
 */
export function setDefaultVehicle(id: number | string): Promise<null> {
  return http.put<null>(`${BASE}/${id}/default`)
}
