<script setup lang="ts">
/**
 * 站点选枪页 —— 车主端**唯一**能发起充电的地方。
 *
 * ★★ 这一页要按顺序回答三个问题，顺序就是车主脑子里的顺序 ★★
 *   ① 这是哪个站？（顶部资料。★ 车主端**没有站点详情接口**，资料是列表页带过来的）
 *   ② 哪把枪能用？（可用优先 + **不可用的也全部列出来**）
 *   ③ 确认下单（选车、选支付方式、说清钱怎么动）
 *
 * ★★ 全页最核心的一条事实：`available` 是快照，不是预订 ★★
 *   后端 `MemberConnectorVO` 的类注释原话：「它不保证下单成功」。从看到
 *   `available=true` 到点「确认下单」之间隔着几秒，别人完全可能先占了。
 *   所以这里有一个**必须存在**的分支：建单失败（枪被占）→ 重拉枪列表 +
 *   明确告诉用户「换一把」。把它当异常弹个原始错误就完事，是这一页最容易犯的错。
 *
 * ★★ 第二条：建单成功 ≠ 开始充电 ★★
 *   后端 `create()` 建出来的订单是 **`1 待启动`**（`OrderStatus.CREATED`），
 *   而且 `start` 这个动作在规则表里 `ends: ['admin']` —— 车主端**没有**
 *   启动充电的接口。真正的 1 → 2 由设备上报/运维触发。
 *   所以成功文案只能是「等待站点启动」，说「正在充电」就是骗人。
 */

import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import {
  ApiError,
  CONNECTOR_STATUS,
  MAX_PAGE_SIZE,
  PAY_METHOD,
  formatKwh,
  isActionLocked,
  msgEquals,
  msgStartsWith,
  payloadKey,
  withActionLock,
  type MemberConnectorVO,
  type MemberStationVO,
  type OrderCreateDTO,
  type VehicleVO,
} from '@chargeops/shared'
import {
  groupByCharger,
  listConnectors,
  openHoursText,
  pageStations,
  type ChargerGroup,
} from '@/api/station'
import { createOrder } from '@/api/order'
import { listVehicles } from '@/api/vehicle'
import { getWallet } from '@/api/wallet'
import { useAsyncData } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import MoneyText from '@/components/MoneyText.vue'
import PageState from '@/components/PageState.vue'

const route = useRoute()
const router = useRouter()

/**
 * 站点 id。
 *
 * ★ 手敲一个 `/station/abc` 也要能落到一个「查不出东西」的状态，而不是把
 *   `NaN` 发给后端 —— `NaN` 在 JSON 里会变成 `null`，后端只会回一句
 *   和参数名对不上的「未知错误」，那种报错最难查。
 */
const stationId = computed(() => {
  const raw = Array.isArray(route.params.id) ? route.params.id[0] : route.params.id
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : 0
})

// ══════════════════════════════════════════════════════════════════════════
// ① 站点资料
// ══════════════════════════════════════════════════════════════════════════

/** 读一个 query 文本参数。数组（`?name=a&name=b`）取第一个。 */
function queryText(key: string): string {
  const raw = route.query[key]
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value.trim() : ''
}

/** 兜底查到的站点（只在「没有 query」时才去查，见 `lookupStation`）。 */
const lookedStation = ref<MemberStationVO | null>(null)

/**
 * 拿不到 query 时，用站点列表接口尽力找一找。
 *
 * ★ 为什么会有这种情况：车主端**没有** `GET /station/{id}` 这个接口
 *   （`MemberStationController` 只有 page 和 by-station 两个端点），
 *   资料只能由列表页通过 query 带过来。而用户可能从别人分享的地址、
 *   或者浏览器收藏直接打开这一个页面 —— 那时 query 是空的。
 *
 * ★ 这是一次**尽力而为**：`pageStations` 没有「按 id 查」的条件，
 *   只能拉一页（后端一次最多 `MAX_PAGE_SIZE` 条）在里面找。
 *   找不到不是错误，页面会明说「没拿到站点资料」—— 不编、不猜。
 *
 * ★ 失败**静默**：这只是为了让顶部好看一点。为它把整页变成错误页是最差的
 *   取舍 —— 枪列表才是这一页的主体，它完全没受影响。
 */
async function lookupStation(): Promise<void> {
  if (queryText('name') || !stationId.value) return
  try {
    const page = await pageStations({ pageNum: 1, pageSize: MAX_PAGE_SIZE })
    lookedStation.value = page.records.find((item) => item.id === stationId.value) ?? null
  } catch {
    // 静默：见上
  }
}

interface StationHead {
  name: string
  address: string
  hours: string | null
  phone: string | null
  /** 两个来源都拿不到名字 —— 顶部要显式说明，而不是显示一个看起来像站名的假名字。 */
  missing: boolean
}

const head = computed<StationHead>(() => {
  const fallback = lookedStation.value
  const name = queryText('name') || fallback?.name || ''
  const address =
    queryText('addr') ||
    fallback?.address ||
    [fallback?.province, fallback?.city, fallback?.district].filter(Boolean).join(' ')
  const hours = queryText('hours') || openHoursText(fallback?.openTime, fallback?.closeTime)
  const phone = queryText('phone') || fallback?.servicePhone || ''

  return {
    // ★ 连名字都没有时用 `充电站 #12` 这种**明显是编号**的写法：
    //   比「未知站点」好，因为用户至少能拿编号去问客服。
    name: name || `充电站 #${stationId.value}`,
    address,
    hours: hours || null,
    phone: phone || null,
    missing: !name,
  }
})

// ══════════════════════════════════════════════════════════════════════════
// ② 枪列表
// ══════════════════════════════════════════════════════════════════════════

const {
  data: connectorList,
  loading: connectorLoading,
  failed: connectorFailed,
  reload: reloadConnectors,
} = useAsyncData(async () => {
  // 站 id 不合法就不发请求（后端对不存在的站点返回**空数组而不是 404**，
  // 所以「查出来是空」这一种结果要能同时容纳「没枪」和「没这个站」）
  if (!stationId.value) return []
  // ★ `listConnectors` 返回裸数组（不是分页信封），空数据时会拿到 null，统一成 []
  return (await listConnectors(stationId.value)) ?? []
})

/**
 * ★★ 必须过 `groupByCharger()`，不能直接用接口返回的顺序 ★★
 *   后端 `listByStation` **不排序**（`gun_no` 是字符串，SQL 排出来是 `1, 10, 2`），
 *   后端注释明确要求前端重排。
 *   `groupByCharger`（内部走 `sortConnectors`）给了三层顺序：
 *   可用的在前 → 按桩分组 → 组内按枪号**自然序**（`1, 2, ..., 10`）。
 */
const groups = computed<ChargerGroup[]>(() => groupByCharger(connectorList.value ?? []))

const gunTotal = computed(() => (connectorList.value ?? []).length)
const gunIdle = computed(() => (connectorList.value ?? []).filter((item) => item.available).length)

const TONE_CLASS: Record<string, string> = {
  success: 'is-ok',
  warning: 'is-busy',
  primary: 'is-busy',
  danger: 'is-fault',
  info: 'is-mute',
  accent: 'is-mute',
}

/**
 * 状态胶囊的样式类。
 *
 * ★ 用字典的 `tone()` 映射，而不是自己写 `status === 4 ? '红' : ...`：
 *   码值与色调的对应关系只有字典一个真源，将来后端加一个状态，
 *   这里不需要改（新状态会落到 `info` → 灰色，不会变成一个刺眼的颜色）。
 */
function statusClass(status: number): string {
  return TONE_CLASS[CONNECTOR_STATUS.tone(status)] ?? 'is-mute'
}

/** 桩的显示名：现场对得上号的是桩名/桩编号，只有 id 的话用户站在桩前面认不出来。 */
function chargerLabel(group: ChargerGroup): string {
  return group.chargerName || group.chargerDeviceNo || `充电桩 #${group.chargerId}`
}

/** 额定功率：`formatKwh` 是「最多 4 位小数 + 去掉尾零」，对功率刚好合适（`formatMoney` 会加两位小数和千分位）。 */
function powerText(power: string | number): string {
  return `${formatKwh(power)} kW`
}

/**
 * 不可用的原因：**逐状态不同**。
 *
 * ★ 为什么不用 `CONNECTOR_STATUS.byCode(status).hint`：字典里那几句是写给
 *   **运维**的（「心跳超时」「不参与派单」），车主看不懂；而且它们解释的是机制，
 *   没有告诉用户下一步做什么。这里按码值给「人话 + 下一步」，
 *   短标签仍然用后端给的 `statusLabel`（那是车主能看懂的那个词）。
 */
const OFF_REASON: Record<number, string> = {
  0: '这把枪暂时联系不上（离线），站点恢复后会重新可用。换一把吧。',
  2: '这把枪已经被一笔订单占用了（还没开始充电）。如果那是你的订单，可以在「我的订单」里看到。',
  3: '正在给别的车充电。它充完之后，这把枪才会重新变空闲。',
  4: '这把枪报故障了，需要运维处理。换一把吧。',
  5: '运维正在维护这把枪，暂时不能使用。',
}

/**
 * 点一把枪。
 *
 * ★ 不可用的枪**也要能点**（点了给一句解释），但**绝不进确认层** ——
 *   进确认层等于告诉用户「点下去就能充」，而它必然失败。
 *   这就是「灰掉但仍然可点」与「直接 disabled」的区别：后者只会让人反复戳。
 */
function onGunTap(connector: MemberConnectorVO): void {
  if (!connector.available) {
    showToast(OFF_REASON[connector.status] ?? `这把枪现在不可用（${connector.statusLabel}）。`)
    return
  }
  openSheet(connector)
}

// ══════════════════════════════════════════════════════════════════════════
// ③ 确认下单
// ══════════════════════════════════════════════════════════════════════════

const sheetOpen = ref(false)
const picked = ref<MemberConnectorVO | null>(null)

const { pending, run } = useSubmitLock()

const pickedLabel = computed(() => {
  const connector = picked.value
  if (!connector) return ''
  const charger = connector.chargerName || connector.chargerDeviceNo || `充电桩 #${connector.chargerId}`
  return `${charger} · ${connector.gunNo} 号枪`
})

// ── 车辆（可选）────────────────────────────────────────────────────────────

/**
 * ★ `immediate: false`：车辆列表**不随页面加载**，等用户真的点了枪再拉 ——
 *   进这一页的人多半只是看看，没必要为此多发一个请求。
 */
const {
  data: vehicleData,
  failed: vehiclesFailed,
  reload: reloadVehicles,
} = useAsyncData(listVehicles, { immediate: false })
const vehicleList = computed<VehicleVO[]>(() => vehicleData.value ?? [])

/** 选中的车辆 id。`null` = 不带车辆（后端允许，DTO 里 `vehicleId` 可空）。 */
const vehicleId = ref<number | null>(null)

/**
 * 首次拿到车辆列表时**预选默认车**。
 *
 * ★ 用 `isDefault === 1` 判断，不能写真值判断：后端那个字段是数字 1/0
 *   （见 `api/vehicle.ts` 的注释：哪天变成字符串 `"0"` 就是 truthy，所有车都会显示成默认车）。
 * ★ 只预选一次（`let` 标志），且**没有默认车时不预选** —— 系统替用户选一辆车，
 *   而用户没注意到，是最容易产生「我的车怎么被算进去了」这类疑问的地方。
 */
let vehicleDefaultApplied = false
watch(vehicleList, (list) => {
  if (vehicleDefaultApplied || list.length === 0) return
  vehicleDefaultApplied = true
  const fallback = list.find((item) => item.isDefault === 1)
  if (fallback) vehicleId.value = fallback.id
})

function vehicleLabel(vehicle: VehicleVO): string {
  const parts: string[] = []
  const model = [vehicle.brand, vehicle.model].filter(Boolean).join(' ')
  if (model) parts.push(model)
  parts.push(
    vehicle.batteryCapacityKwh === null || vehicle.batteryCapacityKwh === undefined
      ? '未填电池容量，按下单方的 60 度估算'
      : `电池 ${formatKwh(vehicle.batteryCapacityKwh)} 度`,
  )
  if (vehicle.isDefault === 1) parts.push('默认车')
  return parts.join(' · ')
}

// ── 支付方式 ───────────────────────────────────────────────────────────────

/**
 * 余额支付的码值。
 *
 * ★ `PAY_METHOD` 是 `createDict` 造的，**没有** `PAY_METHOD.BALANCE` 这种具名键 ——
 *   写成那样会静默拿到 `undefined`（和 `stores/session.ts` 里 `MEMBER_STATUS` 那个坑是同一个）。
 *   后端 `PayMethod.BALANCE` 就是 1，所以这里显式声明并让两处引用共用它。
 */
const PAY_BALANCE = 1

const payMethod = ref<number>(PAY_BALANCE)

/**
 * 置灰的支付方式 = 字典里**除了余额**的其余项。
 *
 * ★ 只有余额能选：后端 `PayMethod.isSupported()` **只放行 BALANCE(1)**，
 *   选 2/3 会被 `ORDER_PAY_METHOD_UNSUPPORTED`（「该支付方式暂未支持」）拒绝。
 *   所以微信/支付宝在这里是**置灰 + 标注「未接入」**，而不是可选后再报错 ——
 *   让用户点一个必然失败的选项，比不让点更糟。
 * ★ 而且从字典过滤、不写死两行：将来后端真接入了微信，字典里加一条就会
 *   自动从这里消失；写死的两行会一直挂着说「未接入」，而没人会想起来改。
 */
const unsupportedPayMethods = PAY_METHOD.list.filter((item) => item.code !== PAY_BALANCE)

const walletQuery = useAsyncData(getWallet, { immediate: false })
const balance = computed(() => walletQuery.data.value?.balance ?? null)

const balanceLabel = computed(() => {
  if (walletQuery.failed.value) return '余额没读到，可以到「我的钱包」里看'
  return '从可用余额里先冻结、结算时按实际用量扣'
})

// ── 层内提示（失败后的「下一步」）─────────────────────────────────────────

/** 提示上的动作。`null` = 只说明，不给按钮。 */
type NoticeAction = 'pick-another' | 'recharge' | 'orders' | null

interface SheetNotice {
  tone: 'warn' | 'error'
  title: string
  desc: string
  action: NoticeAction
}

const notice = ref<SheetNotice | null>(null)

/**
 * 结果未知（网络中断/超时/后端成功但没回数据）。
 *
 * ★ 后端**没有幂等键**，所以「请求超时」时**不能**说「没有下单成功」——
 *   那一秒服务端可能已经把单建好了。这时把提交按钮禁掉，
 *   只留「去看订单」这一条路，比让用户再点一次安全得多。
 */
const outcomeUnknown = ref(false)

function openSheet(connector: MemberConnectorVO): void {
  picked.value = connector
  notice.value = null
  outcomeUnknown.value = false
  sheetOpen.value = true
  // 懒加载：失败时 data 仍是 null，下次打开会自动重试，不需要额外的重试状态
  if (!vehicleData.value) void reloadVehicles()
  if (!walletQuery.data.value) void walletQuery.reload()
}

function onSheetClosed(): void {
  picked.value = null
  notice.value = null
  outcomeUnknown.value = false
}

function runNoticeAction(): void {
  const action = notice.value?.action
  if (action === 'pick-another') {
    // 关掉确认层：用户要的是「换一把」，留在这里只会重复点同一个按钮
    sheetOpen.value = false
    return
  }
  if (action === 'recharge') {
    sheetOpen.value = false
    // ★ 充完值回来**重新选枪**即可，不需要保留选择 —— 枪本来就不能预订，
    //   保留一个几十秒前的选择只会让人以为它还锁着。
    void router.push({ name: 'wallet' })
    return
  }
  if (action === 'orders') {
    sheetOpen.value = false
    void router.push({ name: 'orders' })
  }
}

// ── 建单失败的分类 ─────────────────────────────────────────────────────────

/**
 * 后端文案常量，**逐字**抄自 `MessageConstant`。
 *
 * ★ 它们不在 shared 的 `MSG` 里：那个字典只放「命中后要触发全局逻辑」的句子
 *   （强制登出、刷新重填）。这几句只影响本页的引导文案，所以只能就地匹配。
 * ★ 一律 `msgEquals` / `msgStartsWith`，**不用 `includes`** ——
 *   「该充电枪正在使用中，请换一把：已有订单 SO-xxx」正是靠前缀匹配它的。
 */
const MSG_OCCUPIED = '该充电枪正在使用中，请换一把'
const MSG_BALANCE_NOT_ENOUGH = '余额不足'
const MSG_PAY_METHOD_UNSUPPORTED = '该支付方式暂未支持，请使用余额支付'
const MSG_CHARGER_UNAVAILABLE = '充电桩当前不可用（'
const MSG_TARIFF_NOT_BOUND = '该站点当前没有配置计费方案，请联系运营'
const MSG_VEHICLE_BUSY = '该车辆有进行中的充电订单，请先结束'
const MSG_VEHICLE_NOT_FOUND = '车辆不存在'
const MSG_CONNECTOR_NOT_FOUND = '充电枪不存在'
const MSG_STATION_NOT_FOUND = '充电站不存在'

function onFailed(error: unknown): void {
  // ① 被动作锁拦下（连点、关掉重开再点）：枪没被占、钱没动，
  //    绝不能走下面那套「换一把 / 去充值」的引导，那会把用户带偏。
  if (isActionLocked(error)) {
    notice.value = {
      tone: 'warn',
      title: '上一次提交还在处理中',
      desc: '等一两秒再试，不要连着点「确认下单」。',
      action: null,
    }
    return
  }

  const apiError = error instanceof ApiError ? error : null

  // ② 不是业务失败（超时/断网/5xx）：**结果未知**，必须按未知处理 —— 见 `outcomeUnknown`
  if (!apiError || apiError.kind !== 'biz') {
    outcomeUnknown.value = true
    notice.value = {
      tone: 'error',
      title: '下单请求没有成功返回',
      desc: '先到「我的订单」看一眼有没有这笔单子，再决定要不要重试 —— 网络中断时请求也可能已经到达服务端了。',
      action: 'orders',
    }
    return
  }

  const msg = apiError.message

  // ③ 枪被占用（或这把枪刚被改成故障/维护 —— 后端这两种情况共用同一句，靠后缀区分）
  if (msgStartsWith(msg, MSG_OCCUPIED)) {
    const detail = msg.slice(MSG_OCCUPIED.length)
    const currentLabel = /^（当前：(.+)）$/.exec(detail)?.[1]
    notice.value = {
      tone: 'warn',
      title: '这把枪刚被占用了，换一把',
      desc: currentLabel
        ? `它现在的实际状态是「${currentLabel}」—— 列表已经刷新，灰色的是暂时不能用的。`
        : '你看到的是查列表那一刻的状态，这把枪不能被预订。列表已经刷新，重新选一把就行。',
      action: 'pick-another',
    }
    // ★ 必须重拉：用户手上那一屏已经过期了，不重拉他会照着同一把枪再点一次
    void reloadConnectors()
    return
  }

  // ④ 余额不足 —— 建单时按预估金额冻结，这是最常见的失败
  if (msgEquals(msg, MSG_BALANCE_NOT_ENOUGH)) {
    notice.value = {
      tone: 'error',
      title: '余额不足，这笔预估金额没冻上',
      desc: '下单要先冻结预估金额，余额不够就会被拒。这次没有冻成，枪和钱都没动 —— 充值之后可以直接重试这把枪。',
      action: 'recharge',
    }
    // ★ 这里**不重拉枪列表**：失败和枪的状态无关，重拉只会让用户以为枪被换掉了
    return
  }

  // ⑤ 支付方式不支持。★ 走 UI 到不了这里（微信/支付宝已经置灰），
  //    但接口本身接受这个值 —— 留着这一条，是为了万一哪天真放进一个入口时，
  //    用户看到的是一句「该选哪个」，而不是一句原始错误
  if (msgEquals(msg, MSG_PAY_METHOD_UNSUPPORTED)) {
    notice.value = {
      tone: 'error',
      title: '这个支付方式还没有接入',
      desc: '现在只有余额支付能用，回到上面把「余额支付」选上再下单。',
      action: null,
    }
    return
  }

  // ⑥ 桩不可用。★ 这一条车主端**无法提前发现**：`available` 只看枪自己的状态，
  //    而 VO 里没有桩的状态 —— 所以只能在失败后解释清楚「换一个桩」。
  if (msgStartsWith(msg, MSG_CHARGER_UNAVAILABLE)) {
    notice.value = {
      tone: 'error',
      title: '这个充电桩现在不能用',
      desc: '桩离线或故障时，它下面的枪即使显示空闲也建不了单。换一个桩（换一组枪）试试。',
      action: 'pick-another',
    }
    void reloadConnectors()
    return
  }

  // ⑦ 其余的业务失败：都能给一句「下一步做什么」，而不是只重复后端那句话
  if (msgEquals(msg, MSG_TARIFF_NOT_BOUND)) {
    notice.value = {
      tone: 'error',
      title: '这个站点还没配好计费方案',
      desc: '这是站点的配置问题，不是你操作的问题。换一个站点充电吧。',
      action: null,
    }
    return
  }
  if (msgEquals(msg, MSG_VEHICLE_BUSY)) {
    notice.value = {
      tone: 'error',
      title: '这辆车还有一笔没结束的充电',
      desc: '一辆车同时只能有一笔进行中的订单。换一辆车，或者不选车辆下单。',
      action: null,
    }
    return
  }
  if (msgEquals(msg, MSG_VEHICLE_NOT_FOUND)) {
    notice.value = {
      tone: 'error',
      title: '这辆车不存在了',
      desc: '它可能已经被删除。换一辆车，或者不选车辆下单。',
      action: null,
    }
    return
  }
  if (msgEquals(msg, MSG_CONNECTOR_NOT_FOUND)) {
    notice.value = {
      tone: 'warn',
      title: '这把枪不存在了',
      desc: '可能刚被运维删除。列表已经刷新，重新选一把。',
      action: 'pick-another',
    }
    void reloadConnectors()
    return
  }
  if (msgEquals(msg, MSG_STATION_NOT_FOUND)) {
    notice.value = {
      tone: 'error',
      title: '这个站点不存在了',
      desc: '它可能已经被删除或停用。回首页重新选一个站点。',
      action: 'orders',
    }
    return
  }

  // ⑧ 兜底：把后端的原话显示出来。它已经由 http 层弹过一次 toast 了，
  //    这里再放一遍是为了让它**留在屏幕上**（toast 会消失，而用户需要照着它去问客服）
  notice.value = { tone: 'error', title: '下单失败', desc: msg, action: null }
}

// ── 提交 ───────────────────────────────────────────────────────────────────

async function submit(): Promise<void> {
  const connector = picked.value
  if (!connector || outcomeUnknown.value) return

  const dto: OrderCreateDTO = {
    stationId: stationId.value,
    chargerId: connector.chargerId,
    connectorId: connector.id,
    // ★ 不带车辆时**整个字段不传**，而不是传 0/-1：后端 `vehicleId` 是 Long，
    //   传 0 会被当成「id=0 的车辆」→「车辆不存在」
    vehicleId: vehicleId.value ?? undefined,
    payMethod: payMethod.value,
  }

  const outcome = await run(() =>
    /**
     * ★★ 两层防重，顺序不能反 ★★
     *   外层 `useSubmitLock`（按钮级，H5 默认 300ms 节流）挡连点；
     *   内层 `withActionLock` 按 **payload** 挡「关掉重开再点一次」——
     *   同 payload 才互斥，换了枪/换了车就是合法的另一次下单。
     *   `withActionLock` 是**抛异常**的，所以它必须在 `run()` 的**里面**：
     *   放外面的话被锁会变成 unhandled rejection，按钮的 loading 也不会复位。
     */
    withActionLock(`create-order:${payloadKey(dto)}`, () => createOrder(dto), 5000),
  )

  if (outcome.status !== 'done') {
    if (outcome.status === 'failed') onFailed(outcome.error)
    return
  }

  const created = outcome.value
  if (!created?.id) {
    // code:1 但没给订单号 —— 契约变了。此刻**订单可能已经存在**，
    // 所以按「结果未知」处理，不能提示「重试」。
    outcomeUnknown.value = true
    notice.value = {
      tone: 'error',
      title: '下单没有返回订单号',
      desc: '订单可能已经创建了，请到「我的订单」确认，不要重复下单。',
      action: 'orders',
    }
    return
  }

  // ★ 文案必须是「等启动」：后端建出来的订单是 `1 待启动`，而车主端没有启动充电的接口
  //   （`orderRules` 里 `start` 的 `ends` 只有 admin），真正的 1 → 2 由设备/运维触发。
  showSuccessToast('下单成功，等站点启动充电')
  // ★ `replace` 而不是 `push`：下单后按返回键不该回到这个确认层 ——
  //   枪已经被这单占住了，退回去再点一次「确认下单」必然失败（或更糟）
  await router.replace({ name: 'order-detail', params: { id: created.id } })
}

// ── 导航 ───────────────────────────────────────────────────────────────────

function goBack(): void {
  // ★ 有来路才 back()：从分享链接直接打开时 history 里没有上一页，
  //   直接 back() 会退到浏览器空白页，用户以为 App 崩了
  if (window.history.state?.back) router.back()
  else void router.replace({ name: 'home' })
}

onMounted(() => {
  void lookupStation()
})
</script>

<template>
  <div class="station-page">
    <van-nav-bar
      :title="head.name"
      left-arrow
      fixed
      placeholder
      safe-area-inset-top
      @click-left="goBack"
    />

    <div class="body">
      <!-- ① 站点资料 ------------------------------------------------ -->
      <section class="co-card head">
        <h1 class="head-name">{{ head.name }}</h1>

        <p v-if="head.address" class="head-line">
          <van-icon name="location-o" />
          <span>{{ head.address }}</span>
        </p>

        <div class="head-meta">
          <span v-if="head.hours" class="head-item co-num">
            <van-icon name="clock-o" />
            {{ head.hours }}
          </span>
          <!-- ★ 电话做成 tel: 链接：H5 上「点击拨号」就是这个，不需要额外的按钮 -->
          <a v-if="head.phone" class="head-item head-phone co-num" :href="`tel:${head.phone}`">
            <van-icon name="phone-o" />
            {{ head.phone }}
          </a>
        </div>

        <!-- ★ 没拿到资料就明说，并给出唯一有效的下一步 ——
             显示一行空白会让人以为是加载卡住了 -->
        <p v-if="head.missing" class="head-note">
          没有拿到这个站点的资料。从首页的站点列表重新进入，可以看到完整的站点信息。
        </p>
      </section>

      <!-- ② 枪列表 -------------------------------------------------- -->
      <section class="guns">
        <div class="guns-head">
          <h2>选择充电枪</h2>
          <span v-if="gunTotal" class="guns-count co-num">
            空闲 <b :class="{ 'is-zero': gunIdle === 0 }">{{ gunIdle }}</b> / {{ gunTotal }}
          </span>
          <button type="button" class="link" :disabled="connectorLoading" @click="reloadConnectors">
            刷新
          </button>
        </div>

        <p v-if="gunTotal" class="guns-note">
          可用的枪排在前面。灰色的暂时不能用，仍然列出来是为了让你知道这个站总共有几把枪。
        </p>

        <!-- ★ 刷新失败但手里还有上一份数据：继续显示旧数据（H5 上把已看到的列表清空
             是最差的选择），但必须说清这是旧的 —— 用户可能正照着它选枪 -->
        <p v-if="connectorFailed && connectorList?.length" class="guns-warn">
          刷新失败，下面还是上一次读到的数据。
          <button type="button" class="inline-link" @click="reloadConnectors">重试</button>
        </p>

        <PageState
          v-if="connectorLoading && !connectorList"
          kind="loading"
          title="正在加载充电枪…"
        />

        <PageState
          v-else-if="connectorFailed && !connectorList?.length"
          kind="error"
          title="充电枪列表加载失败"
          description="可能是网络不通，或者服务暂时不可用。稍后重试一次。"
          @retry="reloadConnectors"
        />

        <PageState
          v-else-if="!groups.length"
          kind="empty"
          title="这个站点还没有充电枪"
          description="可能还没有配置充电枪，也可能这个站点已经不存在了。回首页换一个站点试试。"
        />

        <!-- ★ 用 `<template v-else>` 包一层再 v-for，而不是把 v-for 和 v-else 写在同一个
             元素上：Vue 3 里 v-if/v-else 的优先级高于 v-for，混在一处是最容易看错的写法 -->
        <template v-else>
          <article v-for="group in groups" :key="group.chargerId" class="co-card charger">
            <header class="charger-head">
              <span class="charger-name">{{ chargerLabel(group) }}</span>
              <span v-if="group.chargerTypeLabel" class="charger-type">
                {{ group.chargerTypeLabel }}
              </span>
              <span class="charger-sum co-num" :class="{ 'is-none': group.availableCount === 0 }">
                {{ group.availableCount > 0 ? `${group.availableCount} 把可用` : '暂无可用' }}
              </span>
            </header>

            <ul class="gun-list">
              <li v-for="connector in group.connectors" :key="connector.id">
                <button
                  type="button"
                  class="gun"
                  :class="{ 'is-off': !connector.available }"
                  @click="onGunTap(connector)"
                >
                  <span class="gun-no co-num">{{ connector.gunNo }} 号枪</span>
                  <span class="gun-meta">
                    <span v-if="connector.connectorType">{{ connector.connectorType }}</span>
                    <span v-if="connector.ratedPowerKw">
                      {{ powerText(connector.ratedPowerKw) }}
                    </span>
                  </span>
                  <span class="gun-badge" :class="statusClass(connector.status)">
                    {{ connector.statusLabel }}
                  </span>
                </button>
              </li>
            </ul>
          </article>
        </template>
      </section>
    </div>

    <!-- ③ 确认下单层 ------------------------------------------------ -->
    <van-popup
      v-model:show="sheetOpen"
      position="bottom"
      round
      safe-area-inset-bottom
      @closed="onSheetClosed"
    >
      <div class="sheet">
        <header class="sheet-head">
          <h3 class="sheet-title">确认下单</h3>
          <p class="sheet-sub">{{ pickedLabel }}</p>
        </header>

        <div class="sheet-body">
          <!-- 失败后的「下一步」。★ 后端那句话已经由 http 层弹过 toast，
               这里放的是**它没说的事**：现在该干什么 -->
          <div v-if="notice" class="notice" :class="`is-${notice.tone}`">
            <p class="notice-title">{{ notice.title }}</p>
            <p class="notice-desc">{{ notice.desc }}</p>
            <button
              v-if="notice.action"
              type="button"
              class="notice-action"
              @click="runNoticeAction"
            >
              {{ notice.action === 'pick-another' ? '换一把' : notice.action === 'recharge' ? '去充值' : '查看我的订单' }}
            </button>
          </div>

          <!-- 车辆 -->
          <h4 class="sheet-label">选择车辆 <span class="sheet-optional">（可以不选）</span></h4>
          <van-radio-group v-model="vehicleId">
            <van-cell-group inset>
              <van-cell
                v-for="vehicle in vehicleList"
                :key="vehicle.id"
                :title="vehicle.plateNo"
                :label="vehicleLabel(vehicle)"
                clickable
                @click="vehicleId = vehicle.id"
              >
                <template #right-icon>
                  <van-radio :name="vehicle.id" />
                </template>
              </van-cell>
              <van-cell
                title="不带车辆"
                label="不选车辆也能下单，冻结额按 60 度估算"
                clickable
                @click="vehicleId = null"
              >
                <template #right-icon>
                  <van-radio :name="null" />
                </template>
              </van-cell>
            </van-cell-group>
          </van-radio-group>

          <p v-if="vehiclesFailed" class="sheet-warn">
            车辆列表没读出来。可以先不选车辆下单，或者
            <button type="button" class="inline-link" @click="reloadVehicles">重新加载</button>。
          </p>

          <!-- 支付方式 -->
          <h4 class="sheet-label">支付方式</h4>
          <div class="balance-row">
            <span>可用余额</span>
            <MoneyText :value="balance" size="sm" strong />
          </div>

          <van-radio-group v-model="payMethod">
            <van-cell-group inset>
              <van-cell
                title="余额支付"
                :label="balanceLabel"
                clickable
                @click="payMethod = PAY_BALANCE"
              >
                <template #right-icon>
                  <van-radio :name="PAY_BALANCE" />
                </template>
              </van-cell>
            </van-cell-group>
          </van-radio-group>

          <!-- ★ 微信/支付宝：后端只有余额能过 `PayMethod.isSupported()`，
               所以这里是**置灰的说明行**，不是可选项 —— 不给用户一个必然失败的选项 -->
          <van-cell-group inset class="off-group">
            <van-cell
              v-for="item in unsupportedPayMethods"
              :key="item.code"
              :title="item.label"
              label="当前版本没有接入，选了也只会失败"
              class="is-off"
            >
              <template #right-icon><span class="off-tag">未接入</span></template>
            </van-cell>
          </van-cell-group>

          <!-- 钱怎么动 -->
          <div class="money-note">
            <p>
              下单会按<b>预估金额冻结</b>余额 = 本站最贵时段的（电费 + 服务费）× 车辆电池容量，
              最高 ¥500；不带车辆时按 60 度估算。
            </p>
            <p>冻的是余额、不是扣款：结算时按实际用量扣，多冻的部分退回可用余额。</p>
            <p>下单成功后订单是「<b>待启动</b>」，站点启动后才会真的开始充电。</p>
          </div>
        </div>

        <footer class="sheet-foot">
          <van-button
            block
            round
            type="primary"
            :loading="pending"
            :disabled="!picked || outcomeUnknown"
            @click="submit"
          >
            {{ outcomeUnknown ? '结果未知，请先查看订单' : '确认下单' }}
          </van-button>
          <p class="sheet-foot-tip">
            同一把枪同一时刻只能有一笔订单。下单会占用这把枪；不想充了请在订单详情里取消，
            取消会释放冻结的金额。
          </p>
        </footer>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.station-page {
  min-height: 100vh;
  background: var(--co-bg-page);
}

.body {
  padding: 12px;
}

/* ══ ① 站点资料 ═══════════════════════════════════════════════════════ */

/* ★ 一层极淡的品牌色渐变：让子页和首页的品牌区看起来是同一套东西，
     又不会像首页那样抢注意力（这一页的主体是枪列表）。 */
.head {
  padding: 14px;
  background: linear-gradient(135deg, var(--co-primary-weak) 0%, var(--co-bg-card) 58%);
}

.head-name {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--co-text-1);
}

.head-line {
  display: flex;
  gap: 4px;
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--co-text-2);
}

.head-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.head-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 8px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
  font-size: 12px;
  color: var(--co-text-2);
}

.head-phone {
  text-decoration: none;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-weight: 600;
}

.head-note {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-warning-strong);
}

/* ══ ② 枪列表 ═════════════════════════════════════════════════════════ */

.guns {
  margin-top: 16px;
}

.guns-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.guns-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.guns-count {
  font-size: 12px;
  color: var(--co-text-3);
}

/* ★ 空闲数用成功色，0 的时候变灰 —— 它是这一屏唯一「决定去不去」的数字 */
.guns-count b {
  color: var(--co-success-strong);
  font-weight: 700;
}

.guns-count b.is-zero {
  color: var(--co-text-4);
}

.guns-note {
  margin: 6px 0 10px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.guns-warn {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
  font-size: 11.5px;
  line-height: 1.6;
}

.link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

.link:disabled {
  color: var(--co-text-4);
}

.charger + .charger {
  margin-top: 10px;
}

.charger {
  padding: 12px 14px;
}

.charger-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.charger-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--co-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.charger-type {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-3);
  font-size: 11px;
}

.charger-sum {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--co-success-strong);
}

.charger-sum.is-none {
  color: var(--co-text-4);
  font-weight: 400;
}

.gun-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.gun {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--co-primary-weak);
  border-radius: var(--co-radius-md);
  background: var(--co-primary-weak);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    transform var(--co-duration-fast) var(--co-ease),
    background var(--co-duration-fast) var(--co-ease);
}

.gun:active {
  transform: scale(0.97);
}

/* ★ 不可用的枪**照常可点**（点了给解释），只是灰掉 + 去掉品牌色：
     用 `disabled` 会让它完全无法聚焦，用户只能反复戳一个没反应的方块。 */
.gun.is-off {
  border-color: var(--co-border-light);
  background: var(--co-bg-2);
}

.gun-no {
  font-size: 14px;
  font-weight: 600;
  color: var(--co-text-1);
}

.gun.is-off .gun-no {
  color: var(--co-text-3);
}

.gun-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.gun-badge {
  align-self: flex-start;
  margin-top: 2px;
  padding: 1px 7px;
  border-radius: var(--co-radius-sm);
  font-size: 11px;
  font-weight: 600;
}

/* ★ 状态胶囊用「浅底 + 同色深字」，不用实心色块：
     一屏可能有十几把枪，实心块会把页面变成一堵色墙 */
.gun-badge.is-ok {
  background: var(--co-success-weak);
  color: var(--co-success-strong);
}

.gun-badge.is-busy {
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
}

.gun-badge.is-fault {
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
}

.gun-badge.is-mute {
  background: var(--co-bg-3);
  color: var(--co-text-3);
}

/* ══ ③ 确认层 ═════════════════════════════════════════════════════════ */

.sheet {
  display: flex;
  flex-direction: column;
  /* ★ 用 vh 而不是百分比：弹出层的高度由内容决定，
       只有给它一个上限、再让中段滚动，长内容才不会把标题和按钮顶出屏幕 */
  max-height: 86vh;
}

.sheet-head {
  flex-shrink: 0;
  padding: 16px 16px 10px;
}

.sheet-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--co-text-1);
}

.sheet-sub {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.sheet-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 0 12px 12px;
}

/* ★ 层内已经有 12px 内边距，Vant 的 `inset` 会再叠一层（合起来 24px），
     比旁边的提示块和说明块宽出一截。层内取消它，卡片左右对齐。 */
.sheet-body :deep(.van-cell-group--inset) {
  margin: 0;
}

.sheet-label {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-2);
}

.sheet-optional {
  font-weight: 400;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.sheet-warn {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-warning-strong);
}

.inline-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-size: inherit;
  font-family: inherit;
  cursor: pointer;
}

/* ── 层内提示 ─────────────────────────────────────────────────────────── */

.notice {
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
}

.notice.is-warn {
  background: var(--co-warning-weak);
}

.notice.is-error {
  background: var(--co-danger-weak);
}

.notice-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.notice-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-text-2);
}

.notice-action {
  margin-top: 8px;
  padding: 4px 12px;
  border: 1px solid currentcolor;
  border-radius: 999px;
  background: var(--co-bg-card);
  color: var(--co-primary);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

/* ── 余额 ─────────────────────────────────────────────────────────────── */

.balance-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--co-text-3);
}

.off-group {
  margin-top: 8px;
}

.off-group :deep(.is-off) {
  opacity: 0.72;
}

.off-tag {
  padding: 1px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-3);
  font-size: 11px;
}

.money-note {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
}

.money-note p {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--co-text-2);
}

.money-note p + p {
  margin-top: 4px;
}

.money-note b {
  color: var(--co-text-1);
}

/* ── 底部按钮 ─────────────────────────────────────────────────────────── */

.sheet-foot {
  flex-shrink: 0;
  padding: 10px 16px 14px;
  border-top: 1px solid var(--co-border-light);
  background: var(--co-bg-card);
}

.sheet-foot-tip {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--co-text-3);
}
</style>
