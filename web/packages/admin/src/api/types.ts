/**
 * 管理端接口的请求/返回类型。
 *
 * ★★ 为什么每个接口都要有精确类型（**禁止 `any`**）：★★
 *   后端关掉了 `FAIL_ON_UNKNOWN_PROPERTIES`（见 shared 的说明），
 *   所以**字段名拼错不会被报错，会被静默忽略**。
 *   表现是「我明明传了参数，怎么没生效」—— 排查方向会跑偏到后端去。
 *   类型收口是唯一能在编译期挡住这件事的手段。
 *
 * ★ 时间字段一律是 `string`（`yyyy-MM-dd HH:mm:ss`），**不要用 Date** ——
 *   原因见 shared/utils/datetime.ts 的文件头。
 */

import type { PageQuery } from '@chargeops/shared'

// ── 认证 ───────────────────────────────────────────────────────────────────

export interface StaffLoginVO {
  id: number
  username: string
  name: string
  role: number
  stationId: number | null
  token: string
}

export interface StaffVO {
  id: number
  username: string
  name: string
  phone: string | null
  /** ★ 是**字符串**，不是数字枚举 —— 与车主端 `gender` 不同，别照抄那边的写法。 */
  sex: string | null
  role: number
  stationId: number | null
  status: number | null
  createTime: string | null
}

// ── 分页查询基类 ───────────────────────────────────────────────────────────

/** 所有分页查询 DTO 的公共部分。各域自己的条件各自 extends。 */
export type { PageQuery }
