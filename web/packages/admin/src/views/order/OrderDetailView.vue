<script setup lang="ts">
/**
 * 订单详情。
 *
 * ★★ 这个页面存在的**主要理由不是「好看」，是「查账」** ★★
 *
 *   列表页只能回答「这单现在是什么状态」，而这个页面要回答三个问题：
 *
 *     1. **为什么变成这样？** —— 答案只在 `events[].detail` 里。
 *        尤其是 `9 异常挂账`：后端把原因写进了那一条事件的 `detail`，
 *        别的地方一个字都没有。所以事件时间轴不是装饰，**它是核心功能**。
 *     2. **钱是怎么算出来的？** —— `segments[]` 是逐时段拆开的电费/服务费，
 *        而后端保证 `Σ segments.amount == totalAmount`（只在最后取整一次）。
 *        分段表和总额对不上，说明数据有问题（开发期会直接断言）。
 *     3. **钱现在在哪？** —— `frozenAmount`（冻结中）/ `payAmount`（已扣）/
 *        `refundAmount`（已退）是三个不同的位置，混起来看就会以为
 *        「钱扣了两次」或者「退款了怎么余额没变」。
 *
 * ★★ 两个容易误判的地方，页面上都写明了 ★★
 *   · `abnormal` 事件有**两种含义**（见 `describeOrderEvent`）：
 *     `toStatus === 6` 是「余额不足」不是设备异常。文案方向完全相反。
 *   · 状态 4 待支付**会被定时任务扫成 6**（15 分钟窗口，5 分钟一轮）。
 *     所以这里给倒计时，并且写「预计」而不是「已超时」——
 *     到点不等于立刻变，最长可能晚 5 分钟。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ORDER_STATUS,
  PERIOD_TYPE,
  canDo,
  describeOrderEvent,
  durationBetween,
  formatCountdown,
  formatDuration,
  formatPrice,
  formatRelative,
  formatServerTime,
  invalidateOptionsByTag,
  isNoopEvent,
  pendingPayRemainMs,
  periodColor,
  periodWeakColor,
  segmentsMatchTotal,
  transitionText,
  whyNot,
} from '@chargeops/shared'
import type { AdminOrderDetailVO, ChargingSegmentVO, OrderAction } from '@chargeops/shared'
import { getOrder, settleOrder } from '@/api/order'
import { useSubmitLock } from '@/composables/useSubmitLock'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import MoneyText from '@/components/MoneyText.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import CoIcon from '@/components/CoIcon.vue'
import StartOrderDialog from './StartOrderDialog.vue'
import EndOrderDialog from './EndOrderDialog.vue'
import RefundOrderDialog from './RefundOrderDialog.vue'

const route = useRoute()
const router = useRouter()

const orderId = computed(() => String(route.params.id ?? ''))

const order = ref<AdminOrderDetailVO | null>(null)
const loading = ref(false)
const loadError = ref(false)

/** 竞态保护：快速切订单时，先发的旧响应不能覆盖后发的。 */
let seq = 0

async function load(): Promise<void> {
  const my = ++seq
  loading.value = true
  loadError.value = false
  try {
    const data = await getOrder(orderId.value)
    if (my !== seq) return
    order.value = data
  } catch {
    if (my !== seq) return
    loadError.value = true
    order.value = null
  } finally {
    if (my === seq) loading.value = false
  }
}

watch(orderId, () => void load(), { immediate: true })

/* ── 待支付倒计时（事实 4）────────────────────────────────────────────── */

/**
 * ★ 只有状态 4 需要倒计时，所以定时器也只在状态 4 时开着 ——
 *   一个每 30 秒跳一次的组件挂在整个应用里是纯浪费。
 */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

watch(
  () => order.value?.status,
  (status) => {
    stopTicker()
    if (status === ORDER_STATUS.PENDING_PAY) {
      now.value = Date.now()
      ticker = setInterval(() => {
        now.value = Date.now()
      }, 1000)
    }
  },
)

onBeforeUnmount(stopTicker)

const payRemainMs = computed(() =>
  order.value?.status === ORDER_STATUS.PENDING_PAY
    ? pendingPayRemainMs(order.value.createTime, now.value)
    : null,
)

/* ── 动作 ─────────────────────────────────────────────────────────────── */

const startOpen = ref(false)
const endOpen = ref(false)
const refundOpen = ref(false)

const { pending: settling, run: runSettle } = useSubmitLock({ throttleMs: 500 })

/**
 * ★ 结算失败**不是死路**：后端在电价规则有缺口时抛异常并整体回滚，
 *   订单留在 3 可以重试。所以提示要指向真正的原因，并且给重试的意思，
 *   而不是引导用户去挂账（挂账是没有 HTTP 端点的，只能由 end/settle 内部转）。
 */
async function doSettle(): Promise<void> {
  const outcome = await runSettle(() => settleOrder(orderId.value))
  if (outcome.status !== 'done') return
  ElMessage({ type: 'success', message: '结算完成，订单已进入待支付' })
  await load()
}

function onActionDone(message: string): void {
  startOpen.value = false
  endOpen.value = false
  refundOpen.value = false
  ElMessage({ type: 'success', message })
  invalidateOptionsByTag('order')
  void load()
}

/** 管理端能做的动作。与列表页同一套规则（`markAbnormal` 没有端点，不放进来）。 */
const ACTIONS: readonly { action: OrderAction; label: string; run: () => void }[] = [
  { action: 'start', label: '启动充电', run: () => (startOpen.value = true) },
  { action: 'end', label: '结束充电', run: () => (endOpen.value = true) },
  { action: 'settle', label: '结算', run: () => void doSettle() },
  { action: 'refund', label: '退款', run: () => (refundOpen.value = true) },
]

/* ── 展示派生 ─────────────────────────────────────────────────────────── */

const status = computed(() => order.value?.status ?? null)

/** 充电时长：优先用后端的 `durationSeconds`，缺了才用起止时间现算。 */
const durationText = computed(() => {
  const o = order.value
  if (!o) return '-'
  if (typeof o.durationSeconds === 'number' && o.durationSeconds > 0) {
    return formatDuration(o.durationSeconds * 1000)
  }
  return formatDuration(durationBetween(o.startTime, o.endTime)) ?? '-'
})

const segments = computed<ChargingSegmentVO[]>(() => order.value?.segments ?? [])
const events = computed(() => order.value?.events ?? [])

/**
 * ★ 分段占比条：宽度按**各段时长占全部段时长的比例**，不是 00:00→24:00 的时间轴。
 *   刻意复用 `segmentWidthPercent`（那是给「一天 24 小时」的横轴用的）会画错 ——
 *   这个条描述的是「这一次充电由哪些时段构成」，不是「一天里什么时候充的」。
 *   所以标题也要写成「时长占比」，不能让人读成时刻表。
 */
const segmentShares = computed(() => {
  const total = segments.value.reduce((sum, s) => sum + (s.durationSeconds ?? 0), 0)
  if (total <= 0) {
    // 所有段时长都是 0 时等分，而不是渲染成 0 宽（那看起来像「没有分段」）
    const n = segments.value.length || 1
    return segments.value.map((s) => ({ seg: s, percent: 100 / n }))
  }
  return segments.value.map((s) => ({
    seg: s,
    percent: ((s.durationSeconds ?? 0) / total) * 100,
  }))
})

/** 只列出这一单出现过的时段类型作为图例 —— 列出全部 4 种会让人以为缺了数据。 */
const legendPeriods = computed(() => {
  const seen = new Set<number>()
  segments.value.forEach((s) => seen.add(s.periodType))
  return PERIOD_TYPE.list.filter((p) => seen.has(p.code))
})

/**
 * ★ `Σ分段 == 总额` 是后端的承诺，不等就是数据有问题。
 *   只在开发期断言（`import.meta.env.DEV`）：生产环境这是纯计算开销，
 *   而且真出问题时开发期就应该已经炸出来了。见 `segmentsMatchTotal` 的注释。
 */
const segmentsMismatch = computed(() => {
  if (!import.meta.env.DEV) return false
  const o = order.value
  if (!o || segments.value.length === 0 || o.totalAmount === null) return false
  return !segmentsMatchTotal(
    segments.value.map((s) => s.amount),
    o.totalAmount,
  )
})

/** 金额区：把「冻结 / 已扣 / 已退」三个位置分开列，不要合成一个「金额」。 */
const money = computed(() => {
  const o = order.value
  return {
    total: o?.totalAmount ?? null,
    discount: o?.discountAmount ?? null,
    pay: o?.payAmount ?? null,
    frozen: o?.frozenAmount ?? null,
    refund: o?.refundAmount ?? null,
    energy: o?.energyFee ?? null,
    service: o?.serviceFee ?? null,
    hasDiscount: o?.discountAmount != null && Number(o.discountAmount) > 0,
  }
})

const isAbnormal = computed(() => status.value === ORDER_STATUS.ABNORMAL)
const isPendingPay = computed(() => status.value === ORDER_STATUS.PENDING_PAY)

function copyOrderNo(): void {
  const no = order.value?.orderNo
  if (!no) return
  void navigator.clipboard?.writeText(no).then(
    () => ElMessage({ type: 'success', message: '订单号已复制' }),
    () => ElMessage({ type: 'warning', message: '浏览器不允许复制，请手动选中' }),
  )
}
</script>

<template>
  <div v-loading="loading" class="page">
    <!-- ★ 返回按钮 + 订单号 + 状态 + 动作，全部收在一条头里：
         查账时通常是从列表点进来的，随时要能退回继续看下一单。 -->
    <PagePanel>
      <template #actions>
        <div class="head-actions">
          <el-button size="small" @click="router.push('/order')">
            <CoIcon name="chevron-left" :size="13" />
            返回列表
          </el-button>
          <el-button size="small" :loading="loading" @click="load">
            <CoIcon name="refresh" :size="13" />
            刷新
          </el-button>

          <!-- ★ 四个动作的可用性走 canDo / whyNot，不写 `status === N`。
               禁用时用 tooltip 说明原因，比一个灰掉的按钮有解释力。 -->
          <el-tooltip
            v-for="act in ACTIONS"
            :key="act.action"
            :content="whyNot(act.action, status)"
            :disabled="canDo(act.action, status, 'admin')"
            placement="bottom"
          >
            <el-button
              size="small"
              :type="act.action === 'refund' ? 'danger' : 'primary'"
              :plain="act.action === 'refund'"
              :loading="act.action === 'settle' && settling"
              :disabled="!canDo(act.action, status, 'admin')"
              @click="act.run()"
            >
              {{ act.label }}
            </el-button>
          </el-tooltip>
        </div>
      </template>

      <div v-if="order" class="head">
        <div class="head-main">
          <button class="order-no" type="button" title="点击复制" @click="copyOrderNo">
            {{ order.orderNo }}
          </button>
          <OrderStatusTag :status="order.status" />
        </div>
        <div class="head-meta">
          <span>{{ order.stationName || '未知站点' }}</span>
          <i />
          <span>{{ order.memberPhone || '未知车主' }}</span>
          <i />
          <span :title="formatServerTime(order.createTime) ?? ''">
            建单 {{ formatRelative(order.createTime) }}
          </span>
        </div>
      </div>
    </PagePanel>

    <EmptyState
      v-if="!order && !loading"
      :kind="loadError ? 'error' : 'empty'"
      :title="loadError ? '订单加载失败' : '订单不存在'"
      :description="
        loadError
          ? '可能网络不通或该订单已被删除，重试一次看看。'
          : '这个订单号没有对应的记录，可能是链接过期了。'
      "
      @retry="load"
    />

    <template v-if="order">
      <!-- ★ 待支付倒计时：不是"锦上添花的动效"，是告诉运维
           「这单还有几分钟会被定时任务自动扫成支付失败」。 -->
      <div v-if="isPendingPay" class="countdown">
        <CoIcon name="clock" :size="14" />
        <span>
          待支付窗口还剩
          <b class="co-num">{{ formatCountdown(payRemainMs) }}</b>
        </span>
        <span class="countdown-note">
          超时后会被定时任务扫成「支付失败」（5 分钟一轮，所以是<b>预计</b>，到点不一定立刻变）
        </span>
      </div>

      <!-- ★ 异常挂账：原因只在下方的「事件时间轴」里，所以这里要明确指路，
           而不是只标一个红标签就完事。 -->
      <div v-if="isAbnormal" class="alert is-danger">
        <CoIcon name="warning" :size="15" />
        <div>
          <b>这笔订单是异常挂账，不会自动重试。</b>
          <p>
            「为什么变成这样」的答案只在下面的<b>事件时间轴</b>里 ——
            后端把原因写进了那一条事件的「说明」里。请展开查看后再决定怎么处理。
          </p>
        </div>
      </div>

      <!-- ── 交易概览 ───────────────────────────────────────────────── -->
      <PagePanel title="交易概览" subtitle="金额分「冻结 / 已扣 / 已退」三个位置，不要混着看">
        <div class="kpis">
          <div class="kpi">
            <span class="kpi-label">总金额</span>
            <MoneyText :value="money.total" size="lg" strong />
            <span class="kpi-foot">
              <template v-if="money.hasDiscount">
                已优惠 <MoneyText :value="money.discount" size="sm" signed />
              </template>
              <template v-else>无优惠</template>
            </span>
          </div>

          <div class="kpi">
            <span class="kpi-label">已扣款</span>
            <MoneyText :value="money.pay" size="lg" strong />
            <span class="kpi-foot">
              {{ order.payTime ? formatServerTime(order.payTime) : '尚未支付' }}
              <template v-if="order.payMethodLabel"> · {{ order.payMethodLabel }}</template>
            </span>
          </div>

          <div class="kpi">
            <span class="kpi-label">冻结中</span>
            <MoneyText :value="money.frozen" size="lg" :strong="Number(money.frozen ?? 0) > 0" />
            <span class="kpi-foot">
              {{
                Number(money.frozen ?? 0) > 0
                  ? '订单终结前一直占着车主余额'
                  : '没有占用余额'
              }}
            </span>
          </div>

          <div class="kpi">
            <span class="kpi-label">已退款</span>
            <MoneyText :value="money.refund" size="lg" :strong="Number(money.refund ?? 0) > 0" />
            <span class="kpi-foot">
              {{ order.refundTime ? formatServerTime(order.refundTime) : '未退款' }}
            </span>
          </div>

          <div class="kpi">
            <span class="kpi-label">电量</span>
            <span class="kpi-num">{{ order.totalEnergyKwh ?? '--' }}<i>kWh</i></span>
            <span class="kpi-foot">充电时长 {{ durationText }}</span>
          </div>
        </div>

        <div class="grid">
          <div class="cell">
            <span class="cell-label">充电站</span>
            <span class="cell-value">{{ order.stationName || '-' }}</span>
          </div>
          <div class="cell">
            <span class="cell-label">充电桩 / 枪</span>
            <span class="cell-value co-num">#{{ order.chargerId }} / #{{ order.connectorId }}</span>
          </div>
          <div class="cell">
            <span class="cell-label">车主</span>
            <span class="cell-value">
              {{ order.memberPhone || '-' }}
              <em v-if="order.memberNickname">{{ order.memberNickname }}</em>
            </span>
          </div>
          <div class="cell">
            <span class="cell-label">车牌</span>
            <span class="cell-value">{{ order.plateNo || '-' }}</span>
          </div>
          <div class="cell">
            <span class="cell-label">电表读数</span>
            <span class="cell-value co-num">
              {{ order.startMeterKwh ?? '--' }} → {{ order.endMeterKwh ?? '--' }}
            </span>
          </div>
          <div class="cell">
            <span class="cell-label">SOC</span>
            <span class="cell-value co-num">
              {{ order.startSoc ?? '--' }}% → {{ order.endSoc ?? '--' }}%
            </span>
          </div>
          <div class="cell">
            <span class="cell-label">停止原因</span>
            <span class="cell-value">
              {{ order.stopReasonLabel || '-' }}
              <!-- ★ NULL 有信息量：只有「充电真的开始过」的订单才有停止原因 -->
              <em v-if="order.stopReason == null">未开始过充电</em>
            </span>
          </div>
          <div class="cell">
            <span class="cell-label">计费模板</span>
            <span class="cell-value co-num">
              {{ order.tariffTemplateId ? `#${order.tariffTemplateId}` : '未绑定' }}
            </span>
          </div>
          <div class="cell">
            <span class="cell-label">权益</span>
            <span class="cell-value co-num">
              {{ order.memberPackageId ? `#${order.memberPackageId}` : '未使用' }}
            </span>
          </div>
        </div>
      </PagePanel>

      <!-- ── 时间线 ─────────────────────────────────────────────────── -->
      <PagePanel title="关键节点" subtitle="同一格的上下两行分别是「本地时间」与「服务器原始值」">
        <ol class="stages">
          <li :class="{ done: !!order.createTime }">
            <span class="dot" />
            <div>
              <b>建单</b>
              <span class="co-num">{{ formatServerTime(order.createTime) || '-' }}</span>
            </div>
          </li>
          <li :class="{ done: !!order.startTime }">
            <span class="dot" />
            <div>
              <b>启动充电</b>
              <span class="co-num">{{ formatServerTime(order.startTime) || '未开始' }}</span>
            </div>
          </li>
          <li :class="{ done: !!order.endTime }">
            <span class="dot" />
            <div>
              <b>结束充电</b>
              <span class="co-num">{{ formatServerTime(order.endTime) || '未结束' }}</span>
            </div>
          </li>
          <li :class="{ done: !!order.settleTime }">
            <span class="dot" />
            <div>
              <b>结算</b>
              <span class="co-num">{{ formatServerTime(order.settleTime) || '未结算' }}</span>
            </div>
          </li>
          <li :class="{ done: !!order.payTime }">
            <span class="dot" />
            <div>
              <b>支付</b>
              <span class="co-num">{{ formatServerTime(order.payTime) || '未支付' }}</span>
            </div>
          </li>
          <li :class="{ done: !!order.refundTime }">
            <span class="dot" />
            <div>
              <b>退款</b>
              <span class="co-num">{{ formatServerTime(order.refundTime) || '未退款' }}</span>
            </div>
          </li>
        </ol>
      </PagePanel>

      <!-- ── 分段明细 ───────────────────────────────────────────────── -->
      <PagePanel
        title="分段计费明细"
        subtitle="每段按该时段的电价单独算，最后一次性取整 —— 所以各段相加必然等于总额"
        flush
      >
        <template #actions>
          <span v-if="legendPeriods.length" class="legend">
            <span v-for="p in legendPeriods" :key="p.code" class="legend-item">
              <i :style="{ background: periodColor(p.code) }" />
              {{ p.label }}
            </span>
          </span>
        </template>

        <div v-if="segments.length === 0" class="pad">
          <EmptyState
            kind="empty"
            title="还没有分段"
            description="分段在结算时生成。订单走到「待支付」之后才会有这一块内容。"
          />
        </div>

        <template v-else>
          <!-- 占比条：标题写明是「时长占比」，避免被读成时刻表 -->
          <div class="bar-wrap">
            <div class="bar">
              <div
                v-for="item in segmentShares"
                :key="item.seg.seq"
                class="bar-cell"
                :style="{
                  width: `${item.percent}%`,
                  background: periodWeakColor(item.seg.periodType),
                  borderTop: `2px solid ${periodColor(item.seg.periodType)}`,
                }"
                :title="`${item.seg.periodTypeLabel} ${item.seg.durationSeconds}s`"
              />
            </div>
            <span class="bar-note">各段时长占比</span>
          </div>

          <p v-if="segmentsMismatch" class="mismatch">
            开发期断言失败：各段金额相加 ≠ 订单总额。后端承诺这个等式恒成立，
            对不上说明数据有问题（或 mock 造错了），金额不要以本表为准。
          </p>

          <el-table :data="segments" row-key="seq" size="small" stripe>
            <el-table-column label="#" width="52" align="center">
              <template #default="{ row }">
                <span class="co-num muted">{{ row.seq }}</span>
              </template>
            </el-table-column>

            <el-table-column label="时段" width="92">
              <template #default="{ row }">
                <span class="period">
                  <i :style="{ background: periodColor(row.periodType) }" />
                  {{ row.periodTypeLabel || PERIOD_TYPE.label(row.periodType) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="起止" min-width="200">
              <template #default="{ row }">
                <span class="co-num range">
                  {{ formatServerTime(row.startTime) }} → {{ formatServerTime(row.endTime) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="时长" width="90" align="right">
              <template #default="{ row }">
                <span class="co-num">{{ formatDuration(row.durationSeconds * 1000) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="电量" width="96" align="right">
              <template #default="{ row }">
                <span class="co-num">{{ row.energyKwh }}<i class="unit"> kWh</i></span>
              </template>
            </el-table-column>

            <el-table-column label="电费单价" width="106" align="right">
              <template #default="{ row }">
                <span class="co-num muted">{{ formatPrice(row.energyPrice) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="服务费单价" width="116" align="right">
              <template #default="{ row }">
                <span class="co-num muted">{{ formatPrice(row.servicePrice) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="电费" width="100" align="right">
              <template #default="{ row }">
                <MoneyText :value="row.energyFee" size="sm" :grouping="false" />
              </template>
            </el-table-column>

            <el-table-column label="服务费" width="100" align="right">
              <template #default="{ row }">
                <MoneyText :value="row.serviceFee" size="sm" :grouping="false" />
              </template>
            </el-table-column>

            <el-table-column label="小计" width="106" align="right">
              <template #default="{ row }">
                <MoneyText :value="row.amount" size="sm" :grouping="false" strong />
              </template>
            </el-table-column>
          </el-table>
        </template>
      </PagePanel>

      <!-- ── 事件时间轴 ★ 本页的核心 ─────────────────────────────────── -->
      <PagePanel
        title="事件时间轴"
        subtitle="排查「这笔订单为什么变成这样」的唯一依据 —— 后端把原因写在每条事件的说明里"
      >
        <div v-if="events.length === 0" class="pad">
          <EmptyState kind="empty" title="没有事件记录" />
        </div>

        <ol v-else class="timeline">
          <li
            v-for="(ev, i) in events"
            :key="`${ev.createTime}-${i}`"
            :class="[`tone-${describeOrderEvent(ev).tone}`, { noop: isNoopEvent(ev.eventType) }]"
          >
            <span class="tl-dot" />
            <div class="tl-body">
              <div class="tl-head">
                <b>{{ describeOrderEvent(ev).title }}</b>
                <!-- ★ fromStatus/toStatus 后端不给 label，这里用 orderStatusLabel 补 -->
                <span v-if="transitionText(ev.fromStatus, ev.toStatus)" class="tl-transition">
                  {{ transitionText(ev.fromStatus, ev.toStatus) }}
                </span>
                <span class="tl-time co-num" :title="formatServerTime(ev.createTime) ?? ''">
                  {{ formatServerTime(ev.createTime) }}
                </span>
              </div>

              <!-- ★ 后端写过 detail 就显示 detail —— 它是那一刻的原始原因，
                   我们猜的短语只配在 detail 为空时兜底。 -->
              <p class="tl-desc">{{ describeOrderEvent(ev).description }}</p>

              <div class="tl-foot">
                <span class="tl-source">{{ ev.sourceLabel }}</span>
                <span class="tl-type co-num">{{ ev.eventType }}</span>
              </div>
            </div>
          </li>
        </ol>
      </PagePanel>
    </template>

    <StartOrderDialog
      v-if="startOpen && order"
      :order="order"
      @close="startOpen = false"
      @done="onActionDone($event)"
    />
    <EndOrderDialog
      v-if="endOpen && order"
      :order="order"
      @close="endOpen = false"
      @done="onActionDone($event)"
    />
    <RefundOrderDialog
      v-if="refundOpen && order"
      :order="order"
      @close="refundOpen = false"
      @done="onActionDone($event)"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pad {
  padding: 36px 0;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.head-main {
  display: flex;
  align-items: center;
  gap: 10px;
}

.order-no {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-text-1);
  font-family: inherit;
  font-size: 17px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.2px;
  cursor: pointer;
}

.order-no:hover {
  color: var(--co-primary);
}

.head-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--co-text-3);
  font-size: 12.5px;
}

.head-meta i {
  width: 1px;
  height: 10px;
  background: var(--co-border);
}

/* ── 提示条 ───────────────────────────────────────────────────────────── */

.countdown {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--co-warning-weak);
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
  font-size: 12.5px;
}

.countdown b {
  font-weight: 600;
}

.countdown-note {
  margin-left: auto;
  color: var(--co-text-3);
  font-size: 12px;
}

.alert {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--co-radius-md);
  font-size: 12.5px;
  line-height: 1.65;
}

.alert.is-danger {
  border: 1px solid var(--co-danger-weak);
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
}

.alert b {
  font-weight: 600;
}

.alert p {
  margin: 4px 0 0;
  color: var(--co-text-2);
}

/* ── KPI ──────────────────────────────────────────────────────────────── */

.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(148px, 1fr));
  gap: 1px;
  margin: -18px -18px 0;
  background: var(--co-border-light);
  border-bottom: 1px solid var(--co-border-light);
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 16px 18px;
  background: var(--co-bg-card);
}

.kpi-label {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.kpi-foot {
  font-size: 11.5px;
  color: var(--co-text-4);
  line-height: 1.5;
}

.kpi-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--co-text-1);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.kpi-num i {
  margin-left: 3px;
  font-size: 11.5px;
  font-style: normal;
  font-weight: 400;
  color: var(--co-text-3);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 14px 24px;
  margin-top: 18px;
}

.cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.cell-label {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.cell-value {
  font-size: 13px;
  color: var(--co-text-1);
  overflow-wrap: anywhere;
}

.cell-value em {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--co-bg-3);
  color: var(--co-text-3);
  font-size: 11px;
  font-style: normal;
}

/* ── 关键节点 ─────────────────────────────────────────────────────────── */

.stages {
  display: flex;
  margin: 0;
  padding: 0;
  list-style: none;
}

.stages li {
  position: relative;
  flex: 1;
  display: flex;
  gap: 9px;
  padding-top: 2px;
}

/* 连接线：铺在圆点那一行上，最后一个不画 */
.stages li::before {
  content: '';
  position: absolute;
  top: 6px;
  left: 4px;
  right: -8px;
  height: 1px;
  background: var(--co-border);
}

.stages li:last-child::before {
  display: none;
}

.stages li.done::before {
  background: var(--co-primary-weak);
}

.dot {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  margin-top: 2px;
  border: 2px solid var(--co-border);
  border-radius: 50%;
  background: var(--co-bg-card);
}

.stages li.done .dot {
  border-color: var(--co-primary);
  background: var(--co-primary);
}

.stages li > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.stages b {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--co-text-3);
}

.stages li.done b {
  color: var(--co-text-1);
  font-weight: 600;
}

.stages span {
  font-size: 11.5px;
  color: var(--co-text-4);
}

.stages li.done span {
  color: var(--co-text-2);
}

/* ── 分段 ─────────────────────────────────────────────────────────────── */

.legend {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-item i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.bar-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px 0;
}

.bar {
  display: flex;
  flex: 1;
  height: 14px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--co-bg-3);
}

.bar-cell {
  min-width: 2px;
}

.bar-note {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--co-text-4);
}

.mismatch {
  margin: 12px 18px 0;
  padding: 9px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
  font-size: 12px;
  line-height: 1.65;
}

.period {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}

.period i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
}

.co-num {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--co-text-3);
}

.range {
  font-size: 12px;
  color: var(--co-text-2);
}

.unit {
  margin-left: 2px;
  font-size: 11px;
  font-style: normal;
  color: var(--co-text-3);
}

/* ── 事件时间轴 ───────────────────────────────────────────────────────── */

.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline li {
  position: relative;
  display: flex;
  gap: 12px;
  padding-bottom: 18px;
}

/* 竖线：最后一条不画，避免时间轴下面拖一条断掉的线 */
.timeline li::before {
  content: '';
  position: absolute;
  top: 14px;
  bottom: 0;
  left: 4px;
  width: 1px;
  background: var(--co-border-light);
}

.timeline li:last-child {
  padding-bottom: 0;
}

.timeline li:last-child::before {
  display: none;
}

.tl-dot {
  position: relative;
  z-index: 1;
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--co-text-4);
}

.tone-primary .tl-dot { background: var(--co-primary); }
.tone-success .tl-dot { background: var(--co-success); }
.tone-warning .tl-dot { background: var(--co-warning); }
.tone-danger .tl-dot { background: var(--co-danger); }
.tone-accent .tl-dot { background: var(--co-accent); }
.tone-info .tl-dot { background: var(--co-text-4); }

/* ★ 「重复上报已忽略」是「什么都没发生」的记录，视觉上要最弱 ——
     它存在本身是好事（说明后端挡住了重复上报），不该抢注意力。 */
.timeline li.noop .tl-dot {
  width: 7px;
  height: 7px;
  margin-top: 5px;
  margin-left: 1px;
  background: var(--co-border);
}

.timeline li.noop .tl-body {
  opacity: 0.72;
}

.tl-body {
  flex: 1;
  min-width: 0;
}

.tl-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.tl-head b {
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.tl-transition {
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-size: 11px;
}

.tl-time {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--co-text-4);
}

.tl-desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--co-text-2);
  overflow-wrap: anywhere;
}

.tl-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  font-size: 11px;
  color: var(--co-text-4);
}

.tl-type {
  padding: 0 5px;
  border-radius: 3px;
  background: var(--co-bg-3);
}
</style>
