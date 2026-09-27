/**
 * 管理端入口。
 *
 * ★ Mock 的装配方式（`__MOCK__` + 动态 import）是**这次的关键设计**：
 *
 *   普通做法是 `if (import.meta.env.DEV) await import('./mocks')`，
 *   但那样「构建产物里能不能带 mock」就取决于环境变量，而且 msw 会
 *   进入生产依赖图。这里用的是**编译期常量** `__MOCK__`
 *   （由 vite.config.ts 的 `define` 注入，值为 `VITE_USE_MOCK === 'true'`）：
 *
 *     · `VITE_USE_MOCK` 不是 true → `__MOCK__` 编译成字面量 `false`
 *     · `if (false) { await import(...) }` → 整个分支连同 msw 一起被**死代码消除**
 *
 *   → 生产产物里 grep 不到 `msw`（P0 的验收项之一）。
 *     这是「可切换 Mock」能同时做到「离线演示」和「产物干净」的原因。
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import 'element-plus/dist/index.css'
// ★ 顺序很重要：tokens 必须在 element 之后、overrides 之前 ——
//   overrides 里用 color-mix 引用了 tokens 的变量，而 tokens 又要覆盖
//   EP 的默认值。顺序错了会得到「主色变了但派生色没变」。
// ★ 路径是 `@chargeops/shared/styles/...` 而**不是** `@chargeops/shared/src/styles/...`：
//   vite.config.ts 里 alias 是**前缀替换**，写成后者会被
//   `'@chargeops/shared'` 先命中，拼成 `shared/src/src/styles/...`。
//   那个错误**只在 build 时**才暴露（见 vite.config.ts 的注释）。
import '@chargeops/shared/styles/tokens.css'
import '@/styles/element-overrides.css'

import App from './App.vue'
import { router } from './router'
import { setAuthLostHandler } from './api/http'
import { useSessionStore } from './stores/session'

async function bootstrap(): Promise<void> {
  // ★ Mock 必须在 router / app 之前装好：首屏就会发请求，
  //   晚一步装配就会有请求漏到真实后端去（在 Mock 模式下表现为 401 或跨域失败）。
  if (__MOCK__) {
    const { startMockWorker } = await import('./mocks/browser')
    await startMockWorker()
  }

  const app = createApp(App)
  const pinia = createPinia()

  app.use(pinia)
  app.use(router)
  app.use(ElementPlus, { locale: zhCn })

  // ★ 在这里（而不是 http.ts 里）注入 401 处理，以保持依赖无环 ——
  //   见 api/http.ts 文件头的说明。
  setAuthLostHandler((reason) => {
    const session = useSessionStore(pinia)
    session.logout()
    const message = reason === 'locked' ? '该账号已被禁用' : '登录状态已失效，请重新登录'
    // import 放在这里而不是顶层：ElMessage 需要 app 上下文才有时效性保证
    void import('element-plus').then(({ ElMessage }) => {
      ElMessage({ type: 'warning', message, duration: 3000 })
    })
    void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  })

  router.isReady().then(() => {
    app.mount('#app')
  })
}

void bootstrap()
