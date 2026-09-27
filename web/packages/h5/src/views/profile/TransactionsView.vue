<script setup lang="ts">
/**
 * 余额明细（流水）。
 *
 * ★★ 一、**没有**按类型 / 时间筛选，所以页面上一个筛选器都不放 ★★
 *   后端 `WalletTransactionPageQueryDTO` 只有 `pageNum` / `pageSize`
 *   （见 `WalletTransactionPageQuery` 的定义）。做一个「只看充值」的下拉，
 *   要么点了没反应，要么在前端过滤**已经加载的那几页** ——
 *   后者会让用户以为「这个月只充了 2 次」，而真实数据在没加载的页里。
 *   → 不如把这件事写在页面顶上：暂时筛不了，往下滑就有全部记录。
 *
 * ★★ 二、颜色只看 `amount` 的**符号**，文字标签看 `type` ★★
 *   已核实（`WalletServiceImpl`）：冻结/扣款写进去的是 `amount.negate()`（**负数**），
 *   充值/退款写正数。而这个枚举**没有 direction 字段** ——
 *   从 `type` 反推收支，迟早会在这两个类型上反掉：
 *     · `2 冻结` 是负数，但它**不动可用余额**（动的是 frozen_amount）
 *     · `7 管理员调账` 可正可负，取决于管理员干了什么
 *   所以：`MoneyText` 的 `signed` 管颜色，`WALLET_TXN_TYPE.label()` 管文案。
 *
 * ★ 冻结/解冻（`isBalanceAffecting() === false`）**弱化显示**：
 *   它们让「余额」看起来被扣了两次又加回来，其实可用余额一分没动。
 *   不做视觉区分的话，用户对账时会以为钱进出过两次。
 */

import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  WALLET_TXN_TYPE,
  formatMoney,
  formatServerTime,
  isBalanceAffecting,
  type WalletTransactionVO,
} from '@chargeops/shared'
import { pageTransactions } from '@/api/wallet'
import { usePagedList } from '@/composables/usePagedList'
import MoneyText from '@/components/MoneyText.vue'
import PageState from '@/components/PageState.vue'

const router = useRouter()

const { rows, loading, firstLoading, finished, error, loadMore, refresh } =
  usePagedList<WalletTransactionVO>({ fetch: pageTransactions })

// ★★ 必须在 setup 里**同步**发起首页请求，不能等 `onMounted` ★★
//   `firstLoading` 只在 `refresh()` 内部被置位，晚一帧调用的话，
//   首屏会先闪一下「暂无记录」的空态再变成列表 —— 对账页面尤其刺眼。
void refresh()

const refreshing = ref(false)

async function onRefresh(): Promise<void> {
  await refresh()
  refreshing.value = false
}

/** 流水时间。`createTime` 后端必然给值，解析不出来时显示 `--` 而不是空白。 */
function timeText(row: WalletTransactionVO): string {
  const text = formatServerTime(row.createTime)
  // 秒级时间在这里没有意义（一天几十条流水），截到分钟更省横向空间
  return text ? text.slice(0, 16) : '--'
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="余额明细" left-arrow @click-left="router.back()" />

    <p class="note">
      只支持按时间倒序翻页，暂不支持按类型筛选。
      冻结、解冻不会改变可用余额，只是订单预授权的占用与释放。
    </p>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <!-- 首屏三态：都在 `van-list` **外面**，避免空态和「加载更多」缠在一起 -->
      <PageState v-if="firstLoading" kind="loading" />

      <PageState
        v-else-if="error && rows.length === 0"
        kind="error"
        description="流水没加载出来。金额以服务端记录为准，这次失败不会影响余额。"
        @retry="refresh()"
      />

      <PageState
        v-else-if="rows.length === 0"
        kind="empty"
        title="还没有余额流水"
        description="充值、消费、退款都会在这里留下记录。"
      />

      <template v-else>
        <article
          v-for="row in rows"
          :key="row.transactionNo"
          class="row"
          :class="{ muted: !isBalanceAffecting(row.type) }"
        >
          <div class="row-head">
            <span class="type">{{ WALLET_TXN_TYPE.label(row.type) }}</span>
            <MoneyText :value="row.amount" signed show-plus size="md" />
          </div>

          <div class="row-foot">
            <span class="time co-num">{{ timeText(row) }}</span>
            <!-- ★ 记下「这一笔之后余额是多少」：这是流水的意义所在，
                 只有金额的话，用户没法核对某一时点的余额。 -->
            <span class="after co-num">余额 ¥{{ formatMoney(row.balanceAfter) }}</span>
          </div>

          <p v-if="row.remark" class="remark">{{ row.remark }}</p>
        </article>

        <van-list
          v-model:loading="loading"
          :finished="finished"
          :error="error"
          finished-text="没有更多了"
          error-text="加载失败，点击重试"
          @load="loadMore"
        />
      </template>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

.note {
  margin: var(--co-gap-md) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

/* ── 流水行 ───────────────────────────────────────────────────────── */
.row {
  margin: var(--co-gap-sm) var(--co-gap-md) 0;
  padding: var(--co-gap-md) var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border-light);
  border-radius: var(--co-radius-md);
}

/* ★ 冻结/解冻：降一档视觉权重。它们**不动可用余额**，
     和真正的收支画成一样重，用户会以为钱进出过两次。 */
.row.muted {
  background: var(--co-bg-2);
  box-shadow: none;
}

.row.muted .type,
.row.muted .after {
  color: var(--co-text-3);
}

.row-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--co-gap-sm);
}

.type {
  font-size: var(--co-font-md);
  font-weight: 600;
  color: var(--co-text-1);
}

.row-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--co-gap-sm);
  margin-top: 6px;
}

.time,
.after {
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.remark {
  margin: var(--co-gap-sm) 0 0;
  padding-top: var(--co-gap-sm);
  border-top: 1px dashed var(--co-border-light);
  font-size: var(--co-font-xs);
  line-height: 1.6;
  color: var(--co-text-2);
}
</style>
