/**
 * MSW 浏览器端 worker（车主端）。
 *
 * ★ 与管理端的 `admin/src/mocks/browser.ts` 是同一份逻辑，
 *   共用的 `createHandlers()` 也在 `@chargeops/shared/mock` ——
 *   **两端共用一套内存库**，所以「H5 建单 → 管理端 start/end/settle →
 *   H5 支付」这条跨端链路在 mock 模式下也是通的（同一个浏览器里两个标签页
 *   各起一个 worker，但内存库是各自一份 —— ★ 跨标签页的状态**不共享**，
 *   演示跨端链路要用真后端）。
 *
 * ★ 只会在 `__MOCK__ === true` 时被动态 import（见 main.ts）——
 *   非 mock 构建里 msw 不会进产物。
 */

import { setupWorker } from 'msw/browser'

export async function startMockWorker(): Promise<void> {
  const { createHandlers } = await import('@chargeops/shared/mock')

  const worker = setupWorker(...createHandlers())

  await worker.start({
    // ★ 没被 handler 命中的请求放它走真实网络。
    //   默认的 `warn` 会对 /img 静态资源和 HMR 的 websocket 刷一屏警告。
    onUnhandledRequest: 'bypass',
    quiet: false,
    serviceWorker: {
      url: '/mockServiceWorker.js',
    },
  })

  ;(window as unknown as { __msw?: unknown }).__msw = worker
}
