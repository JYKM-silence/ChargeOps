<script setup lang="ts">
/**
 * 车主详情。
 *
 * ★★ 这是一张**组装页**，而且它必须自己说出来 ★★
 *
 *   后端**没有**「车主详情」这个端点。这一页的数据来自三个互不相干的接口：
 *     · `GET  /api/admin/member/{id}`              —— 车主档案
 *     · `GET  /api/admin/member-package?memberId=` —— 他买过的权益（**裸数组**）
 *     · `GET  /api/admin/orders?memberPhone=`      —— 他的订单（**前缀**匹配）
 *   三份数据的**口径、范围、完整度都不一样**：档案是一次查全，权益是全部但状态可能
 *   晚 30 分钟，订单是按手机号前缀收窄的。把它们并排放在一张「车主详情」的标题下，
 *   读者会默认它们是一致且完整的 —— 所以这一页顶部常驻一块组装说明，
 *   并且在每个 Tab 里各自标注自己的口径。**不要**把这些说明只在出错时才显示。
 *
 * ★★ 这一页**看不到余额**，而且是后端没有，不是没做 ★★
 *   `MemberVO` 上没有 `balance`，管理端 52 个端点里只有
 *   `POST /api/admin/wallet/{memberId}/adjust` 一个钱包接口，它**只在响应里**
 *   返回调账后的 `WalletVO`。所以这一页**一个余额数字都不画**，
 *   只给「去调账」的入口 —— 调账页是唯一能看到余额的地方（且只能看到调账后的）。
 *   画一个永远 `--` 的余额，读者只会以为是 bug；凭印象填一个数，那是编数据。
 *
 * ★★ 订单只按**手机号前缀**查，而且手机号太短时**不能发请求** ★★
 *   空串会被 `http` 层的清洗规则丢掉，而丢掉就等于**没有任何过滤** ——
 *   后端会返回全平台的订单，列有数据、分页也对、一条错都不报。
 *   所以：手机号不过 `canSearchByPhone()`（≥6 位数字）时，订单 Tab
 *   只显示说明，**不发请求**（详见 `ensureOrders`）。
 */

import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  GENDER,
  MEMBER_PACKAGE_STATUS,
  MEMBER_STATUS,
  PHONE_SEARCH_MIN_LENGTH,
  canSearchByPhone,
  formatMoney,
  formatRelative,
  formatServerTime,
  isPositive,
  parseServerTime,
  percentOf,
  resolveAssetUrl,
  type AdminOrderVO,
  type Decimal,
  type MemberPackageVO,
  type MemberVO,
} from '@chargeops/shared'
import { getMember, listMemberPackages, pageMemberOrders } from '@/api/member'
import { usePagedList } from '@/composables/usePagedList'
import PagePanel from '@/components/PagePanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'
import MoneyText from '@/components/MoneyText.vue'
import CoIcon from '@/components/CoIcon.vue'

const route = useRoute()
const router = useRouter()

/* ── 路由参数 ─────────────────────────────────────────────────────────── */

/**
 * `:id` 从 URL 来，所以它是**外部输入**：手改地址栏就能塞进 `abc`。
 * 先在这里解析成数字，非法值直接进错误态，不要让 `NaN` 一路飘到请求里 ——
 * `/api/admin/member/NaN` 会得到后端的 400/500，而错误信息对用户毫无意义。
 */
const memberId = computed<number | null>(() => {
  const raw = route.params.id
  const text = Array.isArray(raw) ? raw[0] : raw
  const n = Number(text)
  return Number.isInteger(n) && n > 0 ? n : null
})

/* ── 档案 ─────────────────────────────────────────────────────────────── */

const member = ref<MemberVO | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)

function errorText(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '请检查网络后重试。'
}

async function loadMember(): Promise<void> {
  const id = memberId.value
  if (id === null) {
    error.value = new Error('地址里的车主 id 不是正整数，无法查询。')
    return
  }
  loading.value = true
  error.value = null
  try {
    // ★ 后端查不到时返回 code:0「车主不存在」，`http` 层会抛；这里仍兜一次 null，
    //   因为「返回了空数据」和「请求失败」在 UI 上要给出不同的下一步
    const found = await getMember(id)
    if (!found) {
      error.value = new Error(`没有 id 为 ${id} 的车主，他可能已被删除。`)
      return
    }
    member.value = found
  } catch (e) {
    error.value = e
    member.value = null
  } finally {
    loading.value = false
  }
}

/** 头像走 `resolveAssetUrl` 剥掉 origin：后端返回绝对 URL，换域名就挂。 */
const avatar = computed(() => resolveAssetUrl(member.value?.avatar))

const initial = computed(() => {
  const name = member.value?.nickname?.trim()
  return (name ? name.slice(0, 1) : member.value?.phone.slice(0, 1)) || '?'
})

/** ★ 优先用后端的 `genderLabel`，为 null 时才退回本地字典。 */
const genderText = computed(() =>
  member.value ? (member.value.genderLabel ?? GENDER.label(member.value.gender)) : '-',
)

/* ── Tab ──────────────────────────────────────────────────────────────── */

type TabName = 'basic' | 'packages' | 'orders'

const activeTab = ref<TabName>('basic')

/**
 * ★ 用 `watch(activeTab)` 而不是 `el-tabs` 的 `tab-change` 事件：
 *   事件参数类型是 `TabPaneName`（`string | number` 的联合），接过来还得再收窄一次；
 *   而这个 ref 本来就是 `TabName`，watch 里直接就是收窄好的类型。
 */
watch(activeTab, (tab) => {
  // 懒加载：进页面时只查档案，“权益”和“订单”各自等人切过去再查 ——
  // 三个接口同时发会在打开页面的瞬间占满并发，而多数人只看其中一个 Tab。
  if (tab === 'packages') void ensurePackages()
  if (tab === 'orders') void ensureOrders()
})

/* ── 权益 ─────────────────────────────────────────────────────────────── */

const packages = ref<MemberPackageVO[]>([])
const packagesLoading = ref(false)
const packagesError = ref<unknown>(null)
/** 只加载一次：切来切去不该反复打同一个接口（要刷新有「刷新」按钮）。 */
const packagesLoaded = ref(false)

async function loadPackages(): Promise<void> {
  const id = memberId.value
  if (id === null) return
  packagesLoading.value = true
  packagesError.value = null
  try {
    packages.value = await listMemberPackages(id)
    packagesLoaded.value = true
  } catch (e) {
    packagesError.value = e
    packages.value = []
  } finally {
    packagesLoading.value = false
  }
}

function ensurePackages(): void {
  if (!packagesLoaded.value && !packagesLoading.value) void loadPackages()
}

/**
 * 有效期里的哨兵值。
 *
 * ★ 后端给「永久有效」写的不是 NULL 而是 `9999-12-31 23:59:59`
 *   （NULL 参与任何比较都不成立，永久权益会被 `expire_time > NOW()` 的查询漏掉）。
 *   所以「永久」要认这个前缀，否则会显示成一个 9999 年的日期。
 */
function expireText(t: string | null): string {
  if (!t) return '未设置'
  return t.startsWith('9999') ? '永久有效' : t
}

/**
 * 状态是**缓存**：`已过期` 由定时任务每 30 分钟落库一轮，
 * 在那之前 `status` 还是「生效中」。本地时钟越过了 `expireTime` 就提示一句 ——
 * 只提示，**不用来禁用任何操作**（后端作废时只看 status）。
 */
function isStaleExpired(row: MemberPackageVO): boolean {
  if (row.status !== 1) return false
  if (!row.expireTime || row.expireTime.startsWith('9999')) return false
  const at = parseServerTime(row.expireTime)
  return at !== null && at.getTime() < Date.now()
}

/**
 * 剩余额度的一行字。
 *
 * ★ 口径与「车主权益」页一致，但那边还要画进度条、判 20% 告警色，
 *   这里只要一行文本 —— 把两处抽成一个公共函数的代价是给它传一堆展示参数，
 *   而这条规则一共就四行，所以各写各的。**口径变了要同时改两个地方。**
 */
function quotaText(row: MemberPackageVO): string {
  switch (row.packageType) {
    case 1:
      return `${formatMoney(row.remainingKwh)} / ${formatMoney(row.totalKwh)} 度`
    case 2:
      return `${row.remainingMinutes ?? '--'} / ${row.totalMinutes ?? '--'} 分钟`
    case 4:
      return `${row.remainingTimes ?? '--'} / ${row.totalTimes ?? '--'} 次`
    case 3:
      return `服务费折扣 ${formatMoney(row.serviceDiscount)}`
    default:
      return `未知额度（类型 ${row.packageType}）`
  }
}

/** 剩余比例，< 20% 时这一行转 warning 色。没有「剩余/总量」概念的套餐返回 null。 */
function lowQuota(row: MemberPackageVO): boolean {
  const pairs: Array<[Decimal, Decimal]> = [
    [row.remainingKwh, row.totalKwh],
    [row.remainingMinutes, row.totalMinutes],
    [row.remainingTimes, row.totalTimes],
  ]
  for (const [remaining, total] of pairs) {
    if (remaining === null || remaining === undefined) continue
    if (total === null || total === undefined || !isPositive(total)) continue
    return percentOf(remaining, total) < 20
  }
  return false
}

/* ── 订单 ─────────────────────────────────────────────────────────────── */

interface MemberOrderFilters {
  memberPhone: string
}

const {
  rows: orders,
  total: orderTotal,
  pageNum: orderPage,
  pageSize: orderPageSize,
  loading: ordersLoading,
  loaded: ordersLoaded,
  error: ordersError,
  filters: orderFilters,
  search: searchOrders,
  reload: reloadOrders,
  goPage: goOrderPage,
} = usePagedList<AdminOrderVO, MemberOrderFilters>({
  // ★ 刻意不带近 7 天的默认区间：`memberPhone` 已经把范围收窄到一个人，
  //   再叠一个区间会**藏掉这个人的历史订单**，而进这个 Tab 十次有九次是来看历史的。
  //   （后端在这个端点上也是这么设计的，见 `api/member.ts` 的说明。）
  initial: { memberPhone: '' },
  fetch: (q) => pageMemberOrders(q),
  immediate: false,
})

/**
 * 手机号是否够长、可以发订单查询。
 *
 * ★ 这是这一页**最危险的一个判断**：`memberPhone` 传空串会被 `http` 的清洗规则
 *   丢掉，丢掉就等于**完全没有过滤** —— 后端会返回全平台的订单，
 *   而列表看起来完全正常（有数据、分页对、不报错）。
 *   所以在拿到手机号并确认它 ≥6 位数字之前，**一个请求都不发**。
 *   历史遗留的短号（注册早于手机号正则收紧）会落到这个分支，这是真实存在的。
 */
const phoneReady = computed(() => canSearchByPhone(member.value?.phone))

const ordersBlockedReason = computed(() => {
  if (!member.value) return '车主档案还没加载出来。'
  if (!phoneReady.value) {
    return `这位车主的手机号是「${member.value.phone || '空'}」，不足 ${PHONE_SEARCH_MIN_LENGTH} 位数字。订单只能按手机号前缀查，前缀太短会命中全平台订单，所以这里不发起查询。请在订单管理里按其他条件找他的订单。`
  }
  return ''
})

let ordersRequested = false

function ensureOrders(): void {
  if (ordersRequested) return
  const target = member.value
  if (!target || !phoneReady.value) return
  ordersRequested = true
  // 把手机号写进筛选条件再查。**不是**在 `initial` 里写 ——
  // `usePagedList` 的 `immediate: false` 只管首次，之后每次 search() 都会带上 filters，
  // 而档案是异步到的，写进 initial 只能是空串。
  orderFilters.memberPhone = target.phone
  void searchOrders()
}

/** 档案后到的情况：手机号到位后，如果人已经站在订单 Tab 上，补一次查询。 */
watch(member, () => {
  if (activeTab.value === 'orders') ensureOrders()
})

function openOrder(row: AdminOrderVO): void {
  void router.push(`/order/${row.id}`)
}

/* ── 页头动作 ─────────────────────────────────────────────────────────── */

/**
 * 刷新：把**已经加载过的**部分重拉一遍，没碰过的 Tab 保持不加载 ——
 * 「刷新」不应该顺手把两个没打开过的 Tab 也请求了。
 */
async function refresh(): Promise<void> {
  await loadMember()
  if (packagesLoaded.value) await loadPackages()
  if (ordersRequested) await reloadOrders()
}
</script>

<template>
  <div class="page">
    <PagePanel
      :title="member ? (member.nickname || member.phone) : `车主 #${memberId ?? '?'}`"
      subtitle="本页由车主档案、权益、订单三个接口拼装而成，三份数据的口径与完整度都不一样，各自的说明写在下面对应的 Tab 里。"
      flush
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="refresh">
          <CoIcon name="refresh" :size="14" />
          刷新
        </el-button>
        <el-button size="small" type="primary" :disabled="!member" @click="router.push(`/wallet-adjust?memberId=${memberId}`)">
          钱包调账
        </el-button>
      </template>

      <!-- ★★ 组装说明常驻显示，不是出错时才出现 ★★ -->
      <ul class="notes">
        <li>
          <b>本页看不到钱包余额。</b>后端没有「查询车主钱包」这个接口（<code>MemberVO</code> 上也没有余额字段），
          唯一能返回余额的是调账接口，而它只在<b>调账成功后</b>返回调账后的余额。
          要动钱请走右上角的「钱包调账」——<b>调账页可以看到调账后的余额</b>。
        </li>
        <li>
          <b>订单只按手机号前缀查。</b>同一手机号换绑、换号的历史订单不会出现在这里；
          手机号不足 {{ PHONE_SEARCH_MIN_LENGTH }} 位数字时不发起查询（原因见订单 Tab）。
        </li>
        <li>
          <b>三个接口各自加载。</b>任何一个失败都只影响它自己那一块，不会让整页变空 ——
          哪一块是空的、还是加载失败的，看那一块自己的提示。
        </li>
      </ul>

      <!-- ── 档案加载失败 / id 非法：整页只有这一块，因为其余两块都以它为条件 ── -->
      <EmptyState
        v-if="error"
        kind="error"
        title="没能加载车主档案"
        :description="errorText(error)"
        @retry="loadMember"
      />

      <template v-else>
        <div class="hero" v-loading="loading">
          <img v-if="avatar" class="avatar" :src="avatar" alt="" />
          <span v-else class="avatar is-fallback">{{ initial }}</span>

          <div class="hero-text">
            <div class="hero-name">
              <b>{{ member?.nickname || '未设置昵称' }}</b>
              <StatusTag
                v-if="member"
                :label="member.statusLabel ?? MEMBER_STATUS.label(member.status)"
                :tone="MEMBER_STATUS.tone(member.status)"
                size="sm"
              />
            </div>
            <div class="hero-meta co-num">
              <span>{{ member?.phone ?? '-' }}</span>
              <span class="dot">·</span>
              <span>id {{ member?.id ?? memberId ?? '-' }}</span>
              <span class="dot">·</span>
              <span>{{ genderText }}</span>
            </div>
          </div>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <!-- ── 基本信息 ───────────────────────────────────────────────── -->
          <el-tab-pane label="基本信息" name="basic">
            <dl v-if="member" class="facts">
              <div class="fact">
                <dt>手机号</dt>
                <dd class="co-num">{{ member.phone || '-' }}</dd>
              </div>
              <div class="fact">
                <dt>昵称</dt>
                <dd>{{ member.nickname || '未设置' }}</dd>
              </div>
              <div class="fact">
                <dt>性别</dt>
                <dd>{{ genderText }}</dd>
              </div>
              <div class="fact">
                <dt>账号状态</dt>
                <dd>
                  <StatusTag
                    :label="member.statusLabel ?? MEMBER_STATUS.label(member.status)"
                    :tone="MEMBER_STATUS.tone(member.status)"
                    size="sm"
                  />
                </dd>
              </div>
              <div class="fact">
                <dt>注册时间</dt>
                <dd class="co-num">{{ formatServerTime(member.createTime) ?? '-' }}</dd>
              </div>
              <div class="fact">
                <dt>最后登录</dt>
                <dd>
                  <!-- ★ 这个时间只在**签发 token** 时更新，7 天有效期内天天充电也可能停在 7 天前。
                       所以它既不是「最近活跃」，也不是「账号是否可用」的依据。 -->
                  <span class="co-num" :title="formatServerTime(member.lastLoginTime) ?? '从未登录'">
                    {{ formatRelative(member.lastLoginTime) }}
                  </span>
                  <span class="fact-note">只在签发登录凭证时更新，不代表最近活跃</span>
                </dd>
              </div>

              <!-- ★★ 余额这一格是这一页最重要的「不做」★★
                   它不是「没查到」，是后端**没有**这个数据。所以这里画的是入口，
                   不是数字：一个 `--` 的余额会被读成「加载失败」，而编一个数更糟。 -->
              <div class="fact is-wide">
                <dt>钱包余额</dt>
                <dd>
                  <span class="no-data">本页无法查看余额</span>
                  <span class="fact-note">
                    后端没有查询车主钱包的接口。调账页可以在调账成功后看到调账后的余额 ——
                    <el-button link type="primary" size="small" @click="router.push(`/wallet-adjust?memberId=${memberId}`)">
                      去调账
                    </el-button>
                  </span>
                </dd>
              </div>

              <div class="fact is-wide">
                <dt>启用 / 禁用</dt>
                <dd>
                  <span class="fact-note">
                    在「车主管理」里操作。禁用不会撤销已签发的登录凭证，
                    该车主最长还能继续用 7 天（只有资金端点会立刻拒绝）
                    —— 所以它不是「立即踢下线」。
                  </span>
                </dd>
              </div>
            </dl>
          </el-tab-pane>

          <!-- ── 权益 ───────────────────────────────────────────────────── -->
          <el-tab-pane label="权益" name="packages" lazy>
            <!-- ★ 作废是资金动作、要手打金额确认，所以**不在这里做** ——
                 这里只读，跳去权益页处理，那条「确认 + 原因 + 手打金额」的路径只有一条。 -->
            <p class="tab-note">
              权益的作废（会真的退款）请在「车主权益」页操作，本页只做只读展示。
              <el-button link type="primary" size="small" @click="router.push(`/member-package?memberId=${memberId}`)">
                去车主权益页
              </el-button>
            </p>

            <el-table v-loading="packagesLoading" :data="packages" row-key="id" size="small">
              <el-table-column label="套餐" min-width="180">
                <template #default="{ row }">
                  <div class="pkg">
                    <span class="pkg-name">{{ row.packageName }}</span>
                    <span v-if="row.remark" class="pkg-sub">备注：{{ row.remark }}</span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="状态" width="150">
                <template #default="{ row }">
                  <div class="state-cell">
                    <StatusTag
                      :label="row.statusLabel ?? MEMBER_PACKAGE_STATUS.label(row.status)"
                      :tone="MEMBER_PACKAGE_STATUS.tone(row.status)"
                      size="sm"
                    />
                    <span v-if="isStaleExpired(row)" class="stale">已过期，状态待刷新</span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="剩余额度" min-width="150">
                <template #default="{ row }">
                  <!-- ★ 剩余不足 20% 转 warning 色（与权益页同一个阈值） -->
                  <span class="co-num quota" :class="{ 'is-low': lowQuota(row) }">
                    {{ quotaText(row) }}
                  </span>
                </template>
              </el-table-column>

              <el-table-column label="有效期" width="190">
                <template #default="{ row }">
                  <span class="co-num validity">
                    {{ formatServerTime(row.effectiveFrom) ?? '未设置' }} → {{ expireText(row.expireTime) }}
                  </span>
                </template>
              </el-table-column>

              <el-table-column label="实付" width="100" align="right">
                <template #default="{ row }">
                  <MoneyText :value="row.payAmount" size="sm" :grouping="false" />
                </template>
              </el-table-column>

              <template #empty>
                <EmptyState
                  v-if="packagesError"
                  kind="error"
                  title="权益加载失败"
                  :description="errorText(packagesError)"
                  @retry="loadPackages"
                />
                <EmptyState
                  v-else
                  kind="empty"
                  title="这位车主还没有买过套餐"
                  description="权益只能由车主在车主端购买产生，管理端不提供新建。"
                />
              </template>
            </el-table>
          </el-tab-pane>

          <!-- ── 订单 ───────────────────────────────────────────────────── -->
          <el-tab-pane label="订单" name="orders" lazy>
            <!-- ★ 手机号不够长：**显示说明而不是发请求**（见 ensureOrders 的注释） -->
            <p v-if="ordersBlockedReason" class="tab-note is-warn">
              <CoIcon name="warning" :size="13" />
              {{ ordersBlockedReason }}
              <el-button link type="primary" size="small" @click="router.push('/order')">
                去订单管理
              </el-button>
            </p>

            <template v-else>
              <p class="tab-note">
                按手机号 <span class="co-num">{{ member?.phone }}</span> 前缀匹配，
                <b>不含时间范围</b>（后端在这个端点上刻意不加默认 7 天区间，所以历史订单都在这里）。
              </p>

              <el-table
                v-loading="ordersLoading"
                :data="orders"
                row-key="id"
                size="small"
                @row-dblclick="openOrder"
              >
                <el-table-column label="订单号" min-width="170">
                  <template #default="{ row }">
                    <button class="link" type="button" @click="openOrder(row)">
                      {{ row.orderNo }}
                    </button>
                  </template>
                </el-table-column>

                <el-table-column label="状态" width="130">
                  <template #default="{ row }">
                    <OrderStatusTag :status="row.status" />
                  </template>
                </el-table-column>

                <el-table-column prop="stationName" label="站点" min-width="120" show-overflow-tooltip />

                <el-table-column label="电量" width="100" align="right">
                  <template #default="{ row }">
                    <span class="co-num">
                      {{ row.totalEnergyKwh ?? '--' }}<i v-if="row.totalEnergyKwh"> kWh</i>
                    </span>
                  </template>
                </el-table-column>

                <el-table-column label="应付" width="100" align="right">
                  <template #default="{ row }">
                    <MoneyText :value="row.payAmount" size="sm" />
                  </template>
                </el-table-column>

                <el-table-column label="建单时间" width="160">
                  <template #default="{ row }">
                    <span class="co-num">{{ formatServerTime(row.createTime) ?? '-' }}</span>
                  </template>
                </el-table-column>

                <template #empty>
                  <EmptyState
                    v-if="ordersLoaded && ordersError"
                    kind="error"
                    title="订单加载失败"
                    :description="errorText(ordersError)"
                    @retry="reloadOrders"
                  />
                  <EmptyState
                    v-else-if="ordersLoaded"
                    kind="empty"
                    title="这个手机号名下没有订单"
                    description="订单按手机号前缀匹配：如果这位车主换过手机号，旧号下的订单不在这个列表里。"
                  />
                </template>
              </el-table>

              <div v-if="orderTotal > 0" class="pager">
                <el-pagination
                  :current-page="orderPage"
                  :page-size="orderPageSize"
                  :total="orderTotal"
                  :pager-count="7"
                  layout="total, prev, pager, next"
                  background
                  @current-change="goOrderPage"
                />
              </div>
            </template>
          </el-tab-pane>
        </el-tabs>
      </template>
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
  margin: 0;
  padding: 10px 18px 10px 34px;
  border-bottom: 1px solid var(--co-border-light);
  background: var(--co-bg-2);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--co-text-3);
}

.notes li + li {
  margin-top: 3px;
}

.notes b {
  color: var(--co-text-2);
  font-weight: 600;
}

/* 行内字段名用浅灰底，别用浏览器默认的等宽字体（和正文字体不是一套） */
.notes code {
  padding: 1px 5px;
  border-radius: var(--co-radius-sm);
  background: var(--co-bg-3);
  color: var(--co-text-2);
  font-size: 12px;
}

.hero {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px 14px;
}

.avatar {
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  border-radius: 50%;
  object-fit: cover;
  background: var(--co-bg-3);
}

.avatar.is-fallback {
  display: grid;
  place-items: center;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: 18px;
  font-weight: 600;
}

.hero-text {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.hero-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hero-name b {
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.hero-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--co-text-3);
}

.hero-meta .dot {
  color: var(--co-text-4);
}

.detail-tabs {
  padding: 0 18px 4px;
}

/* 信息表：两列网格。用 dl/dt/dd 而不是表格 —— 它是描述，不是数据网格。 */
.facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 24px;
  margin: 4px 0 8px;
}

.fact {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--co-border-light);
  font-size: 13px;
}

.fact.is-wide {
  grid-column: 1 / -1;
}

.fact dt {
  flex-shrink: 0;
  width: 84px;
  color: var(--co-text-3);
  font-size: 12.5px;
  line-height: 1.7;
}

.fact dd {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin: 0;
  min-width: 0;
  color: var(--co-text-1);
  line-height: 1.7;
}

.fact-note {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--co-text-3);
}

/* 「看不到」和「是 0」必须在视觉上就不一样 */
.no-data {
  color: var(--co-text-4);
  font-style: normal;
}

.tab-note {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0 0 10px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--co-text-3);
}

.tab-note.is-warn {
  padding: 9px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
}

.tab-note b {
  color: var(--co-text-2);
  font-weight: 600;
}

.pkg {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.pkg-name {
  font-size: 13px;
  color: var(--co-text-1);
}

.pkg-sub {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.state-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.stale {
  font-size: 11px;
  color: var(--co-warning-strong);
}

.quota {
  font-size: 12.5px;
  color: var(--co-text-2);
}

.quota.is-low {
  color: var(--co-warning-strong);
}

.validity {
  font-size: 12px;
  color: var(--co-text-2);
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--co-primary);
  font-family: inherit;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-align: left;
  cursor: pointer;
}

.link:hover {
  text-decoration: underline;
}

.co-num {
  font-variant-numeric: tabular-nums;
}

.co-num i {
  font-style: normal;
  margin-left: 2px;
  font-size: 11px;
  color: var(--co-text-3);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 4px;
}
</style>
