<script setup lang="ts">
/**
 * 金额展示（车主端）。
 *
 * ★★ 金额全程**字符串**，绝不用浮点求和 ★★
 *   后端是 `BigDecimal`，并且「只在最后取整一次、保证 Σ分段 == 总额」。
 *   前端一旦 `Number(a) + Number(b)` 就会出现分位误差 ——
 *   于是「明细加起来和总额差一分」，这是最容易被截图投诉的那类 bug。
 *   要算就用 `shared/utils/money.ts` 的 `sumDecimal/addDecimal`（内部走 BigInt）。
 *
 * ★ 空值渲染 `--` 而不是 `0.00`：`0.00` 是一个**具体的数字**，
 *   而「后端没给」和「确实是 0」是两件事。显示成 0 会让人以为真的没收钱。
 */

import { computed } from 'vue'
import { formatMoney, type Decimal } from '@chargeops/shared'

const props = withDefaults(
  defineProps<{
    value: Decimal
    size?: 'sm' | 'md' | 'lg' | 'xl'
    /** 是否按正负着色（流水、调账用）。默认不着色。 */
    signed?: boolean
    /** 显示正号。仅在有正负语义时有意义。 */
    showPlus?: boolean
    strong?: boolean
  }>(),
  { size: 'md' },
)

const text = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') return '--'
  const n = Number(props.value)
  if (!Number.isFinite(n)) return '--'
  const body = formatMoney(props.value)
  if (props.showPlus && n > 0) return `+${body}`
  return body
})

const tone = computed(() => {
  if (!props.signed) return ''
  const n = Number(props.value)
  if (!Number.isFinite(n) || n === 0) return ''
  return n > 0 ? 'is-positive' : 'is-negative'
})
</script>

<template>
  <span class="money" :class="[`size-${props.size}`, tone, { strong: props.strong }]">
    <i class="sym">¥</i>{{ text }}
  </span>
</template>

<style scoped>
.money {
  display: inline-flex;
  align-items: baseline;
  /* ★ 等宽数字：金额列上下要对齐，否则一列数字看起来是歪的 */
  font-variant-numeric: tabular-nums;
  color: var(--co-text-1);
  line-height: 1.2;
}

.sym {
  margin-right: 1px;
  font-size: 0.78em;
  font-style: normal;
  font-weight: 400;
  color: var(--co-text-3);
}

.size-sm {
  font-size: 13px;
}

.size-md {
  font-size: 15px;
}

.size-lg {
  font-size: 19px;
  font-weight: 600;
}

.size-xl {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.3px;
}

.strong {
  font-weight: 600;
}

/* ★ 只有符号着色，数字本身始终保持主文本色 ——
     整行变绿/变红会让金额看起来像状态标签，而金额首先是一个数字。 */
.is-positive {
  color: var(--co-success-strong);
}

.is-negative {
  color: var(--co-danger-strong);
}
</style>
