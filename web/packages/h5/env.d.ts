/// <reference types="vite/client" />

/** 见 admin/env.d.ts 的说明：它必须是被 define 替换掉的字面量，而不是运行时读的环境变量。 */
declare const __MOCK__: boolean

interface ImportMetaEnv {
  readonly VITE_API_BASE?: string
  readonly VITE_USE_MOCK?: string
  readonly VITE_MOCK_SCENARIO?: string
  readonly VITE_APP_TITLE?: string
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
