<script setup lang="ts">
/**
 * 订单状态标签。
 *
 * ★ 只是 `StatusTag` 的一层薄封装：把状态码翻译成 label / tone / 附加表达。
 *   翻译表在 `shared/constants/orderStatus.ts` —— **单一真源**，
 *   H5 用的是同一张表，所以两端的「待支付」必然同色同字。
 *
 * ★ 状态码未知时**显示 `状态${code}` 而不是空白**：
 *   后端加了新状态而前端没跟上时，空白会让人以为是渲染 bug，
 *   显示原始码至少能立刻定位问题。
 */

import { computed } from 'vue'
import { orderStatusLabel, orderStatusMeta, type OrderStatusMeta } from '@chargeops/shared'
import StatusTag from './StatusTag.vue'

const props = withDefaults(
  defineProps<{
    status: number | null | undefined
    size?: 'sm' | 'md'
  }>(),
  { size: 'md' },
)

/**
 * ★ `orderStatusMeta()` 返回 `undefined` 表示**这个状态码前端不认识**
 *   （后端新增了状态而我们没跟上，或者 `status` 是 null）。
 *   这时**不能让标签渲染成空白** —— 空白看起来像渲染 bug，
 *   而显示原始码能立刻定位问题。所以这里给一个兜底 meta。
 */
const meta = computed<OrderStatusMeta>(() => {
  const known = orderStatusMeta(props.status)
  if (known) return known

  return {
    // ★ 这里刻意越过 `OrderStatusMeta['code']` 的类型（它被收窄成已知码的联合）。
    //   前提恰恰是「这个码**不在**已知集合里」，所以类型系统在这里帮不上忙，
    //   硬套一个已知码反而是撒谎。整个应用里只有这一处需要这样绕过。
    code: (props.status ?? -1) as OrderStatusMeta['code'],
    label: orderStatusLabel(props.status),
    tone: 'info',
    pulse: false,
    dashed: true,
    solid: false,
    warnIcon: false,
    hint: '前端未识别的订单状态。后端可能新增了状态码，前端字典需要同步。',
  }
})
</script>

<template>
  <StatusTag
    :label="meta.label"
    :tone="meta.tone"
    :pulse="meta.pulse"
    :dashed="meta.dashed"
    :warn-icon="meta.warnIcon"
    :size="props.size"
  />
</template>
