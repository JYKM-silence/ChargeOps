# ChargeOps 前端工程

仓库 `E:\ChargeOps` 的**后端**是 Spring Boot 4 / Java 21（约 75 个接口、378 个测试）。
这里是配套的**前端**，从零新建：

| 应用 | 技术栈 | 端口 | 面向 |
|---|---|---|---|
| `packages/admin` | Vue 3 + TypeScript + **Element Plus** + Pinia | 5173 | 运维后台（52 个接口） |
| `packages/h5` | Vue 3 + TypeScript + **Vant 4** + Pinia | 5174 | 车主手机端（25 个接口） |
| `packages/shared` | 纯 TypeScript，**无 `.vue`** | — | 两端共用的 HTTP / 类型 / 字典 / 规则 / Mock |

设计取向：**浅色精致风**。页面底 `#F5F7FA`，白卡片配极弱阴影 + 1px 浅描边分层，
深色侧边栏（`#1F2733`，不是纯黑），主色 `#0B5FFF`。

---

## 1. 快速开始

### 先装依赖（只需一次）

```bash
cd E:/ChargeOps/web
pnpm install
pnpm mock:init     # 已跑过；生成 public/mockServiceWorker.js（**这个文件要入库**）
```

> 依赖走 `registry.npmmirror.com`（见 `.npmrc`）。改回官方源：删掉 `.npmrc` 里那一行，
> 然后 `pnpm install --force` 重新解析 lockfile。

### 路线 A：连真后端（推荐，也是唯一能验证跨端链路的路线）

```bash
# 终端 1 —— 后端（需要 MySQL + Redis）
cd E:/ChargeOps
docker compose up -d mysql redis        # 或本机自备
export MAVEN_OPTS="-Dfile.encoding=UTF-8"
mvn spring-boot:run                     # :8081

# 终端 2 —— 管理端
cd E:/ChargeOps/web && pnpm dev:admin   # http://localhost:5173

# 终端 3 —— 车主端
cd E:/ChargeOps/web && pnpm dev:h5      # http://localhost:5174
```

**`/api` 与 `/img` 走 Vite 代理转发到 8081** —— 前端 `baseURL` 是空的（相对路径）。

> ★ **为什么不直连 8081**：后端**零 CORS 配置**（全仓库 0 匹配），而且鉴权头是
> **自定义头**（`token` / `authentication`，**不带 `Bearer ` 前缀**）。自定义头必然触发
> `OPTIONS` 预检，而预检会被 JWT 拦截器当成未鉴权请求打成 401 —— 浏览器会直接判失败。
> 走同源代理，跨域在开发期**根本不会出现**。
> 换后端地址：`VITE_PROXY_TARGET=http://192.168.1.10:8081 pnpm dev:admin`

### 路线 B：纯 Mock（不需要 MySQL / Redis）

```bash
cd E:/ChargeOps/web
pnpm dev:admin:mock    # :5173
pnpm dev:h5:mock       # :5174
```

Mock 由 **MSW 2** 在 Service Worker 层拦截 `/api/**`，内存里有一套会**真的变化**的数据
（建单真的冻余额、支付真的改状态、连点第二次真的被 CAS 拒绝）。页面上有一条常驻的
**紫色「演示模式」横幅**提醒当前数据是模拟的。

> ★ **Mock 模式下两个标签页的数据不共享**（各起一个 worker、各一份内存库）。
> 想演示「H5 建单 → 管理端结算 → H5 支付」这条跨端链路，**必须用路线 A**。

---

## 2. 演示账号

| 端 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 管理端 | `admin` | `123456` | 种子数据，可用。**两种模式都一样** |
| 车主端 | `13900000001` | `123456` | **真后端**用这个（`V6__demo_member.sql` 种下的演示车主） |
| 车主端 | `13800138000` | `123456` | ★ **Mock 模式**用这个（真后端数据库里**没有**这个号） |
| 车主端 | `13800000001` | — | ★ **不要用**，见下 |

> ★★ **车主端演示号在两种模式下不是同一个** ★★
>   《路线 A（真后端）》 `13900000001` —— 它是后端 `V6__demo_member.sql` 种下的车主。
>   《路线 B（Mock）》 `13800138000` —— 即 `shared/src/mock/seed.ts` 里的 `DEMO_PHONE`。
>
>   拿真后端的号去登 Mock，会得到**「手机号或密码错误」**：mock 的车主表里
>   压根没有 `13900000001`（那个号在 mock 里是**管理员**的手机号，在 `staff` 表里）。
>   这不是鉴权坏了，是查错了号。反过来也一样。
>   两端各自导出常量（真后端走 Flyway `V6__demo_member.sql`，mock 走 `DEMO_PHONE`），
>   想改就改 `seed.ts` 里的 `DEMO_PHONE` / `DEMO_PASSWORD`。

> ★★ **`13800000001` 无法用密码登录** ★★
> 种子数据里这个车主的 `password` 字段是 **`NULL`**，`BCrypt.matches` 比对 null 必然失败。
> 拿它演示会得到「密码错误」，而密码并没有错 —— 排查方向会被完全带偏。
> 这条也写在 H5 登录页上，省掉以后每一次「为什么登不上」的追问。

---

## 3. 命令

在 `web/` 下执行：

| 命令 | 作用 |
|---|---|
| `pnpm dev:admin` / `pnpm dev:h5` | 开发服务器（连真后端） |
| `pnpm dev:admin:mock` / `pnpm dev:h5:mock` | 开发服务器（Mock 模式） |
| `pnpm build` | 两端一起构建（**含 `vue-tsc` 类型检查**，类型错会直接构建失败） |
| `pnpm build:admin` / `pnpm build:h5` | 单独构建 |
| `pnpm typecheck` | 只做类型检查，不产出 |
| `pnpm preview:admin` / `pnpm preview:h5` | 预览构建产物（4173 / 4174） |
| `pnpm mock:init` | 重新生成 `mockServiceWorker.js` |

**验收：`pnpm build` 之后 `dist/` 里**搜不到 msw 的**运行时代码** —— 见第 5 节。
★ 判据是**运行时标记**（`setupWorker` / `createHandlers` / `msw/browser` / `@mswjs`），
**不是**字符串 `msw`：车主端「关于」页那行依赖版本清单里印着字面的 `msw: '2.15.0'`，
拿 `grep msw` 去查会**误报**。

---

## 4. 架构约定

### 4.1 `@chargeops/shared` 直接吃源码，没有构建步骤

`shared/package.json` 的 `exports` 指向 `./src/index.ts`，两个 app 在 `vite.config.ts` 里
把它 alias 到 `../shared/src`，并配了 `optimizeDeps.exclude` + `resolve.dedupe: ['vue']`
+ `server.fs.allow`。

好处：改一个类型两端**即时生效**，不用多开一个 `tsc -w` 进程。

**红线：`shared/` 里不放 `.vue`** —— 那会引入两份 vue 实例的风险（`dedupe` 只在打包层
合并，运行时若真加载了两份，`inject`/`reactive` 会失效）。

### 4.2 请求信封

后端一律返回 `Result<T>`：`{ code, msg, data }`，**`code === 1` 是成功**。

`createHttp` 的响应拦截器已经把它拆开：调用点拿到的是 **`data` 本身**，不是信封。

三种失败形状，**都要处理**：

1. **业务失败**：HTTP 200 + `code: 0` + 中文 `msg` → 抛 `BizError`，**toast 后端那句中文**
   （后端是文案真源，前端不重写、不美化）。
2. **HTTP 401 且响应体为空** → `onUnauthorized()`，**幂等** —— 并发多个 401 只跳一次登录。
3. **`code:0` 且 msg 是「登录状态已失效，请重新登录」** → 同样走登出。
   这是 `BaseContext` 为空时 **Service 层**的形状，与拦截器 401 是两条不同路径。

> 超时 / 断网 / 5xx 各自有固定文案。**永远不把 axios 原始 message 展示给用户。**

### 4.3 分页

`pageNum` / `pageSize`（后端上限 **100**），返回 `{ total, records }`。

★ 后端 `PageHelper` 开了 **`reasonable: true`** —— 越界的 `pageNum` 会被**静默修正到最后一页**。
所以「加载完最后一页之后再请求下一页」拿到的是**最后一页的重复数据**，不是空数组。

- 管理端 `usePagedList`：先归一成 `{ total, records, hasNext }`，`pageNum` 越界时回落并重发一次。
- H5 `usePagedList`：**用 `total` 判断 `finished`**，不能只看本次返回条数。

不做这件事的后果很具体：删掉末页最后一条后列表显示「空」，而上一页其实有数据 /
H5 无限滚动**无限重复最后一页**。

### 4.4 防重复提交（4 层）

| 层 | 位置 | 挡什么 |
|---|---|---|
| ① `useSubmitLock` | 按钮级 | 连点。H5 额外 300ms 节流 |
| ② `withActionLock(key, fn, ttl)` | 业务级 | ★ 后端**没有幂等键**的资金动作 |
| ③ GET in-flight 合并 + 切路由 abort | 传输级 | 同一 GET 并发重复发 |
| ④ **不做乐观更新** | 策略级 | 写成功后重拉当前页/详情 |

★★ **`withActionLock` 是「抛异常」语义，不返回失败值** ★★
它挡住的第二次调用会抛 `ActionLockedError`。所以正确用法是：

```ts
const { pending, run } = useSubmitLock()
const outcome = await run(() => withActionLock(`refund:${id}:${amount}`, () => refundOrder(id, { amount }), 5000))
if (outcome.status !== 'done') return   // 'skipped' = 被挡（静默）；'failed' = 请求失败（http 层已 toast）
```

写成 `const r = await withActionLock(...)` 然后判 `r.status` 是**错的** ——
那会让「成功」也走异常分支，表现为「操作生效了但弹窗不关、列表不刷新」。

### 4.5 主题

- 令牌是**唯一真源**：`shared/src/styles/tokens.css`（`--co-*`）。
- **Element Plus**：只覆盖 CSS 变量，不引 sass。
  ★ EP 的 `-light-3/5/7/8/9` 与 `dark-2` 是**预计算的 rgb 常量**，不会随 `--el-color-primary` 重算 ——
  只改主色会出现「主色变了，hover / plain / disabled 还是默认蓝」。所以那几个档位用
  `color-mix(in srgb, var(--co-primary) N%, #fff)` **现算**（需要 Chrome 111+ / Safari 16.2+，
  内部后台可接受）。不选 SCSS（`@forward ... with ($colors: ...)`）的理由：它引入 sass 依赖、
  每次冷启动编译整包 theme-chalk，且与 tokens.css 形成**两套真源**；而我们要调的东西 EP
  已全部暴露为变量，只有主色阶一处需要手写，**不足以换回这个构建成本**。
- **Vant 4**：★ **已核实 `vant@4.10.2` 产物里 SCSS 文件数为 0** ★ ——
  网上大量 Vant 2 的 less / `modifyVars` 资料会把人带偏，直接排除。
  好消息是 **Vant 没有 `-light-N` 色阶**，组件直接引用主变量，
  所以 `h5/src/styles/vant-overrides.css` 里改一个 `--van-primary-color` 就全站生效。

---

## 5. Mock 层

**选型 MSW 2**（网络层拦截）。

- 否决 `vite-plugin-mock-dev-server`：它只在 dev-server 里生效，**构建产物没法带 mock 跑**，
  而「离线演示」是明确需求。
- 否决自写 axios adapter：要自己实现路由匹配 / 参数解析 / 延迟 / 断网，越写越像在重造 MSW，
  且与 axios 强耦合。

### 开关与场景

开关是**编译期常量 `__MOCK__`**（`vite.config.ts` 的 `define`），值来自 `VITE_USE_MOCK`。

> ★ `VITE_USE_MOCK` 读的是 `.env.mock` 文件，**不是命令行传参**。
> `packages/{admin,h5}/.env.mock` 必须存在 —— 文件缺失时 `VITE_USE_MOCK` 是 `undefined`，
> `=== 'true'` 静默判成 `false`，**命令照跑、界面正常、请求全部打到真实后端**。
> 这是最难发现的一类故障，所以用显式文件钉死。

场景通过 `VITE_MOCK_SCENARIO` 选（值见 `packages/*/.env.mock`）：`default` / `conflict` /
`insufficient` / `locked` / `token-expired` / `slow` / `error`。

> ★ 场景名字必须与 `shared/src/mock/index.ts` 的 `MockScenario` 联合类型**逐字一致**。
> 写错一个字母**不会报错**，只会静默落回 `default`。

### 生产构建里的隔离

入口是：

```ts
if (__MOCK__) await import('./mocks/browser')
```

`__MOCK__` 是编译期字面量，生产构建恒为 `false`，于是整个 `if` 块**连同 msw 一起被死代码消除**。

**验收（每次改完 mock 相关代码都该跑一次）**：

```bash
cd E:/ChargeOps/web && pnpm build
# ★ 查的是**运行时标记**，不是字符串 "msw" —— 见下面的说明
for p in admin h5; do
  ls packages/$p/dist/mockServiceWorker.js 2>/dev/null && echo "★ $p: worker 文件没删干净"
  for pat in setupWorker createHandlers "msw/browser" "@mswjs"; do
    grep -rl "$pat" packages/$p/dist/ 2>/dev/null && echo "★ $p: msw 进了包（$pat）"
  done
done
echo "=== 走完上面没有任何 ★ 才算干净 ==="
```

> ★★ **别用 `grep msw`** ★★
> 车主端「关于」页（`h5/src/views/profile/AboutView.vue`）会把依赖版本清单
> 渲染到页面上，其中一行是**字面的** `msw: '2.15.0'`。
> 那是一段文案，不是 msw 的代码 —— 但裸 `grep msw` 会命中它，
> 于是这个验收**永远报红**，而要不了几次大家就会当成噪音忽略掉，
> 真正摇不干净的那天反而没人看。
>
> 同理，`dist/mockServiceWorker.js` 是 `public/` 目录里的静态文件，
> Vite **无条件**把它复制进 `dist/`，跟死代码消除无关 ——
> 它是靠 `vite.config.ts` 里的 `stripMockWorker` 插件在 `closeBundle` 时删掉的。
> ★ 新加构建目标（或换打包器）时要记得带上这个插件，否则这个文件会悄悄回来。

> ★ **不要**把 `mocks/browser.ts` 改成静态 import，也不要从别处静态 import 它 ——
> 一旦变成静态依赖，msw（约 200KB，纯开发工具）就会进生产包。
> `shared/src/mock/index.ts` 单独一个入口也是这个原因：主 barrel 是每次请求都会 import 的，
> 只要有一个文件从主 barrel 引入了 msw，按需加载就失效了。
> **约定：只有 `src/mocks/browser.ts` 可以 import `@chargeops/shared/mock`。**

---

## 6. ★ 已知限制（**先读这一节再看代码**）

这些都是**后端既有行为**决定的，前端改不掉。页面上大多已就地标注。

### 6.1 本次不产出生产部署配置

按决策，只保证本地开发可用。但要知道：**上线必须用 Nginx 同源反代 `/api` 与 `/img`**，
否则跨域走不通 —— 后端零 CORS，且不会为一个 378 测试通过的项目去改。

### 6.2 Mock 实质是「第二套后端」，会**静默发散**

为了让 9 个订单状态、分段跨尖峰平谷、CAS 拒绝、冻结余额都能演示，handlers 必须**复刻**
后端的状态机与金额口径。后端一旦改行为，mock **不会报错**，只会让演示变得不可信。

缓解：枚举单一真源（都从 `shared/constants` 取）+ 启动自测断言（9 状态齐全、
`Σ分段 == 总额`）。
**Mock 只保证契约形状与错误文案，不保证数值口径。**

### 6.3 工作台营收是**有偏样本**

后端**没有任何统计接口**。总数与状态分布用 `pageSize=1` 拿**精确 total**；
营收 / 电量只能**客户端聚合**，上限 5 页 × 100 = **500 单**。
→ 页面上常驻口径标注（「近 7 天 / 样本 500 单 / 仅供参考」），**不做成看起来精确的假数字**。

### 6.4 编辑表单**无法清空字段**

所有 `UpdateDTO` 都是「**不传 = 不修改**」。所以 `originPrice` / `effectiveTo` / `remark`
这类字段**设了就抹不掉**（传空串会被当成什么，取决于后端实现，后果不可预期）。
→ 表单**不放「清空」按钮**，相关字段加说明文字。

### 6.5 乐观锁**只有充电枪**

`Charger` 有 `version`；**桩 / 站点 / 套餐 / 模板都没有**。两人同改会**后写覆盖先写且无提示**。

- 枪：`PUT` **必须带 `version`**，冲突时后端给固定文案，前端引导用户「重新拉取再改」。
- 其他：前端只能在编辑弹窗里比对 `updateTime` 做**客户端伪乐观锁**挡误操作，
  **挡不住真正的并发** —— 真要解决得改 DDL。

### 6.6 禁用车主的 **7 天窗口**

鉴权拦截器**不查库** —— 它只验 JWT 签名和有效期。所以「禁用车主」**不会撤销已发出的 token**，
被禁用的车主最长还能用满 **7 天**（车主端 TTL）。

后端的补救是在**资金端点**上拦（那些会查库），但**只读接口不拦**。

前端把这个窗口压到「不主动操作就发现不了」：

| 信号 | 触发时机 |
|---|---|
| **探针** `GET /api/member/me` | 启动 / 路由守卫进需登录页（**30 秒节流**）/ 回前台 |
| `AccountLocked` 文案 | 任何请求返回该文案（`http.ts` 的 `accountLockedLogsOut` 兜底） |

★★ 探针的判据是 **HTTP 200 且 `status === 0`**，**不能只看 HTTP 状态码** ★★ ——
后端禁用是「返回成功，但 body 里 `status` 是 0」。只看状态码会 **100% 漏判**。

★★ 探针必须**软失败** ★★：探针本身网络错误 / 超时**绝不登出**。
否则在地铁里断网一次，用户就会被踢出登录 —— 而他的登录态其实完全有效。
只有「明确拿到了 `status === 0`」才登出。

> 两条信号**都要有**：探针要等用户进页面才跑，兜底要等用户发请求才触发，
> 单独任何一条都会留下「什么都没做所以一直没发现」的空档。
> **只读窗口客观存在，前端关不掉。** 演示与验收时**必须能复现并解释**。

管理端对 `/api/admin/staff/me` 做同样的处理。

### 6.7 金额与时间**全程字符串化**

- 后端金额是 `BigDecimal`，且「**只在最后取整一次，保证 Σ分段 == 总额**」。
  前端若用浮点求和会出现**分位误差**，从而误判后端算错。→ 统一走 `utils/money.ts`（整数分）。
- **不要用 `new Date(str)`**：后端时间格式是 `yyyy-MM-dd HH:mm:ss`（**无时区、非 ISO**），
  部分浏览器会直接得到 `Invalid Date`。→ 统一走 `utils/datetime.ts` 的自写解析。

### 6.8 字段名拼错会被**静默忽略**

后端 `FAIL_ON_UNKNOWN_PROPERTIES` 已关闭。表现是「**参数改了没反应**」而不是报错 ——
排查方向每次跑偏。

→ API 层**强制类型收口**（每个接口一对 request / response 类型，禁 `any`）。

> 这也是共享类型**刻意不把字段标成必填**的原因。比如 `OrderStartDTO.startMeterKwh`
> 后端**没有 `@NotNull`**，如果前端把它标成必填，调用点就得写 `as` 强转 ——
> 而 `as` 会**连拼错的字段名一起掩盖掉**。

### 6.9 **没有行级权限**

`staff` 表有 `role` / `stationId` 字段，但**没有任何后端代码使用它们** ——
任何登录的运维都能改任何站点。

→ 前端**不按 role 隐藏菜单**：那会制造「有权限却看不到」的假象，比直接显示更难排查。
只在本文档说明。

### 6.10 两端主题机制**不对称**

Element Plus 是 12 档预计算色阶，Vant 是单一 primary。颜色一致性靠**一次专门的视觉走查**保障，
**没有自动化**。

（另：`shared/src/mock/db.ts` 里金额用整数分、时间用 `Date`，
出口才 format —— 见该文件头。这条不是限制，但是读 mock 代码前必须知道的前提。）

### 6.11 ★「支付失败后又支付成功」的订单，时间轴会自相矛盾

**这是后端的数据问题，不是前端渲染错了** —— 已写进根 `README.md` 的已知缺口。

后端 `pay()` 的 CAS 收 `status IN (4, 6)`（所以**支付失败(6) 能重新支付并成功**），
但它写事件时 `from` **写死成 `PENDING_PAY(4)`**、没有传订单的真实当前状态。
于是 6→5 这条边落库时是 `from_status = 4, to_status = 5`。

两端的时间轴都是**如实**渲染 `fromStatus → toStatus` 的
（`admin/src/views/order/OrderDetailView.vue` 的 `transitionText`、
`h5/src/views/order/OrderDetailView.vue` 同名字段），所以车主会看到：

```
待支付 → 支付失败
待支付 → 已支付      ← 来源状态错了，实际是从「支付失败」来的
```

★ **前端的立场**：**不做客户端推断去「修」它**。
按「上一条事件的 toStatus 反推这一条的 fromStatus」会把前端变成订单状态的**第二个真源** ——
这正是本项目一直在避免的事（见 4.2 / 6.2），而且后端一旦改了事件写法，
这段推断会**静默**退化成错误答案。
正确做法是改后端那一行（把 `from` 换成 CAS 之前解析出来的真实状态），前端保持如实渲染。

★ 演示与验收时会遇到，**别当成前端的 bug 去查**。

### 6.12 ★ 时间轴上的「**谁做的**」也是错的（同一个根因）

时间轴除了渲染 `fromStatus → toStatus`，还渲染 `sourceLabel`（`用户端` / `设备上报` /
`定时任务` / `运维` / `支付回调`）。**这一列同样大面积记错** —— 已写进根 `README.md`。

最典型的一条：**结算**事件的来源被写死成 `定时任务`，但 `settle()` 全项目只有一个调用点
（管理端接口），**没有任何定时任务会调它** —— 所以每次结算都显示成系统自动做的，
而实际上每一次都是运维点的。对照它是同类动作的 `refund`：那个用的是 `运维` + 真实操作人 id。

★ **前端的立场同 6.11**：如实渲染后端给的 `sourceLabel`，**不猜**。
★ 演示时的后果要提前知道：**别照着时间轴讲「这一步是谁做的」** —— 至少在结算那一步是错的。

---

### 6.13 ★★ `isLoggedIn` **不能直接读 localStorage** —— 会让登录整个失效 ★★

这是本次开发中真实发生过的 bug，两端的 `stores/session.ts` 都中招过，
**改动这两个文件前务必先读完本节**。

**症状**：点「登录」→ 后端返回成功 → 弹「登录成功」→ **界面不动，还停在登录页**。
刷新也进不去。后端日志里登录接口是 200，token 也确实发出来了。

**根因**：把 `isLoggedIn` 写成了 pinia 的 getter 去读 `SessionStore`（localStorage）：

```ts
// ✗ 错误
isLoggedIn: () => adminSession.getToken() !== null,
```

pinia 的 getter **就是 Vue 的 `computed`**，而 `computed` 只在**响应式依赖**变化时重算。
`getToken()` 读的是 localStorage，不是响应式数据 —— 这个 getter **一个依赖都没有**，
首次求值之后**永远返回那一刻的结果**。首屏导航时还没登录，算得 `false` 并被缓存；
`login()` 把会话写进 localStorage 之后，**没有任何响应式依赖发生变化**，
守卫读到的仍然是缓存的 `false` → 当场被打回 `/login`。

**修法**：在 state 里放一个**响应式镜像字段**，`isLoggedIn` 读它。

```ts
state: () => ({ token: null as string | null }),      // ★ 镜像，只为提供响应式依赖
getters: { isLoggedIn: (state) => state.token !== null },
```

**★ 四个同步点一个都不能漏**（漏一个就退回上面的 bug）：

| 位置 | 为什么 |
|---|---|
| `login()` / `_applyLogin()` | 写会话之后。**少了它 = 登录后跳不过去** |
| `restore()` | 刷新后从 localStorage 恢复 |
| `logout()` | 少了它登出后 `isLoggedIn` 还是 `true`，守卫把人留在需要登录的页面上 |

`restore()` 里那句必须在**所有 early-return 之前**、且用 `getToken()`（会判过期）
而不是 `session.token` —— 过期的凭证不该算已登录，否则会变成「进得去页面、每个请求都 401」。

### 6.14 ★ 会话恢复**必须放在路由守卫里**，不能只靠 `App.vue`

`main.ts` 的顺序是 `router.isReady().then(() => app.mount('#app'))` ——
也就是**首次导航的守卫跑在 `App.vue` 之前**。如果 `restore()` 只在 `App.vue` 里调，
那么刷新任意受保护页面时，守卫判定那一刻镜像字段还是 `null`，
**带着完全有效的会话也会被弹回 `/login`**。

两端的 `router.beforeEach` 第一句都是 `session.restore()`。
它是幂等的（内部有 `if (this.profile/this.restored) return`），每次导航调用没有额外开销。

---

## 7. 车主端 UI 里**必须说清**的三件事

否则会被当成 bug 反复提：

1. **待支付超 15 分钟会被后端扫成「支付失败」** —— 定时任务每 5 分钟一轮。
   所以倒计时到点**不等于立刻变状态**，文案要说「**预计**超时」，
   到点后主动重新拉一次详情。（H5 订单详情页）
2. **权益过期扫描 30 分钟一轮** —— 存在「已过期但 `status` 还是 1（生效中）」最长 30 分钟的状态。
   所以权益列表**不能只信 `status`**，必须同时展示 `expireTime`，客户端自己先标「已到期（待系统确认）」。
3. **订单与钱包流水都只有分页参数** —— 订单多一个 `status`。
   **做不了时间范围 / 类型筛选**。→ **不放假筛选器**，直接写明。

另外两条后端事实，前端 UI 已据此设计：

- **「支付失败」(6) 可以重新支付**：后端 CAS 是 `status IN (4, 6)`，6→5 是通的。
  → 详情页对 6 **必须给「重新支付」按钮**，不能只引导充值。
- **`ChargingOrderEventVO.eventType === 'abnormal'` 有两种含义**：
  一是余额不足时后端**自己**把订单 4→6，二是其他异常。
  → 事件流渲染**必须结合 `fromStatus` / `toStatus`**，不能只看 `eventType`。
  另外 `fromStatus`/`toStatus` 是**裸 Integer 没有 label**，要用 `shared/constants` 里的表转。

---

## 8. 排错

| 症状 | 原因 / 处理 |
|---|---|
| 请求全部 401 | 鉴权头是**自定义头**（`token` / `authentication`），**不带 `Bearer `**。别「顺手规范化」 |
| 跨域报错 | 走 Vite 代理，别直连 8081。后端零 CORS |
| 改了 `shared` 没热更新 | 确认 `optimizeDeps.exclude: ['@chargeops/shared']` 还在 |
| Mock 模式没生效 | 检查 `packages/{admin,h5}/.env.mock` **文件是否存在**（见第 5 节） |
| 生产包里发现 msw | 有人把 `mocks/browser.ts` 改成静态 import 了，或者从主 barrel 引了 `shared/mock` |
| 上传成功但图片不显示 | 后端上传返回 `Result<String>`（**裸 URL 字符串，不是 `{url}` 对象**）。`uploadFile()` 已按字符串收 |
| Mock 模式下图片不对/显示成乱码 | ★ 见下方「Mock 的图片 URL 指向真后端」—— 多半是打到真后端去了 |
| 列表翻到某页显示空 | 后端 `reasonable: true` 的越界修正。用 `total` 判到底，别用返回条数 |
| 「参数改了没反应」 | 字段名拼错被静默忽略（6.8）。回去对类型定义 |
| 功能生效了但弹窗不关 | 多半是 `withActionLock` 的抛异常语义用错了（4.4） |
| ★ **登录成功但不跳转，还停在登录页** | `isLoggedIn` 被改回读 localStorage 了 → 见 **6.13**。后端是好的，token 也是好的 |
| ★ **刷新任意页面都被弹回登录页** | 守卫里那句 `session.restore()` 被删了 → 见 **6.14** |

---

## 9. 后端联调笔记

- 后端端口 **8081**；前端相对路径请求 → Vite 代理。
- 上传后的文件由后端以 `/img/**` 提供（**不是同源时**后端返回绝对 URL，
  `resolveAssetUrl()` 负责归一化）。
- ★★ **Mock 的图片 URL 指向真后端** ★★
  mock 的上传 handler 逐字复刻了 `application.yml` 的 `url-prefix`，所以它返回的是
  **`http://localhost:8081/img/xxx.png`** —— 那个地址指向**真后端**，不是 mock。
  整套之所以还能work，全靠 `resolveAssetUrl()` 把 origin 剥掉变成同源 `/img/xxx.png`，
  MSW 才拦得到。
  - **别绕过 `resolveAssetUrl()` 直接用返回的字符串**。粘进浏览器新标签页、或直接写进
    `<img src>`，都会真的打到 8081。真后端**没在跑**时是 `ECONNREFUSED`；在跑时见下条。
  - ★ 真后端的 `/img/**` **不鉴权**（拦截器只挂了 `/api/admin/**` 和 `/api/member/**`，
    见 `WebMvcConfiguration`），所以跨过去**不会**给你 401 把你叫醒 —— 它会：
    - 图**存在** → `200 image/png`，**静默显示一张真后端的图**（最坏的一种：看起来一切正常）；
    - 图**不存在** → ★ **HTTP 200** + `{"code":0,"data":null,"msg":"未知错误"}`。
      注意是 200 **不是 404** —— 按 `res.ok` 判断会以为成功，只有浏览器解码失败才露出破绽。
  - 这两条都不是 mock 的问题，是「跨到了另一个系统」。
- **新增的两个车主端接口**（本次为 H5 补的，见下）：
  - `GET /api/member/station/page`
  - `GET /api/member/connector/by-station/{stationId}`
- ★ `listByStation` **不排序**（`gun_no` 是字符串，排出来是 `1, 10, 2`），
  **后端注释明确要求前端重排** → 见 `h5/src/api/station.ts` 的 `sortConnectors()`
  （先可用、再按桩分组、枪号用 `numeric: true` 的自然序）。
- ★ `MemberConnectorVO.available` 是**快照不是预订** —— 前端**必须处理
  「`available=true` 但建单失败」**这个分支（正常并发，不是 bug），失败时要**重新拉枪列表**。
- `POST /api/member/orders` 是「**建单即按预估价冻结余额**」，所以建单失败最常见的
  原因是**余额不足**而不是枪不可用 —— 两种失败要引导到不同地方（充值 / 换把枪）。
  但**后端只给一句中文 msg**，只能按文案分流。
- 后端的 TTL 常量（与前端 `shared/src/http/session.ts` 对齐）：
  管理端 **2 小时**，车主端 **7 天**。

### 9.1 AI 运维助手（`/api/admin/ai-ops/*`）

三个端点：`POST /chat`、`GET /knowledge/search`、`POST /knowledge/reload`。前端三条约束：

- ★★ **`chat` 是慢请求，调用点必须覆盖超时** ★★
  后端同步等模型多轮往返（有工具调用时至少两轮），读超时配的 60 秒就是上限。
  而 `createHttp` 的默认超时是 **20 秒**（`shared/src/http/createHttp.ts`）。
  `api/aiOps.ts` 给它单独开了 **90 秒**。

  不覆盖的后果不是「慢一点」：**超时的那一侧会变成前端** —— 用户看到失败去重试，
  而服务端第一次调用还在跑。助手是会**建工单**的，重试一次就是两张。
  同理，`AiChatView.vue` 在等待期间**硬性禁止重发**（不是体验优化）。

- ★ **`sessionId` 由后端生成后回传，下一轮必须带回去**，否则助手不记得上一句。
  它存在**服务端内存**里，重启即失效 —— 别存 localStorage 当长期标识
  （刷新页面等于开新会话，这是对的）。
- ★ **`references` 可能是空数组**，那是「知识库里没有对应文档」，不是接口报错。
  页面必须把这种情况**显式说出来**，否则用户会把模型按通用知识编的答案
  当成有据可查的结论。
- 排查「助手答得不对」的第一步是 **`GET /knowledge/search` 这个只检索不调模型的端点**：
  召回为空 → 语料问题（重问一百次也没用，要补文档）；召回正常但答得不好 →
  提示词或模型问题。这条分支判断在 `AiChatView.vue` 的「检索工具」侧栏里。
- `POST /knowledge/reload` **返回 `0` 不能报成功** —— 那说明一条文档都没读到
  （目录空 / 打包没带上），报成「重建完成」会让人带着空知识库去提问然后怪模型。

- ⚠ **这三个端点没有 Mock，两条 AI 页面（`/ai-assistant`、`/maintenance-ticket`）
  在纯 Mock 模式下用不了。** 这是**故意的**：AI 页面的全部价值在于
  「真的调了模型、真的检索了向量库」，mock 一个固定答案既演示不了工具调用，
  又会让「Mock 与后端静默发散」这条已知限制多出一个高危面 —— 那恰好是
  最容易被当成「功能正常」的一种发散。要在离线环境演示 AI，请按
  `README.md` 装 Ollama + pgvector，走真后端。

---

## 10. 目录索引

```
web/
├── package.json              # 根脚本（dev:admin / dev:h5 / dev:*:mock / build / typecheck）
├── pnpm-workspace.yaml       # packages/*，含 msw 的 allowBuilds 白名单
├── tsconfig.base.json        # strict + noUnusedLocals + verbatimModuleSyntax
├── .npmrc                    # engine-strict=false / 国内镜像
└── packages/
    ├── shared/src/
    │   ├── http/             # createHttp / session / locks / options / upload / messages / errors
    │   ├── types/            # admin.ts / member.ts —— 约 77 个接口的入参返回类型
    │   ├── constants/        # 字典（抄自 com.chargeops.enumeration）+ 订单状态/事件 + tone
    │   ├── domain/           # orderRules（状态机动作表）/ queryDefaults / segmentColor / updateDiff
    │   ├── utils/            # money（整数分）/ datetime（自写解析）/ validators
    │   ├── styles/tokens.css # ★ 全部 --co-* CSS 变量的唯一真源
    │   └── mock/             # db.ts（可变内存库）+ seed.ts（种子）+ scenario.ts（场景）
    │       │                 # index.ts 只做一件事：把下面这些拼成 createHandlers()
    │       └── handlers/     # 15 个文件 / 78 条路由
    │           ├── core.ts vo.ts texts.ts timecodec.ts order-rules.ts   # 共用底座
    │           │   # core（ok/fail/paginate/守卫）· vo（实体→VO）· texts（后端文案逐字）
    │           │   # timecodec（时间编解码）· order-rules（状态机/CAS/冻结口径）
    │           ├── admin-*.ts   # auth / station / catalog / tariff / member / order
    │           ├── member-*.ts  # auth / browse / asset / order
    │           └── upload.ts
    ├── admin/src/            # views / api / router / stores / utils / styles / mocks
    │   │                     # views/ai（AI 运维助手 · 对话 + 检索工具）· views/ticket（运维工单）
    │   │                     # utils/chatText.ts（模型输出的极简排版，**不引 markdown 库、不碰 v-html**）
    └── h5/src/               # views / api / router / stores / composables / components / styles / mocks
```

### 依赖矩阵（实际装上的版本）

Vue `3.5.43` · Vite `8.3.0` · Element Plus `2.14.6` · Vant `4.10.2` ·
Pinia `4.0.3` · Vue Router `5.3.1` · MSW `2.15.0` · axios `1.20.0` ·
**TypeScript `5.9.3`** · vue-tsc `3.3.11` · Node `26.2.0` / pnpm `11.22.0`

> ★ **TypeScript 装的是 5.9.3，而不是计划里写的 7.0.2。** 其余版本（Vite 8、
> vue-router 5、pinia 4）都按原定装上去了。
>
> ⚠ **这条回落的原因没有留下记录，需要复核。** 搭建当时做过一次版本回退，但没有把
> 失败信息写下来 —— 而 `vue-tsc@3.3.11` 的 peer 声明其实是 `typescript: ">=5.0.0"`，
> **并不排除 TS 7**。也就是说这条回落**可能是不必要的**。
>
> 想升级的话：把三个 `package.json` 里的 `typescript` 改成 `7.x`，
> 跑 `pnpm install --force`，然后 `pnpm typecheck` ——
> 类型检查是 `pnpm build` 的**前置步骤**（类型错就构建失败），所以两端都过了才算数。
> 升不上去就把失败的那条原始报错**补到本节**，别再让它没有记录。
