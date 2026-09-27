/**
 * 计费域接口：**计费模板**（含 24 小时时段规则）与**站点计费绑定**。
 *
 * ★★ 两个域合在一个文件里，因为它们的字段语义互相咬合 ★★
 *   `station_tariff` 的 `templateId` 指向 `tariff_template` 的 `id`，
 *   「模板被站点引用就不能改规则」这条守卫跨的正是这两个域。
 *   分成两个文件的话，那条守卫的两个端点在代码里就看不见彼此了。
 *
 * ★ 本文件还承载了这个域的两条**纯逻辑**（`validateTariffRules` / `minuteOfDay`）：
 *   它们是后端 `TariffValidator` + `TariffRuleSnapshot` + `TimeCodec` 的前端镜像，
 *   不依赖 vue、不依赖 http，所以放这里而不是埋进 `.vue` 的 `<script setup>` 里 ——
 *   那样既测不了，也没法在别处复用（「站点计费绑定」页将来要在时间轴上
 *   预览模板规则时就会用到）。本项目约定「shared 只放两端共用的东西」，
 *   而这批逻辑目前只有管理端用，所以先落在管理端的域模块里。
 */

import type {
  Paged,
  StationTariffDTO,
  StationTariffUpdateDTO,
  StationTariffVO,
  TariffRuleDTO,
  TariffTemplateDTO,
  TariffTemplatePageQuery,
  TariffTemplateUpdateDTO,
  TariffTemplateVO,
} from '@chargeops/shared'
import { PERIOD_TYPE, isTimeHM, isTimeHMEnd } from '@chargeops/shared'
import { http } from './http'

// ══════════════════════════════════════════════════════════════════════════
// 计费模板
// ══════════════════════════════════════════════════════════════════════════

export function pageTariffTemplates(
  query: TariffTemplatePageQuery,
): Promise<Paged<TariffTemplateVO>> {
  // ★ 列表**不含** rules（后端恒返回 null），所以列表页只渲染模板头。
  //   要拿规则必须走 getTariffTemplate —— 这不是性能取舍，是后端有意为之：
  //   「字段存在但为 null」和「字段不存在」是两种不同的信号。
  return http.page<TariffTemplateVO>('/api/admin/tariff-template/page', { ...query })
}

/** 详情。**含**规则，且已按 startTime 排好序（可直接渲染成时间轴）。 */
export function getTariffTemplate(id: number): Promise<TariffTemplateVO | null> {
  return http.get<TariffTemplateVO>(`/api/admin/tariff-template/${id}`)
}

export function createTariffTemplate(payload: TariffTemplateDTO): Promise<number | null> {
  return http.post<number>('/api/admin/tariff-template', payload)
}

/**
 * 修改模板。
 *
 * ★★ `options.silent` 是给**带 rules 的调用**用的 ★★
 *   模板被任何一行 `station_tariff` 引用时，后端会拒绝改规则
 *   （`该模板已被站点绑定，不能修改计费规则；请新建一个模板版本`）。
 *   这条拒绝不能只弹一句 toast 就完 —— 它有一个明确的出路（另存为新模板），
 *   而「先弹 toast、再弹引导框」会出现两段重叠的提示。
 *   所以调用方传 `{ silent: true }` 自己接管这条错误，其余错误再自行 toast。
 */
export function updateTariffTemplate(
  id: number,
  payload: TariffTemplateUpdateDTO,
  options?: { silent?: boolean },
): Promise<void | null> {
  return http.put<void>(`/api/admin/tariff-template/${id}`, payload, {
    silent: options?.silent,
  })
}

/** 删除模板。理由同 `updateTariffTemplate` —— 被站点引用时同样走引导流程。 */
export function deleteTariffTemplate(
  id: number,
  options?: { silent?: boolean },
): Promise<void | null> {
  return http.del<void>(`/api/admin/tariff-template/${id}`, { silent: options?.silent })
}

/**
 * 后端「模板已被站点绑定」的**逐字文案**。
 *
 * ★ 出处：`MessageConstant.TEMPLATE_IN_USE`。改规则和删模板**共用这一句**，
 *   所以一个前缀判断能同时覆盖两条路径。
 *
 * ★ 刻意不从 `MSG` 取：那边只放「命中之后要触发全局行为」的文案
 *   （强制登出、并发冲突提示）。这条触发的是**本页面的引导框**，
 *   是本域自己的事。用 `startsWith` 而不是 `includes` —— 见 MSG 的说明。
 */
export const TEMPLATE_IN_USE = '该模板已被站点绑定'

// ══════════════════════════════════════════════════════════════════════════
// 站点计费绑定
// ══════════════════════════════════════════════════════════════════════════

/**
 * 查询某站点的全部绑定。
 *
 * ★★ 这个域**没有 `GET /page`** —— 绑定列表永远是「按站点查」的 ★★
 *   它就是这个站点的**调价历史**（后端按生效时刻倒序返回）。
 *   所以页面形态是「先选站点，再展示列表」，而不是一个平铺的分页表格。
 *
 * ★ 返回的是**裸数组**（`Result<List<...>>`），没有分页信封 ——
 *   不要拿 `http.page()` 去接，那会读到两个 undefined 且不报错。
 */
export async function listStationTariffs(stationId: number): Promise<StationTariffVO[]> {
  const list = await http.get<StationTariffVO[]>(
    `/api/admin/station-tariff/by-station/${stationId}`,
  )
  return list ?? []
}

export function getStationTariff(id: number): Promise<StationTariffVO | null> {
  return http.get<StationTariffVO>(`/api/admin/station-tariff/${id}`)
}

export function createStationTariff(payload: StationTariffDTO): Promise<number | null> {
  return http.post<number>('/api/admin/station-tariff', payload)
}

/** ★ 只有 `effectiveTo` / `priority` / `status` 可改，见 `StationTariffUpdateDTO`。 */
export function updateStationTariff(
  id: number,
  payload: StationTariffUpdateDTO,
): Promise<void | null> {
  return http.put<void>(`/api/admin/station-tariff/${id}`, payload)
}

export function deleteStationTariff(id: number): Promise<void | null> {
  return http.del<void>(`/api/admin/station-tariff/${id}`)
}

// ══════════════════════════════════════════════════════════════════════════
// 24 小时覆盖自检（后端 TariffValidator 的前端镜像）
// ══════════════════════════════════════════════════════════════════════════

/** 一天的分钟数。与 `TimeCodec.MINUTES_PER_DAY` 一致；`24:00:00` 就等于它。 */
const MINUTES_PER_DAY = 1440

/**
 * `HH:mm` / `HH:mm:ss` → 当日分钟数。**`24:00:00` 返回 1440**。
 * 解析不出来返回 `null`（不抛 —— 表单正在被编辑，报错由调用方组织文案）。
 *
 * ★ 语义逐条对齐后端 `TimeCodec.toMinuteOfDay`：
 *   · 秒可省（HTML 的原生时间控件在秒为 0 时提交的就是 `08:00`）
 *   · `24` 点**只允许精确的 `24:00:00`**，`24:30:00` 非法 ——
 *     这一列 TIME 表达的是「当天的分界点」，24 点之后只能是次日 0 点
 *   · 返回值是分钟数而不是 `Date`：`24:00:00` 在 `Date` 里表达不了
 *     （它会滚到次日 00:00，和 `00:00:00` 撞成同一个值）
 */
export function minuteOfDay(time: string | null | undefined): number | null {
  if (typeof time !== 'string') return null
  const m = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(time.trim())
  if (!m) return null

  const hour = Number(m[1])
  const minute = Number(m[2])
  const second = m[3] === undefined ? 0 : Number(m[3])

  if (hour === 24 && (minute !== 0 || second !== 0)) return null
  if (hour > 24 || minute > 59 || second > 59) return null
  return hour * 60 + minute
}

/** 当日分钟数 → `HH:mm:ss`。1440 拼出来正好是 `24:00:00`（对齐后端 `toTimeString`）。 */
export function formatMinuteOfDay(minute: number): string {
  const safe = Math.min(Math.max(Math.trunc(minute), 0), MINUTES_PER_DAY)
  const hh = String(Math.floor(safe / 60)).padStart(2, '0')
  const mm = String(safe % 60).padStart(2, '0')
  return `${hh}:${mm}:00`
}

/**
 * 归一到带秒的 `HH:mm:ss`。解析不了就原样返回，让后端的格式校验去报错 ——
 * 前端在这里「顺手修一下」会让用户填错的值被静默改掉。
 */
export function normalizeTime(time: string): string {
  const minute = minuteOfDay(time)
  return minute === null ? time : formatMinuteOfDay(minute)
}

/** 自检的输入：页面表单里的一行规则。 */
export interface TariffRuleInput {
  periodType: number
  startTime: string
  endTime: string
  /** 电费单价，元/kWh。表单里没填时是 null。 */
  energyPrice: number | null
  /** 服务费单价，元/kWh。后端允许省略并按 0 处理。 */
  servicePrice: number | null
}

/** 时间轴上的一段区间（缺口或重叠），分钟数 + 给用户看的文案。 */
export interface TariffRuleRange {
  from: number
  to: number
  message: string
}

/** 行内字段问题。`row` 是**下标**（0 起），展示时记得 +1。 */
export interface TariffRuleRowProblem {
  row: number
  message: string
}

export interface TariffRuleCheck {
  ok: boolean
  /**
   * 行内字段问题。**非空时不会做覆盖分析**（`gaps`/`overlaps` 恒为空）。
   *
   * ★ 这不是偷懒，是照抄后端的执行顺序：`TariffRuleSnapshot` 的构造器在
   *   第一个坏行上就抛异常，所以后端的 `TariffValidator` 从来不会拿
   *   「起点读不出来」的行去做连续性比较。前端若照算，会得到一堆
   *   由坏数据推导出来的假缺口 —— 那种噪声比不报还糟。
   */
  rowProblems: TariffRuleRowProblem[]
  /** 缺口：这段区间**没有任何规则覆盖** → 该时刻的订单算不出电价。 */
  gaps: TariffRuleRange[]
  /** 重叠：两套价格同时生效 → 按哪条算是不确定的。 */
  overlaps: TariffRuleRange[]
}

/** 一行的可读描述，对齐后端 `TariffRuleSnapshot.describe()`：`尖峰 10:00:00-12:00:00`。 */
function describeRule(rule: TariffRuleInput, start: number, end: number): string {
  return `${PERIOD_TYPE.label(rule.periodType)} ${formatMinuteOfDay(start)}-${formatMinuteOfDay(end)}`
}

/**
 * 校验一组规则能否**恰好无缝铺满 00:00:00 ~ 24:00:00**。
 *
 * ★★ 为什么要在前端做一遍 ★★
 *   后端 `TariffValidator` 是唯一的**执行者**，前端这份**不替代它** ——
 *   它存在的意义只有一个：让运营在点「保存」之前就看到问题。
 *   一个 8 行的表里少填了 5 分钟，纯看表格是看不出来的（1000 与 1015 的差别
 *   不会跳进眼里），而漏了 5 分钟的那个模板会让**所有用它的站点**在
 *   03:25 下单时算出空金额。所以这里不只是判断通过与否，
 *   还把缺口/重叠的**位置**算出来交给时间轴标红。
 *
 * ★ 规则逐条对齐后端，包括三处容易被「顺手优化」掉的地方：
 *   1. **起点用 `isTimeHM`、终点用 `isTimeHMEnd`**：`24:00:00` 只对终点合法，
 *      它作为起点是零长度段的前兆，后端的 `@Pattern` 正是这么分开写的。
 *   2. **起点必须严格早于终点**（`start >= end` 非法）：允许相等就是允许
 *      零长度规则 —— 它不被任何时刻命中，却让「24 小时覆盖」看起来成立。
 *   3. **缺口和重叠一次全报**，不是遇到第一个就返回：管理员在填一张 8 行的表，
 *      每往返一次都是一遍完整的重填。只有**行内**问题会提前中断（见上）。
 *
 * ★ 与后端一致的另一点：**重叠优先于缺口**。两者同时存在时，缺口列表没有意义
 *   （覆盖关系已经乱了，差集不代表真实缺失），所以 `ok` 为 false 时
 *   UI 应当先讲重叠那几条。
 */
export function validateTariffRules(rules: readonly TariffRuleInput[]): TariffRuleCheck {
  if (rules.length === 0) {
    return {
      ok: false,
      rowProblems: [
        { row: -1, message: '计费规则不能为空，必须完整覆盖 00:00:00 ~ 24:00:00' },
      ],
      gaps: [],
      overlaps: [],
    }
  }

  // ── 第一遍：单行约束 ────────────────────────────────────────────────────
  const rowProblems: TariffRuleRowProblem[] = []
  /** 能确定起止的行，用于第二遍的连续性比较。 */
  const spans: { index: number; start: number; end: number }[] = []

  rules.forEach((rule, index) => {
    const row = index + 1
    const start = isTimeHM(rule.startTime) ? minuteOfDay(rule.startTime) : null
    const end = isTimeHMEnd(rule.endTime) ? minuteOfDay(rule.endTime) : null

    if (start === null) {
      rowProblems.push({
        row: index,
        message: `第 ${row} 行：开始时间的格式应为 HH:mm 或 HH:mm:ss，且不能是 24:00:00`,
      })
    }
    if (end === null) {
      rowProblems.push({
        row: index,
        message: `第 ${row} 行：结束时间的格式应为 HH:mm、HH:mm:ss，或 24:00:00`,
      })
    }
    if (start === null || end === null) return

    // 起点等于终点是「零长度段」：它不被任何时刻命中，却让覆盖看起来成立
    if (start >= end) {
      rowProblems.push({
        row: index,
        message:
          `第 ${row} 行：起点必须早于终点，否则这是一个永远不会生效的时段` +
          `（${formatMinuteOfDay(start)} - ${formatMinuteOfDay(end)}）`,
      })
      return
    }

    if (rule.energyPrice === null) {
      rowProblems.push({ row: index, message: `第 ${row} 行：电费单价不能为空` })
    } else if (rule.energyPrice < 0) {
      // 负单价意味着「充电越多退钱越多」，而它在计算上是静默的
      rowProblems.push({ row: index, message: `第 ${row} 行：电费单价不能为负数` })
    }
    if (rule.servicePrice !== null && rule.servicePrice < 0) {
      rowProblems.push({ row: index, message: `第 ${row} 行：服务费单价不能为负数` })
    }

    spans.push({ index, start, end })
  })

  if (rowProblems.length > 0) {
    return { ok: false, rowProblems, gaps: [], overlaps: [] }
  }

  // ── 第二遍：跨行连续性 ──────────────────────────────────────────────────
  // 按起点排序后逐对比较 `前一条.end` 与 `后一条.start`：
  // `<` 是缺口、`>` 是重叠、`==` 才合法。一个判断同时证明了「无缺口」
  // 和「无重叠」—— 因为区间是半开的 `[start, end)`，
  // `08:00-10:00` 与 `10:00-12:00` 相接而不重叠。
  const sorted = [...spans].sort((a, b) => a.start - b.start)
  const gaps: TariffRuleRange[] = []
  const overlaps: TariffRuleRange[] = []

  // 首条必须从 00:00 起。相邻比较发现不了它 —— 没有「前一条」和它比
  const first = sorted[0]
  if (first.start !== 0) {
    gaps.push({
      from: 0,
      to: first.start,
      message:
        `缺口 00:00:00-${formatMinuteOfDay(first.start)}：` +
        '这段时间没有任何规则覆盖，此时段下的订单会算不出电价',
    })
  }

  for (let i = 1; i < sorted.length; i += 1) {
    const prev = sorted[i - 1]
    const cur = sorted[i]
    if (prev.end < cur.start) {
      gaps.push({
        from: prev.end,
        to: cur.start,
        message:
          `缺口 ${formatMinuteOfDay(prev.end)}-${formatMinuteOfDay(cur.start)}（` +
          `${describeRule(rules[prev.index], prev.start, prev.end)} 与 ` +
          `${describeRule(rules[cur.index], cur.start, cur.end)} 之间）`,
      })
    } else if (prev.end > cur.start) {
      overlaps.push({
        from: cur.start,
        to: prev.end,
        message:
          `重叠 ${formatMinuteOfDay(cur.start)}-${formatMinuteOfDay(prev.end)}：` +
          `${describeRule(rules[prev.index], prev.start, prev.end)} 与 ` +
          `${describeRule(rules[cur.index], cur.start, cur.end)} 同时生效`,
      })
    }
  }

  // 末条必须到 24:00 收尾。同样相邻比较发现不了。
  // ★ 末段写成 `00:00:00`（而不是 `24:00:00`）时，`[23:00, 00:00)` 会变成一个
  //   零长度段 —— 表现为「23 点到 24 点之间没有电价」，订单金额莫名其妙少了。
  const last = sorted[sorted.length - 1]
  if (last.end !== MINUTES_PER_DAY) {
    gaps.push({
      from: last.end,
      to: MINUTES_PER_DAY,
      message:
        `缺口 ${formatMinuteOfDay(last.end)}-24:00:00：` +
        '最后一段必须以 24:00:00 收尾（写成 00:00:00 会被当成零长度段）',
    })
  }

  return {
    ok: gaps.length === 0 && overlaps.length === 0,
    rowProblems: [],
    gaps,
    overlaps,
  }
}

/** 把表单行转成后端要的 `TariffRuleDTO[]`。`seq` 不传后端也会按下标填，这里显式给出。 */
export function toRulePayloads(rules: readonly TariffRuleInput[]): TariffRuleDTO[] {
  return rules.map((rule, index) => ({
    periodType: rule.periodType,
    startTime: normalizeTime(rule.startTime),
    endTime: normalizeTime(rule.endTime),
    energyPrice: rule.energyPrice ?? 0,
    // ★ 后端这一列 NOT NULL，Service 里空值补 0（DDL 的 DEFAULT 在全列插入下不生效）
    servicePrice: rule.servicePrice ?? 0,
    seq: index,
  }))
}
