/**
 * 运维工单接口（管理端）。
 *
 * 只有查列表和改状态两个接口，**没有建单和改内容**：工单是「已经发生过的事」的记录，
 * 建单的入口是 AI 助手对话，改内容等于篡改历史 —— 而且 AI 建的工单还要拿 `content`
 * 当历史案例召回（只召回 `status = 2` 的那些）。
 */

import { http } from './http'
import type {
  MaintenanceTicketPageQuery,
  MaintenanceTicketStatusDTO,
  MaintenanceTicketVO,
  Paged,
} from '@chargeops/shared'

const BASE = '/api/admin/maintenance-ticket'

export function pageTickets(query: MaintenanceTicketPageQuery): Promise<Paged<MaintenanceTicketVO>> {
  return http.page<MaintenanceTicketVO>(BASE, query)
}

/**
 * 改状态。
 *
 * 把状态推到 2 已完成是有下游意义的：只有已完成的工单会被召回当同类故障的历史案例。
 * 反过来，标成 2 之后再改回 0 也不会撤销什么 —— 历史记录会照常带上它。
 *
 * 重复提交同一个状态是安全的：后端先比对，值没变就直接返回，不会报「工单不存在」。
 */
export function updateTicketStatus(
  id: number | string,
  dto: MaintenanceTicketStatusDTO,
): Promise<null> {
  return http.put<null>(`${BASE}/${id}/status`, dto)
}
