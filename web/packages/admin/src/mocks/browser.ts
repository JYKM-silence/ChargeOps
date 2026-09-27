/**
 * MSW 浏览器端 worker（管理端）。
 *
 * ★★ 这个文件**只会在 `__MOCK__ === true` 时被动态 import** ★★
 *   入口在 `main.ts`：
 *
 *     if (__MOCK__) await import('./mocks/browser')
 *
 *   `__MOCK__` 是 `vite.config.ts` 里 `define` 出来的**编译期字面量**，
 *   生产构建时恒为 `false`，于是整个 `if` 块连同这个文件一起被
 *   死代码消除 —— **产物里不会出现 msw**。
 *
 *   ★ 所以：**不要**把这里的 import 改成静态的，也不要从别处静态
 *     import 这个文件。一旦变成静态依赖，msw 就会被打进生产包
 *     （约 200KB），而它是纯开发工具。
 *
 * ★ 真正干活的是 `handlers`，两端共用同一份 —— 见 `@chargeops/shared/mock`。
 */

import { setupWorker } from 'msw/browser'

export async function startMockWorker(): Promise<void> {
  // ★ 动态 import handlers：让「决定用哪些 handler」这件事也留在运行时，
  //   便于按场景（VITE_MOCK_SCENARIO）挑选数据。
  const { createHandlers } = await import('@chargeops/shared/mock')

  const worker = setupWorker(...createHandlers())

  await worker.start({
    // ★ 没被任何 handler 命中的请求 → **放它走真实网络**。
    //   默认值是 `warn`，会在控制台刷一堆「unhandled request」的警告，
    //   而 /img 静态资源、HMR 的 websocket 请求本来就不该被 mock。
    onUnhandledRequest: 'bypass',
    quiet: false,
    serviceWorker: {
      url: '/mockServiceWorker.js',
    },
  })

  // 挂到 window 上方便演示时在控制台手动排查
  ;(window as unknown as { __msw?: unknown }).__msw = worker
}
