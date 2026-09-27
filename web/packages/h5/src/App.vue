<script setup lang="ts">
/**
 * 车主端根组件。
 *
 * ★ 这里刻意**只有** `<router-view>` 和离线横幅 —— 底部导航属于
 *   `TabLayout`（4 个 Tab 的父子路由），不属于根。放在根上的话
 *   登录页、订单详情这些子页也会带上 Tabbar，而它们需要全屏。
 */
import { onMounted } from 'vue'
import { useSessionStore } from '@/stores/session'
import OfflineBanner from '@/components/OfflineBanner.vue'

const session = useSessionStore()

onMounted(() => {
  // ★ 启动时恢复一次登录态（从 localStorage 读，同步，不发请求）。
  //   真正的「是否被禁用」由路由守卫里的探针查（见 stores/session）。
  session.restore()
})
</script>

<template>
  <OfflineBanner />
  <router-view v-slot="{ Component }">
    <!-- ★ 用 transition 做左右滑动的页面切换：H5 上没有浏览器的前进动画可用
         （我们是 SPA），不加的话每次跳页都是硬切，观感很"网页"。
         `mode="out-in"` 是必须的 —— 不加会有两页同时存在于 DOM 的一瞬，
         在固定定位的 NavBar 上表现为**两条标题重叠**。 -->
    <transition name="page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
/* ★ 不用 scoped：`component` 是动态组件，scoped 属性不会传到它渲染出的
   根元素之外的子元素上，过渡类名会挂不上。 */
.page-enter-active,
.page-leave-active {
  transition:
    opacity var(--co-duration-fast) var(--co-ease),
    transform var(--co-duration-fast) var(--co-ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateX(12px);
}

.page-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}

/* ★ 尊重「减少动态效果」的系统设置 —— 前庭功能障碍的人会因页面滑动眩晕 */
@media (prefers-reduced-motion: reduce) {
  .page-enter-active,
  .page-leave-active {
    transition: none;
  }

  .page-enter-from,
  .page-leave-to {
    transform: none;
  }
}
</style>
