<script setup lang="ts">
/**
 * 404（H5）。
 *
 * ★ 这一页是 `public` 的（见 router 的 `meta`）—— 未登录的人点到一个
 *   失效链接时，应该看到「页面不存在」，而不是被弹去登录页。
 *   弹去登录页会让他以为「登录之后就能看到」，而实际上并没有那个页面。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="nf">
    <van-nav-bar title="页面不存在" left-arrow @click-left="router.back()" />
    <div class="body">
      <div class="code">404</div>
      <p class="title">这个页面不存在</p>
      <p class="desc">链接可能已经失效，或者地址输错了。</p>
      <van-button round type="primary" size="small" @click="router.replace('/')">
        回到首页
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.nf {
  min-height: 100vh;
  background: var(--co-bg-page);
}

.body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 72px 32px;
  text-align: center;
}

.code {
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -2px;
  color: var(--co-primary-weak);
  font-variant-numeric: tabular-nums;
}

.title {
  margin: 4px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--co-text-1);
}

.desc {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--co-text-3);
}
</style>
