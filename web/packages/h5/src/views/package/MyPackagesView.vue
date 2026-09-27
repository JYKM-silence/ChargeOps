<script setup lang="ts">
/**
 * 「我的套餐」（Tab 页）= 我的权益列表。
 *
 * ★★ 一、为什么必须同时显示 `expireTime`，不能只信 `status` ★★
 *
 *   权益过期是**定时任务**扫出来的（`MemberPackageExpireJob`，30 分钟一轮），
 *   后端的 `status` 是**缓存**、`expire_time` 才是真相（见 `MemberPackageStatus`
 *   的类注释）。所以存在一个最长 30 分钟的状态：
 *
 *     已经过了有效期，但 `status` 还是 `1 生效中`
 *
 *   只渲染 `status` 的后果是：用户看着一个「生效中」的套餐去充电，
 *   结算时后端按 `expire_time > now()` 判定它不可用 —— 他一眼看上去
 *   什么事都没有，却什么都用不了。
 *
 *   → 两道保险：① 卡片上**永远**打出有效期；② 客户端拿 `expire_time` 和当前
 *     时间比一次，自己先标「已到期（待系统确认）」。措辞刻意带「待系统确认」：
 *     状态确实还没落库，说「已过期」会让人以为界面和后端不一致。
 *
 * ★★ 二、为什么这一页**不**自己排序 ★★
 *   后端 `listByMember` 是 `ORDER BY status, expire_time, id` —— 生效中的在前、
 *   快到期的在前。这个顺序是有意义的（用户最关心「哪份快到期了」），
 *   前端重排（比如按购买时间倒序）会把快过期的那份埋进列表中间。
 *
 * ★★ 三、为什么「已作废」和「已用尽」必须长得不一样 ★★
 *   两者都是灰的、都不能用，但**原因完全不同**：用尽=自己用完了（正常），
 *   作废=被管理端作废并退了钱（异常，且用户可能正因为「钱到哪去了」才来看这一页）。
 *   只靠一个灰色标签区分，用户会以为作废是「用完了」。
 */

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  MEMBER_PACKAGE_STATUS,
  PRICE_SCALE,
  formatKwhLabel,
  formatServerTime,
  fromScaled,
  isPositive,
  parseServerTime,
  percentOf,
  toScaled,
  type Decimal,
  type MemberPackageVO,
  type Tone,
} from '@chargeops/shared'
import { listMyPackages } from '@/api/package'
import { useAsyncData } from '@/composables/useAsyncData'
import PageState from '@/components/PageState.vue'

/** 剩余额度低于这个百分比就转 warning 色。 */
const LOW_QUOTA_PERCENT = 20

const router = useRouter()

const { data, loading, failed, reload } = useAsyncData<MemberPackageVO[]>(listMyPackages)

interface PackageCard {
  vo: MemberPackageVO
  /** 已作废：删除线 + 降饱和，视觉上与「已用尽」拉开距离。 */
  voided: boolean
  /** 客户端按 `expire_time` 先判出来的「已到期」，用于补一行解释。 */
  expiredByClock: boolean
  /** 状态标签的文案与色调（客户端判定的过期会用合成文案覆盖）。 */
  label: string
  tone: Tone
  /** 剩余额度百分比；`null` = 这个套餐类型没有「总量/剩余」这回事。 */
  percent: number | null
  /** 剩余额度文案（`剩余 120.00 度 / 150.00 度`）。 */
  quotaText: string
  /** 有效期文案。后端「永久有效」的哨兵值是 9999-12-31，不是 null。 */
  expireText: string
}

/**
 * 把服务费折扣（`0.8000` 表示八折）显示成 `8.0 折`。
 *
 * ★ 走 BigInt 缩放而不是 `Number(x) * 10`：本文件里 `0.8 * 10` 恰好是 8，
 *   但 `0.29 * 10` 是 `2.8999999999999995` —— 这类值一旦被格式化就会
 *   显示成「2.9 折」，而它和后端存的 0.29 并不是一回事。
 *   `toScaled(x, 4) / 100n` 等价于 `round(x * 100)`，再按 1 位小数输出就是 10x。
 */
function foldText(discount: Decimal): string {
  const hundredths = (toScaled(discount, PRICE_SCALE) + 50n) / 100n
  return `${fromScaled(hundredths, 1)} 折`
}

function toCard(vo: MemberPackageVO, now: number): PackageCard {
  const voided = vo.status === 4

  // ★ 有效期：先算出来，它同时用于展示和「客户端先标过期」这个判断，
  //   两处各算一遍的话迟早会不一致。
  const expire = parseServerTime(vo.expireTime)
  const permanent = expire !== null && expire.getFullYear() >= 9000
  const serverTime = formatServerTime(vo.expireTime)
  const expireText = permanent
    ? '永久有效'
    : serverTime
      ? `有效期至 ${serverTime.slice(0, 16)}`
      : '有效期未知'

  // ★ `expire_time` 是**不含**的边界（过期时间点本身已经不可用），
  //   所以判据是 `now >= expireTime`，不是 `>`。
  const expiredByClock = vo.status === 1 && expire !== null && expire.getTime() <= now

  let label: string
  let tone: Tone
  if (expiredByClock) {
    // 合成状态：文案里带「待系统确认」，因为库里确实还写着「生效中」——
    // 直接说「已过期」会让用户以为界面和后端不一致。
    label = '已到期（待系统确认）'
    tone = 'warning'
  } else {
    label = MEMBER_PACKAGE_STATUS.label(vo.status)
    tone = MEMBER_PACKAGE_STATUS.tone(vo.status)
  }

  // ★ 分支一律按 `packageType` 的数字走，**不按 `packageTypeLabel`** ——
  //   后端改一次文案（「服务费折扣包」→「服务费折扣」）就会静默改掉一个业务分支。
  // ★ `percentOf(a, b)` 算的是 `a / b`，这里要的是「剩余占比」，
  //   所以第一个参数传**剩余量**而不是「已用量」—— 别被它的参数名带偏。
  let percent: number | null = null
  let quotaText: string
  switch (vo.packageType) {
    case 1: {
      const total = vo.totalKwh
      quotaText = `剩余 ${formatKwhLabel(vo.remainingKwh)} / ${formatKwhLabel(total)}`
      // `percentOf` 在 total <= 0 时返回 0，而「总量 0」和「剩余 0%」是两件事，
      // 所以先自己判一次总量，再决定要不要画进度条。
      percent = isPositive(total) ? percentOf(vo.remainingKwh, total) : null
      break
    }
    case 2: {
      const total = vo.totalMinutes
      quotaText = `剩余 ${vo.remainingMinutes ?? 0} 分钟 / ${total ?? 0} 分钟`
      percent = total !== null && total > 0 ? percentOf(vo.remainingMinutes, total) : null
      break
    }
    case 3:
      // 服务费折扣包**没有**「总量/剩余」，只有折扣本身（后端七列里六列是 null）。
      quotaText = `服务费 ${foldText(vo.serviceDiscount)}`
      percent = null
      break
    case 4: {
      const total = vo.totalTimes
      quotaText = `剩余 ${vo.remainingTimes ?? 0} 次 / ${total ?? 0} 次`
      percent = total !== null && total > 0 ? percentOf(vo.remainingTimes, total) : null
      break
    }
    default:
      // 后端将来加了新的套餐类型：不猜、也不显示成 0 —— 显示原始码，
      // 让「加了一个没适配的类型」立刻可见，而不是变成一个空白的卡片。
      quotaText = `未知套餐类型（${vo.packageType}）`
      break
  }

  return { vo, voided, expiredByClock, label, tone, percent, quotaText, expireText }
}

const cards = computed<PackageCard[]>(() => {
  // ★ `now` 只取一次：这一页的生命周期是秒级，而真正的状态变更由后端定时任务
  //   写库。搞一个每秒自增的「已过期」标记会让人误以为前端在改数据。
  const now = Date.now()
  return (data.value ?? []).map((vo) => toCard(vo, now))
})

function gotoBuy(): void {
  void router.push({ name: 'available-packages' })
}
</script>

<template>
  <div class="page page-with-tabbar">
    <header class="head">
      <h1>我的套餐</h1>
      <button type="button" class="buy" @click="gotoBuy">购买套餐</button>
    </header>

    <PageState v-if="loading && cards.length === 0" kind="loading" />

    <PageState
      v-else-if="failed && cards.length === 0"
      kind="error"
      description="套餐列表没加载出来。已购的权益不会因为这次失败而消失。"
      @retry="reload()"
    />

    <PageState
      v-else-if="cards.length === 0"
      kind="empty"
      title="还没有任何权益"
      description="购买充电套餐后，这里会显示剩余额度和有效期。"
    >
      <van-button size="small" round type="primary" @click="gotoBuy">去购买套餐</van-button>
    </PageState>

    <template v-else>
      <article
        v-for="card in cards"
        :key="card.vo.id"
        class="card"
        :class="{ voided: card.voided }"
      >
        <div class="card-head">
          <h2 class="name">{{ card.vo.packageName }}</h2>
          <span class="tag" :class="`tone-${card.tone}`">{{ card.label }}</span>
        </div>

        <p class="type">{{ card.vo.packageTypeLabel }}</p>

        <div v-if="card.percent !== null" class="bar" :class="{ low: card.percent < LOW_QUOTA_PERCENT }">
          <i :style="{ width: `${card.percent}%` }" />
        </div>

        <div class="card-foot">
          <span class="quota">{{ card.quotaText }}</span>
          <!-- ★ 有效期永远显示，即使 status 还是「生效中」—— 见文件头第一节。 -->
          <span class="expire co-num">{{ card.expireText }}</span>
        </div>

        <p v-if="card.expiredByClock" class="pending-note">
          已过有效期，系统状态最长 30 分钟后更新。这期间该权益已经无法抵扣。
        </p>
      </article>

      <p class="foot-note">
        已用尽、已到期、已作废的权益同样保留在这里（不会消失），方便核对。
      </p>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--co-bg-page);
}

/* ── 头部 ─────────────────────────────────────────────────────────── */
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px var(--co-gap-lg) 12px;
}

.head h1 {
  margin: 0;
  font-size: var(--co-font-xl);
  font-weight: 600;
  color: var(--co-text-1);
}

.buy {
  padding: 6px 12px;
  border: 1px solid var(--co-primary-weak);
  border-radius: 999px;
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
  font-size: var(--co-font-xs);
  font-family: inherit;
  cursor: pointer;
  transition: opacity var(--co-duration-fast) var(--co-ease);
}

.buy:active {
  opacity: 0.7;
}

/* ── 权益卡 ───────────────────────────────────────────────────────── */
.card {
  margin: var(--co-gap-md) var(--co-gap-md) 0;
  padding: var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

/**
 * ★ 已作废：删除线 + 降饱和。
 *   和「已用尽」拉开距离靠的是**两个**信号叠加（线 + 颜色），
 *   只用其中一个时，扫一眼很难分清「用完了」和「被作废退了钱」。
 */
.card.voided {
  filter: saturate(0.35);
}

.card.voided .name {
  text-decoration: line-through;
  color: var(--co-text-3);
}

.card.voided .quota {
  text-decoration: line-through;
}

.card-head {
  display: flex;
  align-items: center;
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
  margin: 4px 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

/* ── 状态标签：浅底 + 同色深字（和管理端标签同一套语义色调）─────────── */
.tag {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--co-radius-sm);
  font-size: var(--co-font-xs);
  line-height: 1.7;
  white-space: nowrap;
}

.tone-primary {
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
}

.tone-success {
  background: var(--co-success-weak);
  color: var(--co-success-strong);
}

.tone-warning {
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
}

.tone-danger {
  background: var(--co-danger-weak);
  color: var(--co-danger-strong);
}

.tone-info {
  background: var(--co-info-weak);
  color: var(--co-info-strong);
}

.tone-accent {
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
}

/* ── 剩余额度进度条 ───────────────────────────────────────────────── */
.bar {
  height: 6px;
  margin: var(--co-gap-md) 0 var(--co-gap-sm);
  border-radius: 999px;
  background: var(--co-bg-3);
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--co-primary);
  transition: width var(--co-duration) var(--co-ease);
}

/* ★ 少于 20% 转 warning：比例本身（6px 的条）在低值时几乎看不出差别，
     颜色是唯一还能传达「快没了」的信号。 */
.bar.low i {
  background: var(--co-warning);
}

.card-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--co-gap-sm);
  font-size: var(--co-font-sm);
  color: var(--co-text-2);
}

.quota {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expire {
  flex: none;
  color: var(--co-text-3);
  font-size: var(--co-font-xs);
}

.pending-note {
  margin: var(--co-gap-sm) 0 0;
  padding: 6px 8px;
  border-radius: var(--co-radius-sm);
  background: var(--co-warning-weak);
  color: var(--co-warning-strong);
  font-size: var(--co-font-xs);
  line-height: 1.6;
}

.foot-note {
  margin: var(--co-gap-lg) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}
</style>
