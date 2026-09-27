/**
 * Mock 演示场景。
 *
 * ★★ 每个场景都必须有**一个能被眼睛看到的落点**，否则它就是不存在的 ★★
 *   下面逐条写明了「切到这个场景之后，页面上应该看到什么」。
 *   一个没有落点的场景比没有场景更糟：演示的人切过去、什么都没发生、
 *   于是以为那条错误路径是好的。
 *
 * ★ 场景是**一次性生效**还是**持续生效**，两者混着：
 *   · `conflict` 是**一次性**的（那把枪上挂了 `pendingConflict` 标记，
 *     第一次 PUT 冲突并清掉标记）—— 因为要演示的第二件事恰恰是
 *     「刷新后重试**成功**」，持续冲突就永远走不到成功那一步；
 *   · 其余都是**持续**的。
 *
 * ★ `scenario` 只影响「把哪个演示落点摆出来」，不改变任何业务规则本身：
 *   余额不足是因为种子数据里余额真的很少、乐观锁冲突是因为那行的 version
 *   真的落后了。所以打开 devtools 看请求，看到的信封格式和其它场景一模一样。
 */

/**
 * 场景名。
 *
 * ★ 必须与 `.env.mock` 里 `VITE_MOCK_SCENARIO` 的取值逐字一致。
 *   写错一个字母不会报错 —— `scenarioFromEnv()` 会静默落回 `default`，
 *   表现是「配了场景但什么都没变」。
 */
export type MockScenario =
  | 'default'
  | 'conflict'
  | 'insufficient'
  | 'locked'
  | 'token-expired'
  | 'slow'
  | 'error'

/** 全部合法取值。顺序即文档顺序。 */
export const MOCK_SCENARIOS: readonly MockScenario[] = [
  'default',
  'conflict',
  'insufficient',
  'locked',
  'token-expired',
  'slow',
  'error',
]

export interface MockOptions {
  scenario?: MockScenario
  /** 人为延迟（毫秒）。用来验证加载态、以及连点是否真的只发了一次。 */
  delayMs?: number
}

/**
 * `slow` 场景的默认延迟。
 *
 * ★ 1200ms 而不是 5000ms：它要长到**肉眼能看见骨架屏**，
 *   又不能长到让人怀疑是不是卡死了。5000ms 会让人不停地刷新。
 */
export const SLOW_DELAY_MS = 1200

/**
 * 从 Vite 注入的 env 里读初始场景。
 *
 * ★★ 为什么要经 `import.meta.env` 而且写得这么绕 ★★
 *   两个 app 的 `main.ts` 都是 `if (__MOCK__) await import('./mocks/browser')`，
 *   而 `browser.ts` 里是 `createHandlers()` —— **不带参数**。
 *   所以「切场景」这件事只能靠构建期注入的环境变量，没有第二条路。
 *   （`stripMockWorker` / `define` 那套见各 app 的 `vite.config.ts`。）
 *
 * ★ shared 自己的 `tsc --noEmit` 里没有 `vite/client` 类型（tsconfig 的
 *   `types: []`），所以 `import.meta.env` 在类型上是不存在的 ——
 *   这里用一次窄化断言把它取出来，并且**把「根本没有这个对象」也当成正常**：
 *   mock 层的类型检查不该依赖消费方的类型声明。
 */
export function scenarioFromEnv(): MockScenario {
  const raw = (import.meta as ImportMeta & { env?: Record<string, string | undefined> }).env
    ?.VITE_MOCK_SCENARIO
  return isMockScenario(raw) ? raw : 'default'
}

/** 类型守卫。`undefined` / 拼错的字符串都返回 false，由调用方决定怎么兜底。 */
export function isMockScenario(value: unknown): value is MockScenario {
  return typeof value === 'string' && (MOCK_SCENARIOS as readonly string[]).includes(value)
}
