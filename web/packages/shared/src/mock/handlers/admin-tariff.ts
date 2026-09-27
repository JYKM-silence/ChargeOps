/**
 * 计价配置：**计费模板（+ 规则）** 与 **站点计费绑定**（管理端）。
 *
 * ★★ 这个文件里真正难的东西只有一个：`TariffValidator.validate` ★★
 *   它是全项目唯一一个「**整体**约束」的校验（其余都是单行或单字段的）：
 *   一个模板的规则集必须**无缝铺满 24 小时**，既不能有缺口、也不能有重叠。
 *   三件事让它和别的校验不一样：
 *
 *   1. **判据是一条 `==`。** 按起点排序后逐对比较「前一条的终点」和
 *      「后一条的起点」：`<` 是缺口、`>` 是重叠、`==` 才合法。一条判断
 *      同时等价于「无缺口 且 无重叠 且 铺满 24 小时」——
 *      而 `==` 天然把「前一条终点恰好是后一条起点」判为合法，
 *      这正是 DDL 要求的半开区间语义。
 *   2. **首尾要单独看。** `00:00` 到首条起点之间、末条终点到 `24:00` 之间
 *      都没有「相邻对」可比 —— 漏掉这两处，一个只有 `08:00~18:00` 一条规则的
 *      模板会被判成合法，而那意味着一天里有 14 个小时算不出电价。
 *   3. **重叠优先于缺口。** 存在重叠时「缺口」列表**没有意义** ——
 *      覆盖关系已经乱了，差集不代表真实缺失。所以两类问题分别收集，
 *      有重叠时只报重叠。
 *
 * ★ 另一半是**校验必须覆盖每一条写规则的路径**：`update` 里规则是
 *   「整体替换」而不是逐条更新，而且是**先校验、后删除**。反过来写的话，
 *   一个校验失败的请求会先把旧规则删掉再报错 —— 事务回滚能撤销，
 *   但那个瞬间库里是空的。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type {
  TariffRuleDTO,
  TariffTemplateDTO,
  TariffTemplateUpdateDTO,
  StationTariffDTO,
  StationTariffUpdateDTO,
} from '../../types/admin'
import {
  checkPageParams,
  envelope,
  fail,
  guardStaff,
  missingRequired,
  numParam,
  ok,
  okVoid,
  pageParams,
  paginate,
  strParam,
  toNumber,
} from './core'
import type { MockContext } from './core'
import { alreadyExists, effectiveRangeInvalid, TEXT } from './texts'
import { toStationTariffVo, toTariffTemplateVo } from './vo'
import type { DbStationTariff, DbTariffRule, DbTariffTemplate } from '../db'
import { clockOf, parseClock, MINUTES_PER_DAY, normalizeClock } from './timecodec'
import type { ClockError } from './timecodec'
import { PERIOD_TYPE } from '../../constants/dicts'
import { parseServerTime } from '../../utils/datetime'

// ══════════════════════════════════════════════════════════════════════════
// 计费规则集校验（`TariffValidator` + `TariffRuleSnapshot`）
// ══════════════════════════════════════════════════════════════════════════

/** 一条规则的**快照** —— 与后端 `TariffRuleSnapshot` 同形，只保留校验要用的列。 */
interface RuleSnapshot {
  periodType: number
  startMinute: number
  endMinute: number
  /** 校验通过后要用它落库，所以快照里带着原始 DTO。 */
  source: TariffRuleDTO
}

/** `TariffRuleSnapshot.describe()`：`尖峰 10:00:00-12:00:00`。 */
function describeRule(rule: RuleSnapshot): string {
  return `${PERIOD_TYPE.label(rule.periodType)} ${clockOf(rule.startMinute)}-${clockOf(rule.endMinute)}`
}

/**
 * 单行校验 —— 复刻 `TariffRuleSnapshot` 的构造器。
 *
 * ★★ 单行约束**只在一个地方守** ★★ 校验器不重复检查这些，否则
 *   「起点晚于终点」会在两个地方各有一条消息，而两者迟早会分叉。
 */
function snapshotOf(dto: TariffRuleDTO, index: number): RuleSnapshot | { msg: string } {
  if (dto.periodType === null || dto.periodType === undefined) {
    return { msg: '计费时段类型不能为空' }
  }
  if (!PERIOD_TYPE.byCode(dto.periodType)) {
    return { msg: `未知的时段类型：${dto.periodType}` }
  }

  const start = parseClock(dto.startTime)
  if (!start.ok) return { msg: clockErrorMessage(start.error, start.raw, true) }
  const end = parseClock(dto.endTime)
  if (!end.ok) return { msg: clockErrorMessage(end.error, end.raw, false) }

  // ★ 越界检查在解析里已经做过了（`parseClock` 只放行 0~1440），
  //   这里保留的是**起点必须早于终点**那条 —— 它是唯一一条解析看不出来的。
  //   ★ `>=` 而不是 `>`：零长度的时段是一个**永远匹配不到**的规则，
  //   它和「这条规则不存在」在计费时等价，但它在列表页上看起来完全正常。
  if (start.minutes >= end.minutes) {
    return {
      msg: `计费时段的起点必须早于终点：${clockOf(start.minutes)} - ${clockOf(end.minutes)}（第 ${index + 1} 行）`,
    }
  }

  if (dto.energyPrice === null || dto.energyPrice === undefined) {
    return { msg: `计费单价不能为空（第 ${index + 1} 行）` }
  }
  if ((toNumber(dto.energyPrice) ?? 0) < 0) {
    return { msg: `计费单价不能为负（第 ${index + 1} 行）` }
  }
  // ★ 服务费**允许为空**，落库前补 0（本列 NOT NULL）。后端在 `toSnapshots` 里
  //   做这件事，不是在构造器里 —— 「免服务费」是一个正常的产品配置。
  if (dto.servicePrice !== null && dto.servicePrice !== undefined) {
    if ((toNumber(dto.servicePrice) ?? 0) < 0) {
      return { msg: `服务费单价不能为负（第 ${index + 1} 行）` }
    }
  }

  return {
    periodType: dto.periodType,
    startMinute: start.minutes,
    endMinute: end.minutes,
    source: dto,
  }
}

/** `TimeCodec` 的四条解析失败消息。`start` 只用来决定「起点 / 终点」两个字。 */
function clockErrorMessage(error: ClockError, raw: string, isStart: boolean): string {
  if (error === 'EMPTY') return isStart ? '时段开始时间不能为空' : '时段结束时间不能为空'
  const what = isStart ? '时段开始时间' : '时段结束时间'
  if (error === 'FORMAT') {
    // ★ 起点和终点的合法范围**不一样**：起点不能是 `24:00:00`（那会让这条规则
    //   长度为 0），而终点可以。这是 `TariffRuleDTO` 上两条 `@Pattern` 的差异，
    //   不是笔误 —— 抄的时候别顺手统一。
    return isStart
      ? `${what}格式应为 HH:mm 或 HH:mm:ss，且不能为 24:00:00，收到：${raw}`
      : `${what}格式应为 HH:mm、HH:mm:ss，或 24:00:00，收到：${raw}`
  }
  if (error === 'HOUR24') return `24 点只允许写 24:00:00，收到：${raw}`
  return `时间超出范围（00:00:00 ~ 24:00:00）：${raw}`
}

/**
 * 复刻 `TariffValidator.validate`。
 *
 * ★★ 顺序：空 → 单行 → **重叠** → 缺口 ★★
 *   重叠排在缺口前面是有意的（见文件头第 3 条）。而单行校验排在两者之前，
 *   因为一条「起点晚于终点」的规则会让相邻比较算出没有意义的区间 ——
 *   拿它的 `endMinute` 去和别人比，报出来的缺口位置是错的。
 *
 * ★ 排序**不看 `seq`**：后端注释明写「校验器是最后一道关，不该有
 *   『你得先按顺序给我』的前置条件 —— 那个条件一旦没被满足，
 *   这里就会把合法数据判成非法」。`seq` 只影响展示，不参与校验。
 *
 * 返回 `null` 表示通过，否则是失败信封里的那句 msg。
 */
export function validateTariffRules(rules: readonly TariffRuleDTO[] | null | undefined): string | null {
  if (rules === null || rules === undefined || rules.length === 0) {
    return '计费规则不能为空，必须完整覆盖 00:00:00 ~ 24:00:00'
  }

  const snapshots: RuleSnapshot[] = []
  for (const [index, dto] of rules.entries()) {
    const snap = snapshotOf(dto, index)
    if ('msg' in snap) return snap.msg
    snapshots.push(snap)
  }
  snapshots.sort((a, b) => a.startMinute - b.startMinute)

  const gaps: string[] = []
  const overlaps: string[] = []

  // ★ 首条必须从 00:00 开始 —— 这一条**相邻比较发现不了**：没有「前一条」和它比。
  if (snapshots[0].startMinute !== 0) {
    gaps.push(`00:00:00-${clockOf(snapshots[0].startMinute)}`)
  }

  for (let i = 1; i < snapshots.length; i += 1) {
    const previous = snapshots[i - 1]
    const current = snapshots[i]
    if (previous.endMinute < current.startMinute) {
      gaps.push(`${clockOf(previous.endMinute)}-${clockOf(current.startMinute)}`)
    } else if (previous.endMinute > current.startMinute) {
      overlaps.push(
        `${describeRule(previous)} 与 ${describeRule(current)} 在 ` +
          `${clockOf(current.startMinute)}-${clockOf(previous.endMinute)} 重叠`,
      )
    }
  }

  // ★ 末条必须到 24:00 结束 —— 同样相邻比较发现不了：它没有「后一条」。
  const last = snapshots[snapshots.length - 1]
  if (last.endMinute !== MINUTES_PER_DAY) {
    gaps.push(`${clockOf(last.endMinute)}-24:00:00`)
  }

  if (overlaps.length > 0) {
    return `${TEXT.TARIFF_OVERLAP}：${overlaps.join('；')}`
  }
  if (gaps.length > 0) {
    return `${TEXT.TARIFF_NOT_COVER_24H}，缺失：${gaps.join('；')}`
  }
  return null
}

/** 把一串规则整体落库（先删后插），`seq` 按数组下标填。 */
function replaceRules(
  ctx: MockContext,
  templateId: number,
  rules: readonly TariffRuleDTO[],
  at: Date,
): void {
  // ★ **整体替换**而不是逐条更新：逐条会产生「中间状态」——
  //   比如删了一条尖峰还没插新的，此刻这个模板就不满足 24 小时覆盖。
  //   中间状态一旦能被观察到，就有人会去处理它，然后处理逻辑扩散到所有读路径上。
  for (let i = ctx.db.tariffRules.length - 1; i >= 0; i -= 1) {
    if (ctx.db.tariffRules[i].templateId === templateId) ctx.db.tariffRules.splice(i, 1)
  }
  rules.forEach((dto, index) => {
    const row: DbTariffRule = {
      id: ctx.db.nextId('tariffRule'),
      templateId,
      periodType: dto.periodType,
      // ★ 入库存的是**归一化之后**的字符串。零填充的 `HH:mm:ss` 是
      //   「字典序等于时间序」这条性质的前提，而 `ORDER BY start_time` 依赖它。
      startTime: normalizeClock(dto.startTime) ?? dto.startTime,
      endTime: normalizeClock(dto.endTime) ?? dto.endTime,
      energyPrice: toNumber(dto.energyPrice) ?? 0,
      servicePrice: toNumber(dto.servicePrice) ?? 0,
      // ★ `seq` 不传就按数组下标填 —— 前端的表单项通常没有「序号」这个概念。
      //   它**不是约束**（`uk_tariff_rule_tpl_start` 才是），只影响展示。
      seq: dto.seq ?? index + 1,
      createTime: at,
      updateTime: at,
    }
    ctx.db.tariffRules.push(row)
  })
}

// ══════════════════════════════════════════════════════════════════════════
// 计费模板
// ══════════════════════════════════════════════════════════════════════════

export function tariffTemplateHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/tariff-template/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        const name = strParam(query, 'name')
        const code = strParam(query, 'code')
        const region = strParam(query, 'region')
        const status = numParam(query, 'status')

        const rows = ctx.db.tariffTemplates.filter((t) => {
          if (name && !t.name.includes(name)) return false
          // ★ `code` 是**前缀**匹配，和站点编码一样 —— 模板编码是人工编的，
          //   前缀有意义（`BJ-` 开头的都是北京的价）。
          if (code && !t.code.startsWith(code)) return false
          if (region && t.region !== region) return false
          if (status !== undefined && t.status !== status) return false
          return true
        })
        rows.sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({
          total: page.total,
          // ★ 列表里带规则（后端 `toVo(template, rules)` 的第二个参数是
          //   「要不要装配规则」）。不带的话，列表页想显示「几条规则」都要再发一次请求。
          records: page.records.map((t) => toTariffTemplateVo(ctx.db, t, true)),
        })
      }),
    ),

    /**
     * 计费模板下拉用的**裸数组**接口。
     *
     * ★★ 路由注册顺序：这条必须排在 `/:id` **之前** ★★
     *   MSW 按注册顺序匹配，`/api/admin/tariff-template/list-enabled`
     *   也会命中 `/api/admin/tariff-template/:id`（`:id` = `list-enabled`）。
     */
    http.get('/api/admin/tariff-template/list-enabled', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const rows = ctx.db.tariffTemplates.filter((t) => t.status === 1)
        // ★ `ORDER BY id`（**升序**）而不是 `id DESC`：这是下拉框的顺序，
        //   老模板排在前面更符合「先建的先用」的直觉。列表页用倒序是另一回事。
        rows.sort((a, b) => a.id - b.id)
        // ★ 这个接口**不能加分页参数** —— 后端注释的原话是
        //   「一个只能选到前 10 个模板的下拉框会比没有下拉框更糟，
        //     因为它看起来是好的」。所以这里返回**全部**。
        // ★ 不带规则：下拉只需要名字和编码，装配规则等于白读一遍 tariff_rule。
        return ok(rows.map((t) => toTariffTemplateVo(ctx.db, t, false)))
      }),
    ),

    http.get('/api/admin/tariff-template/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const template = ctx.db.templateById(Number(params.id))
        if (!template) return fail(TEXT.TEMPLATE_NOT_FOUND)
        return ok(toTariffTemplateVo(ctx.db, template, true))
      }),
    ),

    http.post('/api/admin/tariff-template', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as TariffTemplateDTO
        const missing = missingRequired([
          [dto.code, '模板编码不能为空'],
          [dto.name, '模板名称不能为空'],
          // ★ `@NotEmpty` 与 `@NotNull` 的区别在这里生效：`rules: []` 会被
          //   `@NotEmpty` 拦下，而 `rules: null` 会被 `@NotNull` 拦下。
          //   `missingRequired` 对空数组判空，正好覆盖两者。
          [dto.rules, '计费规则不能为空'],
        ])
        if (missing) return missing

        if (ctx.db.tariffTemplates.some((t) => t.code === dto.code)) {
          return fail(alreadyExists(dto.code))
        }

        // ★ 校验在**插入之前** —— 一个校验失败的请求不该留下半张模板。
        const invalid = validateTariffRules(dto.rules)
        if (invalid) return fail(invalid)

        const now = ctx.db.now()
        const row: DbTariffTemplate = {
          id: ctx.db.nextId('tariffTemplate'),
          code: dto.code,
          name: dto.name,
          region: dto.region ?? null,
          description: dto.description ?? null,
          // ★ 默认**启用**（`status = 1`）—— 这与站点/桩/枪都不同：
          //   那些默认「离线 / 停用」，因为它们是硬件；模板是纯配置，
          //   建好就是为了能用。建成停用的会让人以为「建完了怎么选不到」。
          status: dto.status ?? 1,
          createTime: now,
          updateTime: now,
        }
        ctx.db.tariffTemplates.push(row)
        replaceRules(ctx, row.id, dto.rules, now)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/tariff-template/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const row = ctx.db.tariffTemplates.find((t) => t.id === id)
        if (!row) return fail(TEXT.TEMPLATE_NOT_FOUND)

        const dto = (await request.json().catch(() => ({}))) as TariffTemplateUpdateDTO
        const now = ctx.db.now()

        // ★ 头部的四个字段**不受守卫约束** —— 它们不改变任何一条规则，
        //   所以不影响已冻结的订单。改名称、地区、说明、状态都不需要检查绑定。
        if (dto.code !== undefined) {
          if (ctx.db.tariffTemplates.some((t) => t.code === dto.code && t.id !== id)) {
            return fail(alreadyExists(dto.code))
          }
          row.code = dto.code
        }
        if (dto.name !== undefined) row.name = dto.name
        if (dto.region !== undefined) row.region = dto.region
        if (dto.description !== undefined) row.description = dto.description
        if (dto.status !== undefined) row.status = dto.status
        row.updateTime = now

        // ★★ `rules` 为空（`null` **或** `[]`）表示「不修改规则」★★
        //   让一个空数组去**清空**一个正在用的模板的规则，会让所有用这个模板的
        //   站点在下单时算出空金额 —— 那个后果和「用户手滑提交了一个空表单」
        //   完全不成比例。所以两种情况都当成「不修改」。
        const rules = dto.rules
        if (rules !== null && rules !== undefined && rules.length > 0) {
          // ★★ 守卫：模板已被站点绑定引用时，**不许改规则** ★★
          //   注意守卫只挡规则、不挡头部（上面已经改完了）——
          //   这正是后端把 `saveRules` 单独抽出来的原因。
          //   判据是「有没有绑定」，不是「有没有订单」：绑定是**更早、更全**的
          //   那一问 —— 一个刚被绑定的模板哪怕还没有订单用它也不该被改，
          //   因为下一笔订单随时会来。
          const bound = ctx.db.stationTariffs.filter((b) => b.templateId === id).length
          if (bound > 0) return fail(TEXT.TEMPLATE_IN_USE)

          // ★ 校验必须排在删除**之前**。反过来写的话，一个校验失败的请求会先把
          //   旧规则删掉再报错 —— 事务回滚能撤销，但那个瞬间库里是空的，
          //   而且回滚是靠 `@Transactional` 生效的：万一这个方法被挪进一个
          //   非事务的调用链，数据就真没了。
          const invalid = validateTariffRules(rules)
          if (invalid) return fail(invalid)
          replaceRules(ctx, id, rules, now)
        }
        return okVoid()
      }),
    ),

    http.delete('/api/admin/tariff-template/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        if (!ctx.db.templateById(id)) return fail(TEXT.TEMPLATE_NOT_FOUND)

        if (ctx.db.stationTariffs.some((b) => b.templateId === id)) {
          return fail(TEXT.TEMPLATE_IN_USE)
        }

        // ★★ 删模板**不级联删规则**，是刻意照抄后端的 ★★
        //   级联删除会让「删掉模板」这个动作在后台顺手抹掉 8 行计价数据，
        //   那是不可恢复的。孤儿规则至少可查、可人工清理。
        //   mock 里同样留下它们 —— 否则「删掉模板后老订单详情还能显示计价依据」
        //   这条演示就做不出来了。
        ctx.db.tariffTemplates.splice(
          ctx.db.tariffTemplates.findIndex((t) => t.id === id),
          1,
        )
        return okVoid()
      }),
    ),
  ]
}

// ══════════════════════════════════════════════════════════════════════════
// 站点计费绑定
// ══════════════════════════════════════════════════════════════════════════

/**
 * 生效区间校验 —— 复刻 `StationServiceImpl` 之外唯一一条跨字段时间规则。
 *
 * ★★ `effectiveTo` 为 `null` 表示**长期有效**，而且它是最常见的情况 ★★
 *   所以第一件事就是判空返回。这个判空**必须在方法内部**，不能靠调用点各自去判 ——
 *   后端注释里写了冒烟测试踩到的原话：`bind` 一开始没判空，于是
 *   「不填失效时刻」这个最常见的合法请求抛 NPE、被兜底翻成 HTTP 500，
 *   一个完全正常的操作看起来像服务挂了。
 *
 * ★ 区间是**半开**的 `[effectiveFrom, effectiveTo)`：`effectiveTo == effectiveFrom`
 *   是一个**空区间**，永远不会被 `resolveTemplateAt` 命中。
 *   为什么要拦它：一条永远不会生效的绑定和一条正常绑定在列表页上**长得一模一样**——
 *   运维建了它、看到它在列表里、以为配置好了，而系统在该站点下单时会说
 *   「没有配置计费方案」。
 */
function assertEffectiveRange(from: Date | null, to: Date | null): string | null {
  if (to === null) return null
  if (from === null || to.getTime() <= from.getTime()) {
    return effectiveRangeInvalid(tsOf(from), tsOf(to))
  }
  return null
}

/** 只在错误消息里用。`null` 写成 `-`，因为那句消息会直接给用户看。 */
function tsOf(at: Date | null): string {
  if (at === null) return '-'
  return (
    `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}-${String(at.getDate()).padStart(2, '0')} ` +
    `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}:${String(at.getSeconds()).padStart(2, '0')}`
  )
}

export function stationTariffHandlers(ctx: MockContext): RequestHandler[] {
  return [
    /**
     * 某站点的**全部**绑定（裸数组，没有分页信封）。
     *
     * ★★ 这个域**没有 `GET /page`** ★★ 绑定列表永远是「按站点查」的 ——
     *   它就是这个站点的**调价历史**。所以页面形态是「先选站点，再展示列表」，
     *   而不是一个平铺的分页表格。返回分页信封会让前端读到两个 `undefined`。
     */
    http.get('/api/admin/station-tariff/by-station/:stationId', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const stationId = Number(params.stationId)
        const rows = ctx.db.stationTariffs.filter((b) => b.stationId === stationId)
        // ★ 排序是 `effective_from DESC, id DESC`：最近生效的在最上面。
        //   `effectiveFrom` 可空（null = 一直有效），那些排在最后 ——
        //   和 MySQL 的 `ORDER BY` 行为一致（`NULL` 在 ASC 里最小，
        //   而 DESC 里 `NULL` 最大…… 所以这里显式把它们压到最后，
        //   与 `resolveTemplateAt` 的候选筛选保持一致）。
        rows.sort((a, b) => {
          const af = a.effectiveFrom?.getTime() ?? null
          const bf = b.effectiveFrom?.getTime() ?? null
          if (af !== bf) {
            if (af === null) return 1
            if (bf === null) return -1
            return bf - af
          }
          return b.id - a.id
        })
        return ok(rows.map((b) => toStationTariffVo(ctx.db, b)))
      }),
    ),

    http.get('/api/admin/station-tariff/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const binding = ctx.db.stationTariffs.find((b) => b.id === Number(params.id))
        if (!binding) return fail(TEXT.STATION_TARIFF_NOT_FOUND)
        return ok(toStationTariffVo(ctx.db, binding))
      }),
    ),

    http.post('/api/admin/station-tariff', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as StationTariffDTO
        const missing = missingRequired([
          [dto.stationId, '站点不能为空'],
          [dto.templateId, '计费模板不能为空'],
          // ★★ 这是一处**前端类型与后端契约不一致**的地方 ★★
          //   后端的 `StationTariffDTO.effectiveFrom` 上有 `@NotNull`（**必填**），
          //   而 shared 的 `StationTariffDTO` 把它写成了 `effectiveFrom?` / 可空。
          //   类型上允许省略的字段，后端会拒绝 —— mock 按**后端**判，
          //   所以这里必须有这条校验（否则演示时「建绑定成功」而真后端会报错）。
          [dto.effectiveFrom, '生效时刻不能为空'],
        ])
        if (missing) return missing

        // ★ 顺序照抄后端：站点 → 模板 → 停用检查 → 默认值 → 区间校验。
        //   顺序决定了用户先看到哪一句，而这三句指向三个完全不同的排查方向。
        if (!ctx.db.stationById(dto.stationId)) return fail(TEXT.STATION_NOT_FOUND)
        const template = ctx.db.templateById(dto.templateId)
        if (!template) return fail(TEXT.TEMPLATE_NOT_FOUND)

        // ★ 停用的模板不能用于【新建】绑定 —— status 的语义就是
        //   「这个模板还能不能被新绑定」。不拦的话，停用就是一个不完整的操作：
        //   管理员停用了它，但仍然有人能拿它去配新站点。
        //   ★ 这不影响**已经存在**的绑定：那些绑定有自己的行，解析时不经过这里 ——
        //   「停用模板不会改变现有站点计费」这个承诺就是靠这两条路径实现的。
        if (template.status === 0) return fail(TEXT.TEMPLATE_DISABLED)

        // ★ `dto.effectiveFrom` 在 shared 的类型里是可选的（后端是 `@NotNull`，
        //   上面那条 `missingRequired` 就是为这个差异存在的），TS 看不见那层保证。
        //   这里顺手把它收成 string，同时**原样留住用户敲的那个串** ——
        //   下面的唯一索引冲突要报的正是它，不是格式规整后的版本。
        const fromRaw = String(dto.effectiveFrom)
        const from = parseServerTime(fromRaw)
        if (!from) return fail('生效时刻格式应为 yyyy-MM-dd HH:mm:ss，收到：' + fromRaw)
        const to = dto.effectiveTo ? parseServerTime(dto.effectiveTo) : null
        if (dto.effectiveTo && !to) {
          return fail('失效时刻格式应为 yyyy-MM-dd HH:mm:ss，收到：' + dto.effectiveTo)
        }

        const invalid = assertEffectiveRange(from, to)
        if (invalid) return fail(invalid)

        // ★ `uk_station_tariff_start (station_id, effective_from)`：
        //   同一个站点在**同一时刻**不能有两条绑定 —— 否则 `resolveTemplateAt`
        //   的两条候选优先级完全相同，谁赢是随机的，表现是
        //   「同一张单，刷新两次价格不一样」。
        const clash = ctx.db.stationTariffs.some(
          (b) => b.stationId === dto.stationId && b.effectiveFrom?.getTime() === from.getTime(),
        )
        if (clash) return fail(alreadyExists(fromRaw))

        const now = ctx.db.now()
        const row: DbStationTariff = {
          id: ctx.db.nextId('stationTariff'),
          stationId: dto.stationId,
          templateId: dto.templateId,
          effectiveFrom: from,
          effectiveTo: to,
          // ★ 两个默认值对应后端那两个 `if (x == null)`：字段初始值挡不住
          //   显式 JSON null，而这两列都是 NOT NULL。
          priority: dto.priority ?? 0,
          status: dto.status ?? 1,
          createTime: now,
          updateTime: now,
        }
        ctx.db.stationTariffs.push(row)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/station-tariff/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        // ★ 必须先把库里那一行读出来 —— 因为跨字段校验要比的 `effectiveFrom`
        //   **不在 DTO 里**（它是身份的一部分）。这也正是
        //   「绑定关系不可改、要换模板必须删了重建」这条规则的由来。
        const row = ctx.db.stationTariffs.find((b) => b.id === id)
        if (!row) return fail(TEXT.STATION_TARIFF_NOT_FOUND)

        const dto = (await request.json().catch(() => ({}))) as StationTariffUpdateDTO

        // ★★ `null` 与 `undefined` 都表示「不修改」★★
        //   `StationTariffMapper.update` 是 `<set>` + `<if>`，显式 `null` 会被跳过；
        //   而 `assertEffectiveRange` 收到 `null` 会立刻返回（长期有效）——
        //   两处合起来的效果就是「传 null 等于没传」。
        //   ★ 顺带一个**有意留下的缺口**：因此**没有**办法把一条已有的绑定
        //     改成「长期有效」（清空 `effectiveTo`）。这是后端的现状。
        if (dto.effectiveTo !== undefined && dto.effectiveTo !== null) {
          const to = parseServerTime(dto.effectiveTo)
          if (!to) return fail('失效时刻格式应为 yyyy-MM-dd HH:mm:ss，收到：' + dto.effectiveTo)
          const invalid = assertEffectiveRange(row.effectiveFrom, to)
          if (invalid) return fail(invalid)
          row.effectiveTo = to
        }
        if (dto.priority !== undefined && dto.priority !== null) row.priority = dto.priority
        if (dto.status !== undefined && dto.status !== null) row.status = dto.status
        row.updateTime = ctx.db.now()
        return okVoid()
      }),
    ),

    http.delete('/api/admin/station-tariff/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        if (!ctx.db.stationTariffs.some((b) => b.id === id)) {
          return fail(TEXT.STATION_TARIFF_NOT_FOUND)
        }

        // ★ **没有守卫**，和别的域不同 —— 绑定没有「子记录」，而且删掉一条绑定
        //   不会让任何已有订单失去依据（订单冻结的是**模板 id**，和这条绑定行无关）。
        //   唯一的代价是丢掉一段历史：那个时期在 station_tariff 里变成空白，
        //   事后追查老订单为什么用了这个价时没有依据。
        ctx.db.stationTariffs.splice(
          ctx.db.stationTariffs.findIndex((b) => b.id === id),
          1,
        )
        return okVoid()
      }),
    ),
  ]
}
