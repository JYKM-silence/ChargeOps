<script setup lang="ts">
/**
 * 工作台。
 *
 * ★★ 这一页是**降级实现**，而且它必须自己承认这一点 ★★
 *
 *   后端 **52 个管理端端点里没有任何统计接口** —— 没有 `/statistics`、
 *   没有 `/overview`、没有 `/summary`。所以这里的每一个数字都是前端拼出来的，
 *   而拼法有两种，**可信度完全不同**，卡片上必须各自标清楚：
 *
 *     1. **精确值**：列表接口 `pageSize=1`，取响应里的 `total`。
 *        那是后端 `COUNT(*)` 的结果。适用于：站点/充电桩/充电枪/车主总数，
 *        以及近 7 天的订单总数和**各状态的单数**（每个状态一次查询）。
 *     2. **样本聚合**：营收与电量只能把订单翻回来在客户端加，上限
 *        `DASHBOARD_SAMPLE_LIMIT`（500 单 = 5 页 × 100）。
 *        它是**有偏样本**（偏「近 7 天里最新的 500 单」），
 *        所以那两张卡上**常驻**一句「近 7 天 · 样本 N 单 · 仅供参考」。
 *        ★ 不要把它渲染成「总营收 ¥x」这种没有口径的说法 ——
 *          一个看起来精确的假数字比不做更糟，因为它会被人拿去对账。
 *
 *   样本自己还有三种「不完整」，措辞**不能合并**（见 `sampleCaption`）：
 *   触到 500 单上限（实际值**确定**更高）、分页失败（**不知道**差多少）、
 *   已经拿全（那就不只是样本，是全量）。
 *
 * ★★ 18 个请求，所以两件事必须在代码里看得见 ★★
 *   · **并发上限**：全部走 `DASHBOARD_CONCURRENCY`（= 4）。全并发会把后端连接池
 *     瞬间打满，而工作台是首屏 —— 它把库拖慢等于整个后台都慢。
 *   · **失败隔离**：任何一张卡片失败只影响它自己，页面上给出该卡片自己的错误与重试。
 *     一个状态查询挂了不该让整页白屏。
 */

import { computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import {
  DASHBOARD_CONCURRENCY,
  DASHBOARD_SAMPLE_LIMIT,
  ORDER_STATUS_LIST,
  TONE_VAR,
  defaultOrderRange,
  formatMoney,
  formatRelative,
  formatServerTime,
  type AdminOrderVO,
  type Tone,
} from '@chargeops/shared'
import {
  aggregateSample,
  fetchOrderSample,
  fetchTotal,
  type CountKey,
  type OrderSample,
  type OrderStats,
} from '@/api/dashboard'
import { pageOrders } from '@/api/order'
import type { IconName } from '@/components/icons'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import MoneyText from '@/components/MoneyText.vue'
import CoIcon from '@/components/CoIcon.vue'

const router = useRouter()

/**
 * 近 7 天。**每次进页面重算一次**（而不是模块级常量）：
 * 标签页挂过夜之后，范围应该是「现在往前 7 天」，不是「昨天往前 7 天」。
 * 这个区间会同时用于：订单总数、9 个状态单数、订单采样。
 */
const range = defaultOrderRange()

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

/* ── 受限并发 ─────────────────────────────────────────────────────────── */

/**
 * 受限并发地跑一批任务。
 *
 * ★ `@/api/dashboard` 里有一个功能相同的私有实现（`mapLimit`），但**没有导出**，
 *   而它的返回形态（settled 数组）是给翻页用的。这里要的是「每个任务自己写自己的
 *   卡片状态」，不需要返回值，所以另写一个 15 行的 —— 不改那个文件（它不属于本次范围）。
 */
async function runWithLimit(
  tasks: readonly (() => Promise<void>)[],
  limit: number,
): Promise<void> {
  let cursor = 0

  async function worker(): Promise<void> {
    while (cursor < tasks.length) {
      const index = cursor
      cursor += 1
      // 每个任务自己兜住异常（写进它自己的卡片），所以这里 catch 只是为了
      // 不让一个失败把整批 worker 中断掉。
      try {
        await tasks[index]()
      } catch {
        /* 已由任务自身处理 */
      }
    }
  }

  const workers = Math.min(Math.max(limit, 1), tasks.length)
  await Promise.all(Array.from({ length: workers }, () => worker()))
}

/* ── 卡片（每个自带 加载态 / 值 / 错误 / 重试）────────────────────────── */

interface AsyncCard<T> {
  state: 'loading' | 'done' | 'error'
  value: T | null
  error: unknown
  load: () => Promise<void>
}

/**
 * ★ 用 `reactive` 而不是 `ref`：模板里就不需要写 `.value`（这是本仓库反复踩过的坑），
 *   而卡片是三个字段绑在一起的小状态机，本来就该整体响应。
 */
function asyncCard<T>(loader: () => Promise<T>): AsyncCard<T> {
  const card: AsyncCard<T> = reactive({
    state: 'loading',
    value: null as T | null,
    error: null as unknown,
    load: async (): Promise<void> => {
      card.state = 'loading'
      card.error = null
      try {
        card.value = await loader()
        card.state = 'done'
      } catch (e) {
        // ★ 失败时把值清掉：留着上一次的数字、只加一行红字，会被当成「一次成功」读
        card.value = null
        card.error = e
        card.state = 'error'
      }
    },
  }) as AsyncCard<T>
  return card
}

// ① 各域总数：4 个请求，**精确**（后端 COUNT）
interface KpiDef {
  key: CountKey
  label: string
  icon: IconName
  /** 点进去看明细。数字旁边没有入口的话，用户会自己去侧边栏找，然后丢掉这里的上下文。 */
  to: string
}

const KPI_DEFS: readonly KpiDef[] = [
  { key: 'station', label: '站点', icon: 'station', to: '/station' },
  { key: 'charger', label: '充电桩', icon: 'charger', to: '/charger' },
  { key: 'connector', label: '充电枪', icon: 'connector', to: '/connector' },
  { key: 'member', label: '车主', icon: 'member', to: '/member' },
]

const kpiCards: Record<CountKey, AsyncCard<number>> = {
  station: asyncCard(() => fetchTotal('station')),
  charger: asyncCard(() => fetchTotal('charger')),
  connector: asyncCard(() => fetchTotal('connector')),
  member: asyncCard(() => fetchTotal('member')),
}

// ② 各状态单数：9 个请求，**精确**（同样取 total，但带 status 条件）
async function countOrders(status: number): Promise<number> {
  // ★ 带 `status` + 近 7 天区间：`idx_order_status_create` 正好吃这两个条件。
  //   不带区间就是全表 filesort（后端注释原话），工作台是首屏，不能这么干。
  const paged = await pageOrders({
    status,
    pageNum: 1,
    pageSize: 1,
    createTimeFrom: range.begin,
    createTimeTo: range.end,
  })
  return paged.total
}

interface StatusCard {
  code: number
  label: string
  tone: Tone
  card: AsyncCard<number>
}

const statusCards: StatusCard[] = ORDER_STATUS_LIST.map((meta) => ({
  code: meta.code,
  label: meta.label,
  tone: meta.tone,
  card: asyncCard(() => countOrders(meta.code)),
}))

/** 条形图的比例基准。★ 用 `Math.max(1, ...)`：全 0 时除以 0 会得到 NaN 宽度。 */
const maxStatusCount = computed(() =>
  Math.max(1, ...statusCards.map((s) => s.card.value ?? 0)),
)

function barWidth(card: AsyncCard<number>): string {
  const n = card.value ?? 0
  return `${Math.round((n / maxStatusCount.value) * 100)}%`
}

// ③ 订单样本：它同时供四个地方用（订单总数、营收、电量、最近订单）
const sample = asyncCard<OrderSample>(() => fetchOrderSample())

const stats = computed<OrderStats | null>(() => {
  const s = sample.value
  return s ? aggregateSample(s.rows) : null
})

/**
 * 样本口径说明。**常驻**（营收/电量卡上一直在），不是出错时才出现。
 *
 * ★ 三种「不完整」分开写，理由见 `OrderSample` 的注释：
 *   触上限 = 实际值确定更高；分页失败 = 不知道差多少；完整 = 已经不是样本了。
 *   混成一句会丢掉最有用的那条信息。
 */
const sampleCaption = computed(() => {
  const s = sample.value
  if (!s) return `近 7 天 · 采样上限 ${DASHBOARD_SAMPLE_LIMIT} 单 · 仅供参考`
  const parts = ['近 7 天', `样本 ${s.rows.length} 单`]
  if (s.truncated) parts.push(`已达 ${DASHBOARD_SAMPLE_LIMIT} 单上限，实际值高于此`)
  if (s.failedPages > 0) parts.push(`${s.failedPages} 页加载失败，样本残缺`)
  if (s.complete) parts.push('已是全量')
  parts.push('仅供参考')
  return parts.join(' · ')
})

/** 最近订单：样本里最新的 8 条。**不是全量**，所以标题写「样本内」。 */
const recentOrders = computed<AdminOrderVO[]>(() => (sample.value?.rows ?? []).slice(0, 8))

function openOrder(row: AdminOrderVO): void {
  void router.push(`/order/${row.id}`)
}

/* ── 首屏加载 ─────────────────────────────────────────────────────────── */

/**
 * 两批**串行**，不是并行。
 *
 * ★ `fetchOrderSample` 内部自己按 `DASHBOARD_CONCURRENCY` 翻页，如果和上面那批
 *   （13 个请求，同样按这个上限跑）同时发起，峰值并发会变成两倍 ——
 *   而这个常量的意义就是「别把后端连接池打满」。串行的代价是慢一个往返，
 *   首屏慢 200ms 比把库拖慢要好。
 */
async function loadAll(): Promise<void> {
  const batch: Array<() => Promise<void>> = [
    ...Object.values(kpiCards).map((c) => c.load),
    ...statusCards.map((s) => s.card.load),
  ]
  await runWithLimit(batch, DASHBOARD_CONCURRENCY)
  await sample.load()
}

onMounted(() => {
  void loadAll()
})

/** 数字的来路口径标签，直接写在卡片上 —— 同一个页面里两种可信度必须一眼能分开。 */
const EXACT_LABEL = '精确 total（后端 COUNT）'
const RANGE_EXACT_LABEL = '近 7 天 · 精确 total（后端 COUNT）'
const SAMPLE_LABEL = '样本聚合'
</script>

<template>
  <div class="page">
    <PagePanel
      title="工作台"
      subtitle="后端没有统计接口，本页所有数字都由前端拼装。每张卡片上标了它的来源：精确 total 或样本聚合。"
    >
      <!-- ★ 降级说明常驻：不知道「营收是样本」的人会拿它去对账 -->
      <ul class="notes">
        <li>
          <b>带「样本聚合」的数字不要拿去对账。</b>后端没有聚合接口，营收与电量是把订单列表
          翻回来在客户端加出来的，最多只取近 7 天的 <b>{{ DASHBOARD_SAMPLE_LIMIT }} 单</b>，
          所以它一定偏小。
        </li>
        <li>
          标着 <b>精确 total</b> 的数字是后端 <code>COUNT(*)</code> 的结果，可以直接用 ——
          包括各状态的单数。
        </li>
      </ul>

      <!-- ── KPI 行：4 个域总数（精确）──────────────────────────────── -->
      <div class="kpi-row">
        <button
          v-for="def in KPI_DEFS"
          :key="def.key"
          type="button"
          class="kpi"
          @click="router.push(def.to)"
        >
          <span class="kpi-head">
            <CoIcon :name="def.icon" :size="15" />
            {{ def.label }}
          </span>

          <span v-if="kpiCards[def.key].state === 'loading'" class="kpi-value is-muted">···</span>
          <span v-else-if="kpiCards[def.key].state === 'error'" class="kpi-value is-error">
            加载失败
          </span>
          <span v-else class="kpi-value co-num">{{ kpiCards[def.key].value }}</span>

          <!-- ★ 失败态**不写「点这里重试」**：这张卡片整体是个跳转按钮，
               点它去的是列表页，重试入口在下面那条错误栏里。 -->
          <span class="kpi-foot">
            <template v-if="kpiCards[def.key].state === 'error'">
              <span class="err-text" :title="errorText(kpiCards[def.key].error)">总数未取到</span>
            </template>
            <template v-else>{{ EXACT_LABEL }}</template>
          </span>
        </button>

        <!-- 订单总数来自采样响应里的 `totalInRange`：它本身是精确值，
             但和采样共用一次请求，所以失败也一起失败（重试也是同一个）。 -->
        <button type="button" class="kpi" @click="router.push('/order')">
          <span class="kpi-head">
            <CoIcon name="order" :size="15" />
            订单
          </span>

          <span v-if="sample.state === 'loading'" class="kpi-value is-muted">···</span>
          <span v-else-if="sample.state === 'error'" class="kpi-value is-error">加载失败</span>
          <span v-else class="kpi-value co-num">{{ sample.value?.totalInRange ?? '—' }}</span>

          <span class="kpi-foot">
            <template v-if="sample.state === 'error'">
              <span class="err-text" @click.stop="sample.load()">点这里重试</span>
            </template>
            <template v-else>{{ RANGE_EXACT_LABEL }}</template>
          </span>
        </button>
      </div>

      <!-- 精确总数的重试入口：卡片本身是跳转按钮，所以错误条单独放一行 -->
      <p
        v-for="def in KPI_DEFS"
        v-show="kpiCards[def.key].state === 'error'"
        :key="`err-${def.key}`"
        class="err-bar"
      >
        「{{ def.label }}」总数没取到：{{ errorText(kpiCards[def.key].error) }}
        <el-button link type="primary" size="small" @click="kpiCards[def.key].load()">
          重试
        </el-button>
      </p>

      <div class="cols">
        <!-- ── 左列：样本聚合的两个数字 + 最近订单 ───────────────────── -->
        <div class="col">
          <div class="card">
            <div class="card-head">
              <h3>近 7 天营收</h3>
              <span class="tag-sample">{{ SAMPLE_LABEL }}</span>
            </div>

            <div v-if="sample.state === 'loading'" class="big is-muted">···</div>
            <template v-else-if="sample.state === 'error'">
              <div class="card-error">
                订单样本没取到，营收与下面的电量、最近订单都无法计算。
                <el-button link type="primary" size="small" @click="sample.load()">重试</el-button>
              </div>
            </template>
            <template v-else>
              <div class="big co-num">¥{{ formatMoney(stats?.paidAmount ?? '0') }}</div>
              <p class="big-note">
                只统计「已支付」的实付金额：待支付 / 支付失败的钱还没收到，算进去是虚增；
                <b>已退款的那部分没有扣减</b> —— 列表接口里没有退款金额字段，
                所以宁可少算，不要算错。
              </p>
              <div class="split">
                <div class="split-item">
                  <span class="s-label">样本内订单</span>
                  <span class="s-value co-num">{{ stats?.sampleSize ?? 0 }}</span>
                </div>
                <div class="split-item">
                  <span class="s-label">近 7 天订单（精确）</span>
                  <span class="s-value co-num">{{ sample.value?.totalInRange ?? '—' }}</span>
                </div>
              </div>
            </template>

            <!-- ★ 常驻口径，永远在这一张卡上（见文件头） -->
            <p class="caption">{{ sampleCaption }}</p>
          </div>

          <div class="card">
            <div class="card-head">
              <h3>近 7 天充电量</h3>
              <span class="tag-sample">{{ SAMPLE_LABEL }}</span>
            </div>

            <div v-if="sample.state === 'loading'" class="big is-muted">···</div>
            <div v-else-if="sample.state === 'error'" class="card-error">与上面的营收同源，一起失败。</div>
            <template v-else>
              <div class="big co-num">
                {{ formatMoney(stats?.energyKwh ?? '0') }}<i class="unit">kWh</i>
              </div>
              <p class="big-note">
                统计的是<b>全部样本</b>：电已经充进车里了，和订单后来是退款还是取消无关。
                没充过电的订单电量是空值，按 0 计入（不是漏算）。
                求和时保留 4 位小数（避免逐单取整后再相加），这里显示 2 位。
              </p>
            </template>
            <p class="caption">{{ sampleCaption }}</p>
          </div>

          <div class="card">
            <div class="card-head">
              <h3>最近订单</h3>
              <span class="tag-sample">样本内最新 {{ recentOrders.length }} 单</span>
              <el-button link type="primary" size="small" @click="router.push('/order')">
                全部订单
              </el-button>
            </div>

            <EmptyState
              v-if="sample.state === 'error'"
              kind="error"
              title="订单样本没取到"
              :description="errorText(sample.error)"
              @retry="sample.load"
            />
            <el-table
              v-else
              v-loading="sample.state === 'loading'"
              :data="recentOrders"
              row-key="id"
              size="small"
              @row-dblclick="openOrder"
            >
              <el-table-column label="订单号" min-width="160">
                <template #default="{ row }">
                  <button class="link" type="button" @click="openOrder(row)">{{ row.orderNo }}</button>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <OrderStatusTag :status="row.status" size="sm" />
                </template>
              </el-table-column>
              <el-table-column label="车主" width="120">
                <template #default="{ row }">
                  <span class="co-num">{{ row.memberPhone || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="应付" width="100" align="right">
                <template #default="{ row }">
                  <MoneyText :value="row.payAmount" size="sm" />
                </template>
              </el-table-column>
              <el-table-column label="建单" width="110">
                <template #default="{ row }">
                  <span class="co-num time" :title="formatServerTime(row.createTime) ?? ''">
                    {{ formatRelative(row.createTime) }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- ── 右列：状态分布（精确）───────────────────────────────── -->
        <div class="col">
          <div class="card">
            <div class="card-head">
              <h3>近 7 天订单状态分布</h3>
              <span class="tag-exact">精确 total</span>
            </div>

            <p class="card-note">
              每个状态单独查一次（只取后端返回的 <code>total</code>，不搬记录），
              所以这 9 个数字是精确的、可以相加。
              ★ 这里刻意<b>不</b>显示样本里的状态占比：那会是同一个事实的第二个、
              更小的数字，两者并排只会让人以为其中一个错了。
            </p>

            <ul class="bars">
              <li v-for="item in statusCards" :key="item.code" class="bar-row">
                <span class="bar-label">
                  <StatusTag
                    :label="item.label"
                    :tone="item.tone"
                    size="sm"
                  />
                </span>

                <span class="bar-track">
                  <span
                    v-if="item.card.state === 'done'"
                    class="bar-fill"
                    :style="{
                      width: barWidth(item.card),
                      background: TONE_VAR[item.tone].base,
                    }"
                  />
                  <span v-else-if="item.card.state === 'error'" class="bar-failed">加载失败</span>
                </span>

                <span class="bar-num co-num">
                  <template v-if="item.card.state === 'done'">{{ item.card.value }}</template>
                  <template v-else-if="item.card.state === 'error'">
                    <el-button link type="primary" size="small" @click="item.card.load()">
                      重试
                    </el-button>
                  </template>
                  <template v-else>···</template>
                </span>
              </li>
            </ul>

            <p class="caption">近 7 天 · 精确 total · 与「订单」卡片的数字同区间</p>
          </div>
        </div>
      </div>
    </PagePanel>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.notes {
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
}

.notes li {
  position: relative;
  padding: 8px 12px 8px 30px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.notes li + li {
  margin-top: 8px;
}

.notes li::before {
  content: '!';
  position: absolute;
  left: 11px;
  top: 9px;
  display: grid;
  place-items: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--co-warning);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

.notes b {
  color: var(--co-text-2);
  font-weight: 600;
}

/* 行内的代码/字段名：浏览器默认的等宽字体在这一页的字体栈里很突兀，
   所以统一压成「浅灰底 + 正文色」，和其它页面保持一致。 */
.notes code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-card);
  color: var(--co-text-2);
  font-size: 12px;
}

/* ── KPI ─────────────────────────────────────────────────────────────── */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 4px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 13px 14px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-lg);
  background: var(--co-bg-card);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--co-duration-fast) var(--co-ease),
    box-shadow var(--co-duration-fast) var(--co-ease);
}

.kpi:hover {
  border-color: var(--co-primary);
  box-shadow: var(--co-shadow-sm);
}

.kpi-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.kpi-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--co-text-1);
}

.kpi-value.is-muted {
  color: var(--co-text-4);
}

.kpi-value.is-error {
  font-size: 14px;
  color: var(--co-danger);
}

.kpi-foot {
  font-size: 11px;
  line-height: 1.4;
  color: var(--co-text-4);
}

.err-text {
  color: var(--co-danger);
  cursor: pointer;
}

.err-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 8px 0 0;
  padding: 8px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
  font-size: 12.5px;
}

/* ── 两列 ─────────────────────────────────────────────────────────────── */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  margin-top: 16px;
}

.col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.card {
  padding: 14px 16px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-lg);
  background: var(--co-bg-card);
  box-shadow: var(--co-shadow-sm);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.card-head h3 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
}

/* 口径标签用**中性浅底**而不是彩色：它说明来源，不表达状态。
   上色会让它看起来像状态标签，和卡片上的真实状态抢注意力。 */
.tag-sample,
.tag-exact {
  padding: 1px 7px;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-2);
  font-size: 11px;
  color: var(--co-text-3);
}

.tag-exact {
  border-color: color-mix(in srgb, var(--co-success) 30%, transparent);
  background: var(--co-success-weak);
  color: var(--co-success-strong);
}

.card-head .el-button:last-child {
  margin-left: auto;
}

.card-note {
  margin: 0 0 12px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.card-note code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
}

.card-note b {
  color: var(--co-text-2);
  font-weight: 600;
}

.big {
  font-size: var(--co-font-money);
  font-weight: 600;
  line-height: 1.2;
  color: var(--co-text-1);
}

.big.is-muted {
  color: var(--co-text-4);
}

.big .unit {
  margin-left: 4px;
  font-size: 12px;
  font-style: normal;
  font-weight: 400;
  color: var(--co-text-3);
}

.big-note {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.big-note b {
  color: var(--co-text-2);
  font-weight: 600;
}

.split {
  display: flex;
  gap: 24px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--co-border-light);
}

.split-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.s-label {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.s-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.caption {
  margin: 12px 0 0;
  padding-top: 10px;
  border-top: 1px solid var(--co-border-light);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

.card-error {
  padding: 8px 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-danger-strong);
}

/* ── 状态分布条 ───────────────────────────────────────────────────────── */
.bars {
  margin: 0;
  padding: 0;
  list-style: none;
}

.bar-row {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr) 48px;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
}

.bar-label {
  min-width: 0;
  overflow: hidden;
}

.bar-track {
  position: relative;
  height: 8px;
  border-radius: 4px;
  background: var(--co-bg-3);
  overflow: hidden;
}

.bar-fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  /* ★ 条宽变化加过渡，但**不做循环动效** —— 工作台上跑马灯一样的动画是噪声 */
  transition: width var(--co-duration) var(--co-ease);
}

.bar-failed {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  font-size: 10.5px;
  color: var(--co-danger);
}

.bar-num {
  text-align: right;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-family: inherit;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  text-align: left;
  cursor: pointer;
}

.link:hover {
  text-decoration: underline;
}

.time {
  font-size: 12px;
  color: var(--co-text-2);
}

.co-num {
  font-variant-numeric: tabular-nums;
}
</style>
