import { rmSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv, searchForWorkspaceRoot, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 非 mock 构建时，把 `mockServiceWorker.js` 从产物里删掉。
 *
 * ★★ 为什么需要这个插件 ★★
 *   `public/` 里的文件会被 Vite **无条件**整目录拷进 `dist/`，
 *   而 `mockServiceWorker.js` 必须一直待在 `public/` 里 ——
 *   mock 模式（尤其是「把构建产物拿到没有后端的机器上演示」）要靠它拦截请求，
 *   所以它**入库**且不能按环境删。
 *
 *   结果就是：生产构建的 `dist/` 里会躺着一个 Service Worker 文件。
 *   它**不会被执行** —— `worker.start()` 只在 `__MOCK__` 为 true 时被调用，
 *   而那个分支已经被死代码消除了 —— 但它会让
 *   「dist 里 grep 不到 msw / mockServiceWorker」这条验收项**字面上不成立**，
 *   下次有人做这条检查时会以为摇树失效了，去翻一个根本没坏的地方。
 *
 *   → 与其把验收项改软（「请只 grep assets 目录」），不如让产物真的干净：
 *     构建结束后删掉它。
 *
 * ★ `apply: 'build'` —— dev server 不跑这个钩子，
 *   否则 `pnpm dev:mock` 会在启动时把自己的 worker 删掉。
 */
function stripMockWorker(useMock: boolean): Plugin {
  return {
    name: 'chargeops:strip-mock-worker',
    apply: 'build',
    closeBundle() {
      if (useMock) return
      // `force: true`：文件本来就可能不存在（比如 public/ 被清空了）——
      // 那不该让构建失败。
      rmSync(fileURLToPath(new URL('./dist/mockServiceWorker.js', import.meta.url)), { force: true })
    },
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const useMock = env.VITE_USE_MOCK === 'true'
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [vue(), stripMockWorker(useMock)],

    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),

        // ★★ 这条**必须排在 `'@chargeops/shared'` 前面** ★★
        //   Vite 的 alias 是**前缀替换**（`@rollup/plugin-alias` 的语义：
        //   `importee === find || importee.startsWith(find + '/')`），
        //   而且按**数组顺序**取第一个命中的。
        //   所以 `@chargeops/shared/src/styles/tokens.css` 会被下面那条
        //   `'@chargeops/shared'` 先命中，拼成 `../shared/src` + `/src/styles/tokens.css`
        //   = `shared/src/src/styles/tokens.css` —— 一个不存在的路径。
        //   ★ 症状是 `Could not load ../shared/src/src/styles/tokens.css (os error 3)`，
        //     而且**只在 build 时才炸**（dev 下 Vite 对 CSS 的处理路径不同），
        //     所以它能在 `pnpm dev` 一切正常的情况下潜伏很久。
        '@chargeops/shared/styles': fileURLToPath(new URL('../shared/src/styles', import.meta.url)),

        // ★ 共享层 alias 到**源码**而不是构建产物：省掉 tsc -w 第二进程，
        //   改一个类型两端即时生效（代价见 web/README.md）。
        '@chargeops/shared': fileURLToPath(new URL('../shared/src', import.meta.url)),
      },
      // shared 里若 import vue，必须解析到 app 这一份实例，否则会出现两个 runtime。
      dedupe: ['vue'],
    },

    // ★ 不排除的话 Vite 预打包会把它当外部依赖扫，改 shared 后不触发 HMR。
    optimizeDeps: { exclude: ['@chargeops/shared'] },

    // ★ 编译期常量：生产构建里 __MOCK__ 恒为 false → mock 分支被死代码消除。
    define: { __MOCK__: JSON.stringify(useMock) },

    server: {
      port: 5173,
      // 换机器后避免 workspace 之外的读取被 403 拦掉。
      fs: { allow: [searchForWorkspaceRoot(process.cwd())] },
      proxy: {
        // ★ 后端**零 CORS 配置**，且鉴权头是自定义头（必然触发 OPTIONS 预检，
        //   而预检会被 JWT 拦截器打成 401）。所以开发期一律走同源代理，
        //   前端 baseURL 留空、用相对路径请求，跨域问题在开发期根本不会出现。
        '/api': { target: proxyTarget, changeOrigin: true },
        // 上传后后端返回的是**绝对 URL**（http://localhost:8081/img/xxx.png），
        // 正常情况下不经过这里；但 Mock 与将来 url-prefix 改成相对路径时用得上。
        '/img': { target: proxyTarget, changeOrigin: true },
      },
    },
  }
})
