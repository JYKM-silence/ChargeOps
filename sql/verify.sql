-- ============================================================================
-- 库结构自检 —— ★ <b>这不是迁移</b>
-- ============================================================================
-- 是一组手工执行的 SELECT。放进 db/migration 的话，每次迁移都会把结果打印进
-- 启动日志，而它只在两种时刻有意义：
--
--   1. 全新库建完之后，确认 Flyway 真的把 19 张表建出来了；
--   2. ★★ 老库**引入 Flyway 的那一刻**，用它回答「这个库到底是不是 V1 的样子」★★
--
--   第 2 条才是要紧的那个。见 README「baseline 是相信，不是校验」：
--   `baseline-on-migrate` 会把已有库记成版本 1 然后**跳过 V1**，
--   Flyway 不会去核对那个库的内容。**跳过不等于相符** ——
--   如果那个库还留着某次手工改表留下的漂移，V1 永远不执行，漂移原封不动。
--   所以「库 vs V1」这一次比对必须由人来做，本文件就是那个工具。
--
-- 执行（★ 必须带 --default-character-set=utf8mb4，理由见文件末尾）：
--   mysql --default-character-set=utf8mb4 -uroot -p chargeops < sql/verify.sql
--   或 docker exec -i chargeops-mysql mysql -uroot -p123456 chargeops < sql/verify.sql
-- ============================================================================

-- ============================================================================
-- 一、逐表列数 —— ★ 拿这个结果去和 V1__init_schema.sql 对
-- ============================================================================
-- 对的不是总数，是**每一行**。总数相同但张冠李戴（A 表多一列、B 表少一列）
-- 是最常见的漏网方式，只比总数看不出来。
--
-- ★ 排除 flyway_schema_history：它是 Flyway 自己的账本，不是业务表。
--   算进来的话「表数 / 列数 / 索引数」三个数会随迁移次数一直变，
--   和 V1 就再也对不上了 —— 而这个查询存在的唯一目的就是「和 V1 对」。
SELECT TABLE_NAME                          AS `表`,
       COUNT(*)                            AS `列数`,
       GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR ',') AS `列（按序）`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'chargeops'
  AND TABLE_NAME <> 'flyway_schema_history'
GROUP BY TABLE_NAME
ORDER BY TABLE_NAME;

-- 期望：19 张表 / 279 列 / 67 个索引
-- ★ 三个数都对上才叫「没有漂移」。比列数更常见的情况是表数对、列数对，
--   而**索引**不一样 —— 那边没有报错，只有查询计划悄悄变了。
--
-- ★★ 索引这里必须是 COUNT(DISTINCT ...)，不能是 COUNT(*) ★★
--   information_schema.STATISTICS 是**一个索引列一行**，不是一个索引一行：
--   `KEY idx_x (a, b)` 在里面是两行。写成 COUNT(*) 会得到 110 ——
--   一个「看起来像索引数、其实是索引列数」的数，而且它照样能对上，
--   不会报错。这种「错得刚好能通过」的查询比写错更值得警惕。
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = 'chargeops'
           AND TABLE_NAME <> 'flyway_schema_history')             AS `表数（期望 19）`,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = 'chargeops'
           AND TABLE_NAME <> 'flyway_schema_history')             AS `列数（期望 279）`,
       (SELECT COUNT(DISTINCT TABLE_NAME, INDEX_NAME) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = 'chargeops'
           AND TABLE_NAME <> 'flyway_schema_history')             AS `索引数（期望 67）`;

-- ============================================================================
-- 二、Flyway 自己记的账
-- ============================================================================
-- 期望：rank 1 是 BASELINE(version 1)，rank 2 是 seed data(version 2)。
-- ★ 只看到 BASELINE、没有 rank 2 的话，说明 V2 没执行 ——
--   而 V2 里有种子数据，界面会缺一堆演示内容。
SELECT installed_rank  AS `序号`,
       version         AS `版本`,
       description     AS `描述`,
       type            AS `类型`,
       success         AS `成功`
FROM flyway_schema_history
ORDER BY installed_rank;

-- ============================================================================
-- 三、种子数据到位没有
-- ============================================================================
-- 期望：8 行计费规则，时段合计 24 小时
SELECT COUNT(*) AS `计费规则条数（期望 8）`,
       SEC_TO_TIME(SUM(TIME_TO_SEC(TIMEDIFF(`end_time`, `start_time`)))) AS `时段合计（期望 24:00:00）`
FROM `tariff_rule`
WHERE `template_id` = 1;

-- 期望：1 个可用管理员
SELECT `id`, `username`, `name`, `role` FROM `staff` WHERE `username` = 'admin';

-- 期望：1 份可用权益（member_package 是否建对、种子是否挂上）
-- ★ 这里必须打印 expire_time 而不是只 COUNT ——
--   因为种子的有效期是按 NOW() 算的，这一行同时验证了
--   「DATE_ADD/DATE_SUB 算出来的日期格式能被 datetime(3) 接受」这件事。
--   只数行数的话，一个被截断成 0000-00-00 的过期时间是看不出来的。
SELECT `id`, `member_id`, `package_name`, `total_kwh`, `remaining_kwh`,
       `effective_from`, `expire_time`, `status`
FROM `member_package`
WHERE `member_id` = 1;

-- ============================================================================
-- ★ 为什么执行时要带 --default-character-set=utf8mb4
-- ============================================================================
-- 不带的话，**在 Windows 上会直接报语法错误**：
--
--   ERROR 1064 (42000) at line 26: You have an error in your SQL syntax ...
--   near '列数`, GROUP_CONCAT(...'
--
-- 看起来像这份 SQL 写坏了，其实是 mysql 客户端按控制台的代码页（GBK/cp936）
-- 去解释这个 UTF-8 文件：中文字符被当成 GBK 读，某个双字节组合**把紧随其后的
-- 反引号当成了自己的尾字节吃掉了**，于是别名少了一个结束符。
--
-- ★ 和 README 里那条「Git Bash 下测带中文的请求体必须走文件」是同一类问题：
--   Windows 控制台不是 UTF-8，而错误信息指向的位置和真正的原因不在一个层面。
--
-- 顺带一提：`docker exec` 那条命令**不需要**这个参数，因为容器里的 mysql
-- 默认字符集就是 utf8mb4。
