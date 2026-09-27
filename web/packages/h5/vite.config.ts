import { rmSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv, searchForWorkspaceRoot, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 非 mock 构建时，把 `mockServiceWorker.js` 从产物里删掉。
 *
 * ★ 与 admin/vite.config.ts 里是同名同实现的一对（两个包各自独立构建，
 *   共用需要再抽一层 workspace 级的构建配置，不值得）。
 *   详细理由见那一份的注释：`public/` 会被无条件整目录拷进 `dist/`，
 *   而那个文件让「dist 里 grep 不到 msw」这条验收项字面上不成立。
 */
function stripMockWorker(useMock: boolean): Plugin {
  return {
    name: 'chargeops:strip-mock-worker',
    apply: 'build',
    closeBundle() {
      if (useMock) return
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
        //   原因见 admin/vite.config.ts 的同一处注释：alias 是**前缀替换**且
        //   按数组顺序取第一个命中，所以少了这一条，
        //   `@chargeops/shared/src/styles/tokens.css` 会被拼成
        //   `shared/src/src/styles/tokens.css`，**只在 build 时**报 os error 3。
        '@chargeops/shared/styles': fileURLToPath(new URL('../shared/src/styles', import.meta.url)),

        '@chargeops/shared': fileURLToPath(new URL('../shared/src', import.meta.url)),
      },
      dedupe: ['vue'],
    },

    optimizeDeps: { exclude: ['@chargeops/shared'] },

    define: { __MOCK__: JSON.stringify(useMock) },

    server: {
      // ★ 与 admin 错开端口：两端可以同时开着联调（H5 建单 → 管理端结算 → H5 支付）。
      port: 5174,
      fs: { allow: [searchForWorkspaceRoot(process.cwd())] },
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/img': { target: proxyTarget, changeOrigin: true },
      },
    },
  }
})
