/**
 * 设备三兄弟：**充电站 / 充电桩 / 充电枪**（管理端）。
 *
 * ★★ 为什么这三个域合在一个文件里 ★★
 *   它们是**一条级联的外键链**（枪 → 桩 → 站），而且级联关系在业务上是有意义的：
 *   · 「站点下还有桩」→ 不许删站
 *   · 「桩下还有枪」→ 不许删桩
 *   · 「枪上有进行中的订单」→ 不许删枪
 *   三道守卫是同一个形状，放在一起才看得出来它们是**一层层往回问**的。
 *   拆成三个文件的话，读代码的人会在三个地方分别看到一条 `countByXxxId`，
 *   而看不出「删站会连带问两遍」这件事。
 *
 * ★★ 另一个理由：只有**充电枪**有乐观锁 ★★
 *   站和桩的 UpdateDTO 里刻意没有 `version`（改一行不需要知道它被谁改过），
 *   而枪有 —— 因为枪是**会被设备心跳和人工同时改**的那一层。
 *   把三者并排放，这个差异才是一个「看得到的决定」而不是三次巧合。
 *
 * ★ 站点的营业时间校验（`assertBusinessHours`）是本站独有的一条跨字段规则，
 *   它的**两条禁忌**都值得看清楚：`open == close`（会被所有下游解读成全天营业）
 *   和 `close < open`（不支持跨夜营业）。
 */

import { http } from 'msw'
import type { RequestHandler } from 'msw'
import type {
  ChargerDTO,
  ChargerUpdateDTO,
  ConnectorDTO,
  ConnectorUpdateDTO,
  StationDTO,
  StationUpdateDTO,
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
import {
  alreadyExists,
  chargerHasConnector,
  chargerHasOrder,
  stationHasCharger,
  stationHasOrder,
  TEXT,
} from './texts'
import { toChargerVo, toConnectorVo, toStationVo } from './vo'
import { CONNECTOR_ADMIN_SETTABLE } from '../../constants/dicts'
import { clockOf, normalizeClock, parseClock } from './timecodec'
import type { ClockError } from './timecodec'
import type { DbCharger, DbConnector, DbStation } from '../db'
import { toCents } from '../db'
import { BLOCKING_STATUSES, HOLDING_CONNECTOR_STATUSES } from '../../constants/orderStatus'

/**
 * `ConnectorStatus` / `ChargerStatus` / `StationStatus` 的码值。
 *
 * ★★ 这里写**字面量**而不是 `CONNECTOR_STATUS.OFFLINE` ★★
 *   `constants/dicts.ts` 里的 `createDict` 产出的是 `{list, byCode, label,
 *   tone, options}` —— **没有**按名字取码值的成员（后端那三个枚举里有
 *   `OFFLINE` 这样的常量，字典没有）。写 `CONNECTOR_STATUS.OFFLINE`
 *   在类型上就不存在。
 *
 * ★ 之所以不在这里新导出一组 `CONNECTOR_STATUS_CODE = {OFFLINE: 0, ...}`：
 *   那就等于给同一组码值建了**第二个真源**，而字典那一份是逐条对齐后端
 *   `getLabel()` 的。这里只在需要码值的五个地方就地写字面量，
 *   旁边注明字典里的中文，读的时候一眼能对上。
 */
const GUN_OFFLINE = 0 // CONNECTOR_STATUS「离线」
const GUN_OCCUPIED = 2 // 「已占用」
const GUN_CHARGING = 3 // 「充电中」
const CHARGER_OFFLINE = 0 // CHARGER_STATUS「离线」
const STATION_ENABLED = 1 // STATION_STATUS「启用」

// ══════════════════════════════════════════════════════════════════════════
// 营业时间
// ══════════════════════════════════════════════════════════════════════════

/**
 * 复刻 `StationServiceImpl.assertBusinessHours`：**结束必须严格晚于开始**。
 *
 * ★ 两种失败是同一句话，但原因完全不同，抄的时候都留着：
 *   · `open == close` 看起来像「不营业」，但下游会读成「全天营业」——
 *     零长度的休息区间和没有休息区间是一回事。真要不营业请用「停用」。
 *   · `close < open` 是「跨夜营业」，本项目**不支持**：这张表只有一对
 *     open/close，「次日」没有位置。要支持是 schema 变更，不是加一行业务代码。
 *
 * ★ 解析失败的文案**逐字**抄自 `TimeCodec.toMinuteOfDay` 的四条。
 *   它们不会被静默吞掉：格式错的营业时间若被读成 `00:00:00`，
 *   得到的会是一个「全天营业」的站点 —— 一个数据错误伪装成了一条正常数据。
 */
function assertBusinessHours(openTime: string, closeTime: string): string | null {
  const open = parseClock(openTime)
  if (!open.ok) return clockErrorMessage(open.error, open.raw)
  const close = parseClock(closeTime)
  if (!close.ok) return clockErrorMessage(close.error, close.raw)

  if (close.minutes <= open.minutes) {
    return (
      `结束营业时间必须晚于开始营业时间：${clockOf(open.minutes)} - ${clockOf(close.minutes)}` +
      `（本项目不支持跨夜营业，如需 22:00-06:00 请先调整数据结构）`
    )
  }
  return null
}

/** `TimeCodec` 的四条解析失败消息，逐字。 */
function clockErrorMessage(error: ClockError, raw: string): string {
  if (error === 'EMPTY') return '时间不能为空'
  if (error === 'FORMAT') return `时间格式应为 HH:mm 或 HH:mm:ss，收到：${raw}`
  if (error === 'HOUR24') return `24 点只允许写 24:00:00，收到：${raw}`
  return `时间超出范围（00:00:00 ~ 24:00:00）：${raw}`
}

// ══════════════════════════════════════════════════════════════════════════
// 计数（删除守卫用）
// ══════════════════════════════════════════════════════════════════════════

/**
 * 站点下**未完结**的订单数。
 *
 * ★★ 判据是 `blockingCodes()` 而不是「有没有订单」★★
 *   已支付 / 已退款 / 已取消的订单**不**妨碍删除。写成「有没有订单」的话，
 *   任何完成过一次充电的站点都会变成永久不可删 ——
 *   那是把一个可恢复的运维操作变成了不可恢复的。
 */
function blockingOrdersOfStation(ctx: MockContext, stationId: number): number {
  return ctx.db.orders.filter(
    (o) => o.stationId === stationId && BLOCKING_STATUSES.includes(o.status),
  ).length
}

function blockingOrdersOfCharger(ctx: MockContext, chargerId: number): number {
  return ctx.db.orders.filter(
    (o) => o.chargerId === chargerId && BLOCKING_STATUSES.includes(o.status),
  ).length
}

// ══════════════════════════════════════════════════════════════════════════
// 充电站
// ══════════════════════════════════════════════════════════════════════════

/** 把 DTO / UpdateDTO 的字段并进一行。`undefined` 一律按「不修改」处理。 */
function mergeStation(row: DbStation, dto: StationUpdateDTO, now: Date): void {
  if (dto.code !== undefined) row.code = dto.code
  if (dto.name !== undefined) row.name = dto.name
  if (dto.operator !== undefined) row.operator = dto.operator
  if (dto.province !== undefined) row.province = dto.province
  if (dto.city !== undefined) row.city = dto.city
  if (dto.district !== undefined) row.district = dto.district
  if (dto.address !== undefined) row.address = dto.address
  if (dto.longitude !== undefined) row.longitude = toNumber(dto.longitude)
  if (dto.latitude !== undefined) row.latitude = toNumber(dto.latitude)
  if (dto.servicePhone !== undefined) row.servicePhone = dto.servicePhone
  // ★ `normalizeClock` 可能给 `null`（格式不合法），但调用点已经用
  //   `assertBusinessHours` 拦过一道，所以这里的 `??` 只是类型上的兜底，
  //   真落到兜底分支时是「保持原值」而不是「写成 00:00:00」。
  if (dto.openTime !== undefined) row.openTime = normalizeClock(dto.openTime) ?? row.openTime
  if (dto.closeTime !== undefined) row.closeTime = normalizeClock(dto.closeTime) ?? row.closeTime
  // ★ 停车费在库里是**分**，接口上是**元** —— 进出各转一次，见 `db.ts` 文件头。
  if (dto.parkingFee !== undefined) {
    row.parkingFee = dto.parkingFee === null ? null : toCents(dto.parkingFee)
  }
  if (dto.status !== undefined) row.status = dto.status
  row.updateTime = now
}

export function stationHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/station/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        // ★ 四种过滤的**匹配方式各不相同**，这是抄 `StationMapper.xml` 抄来的：
        //   name 是两侧模糊、code 是**前缀**（站点编码是人工编的，前缀有意义）、
        //   city 与 status 是等值。把 code 也写成两侧模糊会让
        //   「BJ」搜出「SHBJS001」这种毫不相干的东西。
        const name = strParam(query, 'name')
        const code = strParam(query, 'code')
        const city = strParam(query, 'city')
        const status = numParam(query, 'status')

        const rows = ctx.db.stations.filter((s) => {
          if (name && !s.name.includes(name)) return false
          if (code && !s.code.startsWith(code)) return false
          if (city && s.city !== city) return false
          if (status !== undefined && s.status !== status) return false
          return true
        })
        rows.sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({ total: page.total, records: page.records.map(toStationVo) })
      }),
    ),

    http.get('/api/admin/station/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const station = ctx.db.stationById(Number(params.id))
        if (!station) return fail(TEXT.STATION_NOT_FOUND)
        return ok(toStationVo(station))
      }),
    ),

    http.post('/api/admin/station', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as StationDTO
        const missing = missingRequired([
          [dto.code, '站点编码不能为空'],
          [dto.name, '站点名称不能为空'],
        ])
        if (missing) return missing

        // ★★ 唯一索引 `uk_station_code` 的冲突**必须在插入前判** ★★
        //   真后端是插进去撞索引、由 `GlobalExceptionHandler` 翻成人话；
        //   内存库没有索引，只能自己查一遍。两者的**输出必须一致**：
        //   `<code>已存在`（值在前，没有分隔词），因为那句话是从
        //   MySQL 的 `Duplicate entry '...' for key` 里抠出来的。
        if (ctx.db.stations.some((s) => s.code === dto.code)) {
          return fail(alreadyExists(dto.code))
        }

        const now = ctx.db.now()
        const row: DbStation = {
          id: ctx.db.nextId('station'),
          code: dto.code,
          name: dto.name,
          operator: dto.operator ?? null,
          province: dto.province ?? null,
          city: dto.city ?? null,
          district: dto.district ?? null,
          address: dto.address ?? null,
          longitude: toNumber(dto.longitude),
          latitude: toNumber(dto.latitude),
          servicePhone: dto.servicePhone ?? null,
          // ★★ 两个默认值来自 `StationDTO` 的**字段初始值**，不是 DDL 默认 ★★
          //   Spring 绑定不到参数时字段保留初始值，所以「没传」等价于「传了这两个」。
          //   写成 null 会让库里的 TIME 列变成 NULL，而列表页那一列会显示空白 ——
          //   看起来像「这个站点没有营业时间」，实际是 mock 少补了一个默认值。
          openTime: normalizeClock(dto.openTime ?? '00:00:00') ?? '00:00:00',
          closeTime: normalizeClock(dto.closeTime ?? '24:00:00') ?? '24:00:00',
          parkingFee:
            dto.parkingFee === undefined || dto.parkingFee === null
              ? null
              : toCents(dto.parkingFee),
          status: dto.status ?? STATION_ENABLED,
          createTime: now,
          updateTime: now,
        }

        // ★ 解析失败的消息由 `assertBusinessHours` 从**原始入参**报出来 ——
        //   拿已经兜过底的 `row.openTime` 去报，收到的是 `格式应为 HH:mm` 却看不到
        //   用户到底填了什么，而那句话是让用户自己改对的唯一线索。
        const hours = assertBusinessHours(
          dto.openTime ?? '00:00:00',
          dto.closeTime ?? '24:00:00',
        )
        if (hours) return fail(hours)

        ctx.db.stations.push(row)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/station/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const dto = (await request.json().catch(() => ({}))) as StationUpdateDTO

        // ★★ 这里刻意**不预检「这一行是否存在」** ★★
        //   后端注释写得很明白：`<set>` + `<if>` 的更新在 id 不存在时影响 0 行、
        //   不报错，而「改一个不存在的站点」和「改一个所有字段都为空的站点」
        //   本该表现一致 —— 都没改到任何东西。
        //   代价是多一次查询只换来一句更明确的错误消息。
        const existing = ctx.db.stationById(id)

        // ★★ 但如果**动了营业时间**，就必须查了 ★★
        //   跨字段校验要拿**合并后的行**来做：「把关门时间改成 06:00」这个请求里
        //   只有一个字段，单看它看不出问题 —— 非法性来自它和库里那个 open_time 的关系。
        //   代价是多一次查询，可接受 —— 这个接口的调用频率以「天」计。
        if (dto.openTime !== undefined || dto.closeTime !== undefined) {
          if (!existing) return fail(TEXT.STATION_NOT_FOUND)
          const hours = assertBusinessHours(
            dto.openTime ?? existing.openTime ?? '',
            dto.closeTime ?? existing.closeTime ?? '',
          )
          if (hours) return fail(hours)
        }

        if (dto.code !== undefined && existing) {
          const clash = ctx.db.stations.some((s) => s.code === dto.code && s.id !== id)
          if (clash) return fail(alreadyExists(dto.code))
        }

        if (existing) mergeStation(existing, dto, ctx.db.now())
        return okVoid()
      }),
    ),

    http.delete('/api/admin/station/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const station = ctx.db.stationById(id)
        if (!station) return fail(TEXT.STATION_NOT_FOUND)

        // ★★ 两道守卫，**顺序是有意的** ★★
        //   先查桩：那句「请先删除或转移」是**可执行**的，命中的管理员当场就能照做，
        //   而且它是最便宜的一次计数。
        //   再查订单：它挡的是第一道**挡不住**的情形 —— 桩都挪走了，
        //   但历史订单的 station_id 还指着这个站。一个站点在有人充电时被删掉，
        //   那笔订单会失去站点归属，而订单是 append-only 的，悬空引用永久留在历史里。
        const chargerCount = ctx.db.chargers.filter((c) => c.stationId === id).length
        if (chargerCount > 0) return fail(stationHasCharger(chargerCount))

        const orderCount = blockingOrdersOfStation(ctx, id)
        if (orderCount > 0) return fail(stationHasOrder(orderCount))

        // ★ 硬删**必须存在**：`uk_station_code` 不看 status，
        //   站点编码建错一次就永久占用，没有硬删就只能去生产库手敲 DELETE。
        const idx = ctx.db.stations.findIndex((s) => s.id === id)
        ctx.db.stations.splice(idx, 1)
        return okVoid()
      }),
    ),
  ]
}

// ══════════════════════════════════════════════════════════════════════════
// 充电桩
// ══════════════════════════════════════════════════════════════════════════

/** 重算桩上的 `gunCount` —— 复刻 `connectorMapper.refreshGunCount`。 */
function refreshGunCount(ctx: MockContext, chargerId: number, at: Date): void {
  const charger = ctx.db.chargerById(chargerId)
  if (!charger) return
  // ★ 是**重算**而不是自增。自增在「有枪被删过」或「上一次事务回滚过」时
  //   会错得无法恢复，而重算永远是精确值。
  charger.gunCount = ctx.db.connectors.filter((c) => c.chargerId === chargerId).length
  charger.updateTime = at
}

export function chargerHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/charger/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        const stationId = numParam(query, 'stationId')
        const deviceNo = strParam(query, 'deviceNo')
        const name = strParam(query, 'name')
        const type = numParam(query, 'type')
        const status = numParam(query, 'status')

        const rows = ctx.db.chargers.filter((c) => {
          if (stationId !== undefined && c.stationId !== stationId) return false
          if (deviceNo && !c.deviceNo.startsWith(deviceNo)) return false
          if (name && !c.name.includes(name)) return false
          if (type !== undefined && c.type !== type) return false
          if (status !== undefined && c.status !== status) return false
          return true
        })
        rows.sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({ total: page.total, records: page.records.map((c) => toChargerVo(ctx.db, c)) })
      }),
    ),

    http.get('/api/admin/charger/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const charger = ctx.db.chargerById(Number(params.id))
        if (!charger) return fail(TEXT.CHARGER_NOT_FOUND)
        return ok(toChargerVo(ctx.db, charger))
      }),
    ),

    http.post('/api/admin/charger', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as ChargerDTO
        const missing = missingRequired([
          [dto.stationId, '所属站点不能为空'],
          [dto.deviceNo, '设备编号不能为空'],
          [dto.name, '充电桩名称不能为空'],
          [dto.type, '充电桩类型不能为空'],
          [dto.ratedPowerKw, '额定功率不能为空'],
        ])
        if (missing) return missing

        // ★ 先确认站点存在。这**不是**为了保住完整性（本项目的 MySQL 外键是关掉的），
        //   而是为了给出一句**能解释的消息**：不查的话，一个建在不存在的站点下的桩
        //   会被安静地插进去，然后永远不出现在任何按站点过滤的列表里 ——
        //   管理员看到的是一句「创建成功」和一块空白列表。
        if (!ctx.db.stationById(dto.stationId)) return fail(TEXT.STATION_NOT_FOUND)

        if (ctx.db.chargers.some((c) => c.deviceNo === dto.deviceNo)) {
          return fail(alreadyExists(dto.deviceNo))
        }

        const now = ctx.db.now()
        const row: DbCharger = {
          id: ctx.db.nextId('charger'),
          stationId: dto.stationId,
          deviceNo: dto.deviceNo,
          name: dto.name,
          type: dto.type,
          model: dto.model ?? null,
          ratedPowerKw: toNumber(dto.ratedPowerKw),
          // ★★ 给 0，**不是** DDL 默认的 1 ★★
          //   这一列的事实来源是 connector 表，而刚建好的桩一把枪都没有。
          //   默认值 1 是给「一桩一枪」省事的近似，代价是列表页会显示一把不存在的枪。
          //   「近似但错的值」不如「暂时不完整但正确」。
          gunCount: 0,
          // ★★ 新建的桩只能是 0 离线 ★★
          //   它还没和任何硬件建立过连接，说它「在线空闲」需要一个从未发生过的握手。
          //   之后由心跳上报，或管理员在更新接口上手工设成故障/维护中 ——
          //   所以 `ChargerDTO` 里根本没有 status 这个字段。
          status: CHARGER_OFFLINE,
          lastHeartbeatTime: dto.lastHeartbeatTime ? new Date(dto.lastHeartbeatTime) : null,
          firmwareVersion: dto.firmwareVersion ?? null,
          createTime: now,
          updateTime: now,
        }
        ctx.db.chargers.push(row)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/charger/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        // ★★ 没有乐观锁，也没有存在性检查 ★★
        //   `ChargerUpdateDTO` 里没有 `version`（站和桩都不需要 —— 它们不会被
        //   设备心跳和人工同时改），`ChargerMapper.update` 也是 `<set>` + `<if>`，
        //   影响 0 行不报错。所以这里对不存在的 id 静默成功，与后端一致。
        const row = ctx.db.chargerById(Number(params.id))
        if (!row) return okVoid()

        const dto = (await request.json().catch(() => ({}))) as ChargerUpdateDTO
        const now = ctx.db.now()
        // ★ 注意 DTO 里**没有** stationId / gunCount / lastHeartbeatTime，
        //   所以「把桩挪到别的站」在接口形状上就不可表达 —— 想挪只能删了重建。
        if (dto.deviceNo !== undefined) {
          if (ctx.db.chargers.some((c) => c.deviceNo === dto.deviceNo && c.id !== row.id)) {
            return fail(alreadyExists(dto.deviceNo))
          }
          row.deviceNo = dto.deviceNo
        }
        if (dto.name !== undefined) row.name = dto.name
        if (dto.type !== undefined) row.type = dto.type
        if (dto.model !== undefined) row.model = dto.model
        if (dto.ratedPowerKw !== undefined) row.ratedPowerKw = toNumber(dto.ratedPowerKw)
        if (dto.status !== undefined) row.status = dto.status
        if (dto.firmwareVersion !== undefined) row.firmwareVersion = dto.firmwareVersion
        row.updateTime = now
        return okVoid()
      }),
    ),

    http.delete('/api/admin/charger/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const charger = ctx.db.chargerById(id)
        if (!charger) return fail(TEXT.CHARGER_NOT_FOUND)

        const connectorCount = ctx.db.connectors.filter((c) => c.chargerId === id).length
        if (connectorCount > 0) return fail(chargerHasConnector(connectorCount))

        const orderCount = blockingOrdersOfCharger(ctx, id)
        if (orderCount > 0) return fail(chargerHasOrder(orderCount))

        ctx.db.chargers.splice(ctx.db.chargers.findIndex((c) => c.id === id), 1)
        return okVoid()
      }),
    ),
  ]
}

// ══════════════════════════════════════════════════════════════════════════
// 充电枪  ★ 全项目唯一带乐观锁的实体
// ══════════════════════════════════════════════════════════════════════════

export function connectorHandlers(ctx: MockContext): RequestHandler[] {
  return [
    http.get('/api/admin/connector/page', ({ request }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const query = new URL(request.url).searchParams
        const bad = checkPageParams(query)
        if (bad) return bad

        const gunNo = strParam(query, 'gunNo')
        const stationId = numParam(query, 'stationId')
        const chargerId = numParam(query, 'chargerId')
        const status = numParam(query, 'status')

        const rows = ctx.db.connectors.filter((c) => {
          // ★ `gunNo` 是**两侧模糊**，和站点编码的前缀匹配不同 ——
          //   枪号是 `A` / `1` 这种短串，前缀匹配会把 `10` 排除在 `0` 之外，
          //   而管理员搜「1」时显然想看到 `10`。
          if (gunNo && !c.gunNo.includes(gunNo)) return false
          if (stationId !== undefined && c.stationId !== stationId) return false
          if (chargerId !== undefined && c.chargerId !== chargerId) return false
          if (status !== undefined && c.status !== status) return false
          return true
        })
        rows.sort((a, b) => b.id - a.id)

        const { pageNum, pageSize } = pageParams(query)
        const page = paginate(rows, pageNum, pageSize)
        return ok({ total: page.total, records: page.records.map(toConnectorVo) })
      }),
    ),

    http.get('/api/admin/connector/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const connector = ctx.db.connectorById(Number(params.id))
        if (!connector) return fail(TEXT.CONNECTOR_NOT_FOUND)
        return ok(toConnectorVo(connector))
      }),
    ),

    http.post('/api/admin/connector', ({ request }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as ConnectorDTO
        const missing = missingRequired([
          [dto.chargerId, '所属充电桩不能为空'],
          [dto.gunNo, '枪口号不能为空'],
          [dto.ratedPowerKw, '额定功率不能为空'],
        ])
        if (missing) return missing

        const charger = ctx.db.chargerById(dto.chargerId)
        if (!charger) return fail(TEXT.CHARGER_NOT_FOUND)

        // ★ `uk_connector_charger_gun` 是**复合**唯一索引：(charger_id, gun_no)。
        //   同一个桩下不能有两把 `A` 枪，但**两个不同的桩**可以各有各的 `A`。
        //   写成「全局唯一」的话，第二个桩建 `A` 枪就会被拒 —— 而现场到处都是一桩两枪。
        if (
          ctx.db.connectors.some((c) => c.chargerId === dto.chargerId && c.gunNo === dto.gunNo)
        ) {
          return fail(alreadyExists(dto.gunNo))
        }

        const now = ctx.db.now()
        const row: DbConnector = {
          id: ctx.db.nextId('connector'),
          chargerId: dto.chargerId,
          // ★ stationId 是**从桩推出来的**，不是入参 —— `ConnectorDTO` 里没有它。
          //   让调用方传的话就多了一个「枪的站和桩的站不一致」的非法状态。
          stationId: charger.stationId,
          gunNo: dto.gunNo,
          connectorType: dto.connectorType ?? null,
          ratedPowerKw: toNumber(dto.ratedPowerKw),
          // ★★ 新建一律 `0 离线`、`version = 0`、`currentOrderId = null` ★★
          //   这三条抄自 `ConnectorServiceImpl.create`，不是随手写的默认值：
          //   · 枪没和硬件握过手，不能是「空闲」；
          //   · `version` 从 0 起，所以「第一次 PUT 传回 0」是**对的**，
          //     传 1 反而会撞乐观锁；
          //   · 一把新枪不可能有进行中的订单，而 `uk_connector_current_order`
          //     唯一索引不约束 NULL —— 正是靠这一点，「空闲的枪」才能有很多把。
          status: GUN_OFFLINE,
          currentOrderId: null,
          totalChargeCount: 0,
          version: 0,
          createTime: now,
          updateTime: now,
        }
        ctx.db.connectors.push(row)
        // ★ 和删除对称：同一个事务里重算枪数。这里「重算」和「+1」结果一样，
        //   但用的必须是同一个函数 —— 两条不同的算法迟早会在某个边界上分叉。
        refreshGunCount(ctx, dto.chargerId, now)
        return ok(row.id)
      }),
    ),

    http.put('/api/admin/connector/:id', ({ request, params }) =>
      envelope(ctx, async () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const dto = (await request.json().catch(() => ({}))) as ConnectorUpdateDTO

        // ★ 状态白名单在**进 SQL 之前**判（照抄后端的注释）：
        //   白名单是 0/1/4/5 这四个散的值，而全集 0~5 里排掉的是中间两个
        //   （2 已占用 / 3 充电中）。用 `@Min(0) @Max(5)` 等于放行 ——
        //   而那意味着管理员可以把一把「正在充电」的枪手工改成「空闲」，
        //   于是一把枪上出现两笔并行订单。
        if (dto.status !== undefined && !(CONNECTOR_ADMIN_SETTABLE as readonly number[]).includes(dto.status)) {
          return fail(TEXT.CONNECTOR_STATUS_NOT_EDITABLE)
        }

        const id = Number(params.id)
        const row = ctx.db.connectorById(id)
        if (!row) return fail(TEXT.CONNECTOR_NOT_FOUND)

        // ★★ 乐观锁 ★★ `WHERE id = ? AND version = ?`，影响行数就是全部判据。
        //   0 行有「版本冲突」和「行没了」两种可能，上面已经排掉了「行没了」，
        //   所以这里只剩冲突 —— 两条消息给用户的意义完全不同：
        //   「被他人修改」→ 刷新一下就能看到新值再改一遍。
        //
        // ★ `pendingConflict` 是 mock 唯一的「造并发」手段：真后端里冲突来自
        //   另一个事务，单线程的内存库造不出来。它是**一次性**的（见下面那行清除），
        //   因为要演示的第二件事恰恰是「刷新后重试**成功**」——
        //   持续冲突就永远走不到成功那一步。
        if (row.pendingConflict) {
          row.pendingConflict = false
          return fail(TEXT.CONCURRENT_MODIFIED)
        }
        if (dto.version !== row.version) return fail(TEXT.CONCURRENT_MODIFIED)

        const now = ctx.db.now()
        if (dto.gunNo !== undefined) {
          const clash = ctx.db.connectors.some(
            (c) => c.chargerId === row.chargerId && c.gunNo === dto.gunNo && c.id !== id,
          )
          if (clash) return fail(alreadyExists(dto.gunNo))
          row.gunNo = dto.gunNo
        }
        if (dto.connectorType !== undefined) row.connectorType = dto.connectorType
        if (dto.ratedPowerKw !== undefined) row.ratedPowerKw = toNumber(dto.ratedPowerKw)
        if (dto.status !== undefined) row.status = dto.status
        // ★★ 成功时必须**递增** version ★★ 不递增的话乐观锁形同虚设：
        //   两个标签页同时打开、都拿着 version=0，第一个保存成功、第二个也成功 ——
        //   后写的把先写的悄悄覆盖掉，而「乐观锁」一点作用都没起。
        row.version += 1
        row.updateTime = now

        // ★ 不重算 gun_count：这个接口改不了 chargerId，也改不了「有几把枪」。
        return okVoid()
      }),
    ),

    http.delete('/api/admin/connector/:id', ({ request, params }) =>
      envelope(ctx, () => {
        const g = guardStaff(ctx, request)
        if (!g.ok) return g.response

        const id = Number(params.id)
        const connector = ctx.db.connectorById(id)
        if (!connector) return fail(TEXT.CONNECTOR_NOT_FOUND)

        // ★★ 三道判据，前两道读枪自己的列、第三道问订单表 ★★
        //   ① `currentOrderId != null` —— 枪自己说「我被占着」；
        //   ② `status ∈ {2 已占用, 3 充电中}` —— 枪自己说「我在忙」，
        //      但 `currentOrderId` 是空的。这是**数据不一致**，比①更该拦：
        //      状态说忙、指针说闲，删掉它会让那笔订单永远找不到自己的枪。
        //   ③ 订单表里还有 `{1,2,3}` 的订单。用 `holdingConnectorCodes` 而不是
        //      更宽的集合：4/6/9 的订单早就把枪放了，拿它们拦删除会让一把枪
        //      在历史上充过一次电之后就**再也删不掉**。
        if (connector.currentOrderId !== null) return fail(TEXT.CONNECTOR_IN_USE)
        if (connector.status === GUN_OCCUPIED || connector.status === GUN_CHARGING) {
          return fail(TEXT.CONNECTOR_IN_USE)
        }
        const holding = ctx.db.orders.some(
          (o) => o.connectorId === id && HOLDING_CONNECTOR_STATUSES.includes(o.status),
        )
        if (holding) return fail(TEXT.CONNECTOR_IN_USE)

        const chargerId = connector.chargerId
        ctx.db.connectors.splice(ctx.db.connectors.findIndex((c) => c.id === id), 1)
        // ★ 删掉最后一把枪时 `gunCount` 会变成 0，而 0 是**正确的值** ——
        //   不是「没枪了所以清空」，是「真的没有枪」。
        refreshGunCount(ctx, chargerId, ctx.db.now())
        return okVoid()
      }),
    ),
  ]
}
