/**
 * 全站下拉选项加载器（集中在一处，**不要在页面里现写 loader**）。
 *
 * ★★ 为什么需要这一层 ★★
 *   后端**没有**站点 / 充电桩 / 充电枪的下拉接口（只有 `category/list`
 *   与 `tariff-template/list-enabled` 两个例外）。所有下拉都只能用
 *   `pageSize=100` 循环翻页。如果每个页面各写一遍，就会出现：
 *     · 同一个「所属站点」下拉，三个页面三种排序
 *     · 打开一个表单就重复拉一遍全表
 *     · 有的页面过滤了停用站点、有的没过滤
 *
 * ★ 依赖标签（`tags`）是这套设计的核心：写操作成功后调
 *   `invalidateOptionsByTag('station')`，所有依赖站点的下拉一起失效。
 *   调用方不需要知道「哪些 loader 用了站点」。
 */

import { createListOptionLoader, createOptionLoader, type Option, type OptionLoader } from '@chargeops/shared'
import type {
  CategoryVO,
  ChargerVO,
  ConnectorVO,
  StationVO,
  TariffTemplateVO,
} from '@chargeops/shared'
import { http } from './http'

/** 全部标签。集中定义，避免各处写字符串拼错（拼错会静默失效）。 */
export const TAG = {
  station: 'station',
  charger: 'charger',
  connector: 'connector',
  category: 'category',
  package: 'package',
  template: 'template',
} as const

/**
 * 站点下拉。
 *
 * ★ **刻意不过滤停用站点** —— 只在 label 上打「(已停用)」标记：
 *   已经绑定了停用站点的充电桩、订单、计费绑定都需要能正确显示这个站点名。
 *   直接过滤掉会让这些页面的站点列变成 `-`，看起来像数据丢了。
 *   需要「只能选启用站点」的地方（如新建充电桩）自己按 `disabled` 过滤。
 */
export const stationOptions: OptionLoader<StationVO> = createOptionLoader<StationVO>(http, {
  key: 'station',
  tags: [TAG.station],
  url: '/api/admin/station/page',
  labelKey: 'name',
  hintKey: 'code',
})

/** 站点下拉（**不含**已停用/维护中的，用于新建时选择）。 */
export const activeStationOptions: OptionLoader<StationVO> = createOptionLoader<StationVO>(http, {
  key: 'station:active',
  tags: [TAG.station],
  url: '/api/admin/station/page',
  labelKey: 'name',
  hintKey: 'code',
  // ★ status: 1 = 启用。0 停用、2 维护中都不该出现在「新建充电桩」的下拉里。
  params: { status: 1 },
})

/**
 * 充电桩下拉。**按站点级联**。
 *
 * ★ 必须传 `stationId`：不传的话会把所有站点的桩都拉进下拉，
 *   用户选中一个别站的桩会导致建单失败（后端校验站点/桩/枪三者归属一致）。
 */
export function chargerOptionsOf(stationId: number | null | undefined): OptionLoader<ChargerVO> {
  return createOptionLoader<ChargerVO>(http, {
    // ★ params 会被编进缓存键（见 options.ts），所以不同站点各自缓存，不会串
    key: `charger@${stationId ?? 'all'}`,
    tags: [TAG.charger],
    url: '/api/admin/charger/page',
    labelKey: 'name',
    hintKey: 'deviceNo',
    params: stationId ? { stationId } : undefined,
  })
}

/**
 * 充电枪下拉。**按充电桩级联**。
 *
 * ★ `gunNo` 是字符串（`A`/`B`/`1`/`2`），label 用 `枪号 + 类型` ——
 *   只显示 `A` 的话，用户不知道是快充还是慢充。
 */
export function connectorOptionsOf(chargerId: number | null | undefined): OptionLoader<ConnectorVO> {
  return createOptionLoader<ConnectorVO>(http, {
    key: `connector@${chargerId ?? 'all'}`,
    tags: [TAG.connector],
    url: '/api/admin/connector/page',
    labelKey: 'gunNo',
    hintKey: 'statusLabel',
    params: chargerId ? { chargerId } : undefined,
  })
}

/**
 * 套餐分类下拉。
 *
 * ★★ 这条走 `createListOptionLoader`，不是 `createOptionLoader` ★★
 *   后端 `CategoryController.list` 的签名是 `Result<List<CategoryVO>>` ——
 *   **裸数组，没有分页信封**。用分页加载器接会读到两个 `undefined`，
 *   表现为「下拉永远空的，且不报错」。
 */
export function categoryOptionsOf(type: number | null | undefined): OptionLoader<CategoryVO> {
  return createListOptionLoader<CategoryVO>(http, {
    key: `category@${type ?? 'all'}`,
    tags: [TAG.category],
    url: '/api/admin/category/list',
    labelKey: 'name',
    hintKey: 'typeLabel',
    params: type ? { type } : undefined,
  })
}

/**
 * 计费模板下拉（只含启用的）。
 *
 * ★★ 同样走 `createListOptionLoader` ★★
 *   后端注释明确写了「**这个接口不能加分页参数** —— 一个只能选到前 10 个
 *   模板的下拉框会比没有下拉框更糟，因为它看起来是好的」。
 */
export const templateOptions: OptionLoader<TariffTemplateVO> = createListOptionLoader<TariffTemplateVO>(
  http,
  {
    key: 'template:enabled',
    tags: [TAG.template],
    url: '/api/admin/tariff-template/list-enabled',
    labelKey: 'name',
    hintKey: 'code',
  },
)

/** 选项类型再导出，页面写级联时不用二次 import。 */
export type { Option, OptionLoader }
