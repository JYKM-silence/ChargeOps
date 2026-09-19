# ChargeOps · 新能源充电桩运营平台

面向充电站运营商的后端系统：管理站点、充电桩与充电枪，按**分时电价**（尖峰平谷）计费，
处理车主扫码充电的完整订单链路、钱包余额冻结与结算，并接收设备遥测上报。

> 当前进度：**P0 + P1a + P1b + P1c-1 已完成**（217 个测试全绿）。
> P0 = 骨架 + 建表 + 登录鉴权；P1a = 站点/桩/枪/套餐/计费模板/站点计费绑定的管理端 CRUD（31 个接口）；
> P1b = `BillingEngine` 计费引擎（纯函数）；
> P1c-1 = 车主端底座（自助注册/登录、我的信息、我的车辆 CRUD + `category` / `member` 管理端）；
> P1c-1.5 = `member_package` 持有权益的表结构（DDL + 实体 + Mapper，无 Service —— 见决策 13）。
> 订单链路（P1c-2 钱包、P1c-3 订单状态机）按下方路线图推进。

---

## 为什么这个业务的复杂度是真的

充电桩不是「换个名字的电商」——它的难点长在业务本身，不需要硬塞秒杀来制造压力：

| 维度 | 典型 CRUD 业务 | 充电桩运营 |
|---|---|---|
| 履约主体 | 人工操作 | 设备协议驱动，无人介入 |
| 计费方式 | 固定单价 × 数量 | **分时电价 × 充电量**，电费与服务费分离 |
| 订单时长 | 秒级 ~ 分钟级 | 数十分钟到数小时，**横跨多个计费时段** |
| 数据来源 | 用户点击 | **设备上报**（心跳 / 遥测，万级设备高频写入） |
| 一致性难点 | 库存扣减 | **余额冻结 + 三方对账** |

几个直接由此推出的设计约束：

- 一条充电订单可能跨过「低谷 → 平段 → 尖峰」三个时段，结算必须**按时间切分成多段分别计价**，且 `Σ分段金额` 必须严格等于订单总额（否则对不上账）
- **电价会调整，但在途订单和历史订单不能被影响** —— 所以计费模板 id 在下单时冻结进订单，结算阶段完全不碰模板表
- 一把充电枪同一时刻只能有一个订单，两个用户同时扫码必须有一个失败，且**不能靠 `synchronized`**
- 设备会**重复上报**同一条「充电结束」事件，重复上报绝不能重复扣款

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | **4.1.1** | 当前稳定线 |
| Java | **21** | |
| MySQL | 8.4 | 8.0 已停止官方支持 |
| Redis | 7.4 | |
| MyBatis | mybatis-spring-boot-starter 4.1.0 | |
| Druid | druid-spring-boot-**4**-starter 1.2.28 | ★ artifactId 里的 `-4-` 不能少 |
| springdoc-openapi | 3.1.1 | 替代 knife4j / springfox |
| jjwt | 0.12.7 | |
| Jackson | **3**（`tools.jackson.*`） | Boot 4 的默认 JSON 库 |

---

## 快速开始

### 前置条件

- JDK 21、Maven
- MySQL 8（本机服务或 Docker）
- Redis

### 1. 建表

```bash
mysql -uroot -p < sql/chargeops.sql
```

脚本是**幂等**的（每张表都是 `CREATE TABLE IF NOT EXISTS`，种子数据用 `INSERT IGNORE`），
可以反复执行。建完会打印一段自检输出。

改表结构时**直接改这个文件重跑**，不要手动 `ALTER` —— 保证任何人拿到仓库都能一次建出正确的库。

### 2. 起依赖

有两条路径，**默认走 A**：

| | MySQL | Redis | 需要的操作 |
|---|---|---|---|
| **A（默认）** | 本机 3306 | 本机 6379，**db 1** | 启动本机 MySQL 服务 + Redis，无需 Docker |
| B | Docker 13306 | Docker 16379，db 0 | `docker compose up -d` |

切到 B：

```bash
docker compose up -d
CHARGE_OPS_DB_PORT=13306 CHARGE_OPS_REDIS_PORT=16379 CHARGE_OPS_REDIS_DB=0 mvn spring-boot:run
```

### 3. 启动

```bash
# Git Bash 下建议加，否则中文日志可能乱码
export MAVEN_OPTS="-Dfile.encoding=UTF-8"
mvn spring-boot:run
```

应用起在 **8081**（配置里刻意避开常见的 8080）。

### 4. 验证

```bash
# 接口文档
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/v3/api-docs      # 期望 200
# 依赖连通性（db / redis 都应显示 UP）
curl -s http://localhost:8081/actuator/health
# 登录
curl -s -X POST http://localhost:8081/api/auth/staff/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","password":"123456"}'
```

管理端业务接口（先拿 token，全部需要 `token` 请求头）：

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/staff/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s "http://localhost:8081/api/admin/station/page?pageNum=1&pageSize=10" -H "token: $TOKEN"
```

六个域（`station` / `charger` / `connector` / `package` / `tariff-template` /
`station-tariff`）基本是同一套形状：

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/admin/{域}` | 新建，返回新 id |
| `PUT` | `/api/admin/{域}/{id}` | 修改（局部更新，不传的字段不动） |
| `DELETE` | `/api/admin/{域}/{id}` | 删除（有守卫，见下） |
| `GET` | `/api/admin/{域}/{id}` | 详情 |
| `GET` | `/api/admin/{域}/page` | 分页 |

两处**有意的**不同：

- `station-tariff` **没有 `/page`**，取而代之的是 `GET /by-station/{stationId}`（不分页）。
  绑定永远是按站点查的，而且一个站点的绑定数量以「个」计 ——
  它就是这个站点的调价历史，分页会把最该一眼看全的东西切碎
- `tariff-template` **多一个** `GET /list-enabled`，给「新建绑定」的下拉框用。
  不分页，而且返回的 `rules` 恒为 `null`（一页 10 个模板就是 80 条规则，
  列表页只显示模板名）

业务失败一律是 **HTTP 200 + `{"code":0,"msg":"..."}`**，只有未登录是 401 ——
这不是偷懒，是为了让前端的 axios 拦截器不用给每个调用点写 try/catch。理由写在 `Result` 的类注释里。

反过来说，**如果你看到 `msg` 是「未知错误」，那说明遇到了预期之外的异常**
（堆栈在服务端日志里）。正常的业务失败都有具体文案，例如：

```bash
# 删除还有充电桩的站点
{"code":0,"msg":"该站点下还有 1 个充电桩，请先删除或转移"}
# 用已经改过的 version 改充电枪
{"code":0,"msg":"数据已被他人修改，请刷新后重试"}
# 手工把枪设成「充电中」
{"code":0,"msg":"「已占用」和「充电中」由充电流程自动维护，不能手工设置"}
# 修改已被站点绑定的模板的计费规则
{"code":0,"msg":"该模板已被站点绑定，不能修改计费规则；请新建一个模板版本"}
```

> ⚠️ **Windows / Git Bash 下测带中文的请求体，必须走文件，不能写在命令行里。**
> 控制台代码页不是 UTF-8，`-d '{"name":"科技园站"}'` 发出去的是 **GBK 字节**，
> Jackson 会（正确地）拒绝：`Invalid UTF-8 middle byte 0xd8` → 兜底分支 →「未知错误」。
> 看起来像接口坏了，其实是请求根本没被解析。
>
> 更阴的是**部分中文字符的 GBK 字节恰好构成合法 UTF-8**（例如「野桩」→ `D2 B0 D7 AE`），
> 于是请求成功、字段变成乱码 —— 同一个命令一半成功一半失败，最难排查。
>
> ```bash
> printf '%s' '{"code":"ST-TEST-9001","name":"冒烟测试站"}' > /tmp/st.json
> curl -s -X POST http://localhost:8081/api/admin/station \
>   -H "token: $TOKEN" -H "Content-Type: application/json" --data-binary @/tmp/st.json
> ```

车主端（`/api/member/**`，token 放在 **`authentication`** 请求头 ——
和管理端的 `token` 是两个不同的头、两把不同的密钥，互相验不过）：

```bash
# 注册即登录，直接返回 token。★ 演示车主已经注册过了，
#   重复调用会得到 {"code":0,"msg":"13900000001已存在"} —— 这本身就是撞唯一索引的样子
curl -s -X POST http://localhost:8081/api/auth/member/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13900000009","password":"123456","nickname":"demo"}'

M=$(curl -s -X POST http://localhost:8081/api/auth/member/login \
  -H "Content-Type: application/json" -d '{"phone":"13900000001","password":"123456"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s http://localhost:8081/api/member/me                    -H "authentication: $M"
curl -s http://localhost:8081/api/member/vehicle               -H "authentication: $M"   # 我的车（不分页，个位数）
curl -s -X POST http://localhost:8081/api/member/vehicle       -H "authentication: $M" \
     -H "Content-Type: application/json" -d '{"plateNo":"yuebd12345","brand":"BYD"}'      # 车牌会被规范成 YUEBD12345
# ★ 下面的 {id} 换成上面那个响应的 data[].id —— 用别人的车辆 id 会得到「车辆不存在」
curl -s -X PUT  http://localhost:8081/api/member/vehicle/{id}/default -H "authentication: $M"
curl -s -X PUT  http://localhost:8081/api/member/me/password   -H "authentication: $M" \
     -H "Content-Type: application/json" -d '{"oldPassword":"123456","newPassword":"newpass456"}'
```

管理端还剩两个域，和上面六个形状不同：

| 路径 | 端点 |
|---|---|
| `/api/admin/category` | 五端点，同六域 |
| `/api/admin/member` | 只有 `GET /page`、`GET /{id}`、`PUT /{id}/status` —— **刻意没有新建和硬删**，见下方缺口与 `MemberAdminController` 的类注释 |

跨身份越权必须**真的跑一次**，不能靠推理：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/member/vehicle \
     -H "authentication: $TOKEN"        # 管理端 token 打车主端 → 401
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/admin/station/page?pageNum=1&pageSize=5" \
     -H "token: $M"                     # 车主 token 打管理端 → 401
```

Swagger UI：<http://localhost:8081/swagger-ui/index.html>
（`/swagger-ui.html` 会 302 跳到这里，是 springdoc 的正常行为，不是错误。）

开发环境还开了 Druid 监控页 <http://localhost:8081/druid/>（admin/admin，**仅 dev**）。

### 5. 跑测试

```bash
mvn test
```

当前 217 条测试，分三类：

| 类别 | 需要 MySQL/Redis | 内容 |
|---|---|---|
| 纯单测 | ❌ | `BillingEngineTest` / `TariffValidatorTest`（★ 计费算法）、`ChargePackageServiceImplTest`、`MemberServiceImplTest`（★ 四条登录分支 + token 签发）、`VehicleServiceImplTest`（★ 默认车不变量） |
| Mapper 集成 | ✅ | 11 个 `*MapperTest`：审计字段、`24:00:00` 往返、乐观锁、唯一索引的枪口互斥、`refreshGunCount`、排序规则 `ai_ci`/NO PAD 的实测行为、`member_package` 的锁定互斥与结算幂等 |
| —— | ✅ | 用 `@SpringBootTest`，需要本机 MySQL / Redis 可用 |

**这一层不是装饰**：`ConflictingBeanDefinitionException` 那个 bug（见决策 9）
能过 `mvn compile`、能过 IDE 检查，**只有起容器才会暴露** ——
抓到它的正是这组 `@SpringBootTest`。写 Mapper 测试的收益不只是「SQL 写对了没」。

只跑不需要数据库的那部分（最快的反馈环，47 条）：

```bash
mvn test -Dtest='BillingEngineTest,TariffValidatorTest,ChargePackageServiceImplTest,MemberServiceImplTest,VehicleServiceImplTest'
```

两个单测里各有一条值得单看的写法：

- `MemberServiceImplTest` 用一个 `CountingPasswordEncoder` 装饰真 BCrypt，
  断言**前三种登录失败一次密码比对都没发生** —— 「把最慢的一步放最后」这句设计
  只断言「抛了哪个异常」是证明不了的
- `VehicleServiceImplTest` 用手写的**内存假库**而不是记录调用的桩，因为要验的是
  **一串操作之后的状态**（「有车的车主一定有且只有一辆默认车」）。
  假库的每条语句都对着真 `VehicleMapper.xml` 抄了承重条件（`id <> #{id}`、
  `WHERE` 里的 `member_id`、`is_default` 的 NOT NULL）——
  一个比真库宽松的假库会让测试通过而生产失败，那比没有测试更糟

---

## 项目结构

```
src/main/java/com/chargeops/
├── annotation/       @AutoFill —— 公共字段自动填充标记
├── mybatis/          AutoFillInterceptor —— 填充的落地实现（MyBatis 插件）
├── config/           WebMvc / Jackson / Password / Storage 配置
├── context/          BaseContext —— 基于 ThreadLocal 的当前登录者
├── interceptor/      JWT 鉴权拦截器（管理端 / 车主端两套）
├── controller/       admin（管理端）/ user（车主端）—— 两侧的类名都自带作用域，见下方决策 9
├── billing/          BillingEngine / TariffValidator / TariffRuleSnapshot 等 —— 纯函数计费内核
├── service/ mapper/ entity/ dto/ vo/     业务分层（service 接口 + impl 各 7 个）
├── handler/          GlobalExceptionHandler —— 统一异常翻译
├── redis/            CacheClient / RedisData / RedisJsonUtil —— 缓存与逻辑过期
├── properties/       JwtProperties / StorageProperties
├── utils/            JwtUtil / FileStorage + LocalFileStorage
└── constant/ exception/ result/ enumeration/    基础件

sql/chargeops.sql     19 张表的完整 DDL + 种子数据（含每条索引的设计理由）
docker-compose.yml    本地依赖（备选路径）
```

单模块。分层包结构与教程形态一致，后期要拆多模块是纯机械操作。

**演示账号**：

| 端 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 管理端 | `admin` | `123456` | 请求头 `token` |
| 车主端 | `13900000001` | `123456` | 自助注册的演示车主，请求头 `authentication` |
| 车主端 | `13900000002` | `123456` | 第二个车主，用于验证「看不到别人的车」 |
| 车主端 | `13800000001` | — | 种子数据，`password IS NULL`，**无法用密码登录**（DDL 里这是合法状态，对应将来的验证码登录） |

---

## 几个值得一读的设计决策

> 下面每一条都是**实际踩过、有证据**的，不是照抄的最佳实践清单。
> 完整推导写在对应的类注释里。

### 1. `@AutoFill` 为什么是 MyBatis 插件，不是 AOP 切面

公共字段自动填充的教科书写法是 `@Aspect` + `@Pointcut("@annotation(AutoFill)")`，
把注解开在 Mapper 方法上。

**这个写法在本项目里是静默失效的**：编译通过、启动成功、零报错，但审计字段永远是 `null`。

排查过程是一步步排除假设的：

```
aspectjweaver 在不在 classpath 上？              → 在
AnnotationAwareAspectJAutoProxyCreator 注册了吗？ → 注册了
切面 bean 存在吗？                                → 存在
切点表达式匹配 StaffMapper.insert 吗？             → true
staffMapper 是 Spring AOP 代理吗？                → false   ← 问题在这
```

根因是**机制上不可能**：Mapper 接口对应的 bean 是 `MapperFactoryBean`（一个 `FactoryBean`），
Spring 容器先对**工厂对象本身**跑 `BeanPostProcessor`（切点永远匹配不上它），
之后才调 `getObject()` 拿到真正被调用的 `MapperProxy` ——
**这一步发生在所有 `BeanPostProcessor` 之后**，而 Spring AOP 的自动代理正是靠
`postProcessAfterInitialization` 织入的。代理没机会织进去。

所以调整 `proxy-target-class`、换 CGLIB、加 `@EnableAspectJAutoProxy` 全都没用，方向就不对。
改成 MyBatis 插件（拦截 `Executor.update`）后**机制上一定生效**。

> 副产品：这个 bug 是**写测试抓到的**。当时三条 P0 验收全绿 ——
> 这正是「接口能通」和「功能正确」是两件事的具体样子。

见 `mybatis/AutoFillInterceptor.java`。

### 2. `connectionTimeZone` 必须写 `+08:00`，不能写 `Asia/Shanghai`

写成时区**名字**会导致启动直接失败：

```
java.sql.SQLException: Unknown or incorrect time zone: 'Asia/Shanghai'
```

这个报错极易被误解成「时区名拼错了」。真正原因是 MySQL 处理命名时区要查
`mysql.time_zone_name` 等几张表，而**那几张表默认是空的**：

```sql
SELECT COUNT(*) FROM mysql.time_zone_name;   -- 返回 0 就说明没导入
```

Windows 上尤其常见 —— `mysql_tzinfo_to_sql` 是 Unix-only 工具，Windows 安装包不带它。

用数字偏移 `+08:00` 绕开整件事（中国不实行夏令时，两者恒等），
再配 `forceConnectionTimeZoneToSession=true` 让数据库端的 `CURRENT_TIMESTAMP` 也走同一时区。

### 3. `datetime(3)` 的精度与四舍五入

`LocalDateTime.now()` 是纳秒精度，而审计列是 `datetime(3)`。落库时超出部分被
**四舍五入**（不是截断）：`.818918500` → `.819`。

写测试时踩到：拿内存里的对象和从库里读回的对象比，断言必然失败，
而失败信息长得像「字段被改掉了」，很容易误导到错误方向。
**正确做法是两边都从库里取。**

同一个坑对所有 `datetime(3)` 列都成立。

### 4. `WebMvcConfigurer`，不是 `WebMvcConfigurationSupport`

继承 `WebMvcConfigurationSupport` 会**关掉 Spring Boot 的 MVC 自动配置** ——
静态资源（含 swagger-ui 的 webjar）、默认消息转换器全部失效。
表现为 swagger-ui 404、上传图片 404，而日志里没有直接线索。

排查这类问题时先访问 `/v3/api-docs`：**它是纯 JSON，不依赖任何前端静态资源**。
有 JSON → 文档数据是好的，问题在静态资源；没 JSON → springdoc 根本没生效。方向完全不同。

### 5. 依赖版本上的两个坑

- **Druid**：Boot 4 要用 `druid-spring-boot-4-starter`（artifactId 里有 `-4-`）。
  引错成普通的 `druid-spring-boot-3-starter` 会在启动时 `ClassNotFoundException: javax.servlet.Filter`
- **Boot 4 把 `spring-boot-starter-aop` 改名成了 `spring-boot-starter-aspectj`**。
  按老经验敲旧名字会报 `'dependencies.dependency.version' ... is missing` ——
  这个报错说的是「缺版本号」，但真实含义是「BOM 里没有这个坐标」

### 6. `DEFAULT` 只在字段**缺席**时生效 —— 两个把 null 变成 500 的机制

写 NOT NULL 列时，很容易相信「DDL 里有 `DEFAULT 0`，不传就是 0」。**在满列 INSERT 下这是假的。**

```sql
INSERT INTO t (a) VALUES (NULL);       -- ERROR 1048: Column 'a' cannot be null
INSERT INTO t (a) VALUES (DEFAULT);    -- 用到 DEFAULT
```

`DEFAULT` 的触发条件是「这一列没有出现在 INSERT 的列清单里」，**不是「值是 null」**。
本项目的 Mapper 全部是满列插入（列清单写死），所以**显式传 null 就是 errno 1048**。

配套的第二个机制在 Java 侧：**DTO 的字段初始值只挡「JSON 里没有这个键」**。
`{"openTime": null}` 是显式 null，Jackson 照常调 setter，**覆盖掉初始值**。

两条合起来是一条规则：**对 NOT NULL 列，「不传」和「传 null」必须在 Service 里被归一成同一件事**。
做法是把默认值写成 DTO 上的 `public static final` 常量
（`ChargePackageDTO.DEFAULT_STATION_ID` 等），字段初始值和 Service 兜底引用**同一个常量** ——
否则两处各写一遍 `0`，改了一处就会漂移，而漂移的表现是「同样的请求，一次成功一次 500」。

冒烟测试里这条被直接验证过：`stationId` 显式传 null 的请求，报出的错误和完全不传**一字不差**。

### 7. 计费模板的规则**不可变**，而这正是 `station_tariff` 存在的理由

`charging_order` 在下单那一刻把 `tariff_template_id` 冻结进去，所以电价调整不影响在途订单。

**但这条保证只在规则不可变时才成立。** 如果模板的规则还能改，已冻结的 id 就会指向
被改过的规则 —— 保证静默失效：订单金额照样算出来，只是和用户启动时看到的不一样。

所以 `TariffTemplateServiceImpl.saveRules` 有一道守卫：模板被任何 `station_tariff` 行引用时，
**拒绝修改规则**。要调价就新建一个模板版本，用 `effective_from` 绑定生效期。
守卫同时把新绑定拦在停用模板之外，但**不动已有绑定** —— 两条路径、两个判断。

边界值得记清楚：**冻结的是规则，不是模板头**。改名称/地区/说明/`status` 不经过守卫，
否则「改个说明」都会变成不可能。冒烟测试把这条边界两侧都走了一遍。

### 8. 两个只有跑起来才发现的 bug

单元测试和集成测试全绿，接口一调就炸 —— 这两条都是冒烟测试抓到的：

- **`charge_package.category_id` 是 NOT NULL 且无 DDL 默认值**，而 DTO 上忘了 `@NotNull`。
  不传分类 → 穿过校验 → 撞 insert → **HTTP 500 +「未知错误」**。
  这是「NOT NULL 无默认值的列必须在 DTO 层拦」这条规则的一个漏网之鱼
  （`charger.rated_power_kw` 拦了，它没拦）。
- **`assertEffectiveRange` 对 `effectiveTo` 直接调 `isAfter`**，而 `effectiveTo` 为 null
  （不填失效时刻 = 长期有效）是**最常见**的合法输入。于是「不填失效时刻」这个正常操作
  抛 NPE → 500 +「未知错误」。
  根因是判空写在了调用点而不是方法内 —— **把前置条件放进方法里，任何一个调用点都不可能再忘**。

两条的共同特征是**报错信息量为零**：调用方拿到「未知错误」，既不知道哪个字段，
也分不清是参数问题还是服务挂了。这类 bug 值得优先修，因为它们会掩盖后面所有的真问题。

### 9. 两个同名 Controller **能编译、能过 IDE、在启动那一刻炸**

P1c-1 加车主端时写了 `controller/user/AuthController`，而 `controller/admin/AuthController`
早就存在。结果是**整个应用起不来**：

```
ConflictingBeanDefinitionException: Annotation-specified bean name 'authController'
for bean class [com.chargeops.controller.user.AuthController] conflicts with
existing, non-compatible bean definition of same name and class
[com.chargeops.controller.admin.AuthController]
```

根因：Spring 的 `AnnotationBeanNameGenerator` 给 `@RestController` 的默认 bean 名是
**类的简单名首字母小写**，它**不看包名**。同名不同包在 Java 里完全合法，在 Spring 里不合法。

这个错误的性质比它本身更值得记：**它躲过了所有静态检查** ——
`mvn compile` 成功、IDE 不报错也不警告，只在容器启动那一刻出现。
抓住它的是 `@SpringBootTest` 那一组 Mapper 测试（它们要起容器），
报错还伪装成 39 个测试一起失败 + `ApplicationContext failure threshold exceeded`。

修法选了**改类名**（`MemberAuthController`），而不是给两个类写显式 bean 名：
后者把「不许重名」变成一条要记得的规则，下一个写 `AuthController` 的人还会再踩。
同一个决定在 `MemberAdminController` 上已经做过一次（它本来叫 `admin/MemberController`）。

**推论**：这个项目里所有 Controller 的类名都必须自带作用域。

### 10. 「每车主至多一辆默认车」**不可能用唯一索引表达**

直觉是给 `vehicle(member_id, is_default)` 加个唯一索引就完事了。**那个索引会禁止
一个车主有两辆非默认车** —— 非默认行的 `is_default` 全是 0，第二辆立刻撞键。
MySQL 没有部分索引（`WHERE is_default = 1`）；业界绕法是让非默认行存 NULL
（唯一索引不约束 NULL），但 DDL 写的是 `is_default tinyint NOT NULL DEFAULT 0`，这条路也已经封了。

**所以 DDL 里那个「缺失的索引」不是疏漏，是这个约束在当前列定义下无法用索引表达。**
它落在两条条件更新上（`clearDefaultExcept` / `markDefault`），
关键细节是 `clearDefaultExcept` 的 `id <> #{id}` 是**正确性要求**：
新建第二辆默认车时是先 insert 再清旧的，那一刻新行在同一事务里已经可见且 `is_default` 已是 1 ——
没有这个排除条件，创建操作会把自己刚设的标记立刻清掉，而且**静默**（接口返回成功）。

不变量「有车的车主一定有且只有一辆默认车」由三条路径共同维护，
`VehicleServiceImplTest` 用内存假库把它逐条钉住（含「先清后置」的顺序、
删掉默认车后提升的是 `ORDER BY is_default DESC, id DESC` 的第一条 = **最新**那辆非默认车）。

### 11. 车牌转大写**不是为了唯一性**，`trim()` 才是

两条都已在真库上验证过（`VehicleMapperTest` 里是可执行的回归测试）：

- 库 / 列排序规则是 `utf8mb4_0900_ai_ci`，**大小写不敏感** ——
  `uk_vehicle_member_plate` **本来**就把 `粤BD12345` 和 `粤bd12345` 当同一个值。
  转大写的目的只是**展示统一**（列表页不出现两种写法）。**不要**把它改成 `_bin`/`_as_cs`：
  唯一约束和「客服按车牌反查车主」会同时静默改变行为。
- 但同一个排序规则是 **NO PAD** —— 它认为**尾随空格有意义**。
  于是 `'粤BD12345' = '粤BD12345 '` 返回 **0**，唯一索引**拦不住**带尾空格的重复车牌：
  实测能把同一块牌登记两次，产生两行看起来一模一样的记录。
  `VehicleServiceImpl.normalizePlateNo` 里那个 `trim()` 是目前唯一挡住它的事情。

`toUpperCase(Locale.ROOT)` 里的 `Locale.ROOT` 同理不是装饰：土耳其语区域下
`'i'.toUpperCase()` 是 `'İ'`，而那个 bug **只在 `tr_TR` 的服务器上出现**，本机永远复现不了。

### 12. 唯一键拆成 `uk(type, name)` 之后，**消费方必须知道自己要哪个 type**

DDL 把老的 `uk(name)` 改成 `uk_category_type_name (type, name)`，注释写着理由：
「『快充』既可能是套餐分类也可能是服务分类，全局唯一会误伤」。

**这个改动有一个不显然的后果：拆了 type 之后，`categoryId` 就不再足以确定一个分类了。**
指错 type 不会撞任何约束，只会**静默地**把套餐挂到一个「停车服务」分类下 ——
type 拆分于是只带来了歧义，没带来秩序。所以 `ChargePackageServiceImpl`
在校验「分类存在」之后还要校验「它是充电套餐分类」：

```
分类类型不匹配：充电套餐只能挂在「充电套餐分类」下，而分类「停车服务」是「增值服务分类」
```

**但刻意不校验 `category.status`。** 停用分类不该让已挂在上面的套餐无法改价
（否则改个价格要先去启用分类）。「下架分类」的语义是「不再出现在新建的下拉框里」，
不是「存量数据失效」—— 和 `TariffTemplateService` 里「停用模板不影响已有绑定」是同一个道理。

**写路径严格、读路径降级**，这条在项目里反复出现：校验只在 `create`/`update` 上，
而 `listEnabled` 之类的读路径遇到脏数据返回 `null` 标签而不是抛异常。

---

### 13. MySQL 的 `SET` 子句**从左到右求值**，后面的子句看得见前面刚改的值

这是 `member_package` 的结算语句写出来之后、被测试抓到的一个真实 bug，不是理论推演。

结算要在一句话里干三件事：扣量、释放锁、用尽则改状态。最初的写法把 `status` 放在最后：

```sql
-- ❌ 错的
UPDATE member_package
   SET remaining_kwh = remaining_kwh - #{usedKwh},
       locked_order_id = NULL,
       status = CASE WHEN remaining_kwh - #{usedKwh} <= 0 THEN 2 ELSE status END
 WHERE id = #{id} AND locked_order_id = #{orderId};
```

读起来很顺：「扣完之后，如果剩的 ≤ 0 就标记用尽」。**但 `CASE` 里的
`remaining_kwh` 读到的是上一行刚减过的值，于是「减 1 次」变成了「减 2 次」，
条件提前一步成立。**

这不是标准 SQL 的行为（标准里所有 `SET` 都读原值），是 MySQL 单表 `UPDATE` 的文档化语义。
后果在一张次卡上最明显：**剩 2 次、用掉 1 次之后，剩余确实是 1，`status` 却被置成了 2（已用尽）**——
用户还剩 1 次没用，第 10 次充电会被静默拒绝，而第 9 次就已经显示「已用尽」了。全程没有报错。

修法是把 `status` 挪到扣量**前面**，并且比较**原值**：

```sql
-- ✅ 对的
UPDATE member_package
   SET status = CASE WHEN remaining_kwh <= #{usedKwh} THEN 2 ELSE status END,
       remaining_kwh = remaining_kwh - #{usedKwh},
       locked_order_id = NULL
 WHERE id = #{id} AND locked_order_id = #{orderId};
```

「我有的 ≤ 你要扣的」⇔ 扣完 ≤ 0 ⇔ 用尽 —— 语义没变，只是不再依赖求值顺序之外的东西。
`settleDuration` / `settleTimes` 是同一个形状（次卡是 `remaining_times <= 1`）。

**真库上的对照实验**（`r` 是剩余量、`s` 是状态，初始 `(2, 1)`）：

| 写法 | 剩余 | 状态 | |
|---|---|---|---|
| 扣量在前，`CASE WHEN r - 1 <= 0` | 1 | **2 已用尽** | ❌ 还剩 1 次却标成用尽 |
| `CASE` 在前，`WHEN r <= 1` | 1 | 1 生效中 | ✅ |

**结论：这三个 `settle` 语句里的 `status` 不能为了「读起来更顺」挪到扣量后面。**
三个方法各自的 XML 注释和 javadoc 里都留了指回这里的标记，测试也钉住了这个行为
（`MemberPackageMapperTest.settleTimesShouldConsumeExactlyOne`）。

---

## 路线图

```
P0 骨架+DDL ✅ ──┬── P1a 站点/桩/枪/套餐/模板 CRUD ✅ ──┐
                 └── P1b 计费引擎(纯函数+单测) ✅ ──────┼── P1c 订单链路
                                     P1c-1 车主端底座 ✅ ┤
                                                        │
                                 P1d 设备上报 ──────────┘
                                                        │
                                 P2 AI 三件套 ──────────┤
                                 P3 稳定性与异步 ───────┤
                                 P4 数据层加深 ─────────┘
```

| 阶段 | 内容 |
|---|---|
| **P0** ✅ | 建库建表 + 骨架 + 基础设施 + 登录鉴权 |
| **P1a** ✅ | 站点 / 充电桩 / 充电枪 / 套餐 / 计费模板 / 站点计费绑定 六个域的管理端 CRUD（31 个接口：5×5 + 模板 6 个含下拉框 + 绑定 5 个按站点查） |
| **P1b** ✅ | `BillingEngine`（纯函数：时间区间 + 总电量 → 计费分段）+ `TariffValidator` |
| **P1c-1** ✅ | **车主端底座**：自助注册/登录（`/api/auth/member/**`）、我的信息、我的车辆 CRUD + `category` CRUD / `member` 管理（分页·详情·启禁用）。19 个接口 |
| **P1c-1.5** ✅ | **`member_package` 表结构**（持有权益）：DDL + 实体 + Mapper，含 `lockForOrder` / `releaseLock` / 三个 `settle*` 条件更新。**没有 Service / Controller** —— 它的调用方是 P1c-3 的订单流程。见决策 13 |
| **P1c-2** | 钱包：余额、冻结、流水（充值 / 下单冻结 / 结算扣款 / 退款）。**必须在这里重新查 `member.status`**，见下方缺口表 |
| **P1c-3** | 订单状态机（创建→启动冻结→结束结算→支付→退款；`plate_no` 读快照列，不 JOIN `vehicle`） |
| **P1d** | 设备上报（遥测批量入库、结束事件幂等、真实电量曲线） |
| **P2** | SSE 流式对话、RAG 知识库、MCP Server、工具层 |
| **P3** | MQ 解耦、最终一致性对账、可观测性 |
| **P4** | 遥测分表、缓存一致性深化（**可选**） |

**P1c 是唯一的关键路径**，必须等 P1a 和 P1b 都完成 —— 结算正确性完全建立在拆分算法上，
所以先用单测把算法锁死，再接订单。

P1c-1 是 P1c 的前置，理由是数据而非顺序：`charging_order.member_id` 是 `NOT NULL`，
**没有车主就一张订单都建不出来**。所以先把车主侧的身份、车辆、管理端补齐。

P1a/P1b 为 P1c 留好的两个接口：

- `StationTariffService.resolveTemplateIdAt(stationId, at)` —— 下单时冻结模板要调的就是它。
  它**永不为 null**：解析不出模板就抛 `TariffNotCoveredException`，
  签名里没有 null 这个可能，所以 P1c 无法忘记检查，也无法「回退到一个没人批准过的默认价」
- `BillingResult` 的字段形状 = `charging_segment` 表的列，逐字段对应，不需要转换层

### 已知缺口（记录在案，不是遗漏）

| 缺口 | 影响 | 位置 |
|---|---|---|
| **禁用车主不撤销已签发的 token** | 拦截器只验签名、从不查库，member token TTL 是 **7 天** —— 禁用后最长 7 天内旧 token 仍可用。**已实测**：禁用后旧 token 请求 `/me` 仍是 HTTP 200（响应里 `status=0`，前端启动探针据此强制登出，但恶意客户端可以忽略）。**P1c-2 / P1c-3 的钱包与订单端点必须在 Service 里重新查一次 `member.status`**；本轮不引入黑名单（那等于每个请求一次 Redis 往返，而 `/api/member/**` 目前只有无资金影响的读写） | `MemberAdminController` / `MemberVO` 类注释 |
| **登录/注册没有限流** | 两者都跑 BCrypt（故意慢），且注册接口会明确返回「该手机号已存在」。正确修法是**在两个口上都加限流 / 人机校验**，不是靠合并文案 —— 合并登录失败文案换来的是**一致性而不是安全**（枚举在这套 API 里本来就可得） | `MemberAuthController` / `MessageConstant.MEMBER_LOGIN_FAILED` |
| **没有文件上传接口** | `FileStorage` / `LocalFileStorage` / `/img/**` 静态映射都在，但没有任何 Controller 调用它们 —— `member.avatar`、`charge_package.image` 都只存 URL，服务端不校验可达性 | `Member.avatar` 注释 |
| 复合唯一键的错误消息会带出键值 | 撞 `uk_vehicle_member_plate` 时用户看到的是 `110-YUEBD11111已存在`、撞 `uk_category_type_name` 时是 `2-smoke-valadd已存在` —— 前缀是**索引里的其他列**，对用户毫无意义。全局翻译点只做了「提取被撞的值 + 已存在」，没有做「只取有意义的那个字段」 | `GlobalExceptionHandler` 的唯一键正则 |
| **无行级权限** | `staff` 表有 `role`/`station_id`，但 `BaseContext` 只带 `currentId` —— 任何登录的运维能改任何站点 | `StationController` 类注释 |
| **`member_package` 的类型专属列还没人校验** | P1c-1.5 只钉死了表结构，「四种类型**只留一组** `total_*` / `remaining_*` 非空」这条规则**目前没有任何代码执行**（`charge_package` 有 `ChargePackageServiceImpl` 管，这张表还没有 Service）。残留列是静默的：一份「30 天时长包」如果 `remaining_kwh` 还留着 150，结算时读到它就会白白送出 150 度电。**P1c-3 写买入路径时必须补上** | `MemberPackageMapper.insert` javadoc |
| **权益过期后 `status` 不会自己变成 3** | 正确性已经被读路径兜住（`listUsable` 里的 `expire_time > #{now}`），所以不会把过期权益发出去；但车主端列表里那份过期的会一直显示成「生效中」，直到有人结算它、或巡检任务扫到。需要一个定时任务，建议并进 P3 的对账/巡检 | `MemberPackageStatus` 类注释 |
| 局部更新清不掉字段 | `<set>` + `<if>` 的固有代价；修法是 `copyNonNullProperties` + 全行更新 | 各 `update` 的 javadoc |
| 模板硬删留下孤儿规则 | 有意的取舍：级联删除会顺手抹掉 8 行计价数据且不可恢复 | `TariffTemplateService.delete` |
| **4xx 被兜底成「未知错误」** | 请求体 JSON 格式错（`HttpMessageNotReadableException`）、路径不存在（`NoResourceFoundException`）都落进 `Exception` 兜底分支 → HTTP 200 +「未知错误」。冒烟测试时一个**编码不对的请求体**（GBK 字节被 Jackson 判为非法 UTF-8）就是这样表现的：排查成本高，因为「参数错了」和「服务挂了」长得一样 | `GlobalExceptionHandler.handleUnexpectedException` |

P1a 记在案的两条**已经关掉**：`categoryId` 不校验存在性（P1c-1 补上了 `CategoryMapper`，
`ChargePackageServiceImpl` 现在会校验分类存在 + 类型匹配，见下方决策 12）；
`ChargePackageDTO.categoryId` 缺 `@NotNull`（决策 8 的第一个 bug，已补）。

---

## 一些约定

- **金额**一律 `BigDecimal`，比较用 `compareTo` 而非 `equals`；中间计算不舍入，**只在最后取整一次**，保证 `Σ分段 == 总额`
- **时间**一律 Java 侧 `LocalDateTime.now()` 生成，不用 `timestamp` 类型（2038 上限 + 隐式时区换算）；精度到毫秒
- **并发控制**靠数据库条件更新 + 唯一索引，不用 `synchronized`
  （单实例有效、多实例失效；且 JDK 21 虚拟线程下 `synchronized` 会 pin 住载体线程）
- **不用外键**：InnoDB 外键检查会在父表加共享锁，与「枪状态高频更新 + 订单高频写入」冲突；
  完整性由 Java 校验 + 唯一索引 + 条件更新保证
- **Redis 库号 1**，与同机其他项目（用 db 0）隔离
