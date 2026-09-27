<script setup lang="ts">
/**
 * 退款（5 已支付 → 7 已退款）。**这一批里唯一的资金动作。**
 *
 * ★★ 为什么必须传金额，而不是「全额退回」★★
 *   后端注释：全额退款看起来是更安全的默认值，但真实退款不总是全额的
 *   （用户投诉了服务费、平台扣了手续费、部分权益已经用掉）。如果接口只支持全额，
 *   那些情况就只能手工改库 —— **而手工改的地方不会有 `charging_order_event`**。
 *   用「必填的金额」换「所有退款都有留痕」，这个交换是划算的。
 *
 * ★★ 后端**没有**校验「退款 ≤ 实付」★★
 *   它只校验绝对区间 `0.01 ~ 100000.00`。也就是说退超过实付的钱是**能成功的**，
 *   钱就这么出去了。这是本次核对里发现的第二个只能由前端兜住的校验缺口
 *   （第一个是调账金额为 0）。→ 用 `isRefundableAmount(value, payAmount)`。
 *
 * ★★ 为什么用 `DangerConfirm` 并让人手打金额 ★★
 *   后端全项目**没有任何幂等键**。钱一旦出去要追回来很难，
 *   而"点一下确认"只要 0.2 秒 —— 手打一遍金额要 3 秒，这 3 秒就是思考时间。
 */
import { computed, ref } from 'vue'
import {
  formatMoney,
  invalidateOptionsByTag,
  isPartialAmountInput,
  isRefundableAmount,
  withActionLock,
} from '@chargeops/shared'
import type { AdminOrderVO } from '@chargeops/shared'
import { refundOrder } from '@/api/order'
import { useSubmitLock } from '@/composables/useSubmitLock'
import DangerConfirm from '@/components/DangerConfirm.vue'
import OrderStatusTag from '@/components/OrderStatusTag.vue'

const props = defineProps<{ order: AdminOrderVO }>()

const emit = defineEmits<{ close: []; done: [message: string] }>()

const amount = ref('')

/**
 * ★ 退款上限**只有一个来源**：`payAmount`。
 *   刻意不从父组件再传一个 `ceiling` 进来 —— 两个 prop 描述同一个数字，
 *   迟早会有一个忘了跟着更新，而这里差了就是多退钱。
 */
const paid = computed(() => props.order.payAmount ?? '0')

const { pending: submitting, run } = useSubmitLock({ throttleMs: 600 })

/** 填了之后才判合法性 —— 空着不该显示红字，那是「还没填」不是「填错了」。 */
const amountInvalid = computed(
  () => amount.value !== '' && !isRefundableAmount(amount.value, paid.value),
)

const canSubmit = computed(
  () => amount.value !== '' && !amountInvalid.value && !submitting.value,
)

/** 溢出的原因分两种，给的引导完全不同 —— 不要合成一句「金额不合法」。 */
const invalidReason = computed(() => {
  if (amount.value === '') return ''
  const n = Number(amount.value)
  if (!Number.isFinite(n)) return '请输入一个数字。'
  if (n < 0.01) return '退款金额必须大于 0。'
  if (n > 100000) return '退款金额超出 10 万元上限，请确认是否多打了几个 0。'
  if (n > Number(paid.value)) {
    return `退款金额不能超过实付金额 ¥${formatMoney(paid.value)}。服务器没有这条校验，所以由这里拦住。`
  }
  return ''
})

function onInput(v: string): void {
  if (isPartialAmountInput(v)) amount.value = v
}

/** 一键填全额。仍要手打确认，所以不会降低安全性，只是省去抄数字。 */
function fillFull(): void {
  amount.value = String(paid.value)
}

async function onConfirm(): Promise<void> {
  if (!canSubmit.value) return

  const value = amount.value

  // 两层锁，管的不是同一件事，缺一不可：
  //   · `run`（SubmitLock）—— 按钮级：这次提交的往返期间不重发
  //   · `withActionLock` —— 业务级：**弹窗关掉再打开**也拦得住
  //     （SubmitLock 拦不住这种，因为那是新的一次提交流程，
  //      而 actionLocks 是模块级共享的，人重开弹窗它还在冷却期内）
  // 后端**没有幂等键**，key 里带上金额是因为「先退 10 元、之后再退 20 元」
  // 是两个不同的动作，不该被互斥。
  const outcome = await run(() =>
    withActionLock(
      `refund:${props.order.id}:${value}`,
      () => refundOrder(props.order.id, { amount: value }),
      5000,
    ),
  )

  // skipped = 连点被挡；failed = 被动作锁拦下或请求失败（提示由 http 层统一弹）。
  if (outcome.status !== 'done') return

  invalidateOptionsByTag('order')
  emit('done', `已退款 ¥${formatMoney(value)}，订单状态更新为「已退款」`)
}
</script>

<template>
  <DangerConfirm
    :model-value="true"
    title="确认退款"
    action-name="退款"
    confirm-text="确认退款"
    :loading="submitting"
    :description="`这笔操作会把钱退回车主钱包。服务器不校验退款金额是否超过实付，所以请核对后再提交。`"
    :consequences="[
      `退款金额不能超过实付 ¥${formatMoney(paid)}`,
      '退款成功后订单进入「已退款」，不可撤销',
      '退款记录会写入订单事件流，可追溯',
    ]"
    :require-typed-value="amount === '' ? null : amount"
    typed-label="请再输入一次退款金额以确认"
    @update:model-value="emit('close')"
    @confirm="onConfirm"
  >
    <div class="order-line">
      <span class="no">{{ props.order.orderNo }}</span>
      <OrderStatusTag :status="props.order.status" size="sm" />
    </div>

    <dl class="facts">
      <div class="fact">
        <dt>实付金额</dt>
        <dd class="co-num strong">¥{{ formatMoney(paid) }}</dd>
      </div>
      <div class="fact">
        <dt>车主</dt>
        <dd>{{ props.order.memberPhone || '-' }}</dd>
      </div>
    </dl>

    <div class="amount-field">
      <label for="refund-amount">退款金额</label>
      <div class="input-row">
        <el-input
          id="refund-amount"
          :model-value="amount"
          placeholder="0.00"
          :disabled="submitting"
          @update:model-value="onInput"
        >
          <template #prepend>¥</template>
        </el-input>
        <el-button :disabled="submitting" @click="fillFull">全额</el-button>
      </div>
      <p v-if="invalidReason" class="err">{{ invalidReason }}</p>
    </div>
  </DangerConfirm>
</template>

<style scoped>
.order-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0 12px;
}

.no {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--co-text-1);
  font-variant-numeric: tabular-nums;
}

.facts {
  display: flex;
  gap: 28px;
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: var(--co-radius-md);
  background: var(--co-bg-3);
}

.fact {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.fact dt {
  font-size: 11.5px;
  color: var(--co-text-3);
}

.fact dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--co-text-1);
}

.co-num {
  font-variant-numeric: tabular-nums;
}

.strong {
  font-weight: 600;
}

.amount-field label {
  display: block;
  margin-bottom: 7px;
  font-size: 12.5px;
  color: var(--co-text-2);
}

.input-row {
  display: flex;
  gap: 8px;
}

.err {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--co-danger);
}
</style>
