<script setup lang="ts">
/**
 * 金额 / 电量文本。
 *
 * ★★ 三件必须一起做的事 ★★
 *   1. **不参与 JS 浮点运算** —— 显示走 `formatMoney()`（内部是 BigInt 缩放），
 *      后端保证「只在最后取整一次、Σ分段 == 总额」，前端用 `+` 求和会掉分位。
 *   2. **`tabular-nums`** —— 等宽数字，否则一列金额的小数点参差不齐，
 *      这是表格看起来「业余」最直接的原因。
 *   3. **右对齐**（由父级表格列决定，这里是 `text-align: right` 的选项）。
 *
 * ★ `signed` 只影响**颜色**，且颜色只看**符号**：
 *   钱包流水里「冻结」写的是负数（后端 `amount.negate()`），
 *   而「扣款」也是负数 —— 两者颜色相同是对的，**文案**才区分它们。
 */

import { computed } from 'vue'
import { formatMoney, toScaled, signedTone, TONE_VAR, AMOUNT_SCALE } from '@chargeops/shared'

const props = withDefaults(
  defineProps<{
    value: string | number | null | undefined
    /** 是否按正负着色（流水、调账用）。默认不着色。 */
    signed?: boolean
    /** 显示正号。仅在有正负语义时有意义。 */
    showPlus?: boolean
    size?: 'sm' | 'md' | 'lg'
    /** 千分位。金额列建议关掉，数字更短、更好扫。 */
    grouping?: boolean
    strong?: boolean
  }>(),
  { size: 'md', grouping: true },
)

const scaled = computed(() => toScaled(props.value, AMOUNT_SCALE))

/** ★ 后端返回 null 表示「还没到这一步」，显示 `--` 而不是 `0.00`。
 *  把 null 显示成 0 会让「未结算」看起来像「结算了但金额是 0」。 */
const text = computed(() =>
  props.value === null || props.value === undefined || props.value === ''
    ? '--'
    : formatMoney(props.value, AMOUNT_SCALE),
)

const color = computed(() => (props.signed ? TONE_VAR[signedTone(scaled.value)].strong : undefined))

const sign = computed(() => {
  if (!props.signed && !props.showPlus) return ''
  if (scaled.value > 0n) return '+'
  return ''
})
</script>

<template>
  <span
    class="money"
    :class="[`is-${props.size}`, { 'is-strong': props.strong }]"
    :style="color ? { color } : undefined"
  >
    <span class="sign">{{ sign }}</span>{{ text }}
  </span>
</template>

<style scoped>
.money {
  /* ★ 等宽数字。整个表格的金额列靠它对齐。 */
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
  white-space: nowrap;
}

.is-sm {
  font-size: 12.5px;
}

.is-md {
  font-size: 13.5px;
}

.is-lg {
  font-size: var(--co-font-money);
  font-weight: 600;
}

.is-strong {
  font-weight: 600;
}

.sign {
  /* 正号比数字窄一点，避免 +1,234.00 比 -1,234.00 宽出一截 */
  margin-right: -1px;
}
</style>
