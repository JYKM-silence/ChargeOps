<script setup lang="ts">
/**
 * 根组件。
 *
 * ★ 这里只做两件事：渲染路由出口、挂 Mock 模式的提示条。
 *   布局在 `layouts/AdminLayout.vue` —— 登录页不属于那个布局，
 *   所以布局不能放在这一层，否则登录页也会带上侧边栏。
 */
import { computed } from 'vue'
import OfflineBanner from '@/components/OfflineBanner.vue'

// ★ `__MOCK__` 是编译期常量。这里没有用 `v-if` 来做环境判断 ——
//   因为 mock 提示条的组件本身是轻量的（没有 msw 依赖），
//   留着它不影响产物大小；但**如果它 import 了 msw**，就必须按同样的
//   `__MOCK__` 方式隔离。当前它没有。
const showMockBanner = computed(() => __MOCK__)
</script>

<template>
  <OfflineBanner v-if="showMockBanner" />
  <router-view />
</template>
