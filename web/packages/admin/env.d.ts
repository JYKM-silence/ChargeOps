/// <reference types="vite/client" />

/**
 * 由 vite.config.ts 的 define 注入的编译期常量。
 *
 * <p>★ 它必须是**字面量**而不是 `import.meta.env.VITE_USE_MOCK`：
 * 构建时 `__MOCK__` 被替换成 `false`，于是 `if (__MOCK__) await import('./mocks/browser')`
 * 整块被 Rollup 当作死代码消除，msw 一行都不会进产物；
 * 而 `import.meta.env.VITE_USE_MOCK` 在运行时才求值，整个 mock 模块会被打进包里。
 */
declare const __MOCK__: boolean

interface ImportMetaEnv {
  /** 接口基地址。开发期留空走同源相对路径，由 vite proxy 转发到后端。 */
  readonly VITE_API_BASE?: string
  /** 是否启用 Mock。注意：这个值是给 define 用的，运行时判断请用 __MOCK__。 */
  readonly VITE_USE_MOCK?: string
  /** Mock 初始场景，见 shared/src/mock/scenario.ts */
  readonly VITE_MOCK_SCENARIO?: string
  /** 浏览器标题 */
  readonly VITE_APP_TITLE?: string
  /** vite proxy 的后端地址，默认 http://localhost:8081 */
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
