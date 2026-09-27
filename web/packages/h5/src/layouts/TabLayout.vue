<script setup lang="ts">
/**
 * 4 个 Tab 的公共外壳（首页 / 订单 / 套餐 / 我的）。
 *
 * ★ 高亮哪一格**不看当前路由**，看 `route.meta.tab` ——
 *   因为子页（订单详情、车辆管理）也要让所属的 Tab 保持高亮，
 *   而那些子页的 path 并不以 Tab 的 path 开头（`/orders/12` 与
 *   `/profile/vehicles` 都不在 TabLayout 的子路由里）。
 *   用 `meta.tab` 是唯一能覆盖全部分支的写法。
 *
 * ★★ 为什么「套餐」Tab 指向 `/packages/mine` 而不是 `/packages` ★★
 *   `/packages` 是**购买页**（需要先选站点，因为可用套餐依赖站点），
 *   把它做成 Tab 会让用户一进来就看到「请先选择充电站」的空列表。
 *   Tab 指向「我的套餐」（一定有的东西），购买入口放在那一页里。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 当前应该高亮哪一格。取不到（理论上不会）时不高亮，而不是瞎猜一个。 */
const active = computed(() => (route.meta.tab as string | undefined) ?? '')
</script>

<template>
  <div class="tab-shell">
    <router-view />

    <van-tabbar :model-value="active" fixed placeholder route>
      <van-tabbar-item name="home" to="/" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item name="orders" to="/orders" icon="orders-o">订单</van-tabbar-item>
      <van-tabbar-item name="packages" to="/packages/mine" icon="coupon-o">套餐</van-tabbar-item>
      <van-tabbar-item name="profile" to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.tab-shell {
  min-height: 100vh;
  background: var(--co-bg-page);
}
</style>
