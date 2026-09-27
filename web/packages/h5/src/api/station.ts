/**
 * 站点 / 充电枪浏览。
 *
 * ★★ 这两个接口是 **Phase 0.5 补的**（`MemberStationController`）★★
 *   补之前车主的充电入口是断的：建单需要 `stationId` / `chargerId` /
 *   `connectorId` 三个数字，而车主端**没有任何接口能拿到它们** ——
 *   原来的 `MemberStationPageQueryDTO` 类注释原话是
 *   「三个 id 在车主端没有任何合法来源」「前端也只能让用户手输三个数字」。
 */

import { http } from './http'
import type {
  MemberConnectorVO,
  MemberStationPageQuery,
  MemberStationVO,
  Paged,
} from '@chargeops/shared'

/**
 * 站点分页。
 *
 * ★ 后端 SQL 里**写死 `status = 1`** —— 停用(0)和维护中(2)的站点
 *   根本不会返回，**前端不用再过滤一遍**（再过滤一次是重复劳动，
 *   而且会让人误以为「后端会返回维护中的站点」）。
 */
export function pageStations(query: MemberStationPageQuery): Promise<Paged<MemberStationVO>> {
  return http.page<MemberStationVO>('/api/member/station/page', query)
}

/**
 * 某站点下的充电枪（**裸数组，不是分页信封**）。
 *
 * ★★ 返回顺序**不是给用户看的顺序** ★★
 *   后端 `listByStation` **不排序**（`gun_no` 是字符串，排出来是 `1, 10, 2`）。
 *   后端注释明确要求**前端重排** —— 见本文件的 `sortConnectors()`。
 *
 * ★★ `available` 是**快照不是预订** ★★
 *   查到 `true` 只代表「查的这一刻空闲」，用户点「开始充电」之前
 *   别人完全可能先占了。调用方**必须处理「`available=true` 但建单失败」**。
 */
export function listConnectors(stationId: number | string): Promise<MemberConnectorVO[] | null> {
  return http.get<MemberConnectorVO[]>(`/api/member/connector/by-station/${stationId}`)
}

/**
 * 充电枪排序：**先可用、再按桩分组、最后按枪号自然序**。
 *
 * ★ 为什么必须自己排（而不是受后端顺序）：
 *   `gun_no` 是**字符串**。直接 `localeCompare` 或字典序会得到
 *   `1, 10, 11, 2, 3` —— 用户看到的第一把枪是「10 号」，会以为列表乱了。
 *   所以枪号用**数字自然序**（`numeric: true`）。
 *
 * ★ 为什么「可用」排最前而不是按桩号排：
 *   车主打开这个页面的**唯一目的**是找一把能用的枪。
 *   按桩号排会把可用的枪埋在故障枪后面，用户要自己一格格扫。
 *
 * ★ 用 `slice()` 复制再排：直接 `sort()` 会**原地改调用方传进来的数组**，
 *   而那个数组往往是 `ref` 里的响应式数据 —— 原地排序会让
 *   「重新拉取后的顺序」和「用户上次手动排序」混在一起，出现莫名其妙的跳动。
 */
export function sortConnectors(list: readonly MemberConnectorVO[]): MemberConnectorVO[] {
  return list.slice().sort((a, b) => {
    // ① 可用的在前
    if (a.available !== b.available) return a.available ? -1 : 1
    // ② 按桩分组（桩的设备号，没有就退化成 id）
    const ca = a.chargerDeviceNo || String(a.chargerId)
    const cb = b.chargerDeviceNo || String(b.chargerId)
    const byCharger = ca.localeCompare(cb, 'zh-CN', { numeric: true })
    if (byCharger !== 0) return byCharger
    // ③ 同一把桩内按枪号自然序（`1, 2, ..., 10`，不是 `1, 10, 2`）
    return a.gunNo.localeCompare(b.gunNo, 'zh-CN', { numeric: true })
  })
}

/**
 * 把充电枪按桩分组，给「桩 → 枪」两级展示用。
 *
 * ★ 分组**不改变 `sortConnectors()` 算出的组间顺序**：
 *   先在原数组里按出现顺序收集桩，这样可用的枪所属的桩会自然排在前面。
 */
export interface ChargerGroup {
  chargerId: number
  chargerDeviceNo: string | null
  chargerName: string | null
  chargerTypeLabel: string | null
  connectors: MemberConnectorVO[]
  /** 这一组里可用的枪数，用于「该桩还有 N 把可用」的摘要。 */
  availableCount: number
}

export function groupByCharger(list: readonly MemberConnectorVO[]): ChargerGroup[] {
  const sorted = sortConnectors(list)
  const groups = new Map<number, ChargerGroup>()

  for (const c of sorted) {
    let g = groups.get(c.chargerId)
    if (!g) {
      g = {
        chargerId: c.chargerId,
        chargerDeviceNo: c.chargerDeviceNo,
        chargerName: c.chargerName,
        chargerTypeLabel: c.chargerTypeLabel,
        connectors: [],
        availableCount: 0,
      }
      groups.set(c.chargerId, g)
    }
    g.connectors.push(c)
    if (c.available) g.availableCount += 1
  }

  return [...groups.values()]
}

/** 站点营业时间展示：`07:00 - 22:00`，缺一个端点就返回 null（不猜）。 */
export function openHoursText(
  openTime: string | null | undefined,
  closeTime: string | null | undefined,
): string | null {
  if (!openTime || !closeTime) return null
  // 后端给的是 `HH:mm:ss`，展示只需要到分钟
  return `${openTime.slice(0, 5)} - ${closeTime.slice(0, 5)}`
}
