/**
 * 建单阶段的两条**共享**规则：解析计费模板、估算冻结额。
 *
 * ★★ 为什么单独一个文件，而不是写进 `member-order.ts` ★★
 *   因为 `seed.ts` 也要用它们 —— 种子订单必须走**同一套**估算逻辑，
 *   否则会出现「手工造的那几单冻结了 100，用户自己建的那单冻结了 145」
 *   这种只会在演示时被发现的偏差。放在建单 handler 里的话，
 *   seed 只能复制一份，而复制的那份不会被任何东西提醒去同步。
 *
 * ★ 这两条都不是「业务状态机」，所以不在 `db.ts` 里（那里只管改行）。
 *   它们也不改任何状态，是**纯函数**：给同样的库和时间，永远给同样的答案。
 */

import type { MockDb } from '../db'
import { CHARGER_STATUS, CONNECTOR_STATUS } from '../../constants/dicts'
import {
  TEXT,
  chargerNotInStation,
  chargerUnavailable,
  connectorBusy,
  connectorHeldByOrder,
  connectorNotInChargerOrStation,
  connectorStateInvalid,
} from './texts'
import { HOLDING_CONNECTOR_STATUSES } from '../../constants/orderStatus'

/**
 * 后端 `DEFAULT_BATTERY_KWH = 60.00`：车辆没填电池容量时的兜底。
 *
 * ★ 它是 `max(容量, 60)` 里的那个下限，不是「默认值」——
 *   一辆标了 30 度电的老年代步车**也会**按 60 度冻结。
 *   写成 `?? 60` 会得到一个偏小的冻结额，而偏小的冻结额在支付时
 *   会变成「余额不足」—— 一条与真实原因完全无关的报错。
 */
const DEFAULT_BATTERY_KWH = 60

/** 后端 `MAX_FREEZE_AMOUNT = 500.00`：单笔冻结上限。 */
const MAX_FREEZE_CENTS = 50000

/**
 * 解析某站点在 `at` 时刻生效的计费模板 —— 复刻 `StationTariffMapper.resolveTemplateAt`。
 *
 * ★★ 排序是 `priority DESC, effective_from DESC, id DESC`，一条都不能少 ★★
 *   · `priority DESC` 决定「两条都生效时听谁的」——运营给新价开高优先级；
 *   · `effective_from DESC` 是**时间**上的就近优先（在优先级相同时，用更晚生效的那条）；
 *   · `id DESC` 只是让结果**确定**：没有它，两条完全同级的绑定谁赢是随机的，
 *     表现是「同一张单，刷新两次价格不一样」。
 *
 * ★ 判据是**半开区间** `effective_from <= at < effective_to`：
 *   结束时刻是**不含**的，所以「上一条 effective_to = 新一条 effective_from」
 *   这种交接写法正好无缝且不重叠。写成闭区间的话，交接的那一秒两条都生效，
 *   然后被上面的 priority 随缘决定。
 *
 * ★ `status = 1` 是硬条件，和生效区间无关：停用的绑定哪怕在时间窗口里也不参与。
 *   返回 `null` 表示**这个站点此刻不能充电**（而不是「用默认价」）——
 *   回退到一个没人批准过的默认价是一个静默的资金错误，见 `TEXT.STATION_TARIFF_NOT_BOUND`。
 */
export function resolveTemplateIdAt(db: MockDb, stationId: number, at: Date): number | null {
  const candidates = db.stationTariffs.filter((t) => {
    if (t.stationId !== stationId || t.status !== 1) return false
    if (t.effectiveFrom !== null && t.effectiveFrom.getTime() > at.getTime()) return false
    if (t.effectiveTo !== null && t.effectiveTo.getTime() <= at.getTime()) return false
    return true
  })
  if (candidates.length === 0) return null

  candidates.sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority
    const af = a.effectiveFrom?.getTime() ?? 0
    const bf = b.effectiveFrom?.getTime() ?? 0
    if (af !== bf) return bf - af
    return b.id - a.id
  })
  return candidates[0].templateId
}

/**
 * 估算冻结额 —— 复刻 `ChargingOrderServiceImpl.estimateFreezeAmount`。
 *
 * ★★ 口径是「**最贵的那个时段** × 容量」，不是「按预估时长算」★★
 *   这是**刻意的保守**：冻结额是上界，宁可多冻一点，也不能出现
 *   「充完了发现钱不够扣」。所以它取的是 `max(energyPrice + servicePrice)`
 *   而不是加权平均 —— 后者要预估充电时间，而那个数没人知道。
 *
 * ★ `maxPrice <= 0` 时回退到 `1`：规则集为空或全 0 不是「不用冻钱」，
 *   是**数据有问题**。让结算去报「某个时刻没有规则覆盖」，
 *   这里先冻一个保守值 —— 免费充电比多冻几块钱严重得多。
 */
export function estimateFreezeCents(
  db: MockDb,
  vehicleBatteryKwh: number | null | undefined,
  templateId: number | null,
): number {
  const capacity =
    vehicleBatteryKwh !== null && vehicleBatteryKwh !== undefined && vehicleBatteryKwh > 0
      ? vehicleBatteryKwh
      : DEFAULT_BATTERY_KWH

  const rules = templateId === null ? [] : db.rulesOf(templateId)
  let maxPrice = 0
  for (const rule of rules) {
    const unit = rule.energyPrice + (rule.servicePrice ?? 0)
    if (unit > maxPrice) maxPrice = unit
  }
  if (maxPrice <= 0) maxPrice = 1

  // ★ 先乘再一次性取整到分，和 Java 的 `multiply().setScale(2)` 一致 ——
  //   分两步舍入（比如先把单价对齐到分）会让结果差几分，
  //   而前端有一条「冻结额 = 容量 × 单价」的显示逻辑，对不上就会被当成 bug。
  const cents = Math.round(capacity * maxPrice * 100)
  return Math.min(cents, MAX_FREEZE_CENTS)
}

/**
 * 站点 / 桩 / 枪三层校验 —— 复刻 `assertConnectorUsable` + `assertConnectorFree`。
 *
 * 返回 `null` 表示通过，否则是**要发给用户的失败信封**（`fail()` 已经包好）。
 *
 * ★ 顺序照抄后端，因为**顺序决定了用户看到哪一句**：
 *   ① 枪存在 → ② 枪属于这个桩 → ③ 桩属于这个站 → ④ 桩可用 → ⑤ 枪空闲
 *      → ⑥ 这枪上没有进行中的订单 → ⑦ 不是脏数据（一笔以上进行中）
 *
 * ★ 第 ⑤ 条和第 ⑥ ⑦ 条是**两件事**，不能合并：
 *   ⑤ 看的是枪自己的 `status`（快照），⑥⑦ 查的是订单表（事实）。
 *   ⑧ 里那句「有订单占用」的文案要带订单号 —— 那是用户去查的唯一线索。
 */
export function describeConnectorProblem(
  db: MockDb,
  stationId: number,
  chargerId: number,
  connectorId: number,
): { msg: string } | null {
  // ★★ 存在性：站 → 桩 → 枪，三层都查 ★★
  //   后端先 `stationMapper.getById` 再 `chargerMapper.getById` 再
  //   `connectorMapper.getById`，所以「站不存在」和「枪不存在」是两句不同的话。
  if (!db.stationById(stationId)) return { msg: TEXT.STATION_NOT_FOUND }

  const charger = db.chargerById(chargerId)
  if (!charger) return { msg: TEXT.CHARGER_NOT_FOUND }

  const connector = db.connectorById(connectorId)
  if (!connector) return { msg: TEXT.CONNECTOR_NOT_FOUND }

  // ★ 归属关系（两条），和存在性分开报：**「查不到」和「查到了但不属于」是两件事**，
  //   前者是脏数据、后者是前端传错了参数。
  if (charger.stationId !== stationId) {
    return { msg: chargerNotInStation(chargerId, stationId) }
  }
  if (connector.chargerId !== chargerId || connector.stationId !== stationId) {
    return { msg: connectorNotInChargerOrStation(connectorId) }
  }

  // ★★ 枪的状态**先于**桩的状态判 —— 顺序是照抄后端的，不是随手排的 ★★
  //   一把「离线」的枪挂在一个「离线」的桩上时，用户看到的是
  //   「该充电枪正在使用中，请换一把（当前：离线）」；把两层调过来，
  //   同一把枪会给出「充电桩当前不可用（离线）：chargerId=N」——
  //   两句都「对」，但演示里核对的就是前一句。
  if (connector.status !== 1) {
    return { msg: connectorBusy(CONNECTOR_STATUS.label(connector.status)) }
  }
  // ★ 桩的可用性单独判：枪空闲但桩离线时，建出来的单永远等不到设备上报。
  //   桩的标签走 `CHARGER_STATUS`（0离线/1在线空闲/2充电中/3故障/4维护中），
  //   和枪的 `CONNECTOR_STATUS` 是**两套**字典 —— 混用会得到
  //   「充电桩当前不可用（已占用）」，而桩根本没有「已占用」这个状态。
  if (charger.status !== 1) {
    return { msg: chargerUnavailable(CHARGER_STATUS.label(charger.status), chargerId) }
  }

  const holding = db.orders.filter(
    (o) => o.connectorId === connectorId && HOLDING_CONNECTOR_STATUSES.includes(o.status),
  )
  // ★ 两种可能分开报：一笔是「被别人先占了」（正常并发），
  //   两笔以上是**脏数据**（并发建单或历史遗留），那时候必须停下来人工处理 ——
  //   随便挑一笔继续，另一笔会永远停在充电中，而它的钱还冻着。
  if (holding.length > 1) {
    return { msg: connectorStateInvalid(connectorId, holding.length) }
  }
  if (holding.length === 1) {
    return { msg: connectorHeldByOrder(holding[0].orderNo) }
  }

  return null
}
