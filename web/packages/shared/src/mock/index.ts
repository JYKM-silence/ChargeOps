/**
 * Mock 层出口（**仅开发 / 演示**）。
 *
 * ★ 单独一个入口而不是塞进 `src/index.ts`：
 *   主 barrel 是两个 app 每次请求都会 import 的，而 msw 是开发工具。
 *   把 handlers 放进主 barrel 会让「按需加载、生产可消除」这件事失效 ——
 *   只要有一个文件从主 barrel 引入了任何东西，msw 就进了依赖图。
 *
 *   所以约定：**只有 `src/mocks/browser.ts` 可以 import 这里**，
 *   而那个文件本身是被 `if (__MOCK__)` 动态 import 的。
 *   ★ `MockScenario` / `MockOptions` 也从**这里**转出，不再各自声明一份：
 *     两个 app 的 `main.ts` 与 `.env.mock` 用的是同一组字符串，
 *     声明成两份的那天起，「切了场景但什么都没变」就会变成一个查不出来的问题。
 */

import type { RequestHandler } from 'msw'
import { createDb } from './db'
import { seedDb } from './seed'
import type { MockContext } from './handlers/core'
import { SLOW_DELAY_MS } from './handlers/core'
import { adminAuthHandlers } from './handlers/admin-auth'
import { chargerHandlers, connectorHandlers, stationHandlers } from './handlers/admin-station'
import { categoryHandlers, packageHandlers } from './handlers/admin-catalog'
import { stationTariffHandlers, tariffTemplateHandlers } from './handlers/admin-tariff'
import { adminMemberHandlers } from './handlers/admin-member'
import { adminOrderHandlers } from './handlers/admin-order'
import { uploadHandlers } from './handlers/upload'
import { memberAuthHandlers } from './handlers/member-auth'
import { memberAssetHandlers } from './handlers/member-asset'
import { memberBrowseHandlers } from './handlers/member-browse'
import { memberOrderHandlers } from './handlers/member-order'
import { scenarioFromEnv } from './scenario'
import type { MockOptions, MockScenario } from './scenario'

export type { MockOptions, MockScenario }
export { MOCK_SCENARIOS, isMockScenario, scenarioFromEnv } from './scenario'
/** 演示账号：登录页上那几个「一键填充」用的就是这些值。 */
export { ADMIN_PASSWORD, ADMIN_USERNAME, DEMO_PASSWORD, DEMO_PHONE, STATION_STAFF_USERNAME } from './seed'

/**
 * 构造全部 handlers。
 *
 * ★ 每次调用返回**新的** handler 数组与**新的**内存库 ——
 *   因为内存库是有状态的（start/end/settle 会真的改它），
 *   如果模块级共享一份，热更新后数据会串。
 *   ★ 这也是 `ctx` 里必须带 `db` 的原因：handler 工厂拿到的必须是**这一次**的库。
 *
 * ★★ `scenario` 与 `delayMs` 在这里落定，之后整个生命周期都不再变 ★★
 *   场景是**构建期**注入的（`VITE_MOCK_SCENARIO`），运行时要换就得刷新页面 ——
 *   做到「运行时可切」会引入一个假象：切了场景之后，已经建好的那几行数据
 *   还是按老场景种的。刷新重来才是诚实的行为。
 *
 * ★ 两条兜底的默认值：
 *   · `scenario` 缺省时读 env（读不到就是 `default`）；
 *   · `slow` 场景**没有显式 `delayMs`** 时用 `SLOW_DELAY_MS`。
 *     后者是「切到 slow 就该看见转圈」的唯一保证 —— 不写它的话，
 *     用户要配两个环境变量才能看到那个场景，而漏配的那次表现是「slow 没生效」。
 */
export function createHandlers(options: MockOptions = {}): RequestHandler[] {
  const scenario: MockScenario = options.scenario ?? scenarioFromEnv()
  const delayMs = options.delayMs ?? (scenario === 'slow' ? SLOW_DELAY_MS : 0)

  const db = createDb()
  // ★ 种子数据也是**按场景**种的：`insufficient` 把余额压到很低、
  //   `conflict` 把某把枪的 version 调旧。没有这一步的话，场景就只能靠
  //   handler 里「假装失败」，而那样的演示经不起打开 devtools 看请求。
  seedDb(db, scenario)
  const ctx: MockContext = { db, scenario, delayMs }

  // ★★ 注册顺序 = 匹配顺序，msw 是**先匹配先赢** ★★
  //   同一端内不能有重叠的路径：`/list-enabled`、`/page` 这类字面量路径
  //   必须排在 `/:id` 之前（各文件内部已经排好），而下面这些跨文件的顺序
  //   只需要保证**前缀不互相吞**：
  //   · `/api/auth/**` 不在拦截器的两个模式里，它和 `/api/admin/**` 无交集；
  //   · 上传（含 `GET /img/:name`）是独立的路径段，放在哪里都不影响别人；
  //   · `member-asset` 的 `/api/member/package/**` 与 `member-order` 的
  //     `/api/member/orders/**` 是两个不同的段，互不遮挡。
  //   顺序本身没有语义，写在这里是因为**将来加路径时必须重新检查这一条** ——
  //   一个「两个 handler 都能匹配」的新端点，症状是其中一个永远不生效。
  return [
    ...adminAuthHandlers(ctx),
    ...memberAuthHandlers(ctx),

    ...stationHandlers(ctx),
    ...chargerHandlers(ctx),
    ...connectorHandlers(ctx),
    ...categoryHandlers(ctx),
    ...packageHandlers(ctx),
    ...tariffTemplateHandlers(ctx),
    ...stationTariffHandlers(ctx),

    ...adminMemberHandlers(ctx),
    ...adminOrderHandlers(ctx),
    ...uploadHandlers(ctx),

    ...memberBrowseHandlers(ctx),
    ...memberAssetHandlers(ctx),
    ...memberOrderHandlers(ctx),
  ]
}
