/**
 * 车主端入口。
 *
 * ★ Mock 的装配方式（`__MOCK__` + 动态 import）与管理端完全一致 ——
 *   见 `admin/src/main.ts` 的长注释。核心是：`__MOCK__` 是 `define` 出来的
 *   **编译期字面量**，非 mock 模式下整个 `if` 块连同 msw 一起被死代码消除，
 *   所以生产产物里 grep 不到 msw。
 *
 * ★ 样式引入顺序（**错了会得到「主色变了但派生色没变」**）：
 *     1. vant 的样式（组件默认值）
 *     2. tokens.css（我们的令牌，同时覆盖 van-* 的基础变量）
 *     3. vant-overrides.css（引用 tokens，重算 Vant 没有色阶的那部分）
 *   Vant 4 **没有** `-light-N` 色阶（产物里 SCSS 文件数为 0），
 *   所以它比 Element Plus 好改得多：改 `--van-primary-color` 就全站生效。
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Vant from 'vant'

import 'vant/lib/index.css'
// ★ 顺序见文件头
import '@chargeops/shared/styles/tokens.css'
import '@/styles/vant-overrides.css'

import App from './App.vue'
import { router } from './router'
import { setAuthLostHandler } from './api/http'
import { useSessionStore } from './stores/session'

async function bootstrap(): Promise<void> {
  // ★ 必须在 router / app 之前装好：首屏就发请求，晚一步会有请求漏到真实后端
  if (__MOCK__) {
    const { startMockWorker } = await import('./mocks/browser')
    await startMockWorker()
  }

  const app = createApp(App)
  const pinia = createPinia()

  app.use(pinia)
  app.use(router)
  // ★ 全量注册。Vant 支持按需引入，但那需要 unplugin-vue-components + resolver，
  //   而本项目两端是「一个组件库里挑几个用」的规模，全量引入省掉一层构建配置，
  //   代价是包里多了几十 KB —— 对一个内部 H5 来说这个交换划算。
  app.use(Vant)

  setAuthLostHandler((reason) => {
    const session = useSessionStore(pinia)
    session.logout()

    const message =
      reason === 'locked' ? '该账号已被禁用，请联系客服' : '登录状态已失效，请重新登录'

    // ★ 动态 import：vant 的 Toast 需要 app 上下文，且在登出瞬间弹
    //   不需要为它在首屏加载。
    void import('vant').then(({ showFailToast }) => {
      showFailToast({ message, duration: 3000 })
    })

    // ★ 带上 redirect，登录后回到原来那一页 —— H5 上用户很可能是
    //   从「我的订单」被踢出来的，登完再让他自己找回去很烦。
    void router.replace({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  })

  router.isReady().then(() => {
    app.mount('#app')
  })
}

void bootstrap()
