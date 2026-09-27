<script setup lang="ts">
/**
 * Mock 模式常驻提示条。
 *
 * ★★ 这个条**必须在**，而且必须显眼、不能自动消失 ★★
 *   开了 Mock 之后，界面上所有数字都是假的。如果没有任何标记，
 *   演示时很容易把假数据当成真的（尤其工作台的营收、订单列表）。
 *   一条会自动消失的 toast 解决不了这个问题 —— 它消失之后
 *   页面看起来就和真后端一模一样了。
 *
 * ★ 措辞刻意写「数据是本地模拟的」，而不是「Mock 模式已开启」——
 *   后者只有开发者看得懂，而演示的观众也需要知道。
 */
import { ref } from 'vue'

const expanded = ref(false)

/** 当前场景。由 `.env.mock` 注入，切换场景要重启（页面内有隐藏入口）。 */
const scenario = import.meta.env.VITE_MOCK_SCENARIO || 'default'
</script>

<template>
  <div class="banner" :class="{ 'is-open': expanded }" @click="expanded = !expanded">
    <span class="tag">MOCK</span>
    <!-- ★ 这里不能用 markdown 星号强调 —— Vue 模板不解析 markdown，
         会原样显示成 `**没有连接后端**`。用 <b> 或样式来强调。 -->
    <span class="text">
      离线演示模式 —— 下方所有数据均为本地模拟，<b>未连接后端</b>
    </span>
    <span class="scenario" v-show="expanded">场景：{{ scenario }}</span>
  </div>
</template>

<style scoped>
.banner {
  position: sticky;
  top: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  height: 34px;
  /* 品牌紫而不是警告黄：这不是错误，是一个模式提示 */
  background: linear-gradient(90deg, var(--co-accent) 0%, #9a86ff 100%);
  color: #fff;
  font-size: 12.5px;
  cursor: pointer;
  user-select: none;
}

.tag {
  padding: 1px 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.22);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.8px;
}

.text {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.scenario {
  opacity: 0.85;
  font-variant-numeric: tabular-nums;
}
</style>
