<script setup lang="ts">
/**
 * 404。
 *
 * ★ 这个页面是**兜底路由**（`/:pathMatch(.*)*`），它同时承担两种情况：
 *   1. 用户手打错了地址
 *   2. 我们自己的链接写错了（这才是更常见的）
 *   所以「返回工作台」这个动作是必需的 —— 用户卡在 404 上没有出路，
 *   只能改地址栏，那体验很差。
 */
import { useRouter } from 'vue-router'
import CoIcon from '@/components/CoIcon.vue'

const router = useRouter()
</script>

<template>
  <div class="notfound">
    <div class="code">404</div>
    <h1>这个页面不存在</h1>
    <p>地址可能输错了，或者对应功能已经下线。</p>
    <div class="actions">
      <el-button type="primary" @click="router.replace('/dashboard')">
        <CoIcon name="dashboard" :size="15" />
        返回工作台
      </el-button>
      <el-button @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.notfound {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px;
  text-align: center;
  background: var(--co-bg-page);
}

.code {
  font-size: 84px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 2px;
  /* 用渐变文字而不是纯色：这个数字是装饰，不该抢主色的语义 */
  background: linear-gradient(160deg, var(--co-primary) 0%, #7ba7ff 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

h1 {
  margin: 20px 0 8px;
  font-size: 19px;
  font-weight: 600;
  color: var(--co-text-1);
}

p {
  margin: 0 0 26px;
  font-size: 13.5px;
  color: var(--co-text-3);
}

.actions {
  display: flex;
  gap: 10px;
}
</style>
