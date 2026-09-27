<script setup lang="ts">
/**
 * 订单详情（车主端）。
 *
 * 这一页要回答三个问题，顺序就是页面从上往下的顺序：
 *   ① 现在是什么状态、我该干什么（顶部大卡 + 待支付倒计时）
 *   ② 钱是怎么算出来的（费用明细 + 分段计费）
 *   ③ 为什么会变成这样（事件时间线 —— 这是**唯一**能回答它的地方）
 *
 * ★★ 后端没有幂等键 ★★
 *   `pay` / `cancel` 点两次就真的执行两次（不是「第二次被忽略」）。
 *   所以下面每个资金动作都套了**两层**：按钮级的 `useSubmitLock` +
 *   业务级的 `withActionLock`，缺一不可（各自能挡住的场景见 shared/http/locks.ts）。
 *   而且支付**不能做乐观更新** —— 余额够不够由后端在事务里判，前端预知不了。
 *
 * ★★ 这一页**不显示支付方式选择器** ★★
 *   支付方式在建单时就定了（`OrderCreateDTO.payMethod`），支付接口也没有请求体。
 *   这里只把 `payMethodLabel` 当只读信息展示。
 *
 * ★ 支付/取消成功后**只刷新本页**，不去通知订单列表 ★
 *   列表页（`/orders`）和详情页是两个相邻但独立的路由，App.vue 里**没有 keep-alive**，
 *   从详情返回列表时列表组件会重新挂载并重拉第一页 —— 它本来就是新的，
 *   不需要、也没有一个「标记列表过期」的接口可调。
 *   （`invalidateOptionsByTag` 是给**下拉框选项**缓存用的，跟订单列表无关，别用错。）
 */

import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import {
  ORDER_STATUS,
  PENDING_PAY_MINUTES,
  PERIOD_COLOR_VAR,
  TONE_VAR,
  canDo,
  describeOrderEvent,
  durationBetween,
  formatCn,
  formatCountdown,
  formatDuration,
  formatKwhLabel,
  formatMoney,
  formatPrice,
  formatTime,
  holdsMoney,
  isActionLocked,
  isNoopEvent,
  isPositive,
  orderStatusMeta,
  pendingPayRemainMs,
  periodColor,
  segmentsMatchTotal,
  transitionText,
  withActionLock,
  type ChargingOrderDetailVO,
  type ChargingSegmentVO,
  type Decimal,
} from '@chargeops/shared'
import { cancelOrder, getOrder, payOrder } from '@/api/order'
import { useAsyncDataOn } from '@/composables/useAsyncData'
import { useSubmitLock, type UseSubmitLock } from '@/composables/useSubmitLock'
import MoneyText from '@/components/MoneyText.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import PageState from '@/components/PageState.vue'

const route = useRoute()
const router = useRouter()

/**
 * 订单 id（路由参数是 string）。
 * 非数字时**不发请求** —— 打过去只会得到一句「订单不存在」，
 * 而真正的问题是 URL 被手改过，那种请求白跑一趟还会弹一个误导性的 toast。
 */
const orderId = computed(() => {
  const raw = route.params.id
  return Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')
})
const idValid = computed(() => /^\d+$/.test(orderId.value))

const {
  data: order,
  loading,
  failed,
  reload,
} = useAsyncDataOn<ChargingOrderDetailVO, string>(orderId, (id) =>
  idValid.value ? getOrder(id) : Promise.resolve(null),
)

const status = computed(() => order.value?.status ?? null)
const meta = computed(() => orderStatusMeta(status.value))
/** 大卡配色：浅底（`-weak`）+ 同色深字（`-strong`），不用纯色填充。 */
const heroVars = computed(() => TONE_VAR[meta.value?.tone ?? 'info'])

/**
 * 「现在该干什么」。
 *
 * ★ 和字典里的 `hint`（「这个状态是什么意思」）**刻意分开写**：
 *   两句话回答的是不同的问题，所以都要留 —— 大字给动作，小字给定义。
 */
const HERO_ADVICE: Record<number, string> = {
  [ORDER_STATUS.CREATED]: '还没开始充电。设备启动前可以取消，取消会释放冻结的金额。',
  [ORDER_STATUS.CHARGING]: '正在充电，结束后会自动生成账单。',
  [ORDER_STATUS.SETTLING]: '正在结算，账单通常几秒内生成。',
  [ORDER_STATUS.PENDING_PAY]: '账单已生成，请在时限内完成支付。',
  [ORDER_STATUS.PAID]: '已完成，无需再操作。',
  [ORDER_STATUS.PAY_FAILED]: '上次扣款没成功，充值后可以重新支付。',
  [ORDER_STATUS.REFUNDED]: '退款已回到余额。',
  [ORDER_STATUS.CANCELLED]: '订单已取消，冻结的金额已释放。',
  [ORDER_STATUS.ABNORMAL]: '系统算不出结算结果，已挂起等人工处理（不会自动重试）。',
}
const advice = computed(() => (status.value === null ? '' : (HERO_ADVICE[status.value] ?? '')))

// ══════════════════════════════════════════════════════════════════════════
// 待支付倒计时
// ══════════════════════════════════════════════════════════════════════════

/**
 * 每秒跳一次的「现在」。
 * ★ 倒计时必须有一个**每秒变化的数据源**才能驱动重渲染 ——
 *   在模板里调 `Date.now()` 只会算一次，数字永远不动。
 */
const nowMs = ref(Date.now())
let tickTimer: number | null = null
/** 本轮倒计时是否已经触发过自动重拉（否则到点后会每秒发一次请求）。 */
let autoReloaded = false

const isPendingPay = computed(() => status.value === ORDER_STATUS.PENDING_PAY)

/**
 * 剩余毫秒。★ 用 shared 的 `pendingPayRemainMs`（按 `createTime + 15 分钟` 反推）：
 *   后端**没有**返回过期时刻 —— 它的超时是定时任务按 `create_time < now() - 15min`
 *   扫出来的，所以「过期时刻」在前端只能这么算。
 *   传进去的 `now` 是响应式的，这是它成为 computed 而不是普通函数的原因。
 */
const remainMs = computed(() => {
  const current = order.value
  if (!current || !isPendingPay.value) return null
  return pendingPayRemainMs(current.createTime, nowMs.value)
})

const countdownText = computed(() => formatCountdown(remainMs.value))
const countdownExpired = computed(() => remainMs.value !== null && remainMs.value <= 0)

function startTick(): void {
  if (tickTimer !== null) return
  nowMs.value = Date.now()
  tickTimer = window.setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
}

function stopTick(): void {
  if (tickTimer === null) return
  clearInterval(tickTimer)
  tickTimer = null
}

watch(
  () => status.value,
  (next) => {
    // ★ 只有「4 待支付」需要倒计时。离开它（付掉了 / 超时被扫成 6）必须**立刻停表** ——
    //   留着一个每秒跑的空转 interval，用户切走后再回来会看到好几个在跑。
    //   `remainMs` 为 null 说明 `createTime` 解析不出来：倒计时块整个不显示，
    //   这时连表都不用起（页面上没有任何依赖时间的东西）。
    if (next === ORDER_STATUS.PENDING_PAY && remainMs.value !== null) {
      autoReloaded = false
      startTick()
    } else {
      stopTick()
    }
  },
  { immediate: true },
)

watch(countdownExpired, (expired) => {
  // ★★ 到点 ≠ 立刻变状态 ★★
  //   后端定时任务每 5 分钟扫一轮（`chargeops.scheduled.pay-timeout-scan-ms`），
  //   所以本地数字归零之后，订单可能还挂在 4 上最长 5 分钟。
  //   → 到点主动重拉一次详情让状态跟上，但**不轮询**：前端猜不出下一轮扫描什么时候来，
  //     与其按一个编出来的间隔反复请求，不如让用户手动刷新（页面上给了按钮）。
  if (!expired || autoReloaded) return
  autoReloaded = true
  stopTick()
  void reload()
})

onUnmounted(stopTick)

// ══════════════════════════════════════════════════════════════════════════
// 费用明细
// ══════════════════════════════════════════════════════════════════════════

/** 只有 5 已支付 / 7 已退款 的钱才是**真的扣过**的；4/6 时 `payAmount` 只是账面上的值。 */
const isDeducted = computed(() => {
  const code = status.value
  return code === ORDER_STATUS.PAID || code === ORDER_STATUS.REFUNDED
})

const hasBill = computed(() => {
  const total = order.value?.totalAmount
  return total !== null && total !== undefined
})

interface KpiCell {
  key: string
  label: string
  /** 有金额的格子交给 `MoneyText`（千分位 + 空值渲染 `--`）。 */
  amount?: Decimal
  /** 没有金额的格子用文字（例如「冻结中」—— 见下面那条注释）。 */
  text?: string
  hint?: string
}

const kpiCells = computed<KpiCell[]>(() => {
  const current = order.value
  if (!current) return []

  const cells: KpiCell[] = [
    { key: 'total', label: '应付金额', amount: current.totalAmount, hint: '按分段计价合计' },
    {
      key: 'paid',
      label: '实付金额',
      amount: isDeducted.value ? current.payAmount : null,
      hint: isDeducted.value ? '已从余额扣除' : '支付后扣款',
    },
    {
      key: 'frozen',
      label: '冻结中',
      // ★★ 这一格刻意**不给金额** ★★
      //   车主端 VO 没有 `frozenAmount`（后端有意不暴露，见 ChargingOrderVO 的类注释），
      //   而冻着的是**建单时的预估价**，与 `totalAmount`（实际账单）不是一个数。
      //   拿账单额冒充冻结额，会造出一个用户永远对不上账的数字 —— 所以只说状态。
      text: holdsMoney(current.status) ? '冻结中' : '已释放',
      hint: holdsMoney(current.status) ? '仍占着余额，支付或取消后释放' : '已不占用余额',
    },
  ]

  // 只在真的有过这两笔时才显示，避免每张订单上都挂两个「¥0.00」
  if (isPositive(current.refundAmount)) {
    cells.push({ key: 'refund', label: '已退款', amount: current.refundAmount, hint: '已回到余额' })
  }
  if (isPositive(current.discountAmount)) {
    cells.push({ key: 'discount', label: '优惠抵扣', amount: current.discountAmount, hint: '权益抵扣，不从余额扣' })
  }

  cells.push({
    key: 'energy',
    // ★ 电量不是金额（单位是度），走文字；未结算时为 `--` 而不是 `0.00 度`
    text: current.totalEnergyKwh === null || current.totalEnergyKwh === undefined
      ? '--'
      : formatKwhLabel(current.totalEnergyKwh),
    label: '电量',
    hint: current.startSoc !== null && current.endSoc !== null
      ? `SOC ${current.startSoc}% → ${current.endSoc}%`
      : undefined,
  })

  return cells
})

/** 电费 / 服务费两行。★ 两者相加就是应付金额，分开列是为了让「服务费」可见。 */
const feeRows = computed(() => {
  const current = order.value
  if (!current || !hasBill.value) return []
  return [
    { key: 'energyFee', label: '电费', value: current.energyFee },
    { key: 'serviceFee', label: '服务费', value: current.serviceFee },
  ]
})

/** 电表读数：`1234.5000 → 1256.7000 度`。两端缺一个就整行不显示。 */
const meterText = computed(() => {
  const current = order.value
  if (!current) return ''
  if (current.startMeterKwh === null || current.endMeterKwh === null) return ''
  return `${formatKwhLabel(current.startMeterKwh)} → ${formatKwhLabel(current.endMeterKwh)}`
})

// ══════════════════════════════════════════════════════════════════════════
// 分段计费
// ══════════════════════════════════════════════════════════════════════════

const segments = computed<ChargingSegmentVO[]>(() => order.value?.segments ?? [])

/**
 * 一段的时长（毫秒）。
 * ★ 优先用后端给的 `durationSeconds`；缺了才用起止时间算 —— 而算的时候
 *   必须走 `durationBetween`（它内部用 `parseServerTime`），
 *   直接 `new Date("2026-01-01 10:00:00")` 在 Safari 上是 `Invalid Date`。
 */
function segmentMs(segment: ChargingSegmentVO): number {
  if (segment.durationSeconds > 0) return segment.durationSeconds * 1000
  return durationBetween(segment.startTime, segment.endTime) ?? 0
}

/**
 * 按时长占比的堆叠条。
 *
 * ★ 宽度用**时长**算，不用金额也不用电量：这条横轴表达的是「这单在时间上跨了几个时段」，
 *   用金额算的话，一段尖峰的短时间会显得很长，读者会以为那是「充了很久」。
 */
const barBlocks = computed(() => {
  const list = segments.value
  const totalMs = list.reduce((sum, s) => sum + segmentMs(s), 0)
  if (totalMs <= 0) return []
  return list.map((s) => {
    const ratio = segmentMs(s) / totalMs
    return {
      seq: s.seq,
      width: `${(ratio * 100).toFixed(2)}%`,
      background: periodColor(s.periodType),
      title: `${s.periodTypeLabel} ${Math.round(ratio * 100)}%`,
    }
  })
})

interface SegmentRow {
  seq: number
  periodLabel: string
  /** 时段色（尖峰平谷那一组，`--co-period-*`，与状态色是两套，不能混用）。 */
  style: { background: string; color: string }
  range: string
  duration: string
  energy: string
  price: string
  amount: Decimal
}

/** 时段 chip 的「浅底 + 同色深字」。未知时段回落中性灰，不要落到尖峰的红色上。 */
function periodStyle(periodType: number): { background: string; color: string } {
  const vars = PERIOD_COLOR_VAR[periodType]
  return {
    background: vars?.weak ?? 'var(--co-bg-2)',
    color: vars?.strong ?? 'var(--co-text-2)',
  }
}

const segmentRows = computed<SegmentRow[]>(() =>
  segments.value.map((s) => ({
    seq: s.seq,
    periodLabel: s.periodTypeLabel,
    style: periodStyle(s.periodType),
    range: `${formatTime(s.startTime) ?? '--'}–${formatTime(s.endTime) ?? '--'}`,
    duration: formatDuration(segmentMs(s)),
    energy: formatKwhLabel(s.energyKwh),
    price: `${formatPrice(s.energyPrice)} + ${formatPrice(s.servicePrice)}`,
    amount: s.amount,
  })),
)

/**
 * 开发期自检：**Σ分段金额 是否等于订单总额**。
 *
 * ★ 后端承诺这个等式恒成立（`BigDecimal` 只在最后取整一次）。
 *   不相等说明要么数据造错了、要么后端口径变了 —— 两者都该在开发期立刻炸出来，
 *   而不是等用户截图来问「为什么加起来差一分」。
 *   **只在 DEV 下算**：生产环境不做这个无用功（`import.meta.env.DEV` 是编译期常量）。
 */
const segmentSumMismatch = computed(() => {
  if (!import.meta.env.DEV) return false
  const current = order.value
  const list = current?.segments
  if (!current || !list || list.length === 0 || !hasBill.value) return false
  return !segmentsMatchTotal(
    list.map((s) => s.amount),
    current.totalAmount as Decimal,
  )
})

// ══════════════════════════════════════════════════════════════════════════
// 事件时间线
// ══════════════════════════════════════════════════════════════════════════

interface TimelineRow {
  key: string
  title: string
  description: string
  dot: string
  /** 状态迁移 chip（`待支付 → 支付失败`）。★ `fromStatus`/`toStatus` 是裸数字，
   *  中文由 `transitionText` 内部用状态字典翻译；取不到时是 null（首条事件没有来源状态）。 */
  transition: string | null
  source: string
  time: string
  /** 「重复上报已忽略」这类「什么都没发生」的记录，视觉上要退到后面。 */
  noop: boolean
}

const timeline = computed<TimelineRow[]>(() =>
  (order.value?.events ?? []).map((ev, index) => {
    const described = describeOrderEvent(ev)
    return {
      key: `${index}-${ev.eventType}-${ev.createTime}`,
      title: described.title,
      // ★ 优先是后端写的 `detail`（那一刻的原始原因），兜底才是我们自己拼的短语
      description: described.description,
      // ★ 不写 `as Tone`：`orderEventTone()` 的返回类型与 `Tone` 是同一个联合，
      //   不收窄反而更安全 —— 以后谁把事件色调扩成新值，这里会**编译失败**，
      //   而不是运行时取到 `undefined.base` 把整页炸掉。
      dot: TONE_VAR[described.tone].base,
      transition: transitionText(ev.fromStatus, ev.toStatus),
      source: ev.sourceLabel,
      time: formatCn(ev.createTime) ?? '',
      noop: isNoopEvent(ev.eventType),
    }
  }),
)

// ══════════════════════════════════════════════════════════════════════════
// 底部操作
// ══════════════════════════════════════════════════════════════════════════

/**
 * 底部 bar 上显示哪些按钮 —— 一律问规则表。
 * ★ 页面里不写 `status === 4`：后端每次流转都是 `WHERE status = <来源>` 的条件更新，
 *   规则表抄的就是那些来源集合，改后端时这里自动跟着变。
 */
const canPay = computed(() => canDo('pay', status.value, 'member'))
const canCancel = computed(() => canDo('cancel', status.value, 'member'))
/**
 * 「去充值」只在**支付失败(6)** 出现，而且必须与「重新支付」**同时**出现。
 * ★ 6 不是死路：`markPaid` 的谓词是 `status IN (4, 6)`，充值之后再点一次是真能成功的
 *   → 只给「去充值」会让人以为这单只能靠充值；只给「重新支付」又会在余额仍不够时
 *   让人反复戳同一个按钮。两个出口是一组。
 */
const canRecharge = computed(() => status.value === ORDER_STATUS.PAY_FAILED)
const hasBarAction = computed(() => canPay.value || canCancel.value || canRecharge.value)
const payLabel = computed(() => (canRecharge.value ? '重新支付' : '去支付'))

/** ★ 两个动作各自一把按钮锁：共用一把的话，取消的 pending 会让支付按钮也转圈。 */
const { pending: paying, run: runPay } = useSubmitLock()
const { pending: cancelling, run: runCancel } = useSubmitLock()

/**
 * 套两层防重地执行一个资金动作。
 *
 * ★ 必须是 `run(() => withActionLock(...))` 这个组合：
 *   `withActionLock` 在冷却期内是**抛异常**（`ActionLockedError`）的，
 *   而 `run()` 把异常收敛成 `{status:'failed'}` —— 直接在 `run` 外面调它，
 *   这个异常会变成一个没人处理的 rejection。
 */
async function submitLocked(
  run: UseSubmitLock['run'],
  key: string,
  action: () => Promise<unknown>,
  successText: string,
): Promise<void> {
  const outcome = await run(() => withActionLock(key, action))
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 只有「被动作锁拦下」需要在这里提示；其它错误 http 层已经弹过 toast 了，
    //   再弹一次就是两条红条叠在一起。
    if (isActionLocked(outcome.error)) showToast('操作正在处理中，请稍候再试')
    return
  }
  showSuccessToast(successText)
}

const refreshing = ref(false)
async function onRefresh(): Promise<void> {
  try {
    await reload()
  } finally {
    // ★ 必须复位，否则下拉头收不回去
    refreshing.value = false
  }
}

async function doPay(): Promise<void> {
  const current = order.value
  if (!current) return

  // ★ 二次确认里必须有金额 —— 这是「人」这一层的防重手段（见 shared/http/locks.ts 的表）：
  //   代码挡不住「刷新页面后重试」，只能靠用户看清金额再点。
  const amount = current.payAmount ?? current.totalAmount
  const amountText = amount === null || amount === undefined ? '账单金额' : `¥${formatMoney(amount)}`
  try {
    await showConfirmDialog({
      title: payLabel.value,
      message:
        `将从余额扣款 ${amountText}。\n` +
        '余额不足时会转入「支付失败」，冻结的金额不会丢，充值后可以再付一次。',
      messageAlign: 'left',
      confirmButtonText: '确认支付',
    })
  } catch {
    // ★ 用户点取消时 `showConfirmDialog` 是 **reject** 而不是 resolve(false)，
    //   不 catch 会留下一个未处理的 rejection。
    return
  }

  // ★ 锁 key 里必须带订单 id（payload 的一部分）：不同订单的支付是**两个**动作，
  //   不该被互相冷却掉；而同一订单的第二次点击几乎必然是重复提交。
  await submitLocked(runPay, `order-pay:${current.id}`, () => payOrder(current.id), '支付成功')

  // ★★ 不管成功还是失败都要重拉详情 ★★
  //   支付结果前端预知不了：余额不够时**后端自己**会把订单 4 → 6 并写一条 abnormal 事件，
  //   所以失败之后页面上的状态**会变**。不重拉的话，用户会看到一个
  //   「明明提示失败了、却还显示待支付」的页面，然后用同一个余额再点一次。
  await reload()
}

async function doCancel(): Promise<void> {
  const current = order.value
  if (!current) return

  try {
    await showConfirmDialog({
      title: '取消订单',
      message: '取消后订单立即关闭，建单时冻结的金额会释放回余额，本单不产生任何费用。',
      messageAlign: 'left',
      confirmButtonText: '确认取消',
    })
  } catch {
    return
  }

  await submitLocked(
    runCancel,
    `order-cancel:${current.id}`,
    () => cancelOrder(current.id),
    '订单已取消',
  )
  // ★ 取消会释放冻结金额、放掉充电枪，状态和金额都变了 —— 必须重拉，不能只改本地状态。
  await reload()
}

function gotoRecharge(): void {
  void router.push({ name: 'wallet' })
}

function gotoOrders(): void {
  void router.replace({ name: 'orders' })
}

function goBack(): void {
  // ★ 从分享链接 / 刷新直接进详情页时没有上一页，`back()` 会原地不动（看起来像按钮坏了）
  if (window.history.state?.back) router.back()
  else gotoOrders()
}
</script>

<template>
  <div class="detail" :class="{ 'with-bar': hasBarAction }">
    <van-nav-bar fixed placeholder title="订单详情" left-arrow @click-left="goBack">
      <template #right>
        <span class="nav-action" @click="onRefresh">刷新</span>
      </template>
    </van-nav-bar>

    <PageState v-if="loading && !order" kind="loading" />
    <PageState
      v-else-if="!order"
      kind="error"
      title="订单打不开"
      :description="
        failed ? '可能是网络不通，也可能这笔订单不属于当前账号。' : '这个订单号不存在，或者链接被改过。'
      "
      @retry="reload"
    >
      <van-button size="small" round plain type="primary" @click="gotoOrders">返回订单列表</van-button>
    </PageState>

    <van-pull-refresh v-else v-model="refreshing" @refresh="onRefresh">
      <!-- ① 状态大卡 -->
      <section class="hero" :style="{ background: heroVars.weak }">
        <div class="hero-top">
          <!-- ★ 这层白底是必要的：大卡背景就是**同色调的浅底**，
               标签自己也是同色浅底 —— 直接放上去，标签的形状会消失成一片纯文字。 -->
          <span class="tag-chip"><OrderStatusTag :status="order.status" /></span>
          <span class="hero-no co-ellipsis">{{ order.orderNo }}</span>
        </div>

        <p class="hero-advice" :style="{ color: heroVars.strong }">{{ advice }}</p>
        <p v-if="meta" class="hero-hint">{{ meta.hint }}</p>

        <!-- 倒计时：只有「4 待支付」有。remainMs 为 null 说明 createTime 解析不出来，
             那时宁可不显示，也不要显示一个恒为 00:00 的假倒计时。 -->
        <div v-if="isPendingPay && remainMs !== null" class="cd">
          <template v-if="!countdownExpired">
            <div class="cd-row">
              <span class="cd-label">支付剩余时间</span>
              <b class="cd-value co-num" :style="{ color: heroVars.strong }">{{ countdownText }}</b>
            </div>
            <p class="cd-note">
              超过 {{ PENDING_PAY_MINUTES }} 分钟未支付会被标记为「支付失败」（不会自动扣款）。
            </p>
          </template>

          <!-- ★★ 到点 ≠ 已超时 ★★ 定时任务每 5 分钟扫一轮
               （`chargeops.scheduled.pay-timeout-scan-ms`），所以文案只说「预计」，
               并且把「为什么刷新了还是待支付」解释清楚 —— 否则用户会以为页面坏了。 -->
          <template v-else>
            <p class="cd-note">
              预计已超过 {{ PENDING_PAY_MINUTES }} 分钟支付时限。系统每 5 分钟扫描一轮，
              所以状态可能还会停留在「待支付」一会儿，这是正常的。
            </p>
            <van-button size="mini" round plain type="primary" @click="onRefresh">
              刷新状态
            </van-button>
          </template>
        </div>
      </section>

      <!-- ② 费用明细 -->
      <section class="panel co-card">
        <h2 class="panel-title">费用明细</h2>

        <p v-if="!hasBill" class="panel-note">
          账单尚未生成：充电结束时会按实际电量与当时的电价结算，生成后这里会显示金额。
        </p>

        <div class="kpi">
          <div v-for="cell in kpiCells" :key="cell.key" class="kpi-cell">
            <span class="kpi-label">{{ cell.label }}</span>
            <MoneyText v-if="cell.amount !== undefined" :value="cell.amount" size="md" strong />
            <b v-else class="kpi-text">{{ cell.text }}</b>
            <span v-if="cell.hint" class="kpi-hint">{{ cell.hint }}</span>
          </div>
        </div>

        <dl v-if="feeRows.length > 0" class="fee-rows">
          <div v-for="row in feeRows" :key="row.key" class="fee-row">
            <dt>{{ row.label }}</dt>
            <dd><MoneyText :value="row.value" size="sm" /></dd>
          </div>
        </dl>
      </section>

      <!-- ③ 分段计费 -->
      <section v-if="segmentRows.length > 0" class="panel co-card">
        <div class="panel-head">
          <h2 class="panel-title">分段计费</h2>
          <span class="panel-sub">共 {{ segmentRows.length }} 段</span>
        </div>

        <!-- 按时长占比的堆叠条：一眼看出这单跨了几个时段 -->
        <div v-if="barBlocks.length > 0" class="bar" role="img" aria-label="各时段时长占比">
          <span
            v-for="block in barBlocks"
            :key="block.seq"
            class="bar-block"
            :style="{ width: block.width, background: block.background }"
            :title="block.title"
          />
        </div>

        <!-- ★ 图与说明一起控制显隐：`barBlocks` 为空说明所有分段的时长都是 0
             （起止时间解析不出来），这时画一条全灰的空条会让人以为「数据丢了」。 -->
        <p v-if="barBlocks.length > 0" class="panel-note">
          单价 = 电费 + 服务费（元/度），时长占比决定上面色块的宽度。
        </p>

        <!-- ★ 只在开发期出现的断言 —— 后端承诺 Σ分段金额 == 订单总额 -->
        <p v-if="segmentSumMismatch" class="dev-warn">
          [DEV] Σ分段金额 ≠ 订单总额。后端承诺「只在最后取整一次」，两者不等说明分段数据或总额有问题。
        </p>

        <ul class="segments">
          <li v-for="row in segmentRows" :key="row.seq" class="segment">
            <div class="seg-head">
              <span class="seg-chip" :style="row.style">{{ row.periodLabel }}</span>
              <span class="seg-range co-num">{{ row.range }}</span>
              <span class="seg-duration co-num">{{ row.duration }}</span>
            </div>
            <div class="seg-body">
              <span class="seg-detail">
                {{ row.energy }} · 单价 <i class="co-num">{{ row.price }}</i>
              </span>
              <MoneyText :value="row.amount" size="sm" strong />
            </div>
          </li>
        </ul>
      </section>

      <!-- ④ 事件时间线 -->
      <section v-if="timeline.length > 0" class="panel co-card">
        <h2 class="panel-title">订单动态</h2>
        <p class="panel-note">
          按时间从早到晚。「为什么变成现在这个状态」只能在这里找到答案。
        </p>

        <ol class="timeline">
          <li v-for="item in timeline" :key="item.key" class="tl-item" :class="{ noop: item.noop }">
            <span class="tl-dot" :style="{ background: item.dot }" />
            <div class="tl-body">
              <div class="tl-head">
                <b class="tl-title">{{ item.title }}</b>
                <span class="tl-time co-num">{{ item.time }}</span>
              </div>
              <!-- ★ 状态码在事件里是**裸数字**，chip 里显示的是翻译后的中文 -->
              <span v-if="item.transition" class="tl-chip">{{ item.transition }}</span>
              <p class="tl-desc">{{ item.description }}</p>
              <p class="tl-source">{{ item.source }}</p>
            </div>
          </li>
        </ol>
      </section>

      <!-- ⑤ 订单信息 -->
      <van-cell-group inset class="info">
        <van-cell title="订单号" :value="order.orderNo" />
        <van-cell title="站点" :value="`#${order.stationId}`" />
        <van-cell title="充电枪" :value="`#${order.connectorId}`" />
        <!-- ★ 车牌为空时给 `-` 而不是「未绑定车辆」：订单里的车牌是**建单那一刻的快照**，
             为空只能说明这单没记车牌，推不出「账号里没绑车」这个结论。 -->
        <van-cell title="车牌" :value="order.plateNo || '-'" />
        <van-cell title="创建时间" :value="formatCn(order.createTime) ?? '-'" />
        <van-cell v-if="order.startTime" title="开始充电" :value="formatCn(order.startTime) ?? '-'" />
        <van-cell v-if="order.endTime" title="结束充电" :value="formatCn(order.endTime) ?? '-'" />
        <van-cell
          v-if="order.durationSeconds"
          title="充电时长"
          :value="formatDuration(order.durationSeconds * 1000)"
        />
        <van-cell v-if="meterText" title="电表读数" :value="meterText" />
        <van-cell
          v-if="order.stopReasonLabel"
          title="结束原因"
          :value="order.stopReasonLabel"
        />
        <!-- ★ 支付方式在建单时就定了，这里只是**只读展示**，不是选择器 -->
        <van-cell title="支付方式" :value="order.payMethodLabel ?? '-'" />
        <van-cell v-if="order.settleTime" title="结算时间" :value="formatCn(order.settleTime) ?? '-'" />
        <van-cell v-if="order.payTime" title="支付时间" :value="formatCn(order.payTime) ?? '-'" />
        <van-cell v-if="order.refundTime" title="退款时间" :value="formatCn(order.refundTime) ?? '-'" />
      </van-cell-group>
    </van-pull-refresh>

    <!-- 底部固定操作栏。★ 没有任何可执行动作时**整条 bar 不渲染**，
         不留一个灰按钮在那里被反复戳。 -->
    <div v-if="order && hasBarAction" class="action-bar">
      <van-button
        v-if="canCancel"
        class="bar-btn"
        round
        plain
        type="danger"
        :loading="cancelling"
        @click="doCancel"
      >
        取消订单
      </van-button>
      <van-button v-if="canRecharge" class="bar-btn" round plain type="primary" @click="gotoRecharge">
        去充值
      </van-button>
      <van-button
        v-if="canPay"
        class="bar-btn"
        round
        type="primary"
        :loading="paying"
        @click="doPay"
      >
        {{ payLabel }}
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.detail {
  min-height: 100vh;
  background: var(--co-bg-page);
}

/* 底部 bar 是 fixed 的，内容要自己让出它的高度 + iPhone 的小黑条 */
.detail.with-bar {
  padding-bottom: calc(74px + env(safe-area-inset-bottom));
}

.nav-action {
  font-size: 13px;
  color: var(--co-primary);
  /* ★ 触摸反馈走 `:active`：H5 上没有 hover，只写 hover 等于没有反馈 */
  transition: opacity var(--co-duration-fast) var(--co-ease);
}

.nav-action:active {
  opacity: 0.55;
}

/* ── ① 状态大卡 ─────────────────────────────────────────────────────── */

.hero {
  padding: 16px 14px;
  border-radius: 0 0 var(--co-radius-lg) var(--co-radius-lg);
}

.hero-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tag-chip {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
}

.hero-no {
  font-size: 12px;
  color: var(--co-text-2);
  -webkit-user-select: text;
  user-select: text;
}

.hero-advice {
  margin: 10px 0 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
}

.hero-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-text-2);
}

.cd {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  /* ★ 倒计时块用半透明白铺一层，而不是再挑一个颜色 ——
     大卡的底色已经由状态 tone 决定了，这里的角色只是「一块更清楚的信息」。 */
  background: rgba(255, 255, 255, 0.72);
}

.cd-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.cd-label {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.cd-value {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.cd-note {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-2);
}

/* ── 面板通用 ───────────────────────────────────────────────────────── */

.panel {
  margin: 10px 12px 0;
  padding: 14px;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--co-text-1);
}

.panel-sub {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.panel-note {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

/* ── ② 费用明细 ─────────────────────────────────────────────────────── */

.kpi {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 10px;
  margin-top: 12px;
}

.kpi-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.kpi-label {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.kpi-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.kpi-hint {
  font-size: 11px;
  line-height: 1.5;
  color: var(--co-text-4);
}

.fee-rows {
  margin: 14px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--co-border-light);
}

.fee-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 3px 0;
}

.fee-row dt {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.fee-row dd {
  margin: 0;
}

/* ── ③ 分段计费 ─────────────────────────────────────────────────────── */

.bar {
  display: flex;
  height: 10px;
  margin-top: 12px;
  border-radius: 999px;
  overflow: hidden;
  /* ★ 给条一个底色：某段宽度接近 0（比如只有几秒）时，那一格的色块几乎看不见，
     有底色至少不会让整条看起来断掉。 */
  background: var(--co-bg-3);
}

.bar-block {
  height: 100%;
}

.dev-warn {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: var(--co-radius-sm);
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
  font-size: 11.5px;
  line-height: 1.6;
}

.segments {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.segment {
  padding: 10px 0;
  border-top: 1px solid var(--co-border-light);
}

.segment:first-child {
  border-top: none;
  padding-top: 0;
}

.seg-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seg-chip {
  flex: none;
  padding: 1px 6px;
  border-radius: var(--co-radius-sm);
  font-size: 11.5px;
  line-height: 1.6;
}

.seg-range {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.seg-duration {
  margin-left: auto;
  font-size: 12px;
  color: var(--co-text-3);
}

.seg-body {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
}

.seg-detail {
  font-size: 12px;
  color: var(--co-text-3);
}

.seg-detail i {
  font-style: normal;
  color: var(--co-text-2);
}

/* ── ④ 事件时间线 ───────────────────────────────────────────────────── */

.timeline {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.tl-item {
  position: relative;
  display: flex;
  gap: 10px;
  padding-bottom: 14px;
}

.tl-item:last-child {
  padding-bottom: 0;
}

/* 竖线：从圆点往下连到下一条。最后一条不画，否则时间轴会垂出去一截 */
.tl-item::before {
  content: '';
  position: absolute;
  left: 3.5px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: var(--co-border);
}

.tl-item:last-child::before {
  display: none;
}

.tl-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  /* ★ 用一圈卡片底色的外阴影把竖线「切断」，比给线写 z-index 稳 */
  box-shadow: 0 0 0 2px var(--co-bg-card);
}

.tl-body {
  min-width: 0;
  flex: 1;
}

.tl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.tl-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.tl-time {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.tl-chip {
  display: inline-block;
  margin-top: 4px;
  padding: 1px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-size: 11.5px;
  line-height: 1.6;
}

.tl-desc {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-text-2);
  word-break: break-all;
}

.tl-source {
  margin: 3px 0 0;
  font-size: 11px;
  color: var(--co-text-4);
}

/* ★ 「重复上报已忽略」这类事件表示**什么都没发生**，它的存在反而是好事。
   降饱和让它在时间轴上后退，而不是和真正的状态流转抢注意力。 */
.noop {
  opacity: 0.62;
}

/* ── ⑤ 订单信息 ─────────────────────────────────────────────────────── */

.info {
  margin-top: 10px;
  margin-bottom: 12px;
}

/* ── 底部操作栏 ─────────────────────────────────────────────────────── */

.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom));
  background: var(--co-bg-card);
  border-top: 1px solid var(--co-border-light);
  box-shadow: var(--co-shadow-md);
}

.bar-btn {
  flex: 1;
}
</style>
