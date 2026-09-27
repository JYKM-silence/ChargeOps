<script setup lang="ts">
/**
 * 状态标签（全站唯一的状态渲染组件）。
 *
 * ★★ 为什么不用 `el-tag` 的 `type` 直接上色 ★★
 *   `el-tag` 的浅底是它自己算的，和我们的 tone 体系是两套色。
 *   并排放会出现「同样是"警告"，一个是橙的一个是黄的」。
 *   所以这里自己画：**浅底 + 同色深字 + 同色描边**，
 *   底/字/边全部来自 `TONE_VAR`，与 `tokens.css` 单一真源。
 *
 * ★ 「不廉价」的关键：状态标签用**浅底深字**，不要纯色填充块。
 *   纯色填充块在一屏里出现十几个时，页面会变成彩色噪声。
 *
 * ★ 三个可选的**附加表达**，用来区分「同为危险色但语义不同」的状态：
 *   · `pulse`    —— 呼吸圆点（只有「充电中」用，全站唯一带动效的状态）
 *   · `dashed`   —— 虚线描边（已取消，表达"未完成/不占资源"）
 *   · `warnIcon` —— 警示图标（异常挂账，与"支付失败"区分：两者都是红）
 */

import { computed } from 'vue'
import { TONE_VAR, type Tone } from '@chargeops/shared'
import CoIcon from './CoIcon.vue'

const props = withDefaults(
  defineProps<{
    label: string
    tone?: Tone
    /** 呼吸圆点。同一屏最多一个。 */
    pulse?: boolean
    /** 虚线描边。 */
    dashed?: boolean
    /** 左侧警示三角。 */
    warnIcon?: boolean
    size?: 'sm' | 'md'
  }>(),
  { tone: 'info', size: 'md' },
)

const vars = computed(() => TONE_VAR[props.tone])
</script>

<template>
  <span
    class="status-tag"
    :class="[`is-${props.size}`, { 'is-dashed': props.dashed }]"
    :style="{
      '--tag-base': vars.base,
      '--tag-weak': vars.weak,
      '--tag-strong': vars.strong,
    }"
  >
    <i v-if="props.pulse" class="dot" aria-hidden="true" />
    <CoIcon v-else-if="props.warnIcon" name="warning" :size="12" :stroke-width="2" />
    <span class="text">{{ props.label }}</span>
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
  height: 22px;
  border: 1px solid color-mix(in srgb, var(--tag-base) 26%, transparent);
  border-radius: var(--co-radius-sm);
  background: var(--tag-weak);
  color: var(--tag-strong);
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  vertical-align: middle;
}

.status-tag.is-dashed {
  border-style: dashed;
}

.status-tag.is-sm {
  height: 20px;
  padding: 0 6px;
  font-size: 11.5px;
}

.text {
  /* ★ 标签里的中文在 12px 下容易显得松散，压一点字距 */
  letter-spacing: 0.2px;
}

.dot {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--tag-base);
  animation: co-pulse 1.8s var(--co-ease) infinite;
}

@keyframes co-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.45;
    transform: scale(0.82);
  }
}

/* ★ 尊重系统的「减少动态效果」设置。
   前庭功能障碍的用户会被持续闪烁的元素影响，这不是可有可无的礼貌。 */
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
  }
}
</style>
