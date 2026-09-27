<script setup lang="ts">
/**
 * 订单状态标签（车主端）。
 *
 * ★ 翻译表来自 `shared/constants/orderStatus.ts` —— **两端同一份**，
 *   所以管理端和 H5 的「待支付」必然同色同字。
 *   车主端**不改措辞**：同一个状态在两处显示不同的字，用户会以为是两件事。
 *
 * ★ 未知状态码显示 `状态${code}` 而不是空白 —— 空白看起来像渲染 bug，
 *   显示原始码能立刻定位「后端加了新状态」。
 */

import { computed } from 'vue'
import { orderStatusLabel, orderStatusMeta, type OrderStatusMeta } from '@chargeops/shared'

const props = defineProps<{ status: number | null | undefined }>()

const meta = computed<OrderStatusMeta>(() => {
  const known = orderStatusMeta(props.status)
  if (known) return known
  return {
    // ★ 这里刻意越过 `OrderStatusMeta['code']` 的类型（它被收窄成已知码的联合）。
    //   前提恰恰是「这个码**不在**已知集合里」，所以类型系统帮不上忙，
    //   硬套一个已知码反而是撒谎。
    code: (props.status ?? -1) as OrderStatusMeta['code'],
    label: orderStatusLabel(props.status),
    tone: 'info',
    pulse: false,
    dashed: true,
    solid: false,
    warnIcon: false,
    hint: '前端未识别的订单状态，可能后端新增了状态码。',
  }
})
</script>

<template>
  <span
    class="tag"
    :class="[`tone-${meta.tone}`, { dashed: meta.dashed, pulse: meta.pulse }]"
    :title="meta.hint || undefined"
  >
    <i v-if="meta.pulse" class="dot" />
    <i v-else-if="meta.warnIcon" class="warn">!</i>
    {{ meta.label }}
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px;
  border: 1px solid transparent;
  border-radius: 4px;
  font-size: 11.5px;
  line-height: 1.7;
  white-space: nowrap;
}

/* ★ 浅底 + 同色深字，**不用实心色块** ——
   实心块在这个尺寸上太重，而且和 Vant 的 tag 抢视觉。 */
.tone-info {
  background: var(--co-info-weak);
  color: var(--co-info-strong);
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

.tone-accent {
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
}

.dashed {
  border-style: dashed;
  border-color: currentColor;
  background: transparent;
  opacity: 0.86;
}

/* ★ 充电中是唯一带呼吸圆点的状态 —— 动效只留给「正在发生」的事。
     满屏都在动等于没有重点。 */
.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: co-breathe 1.6s ease-in-out infinite;
}

@keyframes co-breathe {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.3;
    transform: scale(0.7);
  }
}

.warn {
  font-style: normal;
  font-weight: 700;
  font-size: 10px;
  line-height: 1;
}

@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
  }
}
</style>
