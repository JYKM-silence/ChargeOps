<script setup lang="ts">
/**
 * 我的订单（车主端 Tab 页）。
 *
 * ★★ 这一页只有三个查询参数：`pageNum` / `pageSize` / `status` ★★
 *   已核实 `OrderPageQueryDTO` 就这三个字段 —— 没有时间范围、没有站点、没有订单号。
 *   所以这里**不放**日期选择器，也不放「近 7 天 / 近 30 天」这类控件：
 *   一个点了没反应的筛选器比没有筛选器更糟（用户会以为自己的历史订单丢了）。
 *   页面用一行小字把这个限制写出来，并告诉他「往下滑就是全部历史」。
 *
 * ★★ 状态 Tab 为什么是这 5 档、而且每档只能是**一个**状态码 ★★
 *   后端 `status` 是单值（`pageByMember(memberId, status)`），没有 `statusIn`。
 *   DTO 的类注释写明了：要支持「进行中」这种跨状态的概念，得在后端加 `onlyActive`
 *   字段由服务端映射 —— 也就是说**今天做不到**。所以「进行中(1,2,3)」这种 Tab
 *   是画不出来的（画出来只能靠前端拉全量再过滤，那就是把分页搞坏）。
 *   只在 9 个状态里挑出「用户会主动来找」的几个单独成 Tab，其余靠「全部」兜住：
 *     · 全部     —— 兜底，7/8/9 这些终态只能从这里看到
 *     · 充电中   —— 最常回来确认「充到哪了」
 *     · 待支付   —— 唯一会**过期**的状态（15 分钟窗口），必须有入口
 *     · 支付失败 —— 不主动看就永远发现不了，而钱还冻着
 *     · 已完成   —— 查历史账
 *   没开 Tab 的：1 待启动 / 3 待结算都是**分钟级**的瞬时状态（等设备上报、等结算），
 *   给它们留格子会长期空着；7 已退款 / 8 已取消 / 9 异常挂账是终态或低频态，
 *   占 Tab 位不如让「全部」承担。
 */

import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ORDER_STATUS,
  canDo,
  formatKwhLabel,
  formatRelative,
  type ChargingOrderVO,
  type Decimal,
} from '@chargeops/shared'
import { pageOrders } from '@/api/order'
import { usePagedList } from '@/composables/usePagedList'
import MoneyText from '@/components/MoneyText.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import PageState from '@/components/PageState.vue'

const router = useRouter()

/** 状态筛选档位。★ 每档对应**一个**状态码，理由见文件头。 */
const TABS: readonly { name: string | number; label: string }[] = [
  { name: 'all', label: '全部' },
  { name: ORDER_STATUS.CHARGING, label: '充电中' },
  { name: ORDER_STATUS.PENDING_PAY, label: '待支付' },
  { name: ORDER_STATUS.PAY_FAILED, label: '支付失败' },
  { name: ORDER_STATUS.PAID, label: '已完成' },
]

const activeTab = ref<string | number>('all')

/**
 * 传给后端的 `status`。「全部」时**整个字段不传**，而不是传 0 ——
 * 0 不是一个合法的订单状态码，传过去只会得到一句看不懂的参数校验失败。
 */
const statusParam = computed<{ status?: number }>(() =>
  typeof activeTab.value === 'number' ? { status: activeTab.value } : {},
)

const { rows, loading, finished, error, firstLoading, loadMore, refresh, reset } =
  usePagedList<ChargingOrderVO>({
    fetch: (query) => pageOrders({ ...query, ...statusParam.value }),
  })

/** 下拉刷新。★ 用 `refresh()` 而不是 `reset()`：两者实现相同，但这里语义是「把当前筛选重来一遍」。 */
const refreshing = ref(false)
async function onRefresh(): Promise<void> {
  try {
    await refresh()
  } finally {
    // ★ 必须复位：不复位的话下拉头会一直停在「加载中」收不回去。
    refreshing.value = false
  }
}

async function onTabChange(): Promise<void> {
  // ★★ 筛选变了必须 `reset()`（清空重拉），**不能** `loadMore()` ★★
  //   后者是在「已加载的页码」上追加下一页 —— 换了筛选还用旧页码，
  //   会得到「第 2 页的待支付订单接在已完成订单后面」这种混在一起的列表。
  await reset()
}

onMounted(() => {
  // ★ 首屏走 `reset()`：composable 的 `pageNum` 初值就是 1，
  //   上来就调 `loadMore()` 会直接去拉「第 2 页」，第 1 页永远不会被加载。
  //   （`van-list` 挂载时的 immediateCheck 也调 `loadMore`，两者不冲突：
  //     composable 内部有 inFlight 守卫，同时只会有一个请求在飞。）
  void reset()
})

/**
 * 卡片右下角的主行动。
 *
 * ★★ 判定一律走规则表 `canDo(action, status, 'member')`，页面里不出现 `status === 4` ★★
 *   后端每次流转都是 `UPDATE ... WHERE status = <来源>` 的条件更新，
 *   规则表抄的就是那些来源集合。规则表改了（比如以后允许「充电中」取消），
 *   这里自动跟着变，不会漏。
 */
interface CardAction {
  text: string
  /** `link` 是「查看详情」那档：它**不是**按钮，只是卡片的点击提示，视觉上必须最轻。 */
  kind: 'primary' | 'danger' | 'link'
}

function cardAction(status: number): CardAction {
  if (canDo('pay', status, 'member')) {
    // 4 和 6 都能支付（后端 CAS 是 `status IN (4,6)`），但对用户是两件事：
    // 「去支付」是新账，「重新支付」是上次没扣成功 —— 文案不能混。
    return {
      text: status === ORDER_STATUS.PAY_FAILED ? '重新支付' : '去支付',
      kind: 'primary',
    }
  }
  if (canDo('cancel', status, 'member')) {
    return { text: '取消订单', kind: 'danger' }
  }
  // ★ 没有任何车主可执行的动作时，给「查看详情」而不是一个灰按钮：
  //   灰按钮只会被反复戳，而这里唯一能做的事本来就是进详情。
  return { text: '查看详情', kind: 'link' }
}

/**
 * 列表行 + 该行的主行动。★ 只算一次 —— 在模板里反复调 `cardAction(row.status)`
 * 既慢（每行多次）又容易让各处的判断漂移。
 */
const cards = computed(() => rows.value.map((row) => ({ row, action: cardAction(row.status) })))

/**
 * 点按钮 = 进详情页，**不在列表里直接发起资金动作**。
 *
 * ★ 支付结果客户端**预知不了**（余额够不够由后端在事务里判），而失败原因、
 *   充值入口、分段金额明细都在详情页；列表卡片只有一行摘要。
 *   把「确认金额 → 执行 → 看结果」这条链拆到两个页面，用户会在列表里点出一个
 *   自己看不懂的结果（比如扣款失败却只看到一句 toast）。列表只负责把人送到该去的地方。
 */
function openOrder(id: number): void {
  void router.push({ name: 'order-detail', params: { id } })
}

function gotoHome(): void {
  void router.push('/')
}

function showAll(): void {
  activeTab.value = 'all'
  void reset()
}

/** 只有 5 已支付 / 7 已退款 的钱才是**真的扣过**的。4/6 时 `payAmount` 只是账单上的值。 */
function isDeducted(status: number): boolean {
  return status === ORDER_STATUS.PAID || status === ORDER_STATUS.REFUNDED
}

/** 卡片金额：扣过款看实付，没扣看应付；未结算时两个都是 null（MoneyText 会显示 `--`）。 */
function amountOf(row: ChargingOrderVO): Decimal {
  return isDeducted(row.status) ? row.payAmount : row.totalAmount
}

function amountLabel(status: number): string {
  if (isDeducted(status)) return '实付'
  return status === ORDER_STATUS.PENDING_PAY || status === ORDER_STATUS.PAY_FAILED ? '应付' : '金额'
}

/** 电量：未结算时为 null，显示 `--` 而不是 `0.00 度` ——「没数据」和「确实 0 度」是两件事。 */
function energyText(row: ChargingOrderVO): string {
  return row.totalEnergyKwh === null || row.totalEnergyKwh === undefined
    ? '--'
    : formatKwhLabel(row.totalEnergyKwh)
}

const isFiltered = computed(() => typeof activeTab.value === 'number')
</script>

<template>
  <div class="orders">
    <van-nav-bar fixed placeholder title="我的订单" />

    <div class="filters">
      <!-- ★ sticky + offset-top 46：NavBar 的高度是所有页面共用的 --van-nav-bar-height，
           写死 46 与 vant-overrides.css 保持一致，让筛选条在长列表里一直看得见。 -->
      <van-tabs
        v-model:active="activeTab"
        sticky
        :offset-top="46"
        line-width="20"
        line-height="3"
        @change="onTabChange"
      >
        <van-tab v-for="t in TABS" :key="t.name" :name="t.name" :title="t.label" />
      </van-tabs>

      <!-- ★ 这行小字是刻意的：后端只有 pageNum / pageSize / status 三个参数。
           与其放一个点了没反应的日期筛选器，不如把限制明说，并给出替代动作（下滑加载）。 -->
      <p class="filter-note">
        <van-icon name="info-o" />
        <span>订单仅支持按状态筛选，历史记录请下滑加载</span>
      </p>
    </div>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <div v-if="rows.length > 0" class="cards">
        <article
          v-for="card in cards"
          :key="card.row.id"
          class="card co-card"
          role="button"
          tabindex="0"
          @click="openOrder(card.row.id)"
        >
          <header class="card-head">
            <!-- ★ 列表里永远不换行：订单号很长，截断 + 省略号比撑破卡片好 -->
            <span class="order-no co-ellipsis">{{ card.row.orderNo }}</span>
            <OrderStatusTag :status="card.row.status" />
          </header>

          <!-- ★ 后端列表 VO **没有站点名、没有枪号**（只有 stationId）——
               这里只显示拿得到的字段，不编一个「站点 12」以外的名字出来。 -->
          <p class="meta">
            <span>站点 #{{ card.row.stationId }}</span>
            <template v-if="card.row.plateNo">
              <i class="sep">·</i>
              <span>{{ card.row.plateNo }}</span>
            </template>
          </p>
          <p class="meta">
            <span>创建 {{ formatRelative(card.row.createTime) }}</span>
            <template v-if="card.row.startTime">
              <i class="sep">·</i>
              <span>开始 {{ formatRelative(card.row.startTime) }}</span>
            </template>
          </p>

          <footer class="card-foot">
            <div class="figures">
              <span class="figure">
                <i class="k">电量</i>
                <b class="v co-num">{{ energyText(card.row) }}</b>
              </span>
              <span class="figure">
                <i class="k">{{ amountLabel(card.row.status) }}</i>
                <MoneyText :value="amountOf(card.row)" strong />
              </span>
            </div>

            <van-button
              v-if="card.action.kind !== 'link'"
              class="act"
              size="small"
              round
              :type="card.action.kind === 'danger' ? 'danger' : 'primary'"
              :plain="card.action.kind === 'danger'"
              @click.stop="openOrder(card.row.id)"
            >
              {{ card.action.text }}
            </van-button>
            <span v-else class="link">查看详情 ›</span>
          </footer>
        </article>

        <!-- ★★ `van-list` 必须放在**卡片之后**，不能放在前面 ★★
             已核实 `vant/es/list/List.mjs`：触底判据是「列表内容**末尾**那个
             `.van-list__placeholder` 元素到视口底边的距离 ≤ `offset`(300px)」。
             放在卡片前面时，那个占位元素就落在视口顶部 —— 条件恒成立，
             一进页面会把每一页连着拉完，「下滑加载更多」退化成「一次性全量拉取」，
             白压后端，也让 `reset()` 的意义消失。
             ★ v-model:loading / v-model:error 双向绑定：van-list 会先把 loading 置 true
             再 emit `load`，收尾必须由我们把 loading 置回 false；
             error 同理（点错误文案重试时 van-list 会把它置回 false，然后重新触发 check）。 -->
        <van-list
          v-model:loading="loading"
          v-model:error="error"
          :finished="finished"
          finished-text="没有更多了"
          error-text="加载失败，点击重试"
          @load="loadMore"
        />
      </div>

      <!-- 三态分开：加载中 / 失败可重试 / 真的没有（且「筛选没命中」与「一条都没有」要区别对待） -->
      <PageState v-else-if="firstLoading" kind="loading" />
      <PageState v-else-if="error" kind="error" @retry="refresh" />
      <PageState
        v-else-if="isFiltered"
        kind="filtered"
        title="这个状态下还没有订单"
        description="换一个状态看看，或者看全部订单。"
      >
        <van-button size="small" round plain type="primary" @click="showAll">
          查看全部订单
        </van-button>
      </PageState>
      <PageState
        v-else
        kind="empty"
        title="还没有订单"
        description="去首页挑一个充电站，扫码就能开始充电。"
      >
        <van-button size="small" round type="primary" @click="gotoHome">去充电</van-button>
      </PageState>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.orders {
  min-height: 100vh;
  background: var(--co-bg-page);
}

.filters {
  background: var(--co-bg-card);
}

.filter-note {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 8px 14px 10px;
  border-bottom: 1px solid var(--co-border-light);
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--co-text-3);
}

.cards {
  padding: 10px 12px 16px;
}

.card {
  padding: 12px 14px;
  margin-bottom: 10px;
  /* ★ 触摸反馈走 :active —— H5 上没有 hover，只写 hover 等于没有反馈。
     同时改背景和一点点缩放：只改背景在白色卡片上几乎看不出来。 */
  transition:
    background var(--co-duration-fast) var(--co-ease),
    transform var(--co-duration-fast) var(--co-ease);
}

.card:active {
  background: var(--co-bg-2);
  transform: scale(0.995);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.order-no {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--co-text-1);
  /* ★ 订单号是用户最需要复制给客服的东西，而全局是 user-select: none（见 index.html） */
  -webkit-user-select: text;
  user-select: text;
}

.meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--co-text-3);
}

.sep {
  font-style: normal;
  color: var(--co-text-4);
}

.card-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--co-border-light);
}

.figures {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.figure {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.k {
  font-style: normal;
  font-size: 11.5px;
  color: var(--co-text-3);
}

.v {
  font-size: 13px;
  font-weight: 600;
  color: var(--co-text-1);
}

.act {
  flex: none;
}

.link {
  flex: none;
  font-size: 12.5px;
  color: var(--co-primary);
}
</style>
