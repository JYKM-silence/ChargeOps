<script setup lang="ts">
/**
 * 内联 SVG 图标。
 *
 * ★★ 为什么不用 `@element-plus/icons-vue` ★★
 *   它是独立于 element-plus 的一个包，版本不同步。本次搭建时
 *   **无法确认它当前可用的版本号**，而写一个猜的版本号会让
 *   `pnpm install` 直接失败（或者装到不存在的版本上）。
 *   与其赌，不如内联 —— 顺带拿到了「线宽统一 1.75」这个好处：
 *   图标库里的图标往往来自不同来源，线宽 1.5 和 2 混在一起，
 *   并排看是能看出「不是一套」的。
 *
 * ★ 颜色一律 `currentColor`，跟着文字颜色走。
 */
import { ICON_PATHS, type IconName } from './icons'

const props = withDefaults(
  defineProps<{
    name: IconName
    size?: number | string
    /** 线宽。侧边栏 1.75，正文里 1.6 更轻。 */
    strokeWidth?: number
  }>(),
  { size: 16, strokeWidth: 1.75 },
)
</script>

<template>
  <svg
    :width="props.size"
    :height="props.size"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="props.strokeWidth"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    class="co-icon"
  >
    <path :d="ICON_PATHS[props.name]" />
  </svg>
</template>

<style scoped>
.co-icon {
  /* ★ 图标默认按文字基线对齐，会比文字低几个像素 ——
     这是「图标看起来没对齐」最常见的原因。vertical-align: middle 修掉它。 */
  vertical-align: middle;
  flex-shrink: 0;
}
</style>
