<script setup lang="ts">
/**
 * 我的钱包（余额 + 充值）。
 *
 * ★★ 一、这是**模拟充值**，UI 不能演成真实支付 ★★
 *   后端 `recharge()` 没有接任何支付网关（`WalletRechargeDTO` 的注释写明），
 *   调用即到账。所以这里**不画**微信/支付宝的流程、不出现「正在跳转支付」，
 *   而是把话直接说在前面：「演示充值，立即到账，不产生真实扣款」。
 *   画了支付流程的后果不是「好看一点」，而是用户以为自己真的会被扣钱 ——
 *   演示时不敢点，或者反过来，以为真充了钱。
 *
 * ★★ 二、充值**没有幂等键**，重复提交 = 真的加两次钱 ★★
 *   钱包流水表的唯一索引是 `(order_id, type)`，而充值的 `order_id` 是 **NULL**
 *   —— MySQL 的唯一索引不约束 NULL，所以同一个请求提交两次就是两条流水、
 *   两次加钱，且**不会报错**。防重只能在前端：
 *   `useSubmitLock`（300ms 节流）+ `withActionLock`（3 秒冷却）+ 提交前的二次确认。
 *
 * ★★ 三、金额全程走「整数分」★★
 *   不碰浮点：`0.1 + 0.2` 那一类误差加在余额上是**真实的 0.01 元**，
 *   而且会被后端的 `BigDecimal` 一比一对出来。用 `toScaled/fromScaled`
 *   在「分」上做整数运算与比较。
 *
 * ★ 充值的上下限来自 `LIMITS`（抄后端 `WalletRechargeDTO` 的 `@DecimalMin/@DecimalMax`），
 *   不是自己定的 —— 前端比后端严会让合法值提交不上去，比后端松就是白跑一趟。
 */

import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import {
  LIMITS,
  formatMoney,
  fromScaled,
  isActionLocked,
  isPartialAmountInput,
  toScaled,
  withActionLock,
  type WalletVO,
} from '@chargeops/shared'
import { getWallet, recharge } from '@/api/wallet'
import { useAsyncData } from '@/composables/useAsyncData'
import { useSubmitLock } from '@/composables/useSubmitLock'
import MoneyText from '@/components/MoneyText.vue'

/** 快捷金额。★ 用字符串而不是数字：`0.1` 这种字面量一进 JS 就已经不是精确值了。 */
const QUICK_AMOUNTS = ['20', '50', '100', '200', '500', '1000']

/** 充值的动作锁窗口：3 秒，比节流（300ms）长得多，因为重复充值的代价是钱。 */
const RECHARGE_LOCK_MS = 3_000

const router = useRouter()
const { pending, run } = useSubmitLock()

const { data: wallet, failed, reload } = useAsyncData<WalletVO>(getWallet)

/** 输入框里的金额文本（用户输入的原文）。 */
const amountText = ref('')
/** 是否已经点过一次「充值」—— 空输入的报错只在点过之后才出现。 */
const submitted = ref(false)

const balance = computed(() => wallet.value?.balance)
const frozenText = computed(() => formatMoney(wallet.value?.frozenAmount))
/** 冻结为 0 时不占位置。★ 用格式化后的字符串比，`"0.0000"` 也算 0。 */
const showFrozen = computed(() => frozenText.value !== '0.00')

const amountError = computed(() => {
  const value = amountText.value.trim()
  if (value === '') return submitted.value ? '请输入充值金额' : ''
  // ★ 用「合法中间态」判断：用户输入 `50.` 时 `Number('50.')` 是 50，
  //   但输入 `.` 时是 NaN —— 两种都是**正在输入**，此时标红是错的。
  if (!isPartialAmountInput(value)) return '只能填数字，例如 50 或 50.5'
  const cents = toScaled(value, 2)
  if (cents < toScaled(LIMITS.rechargeMin, 2)) return `最低充值 ${LIMITS.rechargeMin} 元`
  if (cents > toScaled(LIMITS.rechargeMax, 2)) return `单次最多充值 ${LIMITS.rechargeMax} 元`
  return ''
})

const canSubmit = computed(() => amountText.value.trim() !== '' && amountError.value === '')

/** 归一成两位小数的元（后端要的是金额本身，不是分）。 */
const normalizedAmount = computed(() => fromScaled(toScaled(amountText.value.trim(), 2), 2))

function pickQuick(amount: string): void {
  amountText.value = amount
  submitted.value = false
}

async function onRecharge(): Promise<void> {
  submitted.value = true
  if (!canSubmit.value) return

  const amount = normalizedAmount.value
  try {
    await showConfirmDialog({
      title: '演示充值',
      message: `充值 ¥${formatMoney(amount)}，立即到账，不产生真实扣款。`,
      confirmButtonText: '确认充值',
      cancelButtonText: '取消',
    })
  } catch {
    // ★ `showConfirmDialog` 取消时是 **reject**，不是 resolve(false)。
    //   那是一次正常操作，不该冒出一条未捕获的错误。
    return
  }

  // ★ key 带上金额：`withActionLock` 的 key 不含 payload 时，
  //   用户在冷却窗口内改成另一个金额也会被拦下（「改了金额却充不了」）。
  const outcome = await run(() =>
    withActionLock(`wallet-recharge:${toScaled(amount, 2)}`, () => recharge({ amount }), RECHARGE_LOCK_MS),
  )
  if (outcome.status === 'skipped') return
  if (outcome.status === 'failed') {
    // ★ 被动作锁拦下时 http 层是**静默**的（不弹 toast），这里必须自己说一句，
    //   否则用户看到的是「点了充值，什么都没发生」。
    if (isActionLocked(outcome.error)) showToast('刚刚已提交过，请稍候几秒再试')
    return
  }

  // ★ 后端返回的是**充值后的钱包**，直接就地替换，不再多打一次 `getWallet()`：
  //   多打的那次可能比这次响应更旧（并发时有别的订单在扣款），
  //   于是余额显示会往回跳一下。
  if (outcome.value) {
    wallet.value = outcome.value
  } else {
    await reload()
  }

  amountText.value = ''
  submitted.value = false
  showSuccessToast('充值成功')
}

function go(name: string): void {
  void router.push({ name })
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="我的钱包" left-arrow @click-left="router.back()" />

    <!-- ── 余额卡 ───────────────────────────────────────────────────── -->
    <section class="balance-card">
      <p class="label">可用余额（元）</p>
      <MoneyText :value="balance" size="xl" strong />
      <p v-if="showFrozen" class="frozen co-money">冻结中 ¥{{ frozenText }}</p>
      <!-- ★ 加载失败**不清空**卡片：余额是这一页的核心数字，
           把它换成一个错误页会让人以为钱没了。旧数字 + 一行重试更好。 -->
      <p v-if="failed" class="retry" @click="reload()">余额加载失败，点这里重试</p>
    </section>

    <van-cell-group inset class="entry">
      <van-cell title="余额明细" icon="balance-list-o" is-link @click="go('wallet-transactions')" />
    </van-cell-group>

    <p class="hint">
      冻结中的钱是充电订单预先占用的金额，结算后按实际用电多退少补。
    </p>

    <!-- ── 充值 ─────────────────────────────────────────────────────── -->
    <section class="recharge">
      <h2 class="title">充值</h2>
      <p class="demo-note">演示充值，立即到账，不产生真实扣款。</p>

      <div class="quick">
        <button
          v-for="item in QUICK_AMOUNTS"
          :key="item"
          type="button"
          class="quick-item"
          :class="{ active: amountText.trim() === item }"
          @click="pickQuick(item)"
        >
          <span class="co-num">{{ item }}</span>
          <small>元</small>
        </button>
      </div>

      <van-field
        v-model="amountText"
        type="number"
        label="自定义"
        placeholder="其它金额"
        :error="amountError !== ''"
        :error-message="amountError"
        clearable
      >
        <template #button>
          <span class="unit">元</span>
        </template>
      </van-field>

      <p class="range">
        单次 {{ LIMITS.rechargeMin }} ~ {{ LIMITS.rechargeMax }} 元。
      </p>

      <div class="actions">
        <van-button
          block
          round
          type="primary"
          :loading="pending"
          :disabled="!canSubmit"
          loading-text="提交中…"
          @click="onRecharge"
        >
          充值 {{ canSubmit ? `¥${formatMoney(normalizedAmount)}` : '' }}
        </van-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding-bottom: var(--co-gap-2xl);
  background: var(--co-bg-page);
}

/* ── 余额卡 ───────────────────────────────────────────────────────── */
.balance-card {
  padding: 28px var(--co-gap-lg) var(--co-gap-xl);
  background: var(--co-bg-card);
  border-bottom: 1px solid var(--co-border-light);
}

.label {
  margin: 0 0 10px;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.frozen {
  margin: var(--co-gap-sm) 0 0;
  font-size: var(--co-font-sm);
  /* ★ 冻结不用状态色：它是「正在被订单占着」的钱，不是异常，
     给红色会让人以为账目出了问题。 */
  color: var(--co-text-2);
}

.retry {
  margin: var(--co-gap-sm) 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-warning-strong);
  cursor: pointer;
}

.entry {
  margin-top: var(--co-gap-md);
}

.hint {
  margin: var(--co-gap-sm) var(--co-gap-lg) 0;
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-text-3);
}

/* ── 充值 ─────────────────────────────────────────────────────────── */
.recharge {
  margin: var(--co-gap-lg) var(--co-gap-md) 0;
  padding: var(--co-gap-lg);
  background: var(--co-bg-card);
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-lg);
  box-shadow: var(--co-shadow-sm);
}

.title {
  margin: 0;
  font-size: var(--co-font-lg);
  font-weight: 600;
  color: var(--co-text-1);
}

.demo-note {
  margin: 6px 0 var(--co-gap-md);
  font-size: var(--co-font-xs);
  line-height: 1.7;
  color: var(--co-warning-strong);
}

.quick {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--co-gap-sm);
  margin-bottom: var(--co-gap-sm);
}

.quick-item {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 2px;
  padding: 12px 0;
  border: 1px solid var(--co-border);
  border-radius: var(--co-radius-md);
  background: var(--co-bg-2);
  color: var(--co-text-1);
  font-family: inherit;
  font-size: var(--co-font-md);
  font-weight: 600;
  cursor: pointer;
  /* ★ 触摸反馈挂在 `:active`：手机上 hover 会粘住，抬手后还是「选中」的样子 */
  transition: background var(--co-duration-fast) var(--co-ease);
}

.quick-item small {
  font-size: var(--co-font-xs);
  font-weight: 400;
  color: var(--co-text-3);
}

.quick-item:active {
  background: var(--co-bg-3);
}

.quick-item.active {
  border-color: var(--co-primary);
  background: var(--co-primary-weak);
  color: var(--co-primary-strong);
}

.quick-item.active small {
  color: var(--co-primary-strong);
}

.unit {
  font-size: var(--co-font-sm);
  color: var(--co-text-3);
}

.range {
  margin: var(--co-gap-sm) 0 0;
  font-size: var(--co-font-xs);
  color: var(--co-text-3);
}

.actions {
  padding-top: var(--co-gap-lg);
}
</style>
