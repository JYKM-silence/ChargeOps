<script setup lang="ts">
/**
 * Mock 模式常驻横幅。
 *
 * ★★ 这个组件存在的唯一目的是「别把假数据当真」★★
 *   Mock 模式下所有数字都是内存里造的，和真实后端毫无关系。
 *   演示时如果没人提醒，看的人会以为这是真实数据 ——
 *   然后拿它去对账。
 *
 * ★ 只在 `__MOCK__` 为 true 时渲染（编译期常量，非 mock 构建里
 *   整个组件连同它的样式一起被摇掉）。
 */

import { ref } from 'vue'

/**
 * ★ 必须先落到一个**局部常量**再在模板里用。
 *   `__MOCK__` 是 `vite.config.ts` 里 `define` 出来的全局标识符，
 *   它在**表达式里**能用（编译时被替换成字面量），但 `<template>` 里
 *   只能访问 `<script setup>` 顶层绑定的名字 —— 直接写 `__MOCK__`
 *   在模板中会被当成组件实例上的属性，报「Property '__MOCK__' does not exist」。
 *   赋给常量之后，模板里引用的就是那个被替换成 `false` 之后的字面量，
 *   整个组件照样能被死代码消除。
 */
const isMock = __MOCK__

const dismissed = ref(false)
</script>

<template>
  <div v-if="isMock && !dismissed" class="mock-banner">
    <span class="dot" />
    <span class="text">
      <b>演示模式</b>
      <i>数据由前端模拟，未连接后端</i>
    </span>
    <button class="close" type="button" aria-label="收起提示" @click="dismissed = true">×</button>
  </div>
</template>

<style scoped>
.mock-banner {
  position: sticky;
  top: 0;
  z-index: 999;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  /* ★ 用紫色系而不是红/黄：这两个色在我们的体系里是「错误 / 警告」，
     而「正在用 mock」既不是错误也不是警告，它是一个**模式**。
     用警告色会让人以为系统出了问题。 */
  background: var(--co-accent-weak);
  color: var(--co-accent-strong);
  font-size: 12px;
  line-height: 1.5;
}

.dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--co-accent);
  animation: co-pulse 2s ease-in-out infinite;
}

@keyframes co-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.text {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.text b {
  font-weight: 600;
}

.text i {
  font-style: normal;
  opacity: 0.78;
}

.close {
  margin-left: auto;
  padding: 0 4px;
  border: none;
  background: none;
  color: inherit;
  font-size: 16px;
  line-height: 1;
  opacity: 0.6;
  cursor: pointer;
}

@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
  }
}
</style>
