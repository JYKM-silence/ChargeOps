<script setup lang="ts">
/**
 * 车主端首页 —— 打开 App 的第一屏，也是**充电入口**。
 *
 * ★★ 自上而下的顺序是优先级，不是装饰：钱包 → 进行中订单 → 找站 ★★
 *   车主打开 App 只可能带着三个目的：正在充的看一眼、有一笔待付的要付掉、
 *   还没开始充要找一把枪。所以「进行中订单」必须放在最显眼的位置，
 *   而不是塞在页面底部当一条可忽略的通知。
 *
 * ★★ 为什么对「充电中」和「待支付」各发一次查询，而不是拉一页自己筛 ★★
 *   `pageOrders` **只有 `status` 一个筛选条件**（没有时间范围、没有站点）。
 *   如果只拉「最新 5 条」再自己筛，会出现一个很难解释的现象：
 *   车主昨天开的单还在充电中，但今天已经产生了 6 笔已支付的短单 ——
 *   那笔充电中的单**不在最新 5 条里**，首页显示「没有进行中的充电」，而车正在充。
 *   按状态查就不会漏：两次都是 `status` 等值查询（后端有 `idx_order_status_create`），
 *   比「拉一大页再筛」更便宜，也更准。
 *
 * ★ 两个状态之外为什么没有别的：
 *   · `3 充电结束待结算` —— 结算由后端推进，车主没有任何可做的动作，
 *     它只是屏幕上会自己消失的一行（还会和「待支付」抢占注意力）；
 *   · `6 支付失败` —— 它确实还能重新支付，但入口在订单列表/详情页
 *     （那里有金额明细和二次确认），首页只负责「正在发生的事」和「马上就要到期的事」。
 */

import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ORDER_STATUS,
  formatCountdown,
  formatKwhLabel,
  formatRelative,
  pendingPayRemainMs,
  resolveAssetUrl,
  type ChargingOrderVO,
  type MemberStationVO,
} from '@chargeops/shared'
import { openHoursText, pageStations } from '@/api/station'
import { pageOrders } from '@/api/order'
import { getWallet } from '@/api/wallet'
import { useSessionStore } from '@/stores/session'
import { useAsyncData } from '@/composables/useAsyncData'
import { usePagedList } from '@/composables/usePagedList'
import MoneyText from '@/components/MoneyText.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import PageState from '@/components/PageState.vue'

const router = useRouter()
const session = useSessionStore()

/**
 * 首页最多显示几条进行中订单。
 *
 * ★ 3 而不是 10：首页不是订单列表。超过 3 条说明车主有一堆单子没处理，
 *   这时候该看的是「全部订单」，而不是在首页无限往下堆。
 */
const ONGOING_PAGE_SIZE = 3

// ══════════════════════════════════════════════════════════════════════════
// 钱包
// ══════════════════════════════════════════════════════════════════════════

const walletQuery = useAsyncData(getWallet)

/**
 * ★ 用 `computed` 转一层，而不是在模板里写 `walletQuery.data.value.balance`。
 *   `useAsyncData` 返回的是「装着 ref 的普通对象」，模板**只解包顶层绑定** ——
 *   嵌套的 `walletQuery.data` 在模板里仍然是一个 ref 对象，
 *   写 `.value` 能跑，但和页面里其它读法不一致，很容易被下一个人改错。
 */
const balance = computed(() => walletQuery.data.value?.balance ?? null)
const frozen = computed(() => walletQuery.data.value?.frozenAmount ?? null)
/** ★ 只用来决定钱包卡右侧显示「充值 / 明细」还是「加载失败」—— 失败不该把整张卡变空。 */
const walletFailed = computed(() => walletQuery.failed.value)

// ══════════════════════════════════════════════════════════════════════════
// 进行中订单（充电中 + 待支付）
// ══════════════════════════════════════════════════════════════════════════

const chargingQuery = useAsyncData(() =>
  pageOrders({ pageNum: 1, pageSize: ONGOING_PAGE_SIZE, status: ORDER_STATUS.CHARGING }),
)
const pendingQuery = useAsyncData(() =>
  pageOrders({ pageNum: 1, pageSize: ONGOING_PAGE_SIZE, status: ORDER_STATUS.PENDING_PAY }),
)

/**
 * 合并两个查询的结果，**待支付排在前面**。
 *
 * ★ 顺序的理由是「有没有截止时间」：待支付有 15 分钟窗口
 *   （超时会被定时任务扫成「支付失败」），充电中只是正在发生、没有 deadline。
 *   有 deadline 的排在前面是唯一能让人不误事的排法。
 */
const ongoingOrders = computed<ChargingOrderVO[]>(() => [
  ...(pendingQuery.data.value?.records ?? []),
  ...(chargingQuery.data.value?.records ?? []),
])

/**
 * ★ 没有导出 `loading`：这一块**只在真正有单子（或彻底失败）时才渲染**。
 *   加载中先占一块「加载中」再消失，等于凭空多一次跳动；
 *   而两个查询都是 `status` 等值查询，回来得比用户滚动还快。
 *   真正需要「加载中」的是下面的站点列表 —— 它是这屏的主体，不能空着。
 */
/** ★ 两边都失败才当整体失败：一边成功就还有东西可看。 */
const ongoingFailed = computed(() => chargingQuery.failed.value && pendingQuery.failed.value)

async function reloadOngoing(): Promise<void> {
  await Promise.all([pendingQuery.reload(), chargingQuery.reload()])
}

// ── 待支付倒计时 ────────────────────────────────────────────────────────────

const nowMs = ref(Date.now())
let tickTimer: number | undefined

/**
 * 倒计时只在**真的有待支付订单**时推进。
 *
 * ★ 不加这个判断的话，首页每秒都会有一个 `ref` 变化 —— 整页（含站点列表的
 *   v-for）跟着重渲染一次。数字上不贵，但这是**白烧的电**：没有待支付订单时
 *   没有任何人在看这个秒数。
 */
onMounted(() => {
  tickTimer = window.setInterval(() => {
    if (pendingQuery.data.value?.records?.length) nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (tickTimer !== undefined) window.clearInterval(tickTimer)
})

/**
 * 待支付剩余时间文案。
 *
 * ★ 后端**没有**返回过期时间：它的超时是定时任务按 `create_time < now - 15min`
 *   扫出来的（5 分钟一轮），所以「过期时刻」只能由创建时间推算。
 *   → 文案必须是「预计关闭」而不是「已超时」：本地到点**不等于**状态已变，
 *     最长可能晚 5 分钟才被扫到。说「已超时」会让人以为系统坏了。
 */
function countdownText(order: ChargingOrderVO): string {
  const ms = pendingPayRemainMs(order.createTime, nowMs.value)
  if (ms === null) return ''
  if (ms <= 0) return '预计已过支付窗口，请尽快支付'
  return `剩余 ${formatCountdown(ms)} 预计关闭`
}

/**
 * 倒计时归零时，**主动重拉一次**待支付列表。
 *
 * ★ 同上：到点不等于立刻变状态（定时任务 5 分钟才扫一轮）。
 *   主动拉这一次，是为了让「已经变成 6 支付失败」的单子从这张卡上消失 ——
 *   否则用户会对着一个后端已经判死的单子反复点「去支付」。
 * ★ 每个订单只触发一次（`reloadedFor`），否则每秒都会发一个请求。
 */
const reloadedFor = new Set<number>()
watch(nowMs, () => {
  for (const order of pendingQuery.data.value?.records ?? []) {
    const ms = pendingPayRemainMs(order.createTime, nowMs.value)
    if (ms !== null && ms <= 0 && !reloadedFor.has(order.id)) {
      reloadedFor.add(order.id)
      void pendingQuery.reload()
      break
    }
  }
})

/** 卡片右侧的金额：能付的时候就显示应付，否则退到账单总额。 */
function orderAmount(order: ChargingOrderVO): string | number | null {
  return order.payAmount ?? order.totalAmount ?? null
}

function orderSubText(order: ChargingOrderVO): string {
  if (order.status === ORDER_STATUS.CHARGING) {
    const started = formatRelative(order.startTime ?? order.createTime)
    const energy =
      order.totalEnergyKwh === null || order.totalEnergyKwh === undefined
        ? ''
        : ` · 已充 ${formatKwhLabel(order.totalEnergyKwh)}`
    return `${started} 开始${energy}`
  }
  return `账单已生成 · 创建于 ${formatRelative(order.createTime)}`
}

/**
 * 卡片上的主按钮。
 *
 * ★ 两个状态都只是**跳转**，支付动作本身留在订单详情页 ——
 *   那里有金额明细、二次确认和「余额不足 → 充值后再付」的完整分支。
 *   在一个入口点两下就把钱扣掉，是首页最不该做的事（后端没有幂等键）。
 *   `action=pay` 是给详情页的一个提示：用户是奔着支付来的，可以直接把支付区推到眼前。
 */
function primaryAction(order: ChargingOrderVO): void {
  const isPending = order.status === ORDER_STATUS.PENDING_PAY
  void router.push({
    name: 'order-detail',
    params: { id: order.id },
    query: isPending ? { action: 'pay' } : undefined,
  })
}

// ══════════════════════════════════════════════════════════════════════════
// 站点搜索 + 无限滚动
// ══════════════════════════════════════════════════════════════════════════

const keyword = ref('')
const city = ref('')

/**
 * ★ 必须**解构**：`usePagedList` 返回的是一堆 `ref`，
 *   而模板里 `list.loading` 这样的嵌套属性**不会自动解包** ——
 *   `v-model:loading="list.loading"` 会编译成「把布尔值赋给 list.loading」，
 *   直接把那个 ref 覆盖掉。解构到顶层之后，模板的赋值会被编译成 `.value = ...`。
 */
const {
  rows: stationRows,
  total: stationTotal,
  loading: stationLoading,
  firstLoading: stationFirstLoading,
  finished: stationFinished,
  error: stationError,
  loadMore: stationLoadMore,
  refresh: stationRefresh,
  reset: stationReset,
} = usePagedList<MemberStationVO>({
  // ★ 只传后端 DTO 上真实存在的两个条件（`name` 模糊 / `city` 精确）。
  //   空串会被 `createHttp` 的 cleanParams 丢掉，所以「没填」不会变成 `name=`。
  params: () => ({ name: keyword.value.trim() || undefined, city: city.value || undefined }),
  fetch: (query) => pageStations(query),
})

/**
 * 城市选项来自**已经翻到过的行**，不是一个全量城市字典。
 *
 * ★ 为什么必须是「选」而不是「输入」：后端 `city` 是**精确匹配**
 *   （见 `MemberStationPageQueryDTO` 的类注释：城市是从列表里选的，站名才是手敲的）。
 *   手敲一个「深圳」而库里存的是「深圳市」，会得到空列表 —— 而用户看不出
 *   是自己少打了一个字，只会以为「这个 App 搜不到深圳」。
 *
 * ★ 边界要说清楚：选项是**增量**的，翻到过的城市才会出现。
 *   后端没有「城市列表」接口，所以这里做不到全量 ——
 *   筛选结果本身是对的（精确匹配），不全的只是那一排按钮。
 */
const knownCities = ref<string[]>([])
watch(stationRows, (rows) => {
  const seen = new Set(knownCities.value)
  let added = false
  for (const row of rows) {
    if (row.city && !seen.has(row.city)) {
      seen.add(row.city)
      added = true
    }
  }
  if (added) knownCities.value = [...seen]
})

/**
 * 名称搜索**只在提交时触发**（回车 / 清空），不做输入即搜。
 *
 * ★ 后端对 `name` 做的是 `LIKE CONCAT('%', ?, '%')` —— 前后都有通配符，
 *   索引用不上。每敲一个字发一次，就是一串全表扫 + 一串分页请求。
 *   手机键盘上「打完一个词」比「每敲一下就刷新列表」也更符合预期。
 */
function applyFilters(): void {
  // ★ 改筛选条件必须走 reset()：usePagedList 的 pageNum 只在 reset 里回到 1，
  //   直接 loadMore 会从「上一次的第 2 页」接着翻。
  void stationReset()
}

function pickCity(value: string): void {
  if (city.value === value) return
  city.value = value
  applyFilters()
}

function clearFilters(): void {
  keyword.value = ''
  city.value = ''
  applyFilters()
}

const filterActive = computed(() => keyword.value.trim() !== '' || city.value !== '')

// ══════════════════════════════════════════════════════════════════════════
// 展示辅助
// ══════════════════════════════════════════════════════════════════════════

/** `07:00 - 22:00`；缺一个端点返回 null（`openHoursText` 不猜「24 小时」）。 */
function hoursOf(station: MemberStationVO): string | null {
  return openHoursText(station.openTime, station.closeTime)
}

/** 地址缺省时退到省市区拼串，再缺就明说没有 —— 不要显示一个空行让人以为是渲染坏了。 */
function addressOf(station: MemberStationVO): string {
  if (station.address) return station.address
  const region = [station.province, station.city, station.district].filter(Boolean).join(' ')
  return region || '暂无地址信息'
}

/** 昵称首字。★ 用 `Array.from` 而不是 `charAt(0)`：emoji 昵称会被 charAt 劈成半个字符。 */
const avatarText = computed(() => Array.from(session.displayName.trim())[0] ?? '车')

/**
 * ★ 头像必须过 `resolveAssetUrl()`：后端存的是**绝对地址**
 *   （`http://localhost:8081/img/x.jpg`），直接塞进 `src` 会绕开 Vite 代理直连 8081，
 *   换后端地址就整片挂掉。同 `api/member.ts` 的上传返回值处理。
 */
const avatarUrl = computed(() => resolveAssetUrl(session.avatar))

/** 问候语按本机时刻取 —— 这里用的是「现在几点」，不是解析后端时间字符串。 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

function gotoProfile(): void {
  void router.push({ name: 'profile' })
}

function gotoWallet(): void {
  void router.push({ name: 'wallet' })
}

function gotoOrders(): void {
  void router.push({ name: 'orders' })
}

/**
 * 进站点详情。
 *
 * ★★ 为什么把站点信息塞进 query ★★
 *   车主端**没有「站点详情」接口** —— `MemberStationController` 只有
 *   `GET /station/page` 和 `GET /connector/by-station/{id}` 两个端点，
 *   而枪列表里**不含站点名/地址/电话**（它是枪的 VO）。
 *   所以详情页顶部那行信息只能由这里带过去。
 *
 *   ★ 放 query 而不是 `history.state`：query 在**刷新之后还在**。
 *     H5 上用户按刷新、或从微信里重新打开同一个地址都很常见，
 *     而 state 一刷新就没了 —— 那时详情页会退化成「只有一排枪，不知道是哪个站」。
 *   ★ 只带展示字段，不带任何金额/状态：这些是「看一眼」的信息，
 *     站点真正的数据源是枪列表接口，query 里的值过期了也不会造成错误判断。
 */
function gotoStation(station: MemberStationVO): void {
  const hours = hoursOf(station)
  void router.push({
    name: 'station-detail',
    params: { id: station.id },
    query: {
      name: station.name,
      ...(station.address ? { addr: station.address } : {}),
      ...(hours ? { hours } : {}),
      ...(station.servicePhone ? { phone: station.servicePhone } : {}),
    },
  })
}

// ── 下拉刷新 ───────────────────────────────────────────────────────────────

const refreshing = ref(false)

/** 下拉刷新把**整屏**都重拉一遍：首页上的三块数据是同一批「现在的事实」。 */
async function onRefresh(): Promise<void> {
  try {
    await Promise.all([reloadOngoing(), walletQuery.reload(), stationRefresh()])
  } finally {
    // ★ `finally` 里必须置回 false：不置回，下拉指示器会一直转，
    //   用户以为还在刷新，就会一直等下去。
    refreshing.value = false
  }
}

// ── 首屏 ───────────────────────────────────────────────────────────────────

onMounted(() => {
  /**
   * ★★ 这一行是必须的，不能靠 `van-list` 的 `immediate-check` ★★
   *   `usePagedList` 的 `pageNum` 初值是 1，而 `loadMore()` 请求的是 `pageNum + 1`。
   *   如果让 van-list 在挂载时自动触发 `@load`，它会去请求**第 2 页**，
   *   于是第 1 页的数据永远没有机会被加载（列表从第 11 条开始显示）。
   *   → 模板里关掉了 `immediate-check`，由这里显式拉第 1 页（`refresh()` 走 replace）。
   *   ★ 之后每一页的自动衔接由 van-list 自己保证：它 watch 了 loading/finished/error，
   *     每次加载结束都会重新判断「还没填满一屏就继续拉」。
   */
  void stationRefresh()
})
</script>

<template>
  <div class="home page-with-tabbar">
    <!-- ① 顶部问候区 ------------------------------------------------ -->
    <header class="hero">
      <div class="hero-top">
        <div class="who">
          <img v-if="avatarUrl" class="avatar" :src="avatarUrl" alt="头像" />
          <span v-else class="avatar avatar-text">{{ avatarText }}</span>
          <div class="who-text">
            <p class="greeting">{{ greeting }}</p>
            <p class="name">{{ session.displayName }}</p>
          </div>
        </div>
        <button type="button" class="mine" @click="gotoProfile">
          <van-icon name="user-o" />
          我的
        </button>
      </div>
    </header>

    <van-pull-refresh class="pull" v-model="refreshing" @refresh="onRefresh">
      <div class="body">
        <!-- ② 钱包卡 ------------------------------------------------ -->
        <button type="button" class="co-card wallet" @click="gotoWallet">
          <span class="wallet-main">
            <span class="wallet-label">可用余额</span>
            <MoneyText :value="balance" size="xl" strong />
            <span class="wallet-sub">
              冻结
              <MoneyText :value="frozen" size="sm" />
            </span>
          </span>
          <span class="wallet-go">
            <span v-if="walletFailed" class="wallet-err">加载失败</span>
            <span v-else class="wallet-go-text">充值 / 明细</span>
            <van-icon name="arrow" />
          </span>
        </button>

        <!-- ③ 进行中订单 -------------------------------------------- -->
        <!-- ★ 没有进行中订单时整块**不渲染**：首页放一句「暂无进行中的充电」
             只会占掉一屏里最贵的位置，而它传递的信息是「什么都不用做」。 -->
        <section v-if="ongoingOrders.length || ongoingFailed" class="co-card ongoing">
          <div class="card-head">
            <h2>进行中的充电</h2>
            <button type="button" class="link" @click="gotoOrders">全部订单</button>
          </div>

          <PageState
            v-if="ongoingFailed && !ongoingOrders.length"
            kind="error"
            title="进行中订单加载失败"
            description="可能是网络不通。别的数据仍然可以正常使用。"
            @retry="reloadOngoing"
          />

          <ul v-else class="order-list">
            <li
              v-for="order in ongoingOrders"
              :key="order.id"
              class="order-row"
              role="button"
              tabindex="0"
              @click="primaryAction(order)"
            >
              <div class="order-main">
                <div class="order-line">
                  <OrderStatusTag :status="order.status" />
                  <span
                    v-if="order.status === ORDER_STATUS.PENDING_PAY"
                    class="countdown co-num"
                  >
                    {{ countdownText(order) }}
                  </span>
                </div>
                <p class="order-no co-num">{{ order.orderNo }}</p>
                <p class="order-sub">{{ orderSubText(order) }}</p>
              </div>
              <div class="order-side">
                <MoneyText :value="orderAmount(order)" size="lg" strong />
                <van-button
                  size="small"
                  round
                  type="primary"
                  class="order-btn"
                  @click.stop="primaryAction(order)"
                >
                  {{ order.status === ORDER_STATUS.PENDING_PAY ? '去支付' : '看详情' }}
                </van-button>
              </div>
            </li>
          </ul>
        </section>

        <!-- ④ 附近站点 ---------------------------------------------- -->
        <section class="stations">
          <div class="card-head">
            <h2>附近充电站</h2>
            <span v-if="stationTotal > 0" class="total co-num">共 {{ stationTotal }} 个</span>
          </div>

          <!-- ★ 搜索只覆盖「站名」这一个模糊条件；城市是下面那排按钮，
               因为后端 city 是精确匹配（手敲错一个字就是空列表且看不出原因）。 -->
          <van-search
            v-model="keyword"
            class="search"
            shape="round"
            placeholder="搜索站点名称"
            background="transparent"
            @search="applyFilters"
            @clear="applyFilters"
          />

          <!-- ★ 只有 1 个城市时不显示：筛了和没筛结果一样，那排按钮纯属噪音。 -->
          <div v-if="knownCities.length >= 2" class="city-row">
            <button
              type="button"
              class="chip"
              :class="{ active: city === '' }"
              @click="pickCity('')"
            >
              全部城市
            </button>
            <button
              v-for="item in knownCities"
              :key="item"
              type="button"
              class="chip"
              :class="{ active: city === item }"
              @click="pickCity(item)"
            >
              {{ item }}
            </button>
          </div>

          <van-list
            v-model:loading="stationLoading"
            :finished="stationFinished"
            :error="stationError"
            :immediate-check="false"
            :finished-text="stationRows.length ? '没有更多了' : ''"
            :error-text="stationRows.length ? '加载失败，点击重试' : ''"
            @load="stationLoadMore"
          >
            <PageState v-if="stationFirstLoading" kind="loading" title="正在加载附近站点…" />

            <template v-else-if="!stationRows.length && !stationError">
              <PageState
                v-if="filterActive"
                kind="filtered"
                title="没有符合条件的站点"
                description="换个关键词，或者清空筛选看看全部站点。"
              >
                <van-button size="small" round plain type="primary" @click="clearFilters">
                  清空筛选
                </van-button>
              </PageState>
              <PageState
                v-else
                kind="empty"
                title="暂时没有可用的充电站"
                description="停用和维护中的站点不会出现在这里。换个城市或关键词再试试。"
              />
            </template>

            <!-- 首屏就失败时用 PageState 兜底（此时 van-list 自身的 error-text 是空的，
                 否则会出现「一个错误、两种说法」）。 -->
            <PageState
              v-else-if="!stationRows.length && stationError"
              kind="error"
              title="站点列表加载失败"
              description="可能是网络不通，也可能是服务暂时不可用。"
              @retry="stationRefresh"
            />

            <ul v-else class="station-list">
              <li v-for="station in stationRows" :key="station.id">
                <button type="button" class="co-card station" @click="gotoStation(station)">
                  <span class="s-head">
                    <span class="s-name">{{ station.name }}</span>
                    <span v-if="station.operator" class="s-operator">{{ station.operator }}</span>
                  </span>
                  <span class="s-addr">{{ addressOf(station) }}</span>
                  <span class="s-meta">
                    <span v-if="hoursOf(station)" class="s-hours co-num">
                      <van-icon name="clock-o" />
                      {{ hoursOf(station) }}
                    </span>
                    <span
                      class="s-guns co-num"
                      :class="{ 'is-none': station.idleConnectorCount === 0 }"
                    >
                      <template v-if="station.connectorCount === 0">暂无充电枪</template>
                      <template v-else-if="station.idleConnectorCount === 0">暂无空闲</template>
                      <template v-else>
                        空闲 {{ station.idleConnectorCount }}/{{ station.connectorCount }} 把
                      </template>
                    </span>
                  </span>
                </button>
              </li>
            </ul>
          </van-list>
        </section>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: var(--co-bg-page);
}

/* ══ ① 问候区 ══════════════════════════════════════════════════════════ */

.hero {
  /**
   * ★ 品牌渐变上的文字必须是白色，而 tokens.css 里没有「品牌面上的文字」这一档
   *   （它只有侧边栏那组深色面上的白字，语义对不上 —— 借用它会让两个无关的面
   *    被绑在一起：以后有人把侧边栏文字调成暖白，首页这块会跟着变）。
   *   所以就地定义两个局部变量，而不是把 `#fff` 散落到三处 ——
   *   真要换主题时，这一块只改这两行。
   */
  --hero-fg: #ffffff;
  --hero-fg-soft: rgb(255 255 255 / 0.8);

  padding: 16px 16px 40px;
  background: linear-gradient(135deg, var(--co-primary) 0%, var(--co-primary-hover) 100%);
  color: var(--hero-fg);
}

.hero-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.who {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.avatar {
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  object-fit: cover;
  /* ★ 描边用半透明白：在渐变上画一圈实线会显得很硬，而这是「头像浮在品牌色上」
     的标准做法。 */
  border: 1.5px solid var(--hero-fg-soft);
  background: var(--co-bg-card);
}

.avatar-text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  /* ★ 没有头像时用「品牌色字 + 白底」的圆形，比默认灰底小人图标更有身份感 */
  color: var(--co-primary);
}

.who-text {
  min-width: 0;
}

.greeting {
  margin: 0;
  font-size: 12px;
  color: var(--hero-fg-soft);
}

.name {
  margin: 2px 0 0;
  font-size: 17px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mine {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--hero-fg-soft);
  border-radius: 999px;
  background: transparent;
  color: var(--hero-fg);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: transform var(--co-duration-fast) var(--co-ease);
}

.mine:active {
  transform: scale(0.94);
}

/* ══ 内容区 ════════════════════════════════════════════════════════════ */

/* ★ 负 margin 让钱包卡**压住渐变的下缘**。
     注意是加在 pull-refresh 的根上、不是加在它的子元素上：
     Vant 的 pull-refresh 根节点带 `overflow: hidden`（用来裁下拉头），
     给子元素加负 margin 会被裁掉一截。 */
.pull {
  margin-top: -28px;
}

.body {
  padding: 0 12px 8px;
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.card-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.total {
  font-size: 12px;
  color: var(--co-text-3);
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
}

/* ══ ② 钱包卡 ══════════════════════════════════════════════════════════ */

.wallet {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  margin-bottom: 12px;
  padding: 16px;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: transform var(--co-duration-fast) var(--co-ease);
}

.wallet:active {
  transform: scale(0.985);
}

.wallet-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.wallet-label {
  font-size: 12px;
  color: var(--co-text-3);
}

.wallet-sub {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--co-text-3);
}

.wallet-go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.wallet-go-text {
  color: var(--co-primary);
}

.wallet-err {
  color: var(--co-danger-strong);
}

/* ══ ③ 进行中订单 ══════════════════════════════════════════════════════ */

.ongoing {
  margin-bottom: 12px;
  padding: 14px;
}

.order-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.order-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
  cursor: pointer;
  transition: background var(--co-duration-fast) var(--co-ease);
}

.order-row + .order-row {
  margin-top: 8px;
}

.order-row:active {
  background: var(--co-bg-3);
}

.order-main {
  min-width: 0;
  flex: 1;
}

.order-line {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* ★ 倒计时用 warning 色而不是 danger：还没超时，「快到了」和「已经坏了」
     是两种情绪，用红色喊会让人以为订单已经出了问题。 */
.countdown {
  font-size: 11.5px;
  color: var(--co-warning-strong);
}

.order-no {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--co-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-sub {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--co-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

/* ★ 卡片本身已经能点（整行跳转），所以按钮只做视觉锚点，不再放大：
     一个 44px 的大按钮会把「进行中」这一块撑得很空。 */
.order-btn {
  height: 28px;
  padding: 0 12px;
  font-size: 12.5px;
}

/* ══ ④ 站点 ════════════════════════════════════════════════════════════ */

.stations :deep(.search) {
  padding: 0 0 10px;
}

.city-row {
  display: flex;
  gap: 8px;
  padding: 0 0 10px;
  overflow-x: auto;
  /* ★ 横向滚动的按钮排在 iOS 上会触发整页橡皮筋，这里关掉滚动链 */
  overscroll-behavior-x: contain;
  scrollbar-width: none;
}

.city-row::-webkit-scrollbar {
  display: none;
}

.chip {
  flex-shrink: 0;
  padding: 5px 12px;
  border: 1px solid var(--co-border);
  border-radius: 999px;
  background: var(--co-bg-card);
  color: var(--co-text-2);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition:
    background var(--co-duration-fast) var(--co-ease),
    transform var(--co-duration-fast) var(--co-ease);
}

.chip:active {
  transform: scale(0.95);
}

/* ★ 选中的筛选条件用「浅底 + 同色深字」而不是实心主色块 ——
   实心块会把一整排筛选器变成页面最重的东西，而它只是导航。 */
.chip.active {
  border-color: var(--co-primary-weak);
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-weight: 600;
}

.station-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.station-list li + li {
  margin-top: 10px;
}

.station {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  padding: 13px 14px;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: transform var(--co-duration-fast) var(--co-ease);
}

.station:active {
  transform: scale(0.985);
}

.s-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.s-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-operator {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-3);
  font-size: 11px;
}

.s-addr {
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--co-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.s-hours {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  color: var(--co-text-3);
}

/* ★ 空闲枪数是这张卡上**唯一决定去不去**的信息，所以给它颜色：
   有闲枪 = 绿色（浅底同色深字），没闲枪 = 灰掉并明说「暂无空闲」。
   只把数字变灰是不够的 —— 用户会以为是没加载出来。 */
.s-guns {
  margin-left: auto;
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: var(--co-radius-sm);
  background: var(--co-success-weak);
  color: var(--co-success-strong);
  font-size: 12px;
  font-weight: 600;
}

.s-guns.is-none {
  background: var(--co-info-weak);
  color: var(--co-text-3);
  font-weight: 400;
}
</style>
