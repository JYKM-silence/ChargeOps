/**
 * 充电套餐接口。
 *
 * ★ 路径是 `/api/admin/package`：`package` 是 Java 关键字，但那只管后端的类名
 *   （`ChargePackageController`），不影响 URL。
 */

import type {
  ChargePackageDTO,
  ChargePackagePageQuery,
  ChargePackageUpdateDTO,
  ChargePackageVO,
  Paged,
} from '@chargeops/shared'
import { formatKwhLabel, formatPrice } from '@chargeops/shared'
import { http } from './http'

export function pagePackages(query: ChargePackagePageQuery): Promise<Paged<ChargePackageVO>> {
  return http.page<ChargePackageVO>('/api/admin/package/page', { ...query })
}

export function getPackage(id: number): Promise<ChargePackageVO | null> {
  return http.get<ChargePackageVO>(`/api/admin/package/${id}`)
}

export function createPackage(payload: ChargePackageDTO): Promise<number | null> {
  return http.post<number>('/api/admin/package', payload)
}

export function updatePackage(id: number, payload: ChargePackageUpdateDTO): Promise<void | null> {
  return http.put<void>(`/api/admin/package/${id}`, payload)
}

/**
 * 删除套餐。
 *
 * ★ 常规的「下架」手段是 `status = 0`（历史订单还引用着它），硬删存在的原因是
 *   `uk_pkg_name_station` **不看 status** —— 名字打错一次就永久占用，
 *   想用对的名字只能先把错的删掉。所以界面上「下架」和「删除」要并存，
 *   不能只留一个。
 */
export function deletePackage(id: number): Promise<void | null> {
  return http.del<void>(`/api/admin/package/${id}`)
}

/**
 * `packageType` → 该类型**唯一有效**的那个额度字段。
 *
 * ★★ 这张表是本域最容易出错的地方，所以定义在一个地方而不是散在模板里 ★★
 *   四种类型各自只用到一部分额度字段，而**后端不校验「你填了不相关的字段」**：
 *   多填的会被静默存下但永不生效。一个时长包里残留的 `energyKwh = 150`
 *   会被套餐扣减逻辑读走 —— 凭空送出 150 度电，每一步都不报错，
 *   只有对账时那笔钱对不上。
 *
 *   → 所以新建表单必须**按类型动态显示**字段，并且切换类型时清掉不相关字段的值。
 */
export const PACKAGE_QUOTA_FIELD = {
  1: 'energyKwh',
  2: 'durationMinutes',
  3: 'serviceDiscount',
  4: 'times',
} as const

/** 取值同 `PACKAGE_QUOTA_FIELD`，作为联合类型用在表单上。 */
export type PackageQuotaField = (typeof PACKAGE_QUOTA_FIELD)[keyof typeof PACKAGE_QUOTA_FIELD]

/** 取某个套餐类型对应的额度字段名。未知类型返回 `null`（下拉里不会有，防御后端新增类型）。 */
export function quotaFieldOf(packageType: number): PackageQuotaField | null {
  return (
    (PACKAGE_QUOTA_FIELD as Record<number, PackageQuotaField | undefined>)[packageType] ?? null
  )
}

/**
 * 后端「类型专属字段不匹配」的逐字前缀。
 *
 * ★ 出处：`MessageConstant.PACKAGE_TYPE_FIELD_MISMATCH = "套餐的类型专属字段不匹配"`。
 *
 * ★ 前端的动态表单已经拦住了绝大多数情况，但仍有一种够得着的例外：
 *   **库里那行本来就是坏的**（`ChargePackageServiceImpl.update` 校验的是
 *   「库里那行 + 本次改动」合并后的结果，不是请求体）。那时只改一个价格
 *   也会被打回，而后端注释明确说了这不是误伤 —— 那行数据确实不能继续留在库里。
 *   界面改不动它，所以文案必须指向「找后端按 SQL 修」而不是让用户反复重试。
 */
export const PACKAGE_TYPE_FIELD_MISMATCH = '套餐的类型专属字段不匹配'

/** 套餐列表里「面值」列的文案。按类型取值，其余三个字段一律不参与展示。 */
export function quotaTextOf(row: ChargePackageVO): string {
  switch (row.packageType) {
    case 1:
      // ★ 走 shared 的 formatKwhLabel 而不是字符串拼接：后端是 BigDecimal，
      //   JSON 里可能是字符串也可能是数字，直接插值会得到 "150" 或 "150.0000"
      //   这类不统一的形态。
      return row.energyKwh === null ? '-' : formatKwhLabel(row.energyKwh)
    case 2:
      return row.durationMinutes === null ? '-' : minutesText(row.durationMinutes)
    case 3:
      // ★ 折扣率不是金额：0.8 表示八折。它要和「服务费单价」相乘，
      //   精度不够会直接体现为金额误差，所以用 formatPrice（4 位小数），
      //   不能用 formatMoney（2 位，会把 0.8000 显示成 0.80）。
      return row.serviceDiscount === null ? '-' : `服务费 × ${formatPrice(row.serviceDiscount)}`
    case 4:
      return row.times === null ? '-' : `${row.times} 次`
    default:
      return '-'
  }
}

/**
 * 分钟数 → 人话。
 *
 * ★ `durationMinutes` 的单位是分钟，而运营脑子里的单位是「天」——
 *   一张 `43200` 分钟的时长包要在列表里立刻读出「30 天」。
 *   纯整数运算（分钟数本身是整数），不涉及金额浮点。
 */
export function minutesText(minutes: number): string {
  if (minutes % 1440 === 0) return `${minutes / 1440} 天`
  if (minutes >= 60) {
    const h = Math.floor(minutes / 60)
    const m = minutes % 60
    return m === 0 ? `${h} 小时` : `${h} 小时 ${m} 分`
  }
  return `${minutes} 分钟`
}
