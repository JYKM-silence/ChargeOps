<script setup lang="ts">
/**
 * 购买套餐（子页）。
 *
 * ★★ 一、为什么必须带着 `stationId` ★★
 *
 *   套餐分两类：`station_id = 0` 的**全平台通用套餐**，和绑定了某站的**站内套餐**。
 *   后端的 `GET /package/available` 把 `stationId` 声明成 `@RequestParam Long`
 *   （**必传**，没有「不限站点」这个变体），并且明确拒绝「不传时返回全平台套餐」
 *   这种静默放宽 —— 所以**没选站点就没有可用套餐**，而不是「返回全部」。
 *   本页因此必须在没有站点时**不给列表**，只引导选站。
 *
 * ★★ 二、站点选择怎么做的，以及为什么这么定 ★★
 *
 *   两条入口都支持，不是二选一：
 *     ① `route.query.stationId` —— 从站点详情页的「购买套餐」进来时带上，
 *        直接预选。省掉「我刚在那个站点的页面，进来还要再选一次同一个站」；
 *     ② 页内选择器 —— 从「我的套餐」空态进来时没有站点，必须能就地选。
 *        没有它，从套餐页进来的用户会走进一个死胡同。
 *
 *   选完之后把 `stationId` 用 `router.replace` 写回 URL：刷新页面不用重选，
 *   而 `replace` 不产生历史记录 —— 用 `push` 的话，用户改一次站点就多一条
 *   历史，返回键会变成「在站点之间来回跳」而不是「回上一页」。
 *
 *   ★ 站点列表用 `createOptionLoader` 一次拉全（它内部会翻页翻完，见
 *     shared/http/options.ts），再在本地做关键词过滤。理由：站点是低频、
 *     小数据量的基础数据，本地过滤能做到逐字即时响应；改成每次输入都发请求
 *     反而要处理「请求顺序」和「空结果闪一下」两件事，收益为负。
 *     ★ 千万别用 `pageSize=100` 只拉一页 —— 超过 100 个站点时会**静默少站点**，
 *     而用户只会觉得「我那个站在下拉里找不到」。
 *
 * ★★ 三、购买：为什么必须二次确认 + 两层锁 ★★
 *
 *   后端这个接口**没有幂等键**（流水写的是 `order_id = NULL`，而唯一索引
 *   `uk_txn_order_type` 是 `(order_id, type)` —— 唯一索引不约束 NULL，
 *   所以对套餐购买完全不起作用）。连点两次就是**真的扣两次钱**。
 *   服务端只有一道 Redis 削峰锁，它挡的是「几乎同时到达」的那一次。
 *
 *   所以本页有三层：确认弹层（把金额、套餐名、有效期都摊开给用户看）+
 *   `useSubmitLock`（按钮级，300ms 节流）+ `withActionLock`（业务级，同 payload
 *   的第二次在冷却期内直接拒绝）。
 */

import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import {
  PRICE_SCALE,
  compareDecimal,
  createOptionLoader,
  formatKwhLabel,
  formatServerTime,
  fromScaled,
  isActionLocked,
  parseServerTime,
  subDecimal,
  toScaled,
  withActionLock,
  type AvailablePackageVO,
  type Decimal,
  type MemberPackageVO,
  type MemberStationVO,
  type Option,
  type WalletVO,
} from '@chargeops/shared'
import { http } from '@/api/http'
import { listAvailablePackages, purchasePackage } from '@/api/package'
import { getWallet } from '@/api/wallet'
import { useAsyncData, useAsyncDataOn } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import MoneyText from '@/components/MoneyText.vue'
import PageState from '@/components/PageState.vue'

/**
 * 购买动作的冷却窗口。
 *
 * ★ 3 秒（`withActionLock` 的默认值，这里显式写出来让取值可见）。
 *   它长到足以挡住「连点 / 关掉弹层立刻重开再点」，又短到不影响一个
 *   **合法**的重复购买：买两份同款套餐是被后端允许的（一个车主可能有两辆车），
 *   而用户读完成功弹层再点第二次，早就过了这个窗口。
 */
const PURCHASE_LOCK_MS = 3_000

const route = useRoute()
const router = useRouter()

const { pending, run } = useSubmitLock()

// ── 站点 ──────────────────────────────────────────────────────────────

/**
 * `?stationId=3` → `3`。
 *
 * ★ 只接受正整数：`Number('')` 是 0、`Number('abc')` 是 NaN，两者发出去
 *   都会变成一个后端看不懂的 stationId（0 甚至可能是合法站点 id）。
 *   解析不出来就当没传，让用户自己选 —— 比发一个错请求好。
 */
function readQueryStationId(): number | null {
  const raw = route.query.stationId
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || value.trim() === '') return null
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

const stationId = ref<number | null>(readQueryStationId())

const stationLoader = createOptionLoader<MemberStationVO>(http, {
  key: 'h5-member-station',
  tags: ['station'],
  url: '/api/member/station/page',
  labelKey: 'name',
  hintKey: 'address',
})

const stations = ref<Option<number>[]>([])
const stationLoading = ref(false)
const stationFailed = ref(false)

async function loadStations(): Promise<void> {
  stationLoading.value = true
  stationFailed.value = false
  try {
    stations.value = await stationLoader.get()
  } catch {
    // ★ 这里的失败**不能**冒泡：站点拉不到时，只要 URL 带了 stationId，
    //   套餐照样能加载。把整页变成错误态会把一个可用页面变成不可用。
    stationFailed.value = true
  } finally {
    stationLoading.value = false
  }
}

/** 过滤用的关键词。本地过滤，不发请求 —— 见文件头第二节。 */
const keyword = ref('')
const pickerVisible = ref(false)

const filteredStations = computed<Option<number>[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return stations.value
  return stations.value.filter((s) => {
    const hay = `${s.label} ${s.hint ?? ''}`.toLowerCase()
    return hay.includes(kw)
  })
})

/**
 * 已选站点的显示名。
 *
 * ★ 站点列表还没回来时退化成 `站点 #3` 而不是留空：留空的话，一个带了
 *   `?stationId=` 的页面在首屏看起来就像「什么都没选」，用户会去重点一次。
 */
const selectedStationLabel = computed(() => {
  if (stationId.value === null) return '请选择'
  const hit = stations.value.find((s) => s.value === stationId.value)
  return hit ? hit.label : `站点 #${stationId.value}`
})

function openPicker(): void {
  pickerVisible.value = true
  // ★ 懒加载：没打开过就不请求站点列表（从站点详情页带 query 进来的用户
  //   可能压根不需要这个选择器）。
  if (stations.value.length === 0 && !stationLoading.value) void loadStations()
}

function chooseStation(id: number): void {
  stationId.value = id
  pickerVisible.value = false
  keyword.value = ''
  // 写回 URL：刷新后不用重选。用 replace，理由见文件头第二节。
  void router.replace({ name: 'available-packages', query: { stationId: String(id) } })
}

// ── 可用套餐 ──────────────────────────────────────────────────────────

/**
 * ★ `stationId` 一变就重新拉（`useAsyncDataOn` 内部有 seq 竞态保护）：
 *   快速连切两个站点时，先发的那个响应可能后到，没有保护就会显示成
 *   「选的是 A 站，列表是 B 站的套餐」。
 */
const {
  data: packages,
  loading: packagesLoading,
  failed: packagesFailed,
  reload: reloadPackages,
} = useAsyncDataOn(stationId, (id) => (id === null ? Promise.resolve(null) : listAvailablePackages(id)))

const list = computed<AvailablePackageVO[]>(() => packages.value ?? [])

/** 正在二次确认的套餐 / 刚买到手的权益。两个弹层的唯一数据源。 */
const confirmTarget = ref<AvailablePackageVO | null>(null)
const purchased = ref<MemberPackageVO | null>(null)

// ── 钱包（算「还差多少钱」用）────────────────────────────────────────

const { data: wallet, reload: reloadWallet } = useAsyncData<WalletVO>(getWallet)

const balance = computed<Decimal>(() => wallet.value?.balance ?? null)

/** 还差多少钱。`null` = 余额够（或还没打开确认弹层）。 */
const shortfall = computed<string | null>(() => {
  const target = confirmTarget.value
  if (!target || wallet.value === null) return null
  if (compareDecimal(wallet.value.balance, target.price) >= 0) return null
  // ★ 精确减法（内部 BigInt），不是 `Number(a) - Number(b)` ——
  //   浮点算出来的差额会带着 0.0000001，显示给用户就是「还差 ¥0.01」这种错数。
  return subDecimal(target.price, wallet.value.balance)
})

/**
 * 服务费折扣（`0.8000` = 八折）→ `8.0 折`。
 *
 * ★ 走 BigInt 缩放而不是 `Number(x) * 10`：`0.29 * 10` 是 `2.8999999999999995`，
 *   格式化后会显示成「2.9 折」，和后端存的 0.29 不是一回事。
 * ★ 与 `MyPackagesView` 里那份是同一段逻辑。shared 没有折扣格式化器
 *   （`money.ts` 只认金额和单价），真要复用应当提到 shared —— 那要改共享包，
 *   本次不动，两处各自保留一份并保持同步。
 */
function foldText(discount: Decimal): string {
  const hundredths = (toScaled(discount, PRICE_SCALE) + 50n) / 100n
  return `${fromScaled(hundredths, 1)} 折`
}

/** 一行规格摘要：买之前用户唯一关心的是「我买到多少东西」。 */
function specText(vo: AvailablePackageVO): string {
  switch (vo.packageType) {
    case 1:
      return formatKwhLabel(vo.energyKwh)
    case 2:
      return `${vo.durationMinutes ?? 0} 分钟`
    case 3:
      return `服务费 ${foldText(vo.serviceDiscount)}`
    case 4:
      return `${vo.times ?? 0} 次`
    default:
      return vo.packageTypeLabel
  }
}

/** 有效期。后端 `validDays = 0` 表示**永久有效**，不是「0 天」。 */
function validText(vo: AvailablePackageVO): string {
  return vo.validDays > 0 ? `购买后 ${vo.validDays} 天内有效` : '永久有效'
}

/**
 * 权益的绝对到期时刻。后端「永久有效」的哨兵值是 `9999-12-31 23:59:59`，
 * 直接显示出来很难看，所以按年份判一下。
 */
function expireText(vo: MemberPackageVO): string {
  // ★ 走 `parseServerTime` 而不是自己 `slice(0, 4)` 取年份：解析器认得后端的
  //   时间格式（含 `2026-09-21T10:00:00+08:00` 这种带时区的写法），
  //   手切字符串在带时区的输入上会取到**前一天/后一天**的日期。
  const at = parseServerTime(vo.expireTime)
  if (at !== null && at.getFullYear() >= 9000) return '永久有效'
  const text = formatServerTime(vo.expireTime)
  return text ? text.slice(0, 16) : '—'
}

/** 打折前的价格，只有真的更贵时才显示（否则是一条没有信息量的划线）。 */
function showOrigin(vo: AvailablePackageVO): boolean {
  return vo.originPrice !== null && compareDecimal(vo.originPrice, vo.price) > 0
}

// ── 购买 ──────────────────────────────────────────────────────────────

function openConfirm(vo: AvailablePackageVO): void {
  confirmTarget.value = vo
}

async function onConfirmPurchase(): Promise<void> {
  const target = confirmTarget.value
  // ★ 余额不足时按钮本来就是禁用的，这里再挡一次不是冗余：弹层里的
  //   `shortfall` 是响应式的，而余额可能在弹层打开的这几秒里被另一台设备花掉。
  if (!target || shortfall.value !== null) return

  const outcome = await run(() =>
    withActionLock(
      // ★ 锁 key 带 payload（套餐 id）：同款套餐的第一次和第二次是**同一个**
      //   动作（要互斥），而换一个套餐买是另一个动作（不该被挡）。
      `package-purchase:${target.id}`,
      () => purchasePackage(target.id),
      PURCHASE_LOCK_MS,
    ),
  )

  if (outcome.status === 'skipped') return

  if (outcome.status === 'failed') {
    // ★ `ActionLockedError` 在 http 层是**静默**的（`shouldStaySilent`），
    //   不在这里说一句，用户看到的就是「点了没反应」。
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  confirmTarget.value = null

  const vo = outcome.value
  if (!vo) {
    // `http.post` 的返回类型允许 null（code:1 但 data 为空）。钱可能已经扣了，
    // 所以**不能说"失败了"** —— 让用户去「我的套餐」看事实。
    showToast('已提交，但未返回权益信息，请到「我的套餐」确认')
    await reloadWallet()
    return
  }

  purchased.value = vo
  // ★ 扣的是余额，所以余额必须刷新。购买接口只返回新权益、不返回钱包，
  //   只能再 GET 一次（这一步的代价远小于「余额显示成旧值」）。
  await reloadWallet()
  showSuccessToast('购买成功')
}

function gotoMine(): void {
  void router.push({ name: 'my-packages' })
}

function gotoRecharge(): void {
  confirmTarget.value = null
  void router.push({ name: 'wallet' })
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="购买套餐" left-arrow @click-left="router.back()" />

    <!-- ── 站点选择 ─────────────────────────────────────────────────── -->
    <van-cell-group inset class="station-row">
      <van-cell
        title="充电站"
        :value="selectedStationLabel"
        is-link
        @click="openPicker"
      />
    </van-cell-group>

    <p class="hint">
      套餐按站点发放：不选充电站就没有可用套餐（站内套餐只在对应站点生效）。
    </p>

    <!-- ── 套餐列表 ─────────────────────────────────────────────────── -->
    <PageState
      v-if="stationId === null"
      kind="empty"
      title="请先选择充电站"
      description="可用套餐取决于你所在的站点，选好站点后才能看到能买哪些。"
    >
      <van-button size="small" round type="primary" @click="openPicker">选择充电站</van-button>
    </PageState>

    <PageState v-else-if="packagesLoading && list.length === 0" kind="loading" />

    <PageState
      v-else-if="packagesFailed && list.length === 0"
      kind="error"
      description="没有拿到这个站点的套餐列表。"
      @retry="reloadPackages()"
    />

    <PageState
      v-else-if="list.length === 0"
      kind="empty"
      title="这个站点暂无在售套餐"
      description="换一个站点看看，或者先用余额直接充电。"
    />

    <template v-else>
      <article v-for="vo in list" :key="vo.id" class="card">
        <div class="card-head">
          <h2 class="name">{{ vo.name }}</h2>
          <span class="type">{{ vo.packageTypeLabel }}</span>
        </div>

        <p class="spec">{{ specText(vo) }}</p>
        <p class="valid">{{ validText(vo) }}</p>
        <p v-if="vo.description" class="desc">{{ vo.description }}</p>

        <div class="buy-row">
          <div class="price">
            <MoneyText :value="vo.price" size="lg" strong />
            <span v-if="showOrigin(vo)" class="origin co-num">¥{{ vo.originPrice }}</span>
          </div>
          <van-button size="small" round type="primary" @click="openConfirm(vo)">购买</van-button>
        </div>
      </article>

      <p class="foot-note">
        购买款项从账户余额扣除，不是在线的微信/支付宝支付。余额不够时先去钱包充值。
      </p>
    </template>

    <!-- ── 站点选择弹层 ─────────────────────────────────────────────── -->
    <van-popup v-model:show="pickerVisible" position="bottom" round class="picker">
      <div class="picker-head">选择充电站</div>
      <van-search v-model="keyword" placeholder="搜索站点名称或地址" />
      <div class="picker-body">
        <PageState v-if="stationLoading" kind="loading" />
        <PageState
          v-else-if="stationFailed"
          kind="error"
          description="站点列表没加载出来。"
          @retry="loadStations()"
        />
        <PageState
          v-else-if="filteredStations.length === 0"
          kind="empty"
          :title="stations.length === 0 ? '暂无可用站点' : '没有匹配的站点'"
        />
        <van-cell
          v-for="s in filteredStations"
          :key="s.value"
          :title="s.label"
          :label="s.hint"
          is-link
          :class="{ current: s.value === stationId }"
          @click="chooseStation(s.value)"
        />
      </div>
    </van-popup>

    <!-- ── 二次确认：金额、套餐名、有效期都要摊开给用户看 ───────────── -->
    <van-popup
      :show="confirmTarget !== null"
      position="bottom"
      round
      class="sheet"
      @update:show="(v: boolean) => { if (!v) confirmTarget = null }"
    >
      <template v-if="confirmTarget">
        <h3 class="sheet-title">确认购买</h3>

        <dl class="sheet-list">
          <div>
            <dt>套餐</dt>
            <dd>{{ confirmTarget.name }}</dd>
          </div>
          <div>
            <dt>类型</dt>
            <dd>{{ confirmTarget.packageTypeLabel }} · {{ specText(confirmTarget) }}</dd>
          </div>
          <div>
            <dt>有效期</dt>
            <dd>{{ validText(confirmTarget) }}</dd>
          </div>
          <div>
            <dt>应付金额</dt>
            <dd class="pay">
              <MoneyText :value="confirmTarget.price" size="lg" strong />
            </dd>
          </div>
          <div>
            <dt>当前余额</dt>
            <dd><MoneyText :value="balance" size="sm" /></dd>
          </div>
        </dl>

        <!-- 余额不足：说清**差多少**，并给一个能立刻解决的入口 -->
        <div v-if="shortfall !== null" class="shortfall">
          <p class="shortfall-text">
            余额不足，还差 <MoneyText :value="shortfall" size="sm" strong />
          </p>
          <p class="shortfall-sub">可以从钱包充值后再买（充值立即到账）。</p>
        </div>

        <p class="sheet-note">
          款项从账户余额直接扣除，成功后立即生效。
        </p>

        <div class="sheet-actions">
          <van-button block plain type="default" @click="confirmTarget = null">取消</van-button>
          <van-button
            v-if="shortfall === null"
            block
            type="primary"
            :loading="pending"
            loading-text="处理中…"
            @click="onConfirmPurchase"
          >
            确认购买
          </van-button>
          <van-button v-else block type="primary" @click="gotoRecharge">去充值</van-button>
        </div>
      </template>
    </van-popup>

    <!-- ── 成功：展示后端返回的**新权益**，并引到「我的套餐」 ───────── -->
    <van-popup
      :show="purchased !== null"
      round
      class="sheet result"
      @update:show="(v: boolean) => { if (!v) purchased = null }"
    >
      <template v-if="purchased">
        <div class="result-art">✓</div>
        <h3 class="result-title">购买成功</h3>
        <p class="result-name">{{ purchased.packageName }}</p>

        <dl class="sheet-list">
          <div>
            <dt>实付</dt>
            <dd><MoneyText :value="purchased.payAmount" size="sm" /></dd>
          </div>
          <div>
            <dt>有效期</dt>
            <dd class="co-num">{{ expireText(purchased) }}</dd>
          </div>
        </dl>

        <div class="sheet-actions">
          <van-button block plain type="default" @click="purchased = null">继续挑选</van-button>
          <van-button block type="primary" @click="gotoMine">查看我的套餐</van-button>
        </div>
      </template>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

.station-row {
  margin-top: var(--co-gap-md);
}

.hint {
  margin: var(--co-gap-sm) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.6;
  color: var(--co-text-3);
}

/* ── 套餐卡 ───────────────────────────────────────────────────────── */
.card {
  margin: var(--co-gap-md) var(--co-gap-md) 0;
  padding: var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

.card-head {
  display: flex;
  align-items: baseline;
  gap: var(--co-gap-sm);
}

.name {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--co-radius-sm);
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: var(--co-font-xs);
  line-height: 1.7;
}

.spec {
  margin: 6px 0 0;
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-primary-strong);
  font-variant-numeric: tabular-nums;
}

.valid,
.desc {
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  line-height: 1.6;
  color: var(--co-text-3);
}

.desc {
  color: var(--co-text-2);
}

.buy-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--co-gap-md);
}

.price {
  display: flex;
  align-items: baseline;
  gap: var(--co-gap-sm);
}

.origin {
  font-size: var(--co-font-xs);
  color: var(--co-text-4);
  text-decoration: line-through;
}

.foot-note {
  margin: var(--co-gap-lg) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

/* ── 站点选择器 ───────────────────────────────────────────────────── */
.picker {
  display: flex;
  flex-direction: column;
  height: 72vh;
}

.picker-head {
  padding: var(--co-gap-lg) var(--co-gap-lg) var(--co-gap-sm);
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.picker-body {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* 当前站点：浅底 + 主色字，不用边框（列表里再加边框会显得脏） */
.picker-body .current {
  background: var(--co-primary-weak);
}

.picker-body .current :deep(.van-cell__title) {
  color: var(--co-primary-strong);
  font-weight: 600;
}

/* ── 弹层（确认 / 结果）───────────────────────────────────────────── */
.sheet {
  padding: var(--co-gap-lg) var(--co-gap-lg) calc(var(--co-gap-lg) + env(safe-area-inset-bottom));
}

.sheet-title,
.result-title {
  margin: 0 0 var(--co-gap-md);
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.sheet-list {
  margin: 0;
}

.sheet-list > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--co-gap-md);
  padding: 7px 0;
  border-bottom: 1px solid var(--co-border-light);
}

.sheet-list > div:last-child {
  border-bottom: 0;
}

.sheet-list dt {
  flex: none;
  font-size: var(--co-font-sm);
  color: var(--co-text-3);
}

.sheet-list dd {
  margin: 0;
  min-width: 0;
  font-size: var(--co-font-sm);
  color: var(--co-text-1);
  text-align: right;
  word-break: break-all;
}

.pay {
  display: flex;
  justify-content: flex-end;
}

.shortfall {
  margin-top: var(--co-gap-md);
  padding: var(--co-gap-sm) var(--co-gap-md);
  border-radius: var(--co-radius-md);
  background: var(--co-danger-weak);
}

.shortfall-text {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin: 0;
  font-size: var(--co-font-sm);
  color: var(--co-danger-strong);
  font-weight: 600;
}

.shortfall-sub {
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-danger-strong);
  opacity: 0.85;
}

.sheet-note {
  margin: var(--co-gap-md) 0 0;
  font-size: var(--co-font-xs);
  line-height: 1.6;
  color: var(--co-text-3);
}

.sheet-actions {
  display: flex;
  gap: var(--co-gap-md);
  margin-top: var(--co-gap-lg);
}

/* ── 成功结果 ─────────────────────────────────────────────────────── */
.result {
  width: 82%;
  text-align: center;
}

.result-art {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  margin: 0 auto var(--co-gap-md);
  border-radius: 50%;
  background: var(--co-success-weak);
  color: var(--co-success-strong);
  font-size: 24px;
  font-weight: 700;
}

.result-name {
  margin: 0 0 var(--co-gap-sm);
  font-size: var(--co-font-md);
  color: var(--co-text-2);
}

.result .sheet-list dd,
.result .sheet-list dt {
  text-align: left;
}
</style>
