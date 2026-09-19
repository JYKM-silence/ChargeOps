-- ============================================================================
-- ChargeOps 新能源充电桩运营平台 —— 建库脚本
-- ============================================================================
-- 目标：MySQL 8.4 / utf8mb4_0900_ai_ci / InnoDB / **不使用外键**
--
-- 执行方式（脚本是幂等的，可以反复执行）：
--   docker exec -i chargeops-mysql mysql -uroot -p123456 < sql/chargeops.sql
--
-- ★ 为什么这个脚本不挂在 docker-entrypoint-initdb.d 上：
--   挂上去的话只有「数据卷为空」的那一次启动才会执行。改了脚本、重启容器，
--   什么都不会发生，而你会以为脚本有问题。手工执行 + 幂等设计更可控。
--
-- ★★ 全库共 19 张表。开始看之前，先看这五条贯穿全库的约定：★★
--
--  1) 时间一律 datetime(3)（毫秒精度），不用 timestamp。
--     timestamp 有 2038 上限，且会依赖会话时区做隐式换算 ——
--     容器 TZ 一变，历史数据的语义就漂移了。datetime 存的是"字面值"，不含时区。
--     也不用 bigint 存毫秒：那样报表、分区、TO_DAYS() 全都用不了，
--     对 P4 的遥测分表是致命的。
--
--  2) 不用外键。InnoDB 的外键检查会在父表上加共享锁，而充电桩场景是
--     "枪状态高频更新 + 订单高频写入"，外键会显著扩大锁范围、提高死锁概率；
--     而且 charging_telemetry 后期要分表、历史订单要归档，外键是直接障碍。
--     完整性靠三件套：Java 层校验 + 唯一索引 + 条件更新，
--     孤儿数据由 P3 的对账巡检兜底。
--
--  3) 金额分三档精度，不要统一：
--       单价（元/kWh）  decimal(10,4)   —— 工商业分时电价就是 4 位小数（如 0.6188）
--       电量（kWh）     decimal(12,4)
--       总额/余额       decimal(12,2)
--     Java 侧全程 BigDecimal，**只在最终一步 setScale(2, HALF_UP)**，
--     中间分段不舍入 —— 否则各段各舍一次，Σ分段 ≠ 订单总额（对账前提就没了）。
--
--  4) 审计四列统一为：
--       create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
--       update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
--       create_user bigint DEFAULT NULL
--       update_user bigint DEFAULT NULL
--     ★ 时间列有数据库默认值，不代表 Java 侧不用填（AutoFillAspect 仍然会填）。
--       默认值是兜底，切面是主路径，见 AutoFill 注解的注释。
--
--  5) 每个索引都对应一条真实查询，理由写在建表语句下面。
--     "先建索引，等慢了再说" 在这个项目里是错的 —— 遥测表每秒都在写。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `chargeops`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `chargeops`;

SET NAMES utf8mb4;

-- ============================================================================
-- 1. member 车主
-- ============================================================================
CREATE TABLE IF NOT EXISTS `member`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `phone`           varchar(20)  NOT NULL COMMENT '手机号，登录账号',
    `password`        varchar(100) DEFAULT NULL COMMENT 'BCrypt 密文；验证码登录的用户为 NULL',
    `nickname`        varchar(50)  DEFAULT NULL,
    `avatar`          varchar(255) DEFAULT NULL,
    `gender`          tinyint      DEFAULT NULL COMMENT '0未知 1男 2女',
    `status`          tinyint      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    `last_login_time` datetime(3)  DEFAULT NULL,
    `create_time`     datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`     datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`     bigint       DEFAULT NULL,
    `update_user`     bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_member_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='车主';
-- 索引理由：登录是按手机号等值查，唯一索引一份成本同时满足"查询"与"约束"两件事。

-- ============================================================================
-- 2. staff 运维人员
-- ============================================================================
CREATE TABLE IF NOT EXISTS `staff`
(
    `id`           bigint      NOT NULL AUTO_INCREMENT,
    `username`     varchar(50) NOT NULL COMMENT '登录账号',
    `password`     varchar(100) NOT NULL COMMENT 'BCrypt 密文，固定 60 字符',
    `name`         varchar(50) NOT NULL,
    `phone`        varchar(20)  DEFAULT NULL,
    `sex`          char(1)      DEFAULT NULL COMMENT '0女 1男',
    `id_number`    varchar(20)  DEFAULT NULL,
    `role`         tinyint     NOT NULL DEFAULT 2 COMMENT '1平台管理员 2站点运维',
    `station_id`   bigint      NOT NULL DEFAULT 0 COMMENT '负责站点；0=平台级',
    `status`       tinyint     NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
    `create_time`  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`  datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`  bigint       DEFAULT NULL,
    `update_user`  bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_staff_username` (`username`),
    KEY `idx_staff_station_status` (`station_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='运维人员';
-- uk_staff_username 同时让重名在 DB 层失败，GlobalExceptionHandler 的
--   DuplicateKeyException 正则处理能直接复用，不需要为它单独写校验。
-- station_id 用 0 而不是 NULL 表示"平台级"：NULL 在唯一索引和等值查询里语义含糊，
--   而且 `where station_id = null` 永远不匹配（要写 IS NULL），是个经典陷阱。

-- ============================================================================
-- 3. vehicle 车辆
-- ============================================================================
CREATE TABLE IF NOT EXISTS `vehicle`
(
    `id`                   bigint      NOT NULL AUTO_INCREMENT,
    `member_id`            bigint      NOT NULL,
    `plate_no`             varchar(16) NOT NULL COMMENT '车牌号，入库统一转大写',
    `plate_color`          tinyint      DEFAULT NULL COMMENT '1蓝 2黄 3绿 4渐变绿',
    `brand`                varchar(50)  DEFAULT NULL,
    `model`                varchar(50)  DEFAULT NULL,
    `battery_capacity_kwh` decimal(8, 2) DEFAULT NULL COMMENT '电池容量，用于估算充满所需电量',
    `is_default`           tinyint     NOT NULL DEFAULT 0,
    `remark`               varchar(255) DEFAULT NULL,
    `create_time`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`          datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`          bigint       DEFAULT NULL,
    `update_user`          bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vehicle_member_plate` (`member_id`, `plate_no`),
    KEY `idx_vehicle_plate` (`plate_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='车辆';
-- uk(member_id, plate_no) 一次解决两件事：约束 + "查我的车辆"（最左前缀），
--   因此不再单独建 member_id 索引 —— 那会是一条永远用不上的冗余索引。
-- idx_vehicle_plate 服务反向场景：按车牌反查车主（站点客服常用）。

-- ============================================================================
-- 4. category 分类
-- ============================================================================
CREATE TABLE IF NOT EXISTS `category`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT,
    `name`        varchar(32) NOT NULL,
    `type`        tinyint     NOT NULL COMMENT '1充电套餐分类 2增值服务分类',
    `sort`        int         NOT NULL DEFAULT 0,
    `status`      tinyint     NOT NULL DEFAULT 1,
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user` bigint       DEFAULT NULL,
    `update_user` bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_type_name` (`type`, `name`),
    KEY `idx_category_type_sort` (`type`, `status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='分类';
-- 老库是 uk(name) 全局唯一。新库分了 type —— "快充"既可能是套餐分类也可能是服务分类，
--   全局唯一会误伤，所以改成复合唯一。
-- 列表查询固定是 where type=? and status=1 order by sort，
--   索引顺序 (type, status, sort) 让过滤和排序都走索引，不产生 filesort。

-- ============================================================================
-- 5. station 充电站
-- ============================================================================
CREATE TABLE IF NOT EXISTS `station`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT,
    `code`           varchar(32)    NOT NULL COMMENT '站点编码，对外可见',
    `name`           varchar(64)    NOT NULL,
    `operator`       varchar(64)     DEFAULT NULL COMMENT '运营商',
    `province`       varchar(32)     DEFAULT NULL,
    `city`           varchar(32)     DEFAULT NULL,
    `district`       varchar(32)     DEFAULT NULL,
    `address`        varchar(255)    DEFAULT NULL,
    `longitude`      decimal(10, 6)  DEFAULT NULL COMMENT '经度（decimal 而非 float：float 会漂移数十米）',
    `latitude`       decimal(10, 6)  DEFAULT NULL,
    `service_phone`  varchar(20)     DEFAULT NULL,
    `open_time`      time           NOT NULL DEFAULT '00:00:00',
    `close_time`     time           NOT NULL DEFAULT '24:00:00' COMMENT '24:00:00 表示到当天结束',
    `parking_fee`    decimal(8, 2)   DEFAULT NULL COMMENT '停车费 元/小时',
    `status`         tinyint        NOT NULL DEFAULT 1 COMMENT '0停用 1启用 2维护中',
    `create_time`    datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`    datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`    bigint          DEFAULT NULL,
    `update_user`    bigint          DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_station_code` (`code`),
    KEY `idx_station_city_status` (`city`, `status`),
    KEY `idx_station_lat_lng` (`latitude`, `longitude`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电站';
-- 真正按距离排序时：2 万站点以内用"包围盒 SQL + Java 侧 Haversine"就够了；
--   P4 再上 Redis GEO。
-- ★ 不要在 ORDER BY 里写三角函数表达式 —— 那必然全表扫描，
--   而且没法用索引，站点一多就是秒级响应。

-- ============================================================================
-- 6. charger 充电桩
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charger`
(
    `id`                   bigint        NOT NULL AUTO_INCREMENT,
    `station_id`           bigint        NOT NULL,
    `device_no`            varchar(64)   NOT NULL COMMENT '设备编号（设备报文里的唯一标识）',
    `name`                 varchar(64)    DEFAULT NULL COMMENT '桩位名，如 A区-01',
    `type`                 tinyint       NOT NULL DEFAULT 1 COMMENT '1直流快充 2交流慢充',
    `model`                varchar(64)    DEFAULT NULL,
    `rated_power_kw`       decimal(6, 2) NOT NULL,
    `gun_count`            tinyint       NOT NULL DEFAULT 1 COMMENT '枪数，冗余自 connector 便于列表展示',
    `status`               tinyint       NOT NULL DEFAULT 1 COMMENT '0离线 1在线空闲 2充电中 3故障 4维护中',
    `last_heartbeat_time`  datetime(3)    DEFAULT NULL,
    `firmware_version`     varchar(32)    DEFAULT NULL,
    `create_time`          datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`          datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`          bigint         DEFAULT NULL,
    `update_user`          bigint         DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_charger_device_no` (`device_no`),
    KEY `idx_charger_station_status` (`station_id`, `status`),
    KEY `idx_charger_heartbeat` (`last_heartbeat_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电桩';
-- idx_charger_heartbeat 服务离线巡检：
--   where last_heartbeat_time < now() - interval 5 minute and status <> 0
--   范围条件必须放在最左列，所以这条索引第一列就是时间。

-- ============================================================================
-- 7. connector 充电枪
--    ★★ 全库最关键的索引在这张表上（uk_connector_current_order）★★
-- ============================================================================
CREATE TABLE IF NOT EXISTS `connector`
(
    `id`                 bigint        NOT NULL AUTO_INCREMENT,
    `charger_id`         bigint        NOT NULL,
    `station_id`         bigint        NOT NULL COMMENT '冗余站点 id：枪的查询几乎都带站点维度，免去每次 join charger',
    `gun_no`             varchar(8)    NOT NULL COMMENT '枪口号 A/B/C',
    `connector_type`     varchar(32)    DEFAULT NULL COMMENT '接口类型，如 GB/T 20234.3',
    `rated_power_kw`     decimal(6, 2) NOT NULL,
    `status`             tinyint       NOT NULL DEFAULT 1 COMMENT '0离线 1空闲 2已占用 3充电中 4故障 5维护',
    `current_order_id`   bigint         DEFAULT NULL COMMENT '当前占用/在充的订单 id；空闲时 NULL',
    `total_charge_count` int           NOT NULL DEFAULT 0,
    `version`            int           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `create_time`        datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`        datetime(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_connector_charger_gun` (`charger_id`, `gun_no`),
    UNIQUE KEY `uk_connector_current_order` (`current_order_id`),
    KEY `idx_connector_station_status` (`station_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电枪';
-- ★★ uk_connector_current_order 是"枪口互斥"的最后一道防线。★★
--
--    MySQL 的唯一索引不约束 NULL —— 这条特性在这里被正面利用：
--      · 任意多把空闲枪（current_order_id IS NULL）可以并存，互不冲突
--      · 但一旦 1 号枪写入 current_order_id=1001，
--        第二个想写 1001 的请求在**数据库层**必然失败
--
--    它把"两个用户同时启动同一把枪"从"靠代码写对"变成"数据库不允许错"。
--
--    为什么不靠 synchronized：单实例有效、多实例失效；
--    而且 JDK 21 的虚拟线程下 synchronized 会 pin 住载体线程
--    （JDK 24 的 JEP 491 才修复），在开了虚拟线程的项目里是性能陷阱。
--    CacheClient.tryLock 只用于前置削峰，正确性由这里和条件更新保证。
--
--    ★ 面试金句：「锁是用来减少无效竞争的，不是用来保证正确性的。」

-- ============================================================================
-- 8. tariff_template 计费模板头
-- ============================================================================
CREATE TABLE IF NOT EXISTS `tariff_template`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT,
    `code`        varchar(32) NOT NULL,
    `name`        varchar(64) NOT NULL COMMENT '如 广东省 2026 工商业峰谷电价',
    `region`      varchar(32)  DEFAULT NULL,
    `description` varchar(255) DEFAULT NULL,
    `status`      tinyint     NOT NULL DEFAULT 1,
    `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user` bigint       DEFAULT NULL,
    `update_user` bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tariff_template_code` (`code`),
    KEY `idx_tariff_template_region_status` (`region`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='计费模板头';
-- idx(region, status) 服务"某地区有哪些可用模板"—— 运维新建站点时的选择列表。

-- ============================================================================
-- 9. tariff_rule 计费规则（尖峰平谷时段）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `tariff_rule`
(
    `id`            bigint         NOT NULL AUTO_INCREMENT,
    `template_id`   bigint         NOT NULL,
    `period_type`   tinyint        NOT NULL COMMENT '1尖峰 2高峰 3平段 4低谷',
    `start_time`    time           NOT NULL COMMENT '时段开始（含）',
    `end_time`      time           NOT NULL COMMENT '时段结束（不含）；24:00:00 表示到当天结束',
    `energy_price`  decimal(10, 4) NOT NULL COMMENT '电费单价 元/kWh',
    `service_price` decimal(10, 4) NOT NULL DEFAULT 0.0000 COMMENT '服务费单价 元/kWh',
    `seq`           int            NOT NULL DEFAULT 0,
    `create_time`   datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`   datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tariff_rule_tpl_start` (`template_id`, `start_time`),
    KEY `idx_tariff_rule_tpl_type` (`template_id`, `period_type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='计费规则';
-- uk(template_id, start_time) 保证同一模板内某个起始时刻只属于一个时段。
-- ★ 为什么不把 end_time 也放进唯一键：那样 [08:00,12:00) 和 [08:00,10:00)
--   就能并存了，唯一键就失去了意义。约束的是"起点不重复"，
--   而"无重叠"由应用层的 TariffValidator 在保存时校验。
--
-- ★★ 两条必须写进代码注释的约定：★★
--   1. 时段用半开区间 [start_time, end_time)，一天用 24:00:00 收尾。
--      若用 '00:00:00' 表示"结束"，23:00-24:00 会变成零长度段，
--      跨天和边界场景必出 bug。
--   2. 一个模板必须**完整、无重叠地覆盖 24 小时** ——
--      P1 的 TariffValidator 在保存模板时校验这一点。
--      不校验的话，某个时刻没有对应时段，计费时那一段的能量就没有价格，
--      表现为"订单金额莫名其妙少了"。

-- ============================================================================
-- 10. station_tariff 站点与模板绑定（带生效期）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `station_tariff`
(
    `id`             bigint      NOT NULL AUTO_INCREMENT,
    `station_id`     bigint      NOT NULL,
    `template_id`    bigint      NOT NULL,
    `effective_from` datetime(3) NOT NULL COMMENT '生效时间（含）',
    `effective_to`   datetime(3)  DEFAULT NULL COMMENT '失效时间（不含）；NULL=长期有效',
    `priority`       int         NOT NULL DEFAULT 0 COMMENT '同站同刻多条时的优先级，大者胜',
    `status`         tinyint     NOT NULL DEFAULT 1,
    `create_time`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`    bigint       DEFAULT NULL,
    `update_user`    bigint       DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_station_tariff_start` (`station_id`, `effective_from`),
    KEY `idx_station_tariff_lookup` (`station_id`, `status`, `effective_from`, `effective_to`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='站点-计费模板绑定';
-- ★★ 本表只负责"此刻该用哪个模板"的决策。★★
--
--    真正计费时用的是**订单上冻结的 tariff_template_id**。
--
--    这条铁律（电价调整不得影响历史/在途订单）是这张表存在的全部意义：
--    电价是电网按地区发布的，会调整；如果一个充电中的订单在结算时
--    才去查"现在这个站点用哪个价"，那么电价一调，
--    已经在充的订单会被按新价结算 —— 用户看到的金额和启动时预估的不一样，
--    而且没法解释。所以模板 id 在**下单那一刻**就冻结进 charging_order。

-- ============================================================================
-- 11. charge_package 充电套餐（扁平 SKU）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charge_package`
(
    `id`               bigint         NOT NULL AUTO_INCREMENT,
    `name`             varchar(64)    NOT NULL,
    `category_id`      bigint         NOT NULL,
    `package_type`     tinyint        NOT NULL COMMENT '1电量包 2时长包 3服务费折扣包 4次卡',
    `station_id`       bigint         NOT NULL DEFAULT 0 COMMENT '限定站点；0=全平台通用',
    `price`            decimal(12, 2) NOT NULL,
    `origin_price`     decimal(12, 2)  DEFAULT NULL COMMENT '划线价',
    `energy_kwh`       decimal(10, 2)  DEFAULT NULL COMMENT 'type=1 时的电量',
    `duration_minutes` int             DEFAULT NULL COMMENT 'type=2 时的时长',
    `times`            int             DEFAULT NULL COMMENT 'type=4 时的次数',
    `service_discount` decimal(5, 4)   DEFAULT NULL COMMENT 'type=3 时服务费折扣，0.8000=八折',
    `valid_days`       int            NOT NULL DEFAULT 0 COMMENT '0=永久',
    `description`      varchar(255)    DEFAULT NULL,
    `image`            varchar(255)    DEFAULT NULL,
    `status`           tinyint        NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    `sort`             int            NOT NULL DEFAULT 0,
    `create_time`      datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`      datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`      bigint          DEFAULT NULL,
    `update_user`      bigint          DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pkg_name_station` (`name`, `station_id`),
    KEY `idx_pkg_category_status_sort` (`category_id`, `status`, `sort`),
    KEY `idx_pkg_station_status` (`station_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电套餐';
-- ★ 为什么不沿用老库的 setmeal + setmeal_dish 嵌套结构：
--   老库那套是"套餐由多个菜品按份数组成"的语义，所以必须有一张关系表。
--   而充电套餐是"花 100 元买 150 度电"这种**单一权益**，没有子项。
--   硬套的话会造出一张永远只有一行子记录的关系表 —— 纯负担。
--
--   代价是上面那几个"按 package_type 二选一"的列必须靠应用层校验约束
--   （type=1 时 energy_kwh 必填，type=2 时 duration_minutes 必填）。
--   这个税值得交：MySQL 没有"条件必填"的约束能力，
--   而多一张表是每一条查询都要多一次 join 的长期成本。
--
-- station_id=0 而不是 NULL：唯一索引不约束 NULL，
--   留 NULL 的话"全平台套餐"可以任意重名，约束就废了。

-- ============================================================================
-- 12. member_package 车主持有权益（SKU 的「实例」）
--    ★★ 与 charge_package 是 SKU / 持有实例的关系，不是同一件事 ★★
-- ============================================================================
CREATE TABLE IF NOT EXISTS `member_package`
(
    `id`                bigint         NOT NULL AUTO_INCREMENT,
    `member_id`         bigint         NOT NULL COMMENT '持有人',
    `package_id`        bigint         NOT NULL COMMENT 'SKU：charge_package.id',
    `buy_order_id`      bigint          DEFAULT NULL COMMENT '购买时的支付单；P1c-3 之前为 NULL',
    `package_type`      tinyint        NOT NULL COMMENT '★ 购买时冻结的 SKU 类型：1电量包 2时长包 3服务费折扣包 4次卡',
    `package_name`      varchar(64)    NOT NULL COMMENT '★ 购买时的 SKU 名称快照',
    `total_kwh`         decimal(10, 2)  DEFAULT NULL COMMENT 'type=1 总电量',
    `remaining_kwh`     decimal(10, 2)  DEFAULT NULL COMMENT 'type=1 剩余电量；结算只动这一列',
    `total_minutes`     int             DEFAULT NULL COMMENT 'type=2 总时长（分钟）',
    `remaining_minutes` int             DEFAULT NULL COMMENT 'type=2 剩余时长（分钟）',
    `total_times`       int             DEFAULT NULL COMMENT 'type=4 总次数',
    `remaining_times`   int             DEFAULT NULL COMMENT 'type=4 剩余次数',
    `service_discount`  decimal(5, 4)   DEFAULT NULL COMMENT 'type=3 服务费折扣快照，0.8000=八折',
    `effective_from`    datetime(3)    NOT NULL COMMENT '生效时间（含）',
    `expire_time`       datetime(3)    NOT NULL COMMENT '过期时间（不含）；★ 永久用 9999-12-31 哨兵，不用 NULL',
    `locked_order_id`   bigint          DEFAULT NULL COMMENT '被哪笔订单锁定；NULL=未锁定。见下方 uk_mp_locked_order',
    `status`            tinyint        NOT NULL DEFAULT 1 COMMENT '1生效中 2已用尽 3已过期 4已作废',
    `remark`            varchar(255)    DEFAULT NULL,
    `version`           int            NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `create_time`       datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`       datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`       bigint          DEFAULT NULL,
    `update_user`       bigint          DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mp_locked_order` (`locked_order_id`),
    KEY `idx_mp_member_status_expire` (`member_id`, `status`, `expire_time`),
    KEY `idx_mp_member_type_status` (`member_id`, `package_type`, `status`, `expire_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='车主持有权益';

-- ★★ 为什么必须是独立的一张表 ★★
--
--   charge_package 是 **SKU**（可售的东西），member_package 是 **持有实例**
--   （某个车主买到的那一份）。四种 package_type 里有三种根本没法用 SKU 表达：
--
--     type=2 时长包        → 「还剩多少分钟」是持有属性，不是 SKU 属性
--     type=3 服务费折扣包  → 「这个车主此刻有没有生效中的折扣」是持有属性
--     type=4 次卡          → 「还剩几次、已用几次」是持有属性
--     type=1 电量包        → 150 度跨多笔订单核销，「还剩多少度」也是持有属性
--
--   所以「SKU 表够用，持有表多余」这个判断是错的：SKU 只能回答
--   「花 100 元能买什么」，回答不了「你已经买了什么、还剩多少」。

-- ★★ 为什么这些列要**快照**（package_type / package_name / total_* 等）★★
--
--   和 charging_order 冻结 tariff_template_id 是同一条铁律，只是换了个对象：
--   **已经卖出去的东西，条款不能被售后修改。**
--
--   charge_package 是可编辑的（管理端有 update 接口）。如果会员包的
--   「总电量」在核销时去查 SKU，那么管理员把 150 度改成 100 度的那一刻，
--   所有已经付了 150 度钱的用户会**静默地少掉 50 度** —— 每一步都不报错，
--   只有某天对账时发现余额对不上，而那时已经无从追溯是哪次改动引入的。
--
--   快照之后，charge_package 只负责「货架」，member_package 负责「账」。
--   charge_package.delete 时也不用担心影响已售出的权益
--   （这正是 ChargePackageMapper.deleteById 注释里预留的那个检查点）。
--
--   ★ 只快照「计费要用到的」和「用户会看到的」：名称、总/剩余量、折扣、有效期。
--     描述、图片这类纯展示字段不快照 —— 用户想看最新的商品图，就去看 SKU。

-- ★★ uk_mp_locked_order：唯一索引不约束 NULL 的**第四次**利用 ★★
--
--   前三次分别在 connector.current_order_id、charging_order_event.idempotent_key、
--   wallet_transaction.(order_id, type)。这里是第四次，语义和第一次最接近：
--
--     · 任意多份未锁定的权益（locked_order_id IS NULL）并存，互不冲突
--     · 一旦把某份权益写成 locked_order_id = 1001，
--       第二笔订单想锁同一份权益，在**数据库层**必然失败
--
--   它把「同一份权益被两笔订单同时消费」从「靠代码写对」变成「数据库不允许错」。
--
--   ★ 反方向也一并约束了：一笔订单**最多锁定一个消耗型权益实例**。
--     这是符合业务语义的 —— 「一次充电只消耗一种主权益」：
--     要么走电量包，要么走时长包，要么走次卡，要么余额直充。
--     这也正是 charging_order 上那个单一的可空外键所表达的意思。
--
--   ★★ 折扣包（type=3）**永远不写这个字段**，恒为 NULL ★★
--     它不是消耗品，是「有效期内自动适用」的修饰符 ——
--     结算时问「这个车主此刻有没有生效的折扣包」，有就打折，不扣任何量。
--     给它加锁没有意义，反而会因为「一笔订单同时用了电量包和折扣包」
--     而撞上唯一键。所以豁免它，不是漏了。
--
--   ★ 和前面三处一样：CacheClient.tryLock 只用于前置削峰，
--     **正确性由这里和条件更新保证**。锁是用来减少无效竞争的，
--     不是用来保证正确性的。

-- ★★ 结算为什么是幂等的：把「判断」和「修改」放进同一条 UPDATE ★★
--
--   设备会重复上报「充电结束」，结算因此可能被触发多次。防重复扣减靠的是
--   locked_order_id 本身当作**幂等标记**：
--
--       -- 正确：影响行数为 0 就说明这笔订单已经结算过了（或锁不属于它）
--       UPDATE member_package
--          SET remaining_kwh = remaining_kwh - #{kwh},
--              locked_order_id = NULL,
--              status = CASE WHEN remaining_kwh - #{kwh} <= 0 THEN 2 ELSE status END
--        WHERE id = #{id} AND locked_order_id = #{orderId};
--
--   第一次执行后 locked_order_id 变成 NULL，第二次必然匹配不上。
--   **不要**写成「先 SELECT 判断 locked_order_id 是不是这笔订单，再 UPDATE」——
--   那是 TOCTOU（检查与使用之间存在时间窗口），
--   并发下两个线程都能通过检查，然后各扣一次。
--   这条和 wallet 那段「严禁先 select 判断再 update」是同一条规则。
--
--   ★ 取消订单走的是**另一个分支**：只清 locked_order_id，remaining_* 不变。
--
--   ★ 为什么不额外建一张「权益核销流水表」：
--     消费的凭证已经有两份了 —— charging_order 记着总额和电量，
--     charging_segment 记着分段金额，而订单上指向的就是本表的某一行。
--     再加一张流水表，它记的东西是这三张表的子集，
--     却多出一份「必须和它们保持一致」的责任。资金侧不同：
--     wallet_transaction 存在是因为余额是**累加出来的**，
--     必须能独立重放出任意时刻的值；而权益的剩余量是**直接存着的**，
--     每次核销也把 before/after 记在了订单事件表里，不需要再重放一遍。

-- ★ expire_time 用 9999-12-31 哨兵而不是 NULL，理由和 station_id=0 一模一样：
--   「有效期到那天」的查询是 where expire_time > now()，
--   而 NULL 参与任何比较都是 NULL（不成立）——
--   永久有效的权益会**被这个查询漏掉**，表现是「明明买了永久套餐，结算时说没有」。
--   这和 where station_id = null 永远不匹配是同一个陷阱。
--   （提前说清楚：这个列**故意没有 DEFAULT**。
--     忘了填应该是 errno 1364 这种响亮的报错，
--     而不是静默地拿到一个「永久有效」—— 后者是能白送钱的错误。）

-- ★ status 的五种取值里，2/3 是**推导出来的**状态（剩余量归零 / 过时间点），
--   由定时任务或读路径惰性刷新。判断「这个包现在能不能用」时，
--   **不要只看 status**，还要看 expire_time 和剩余量 —— 那三者的组合才是真相，
--   status 只是它的一个缓存。见 idx_mp_member_status_expire 的用途。

-- 索引逐条说明：
--
--   uk_mp_locked_order (locked_order_id)
--       枪口互斥的对应物，用处见上面。NULL 不参与约束，空闲态零成本。
--
--   idx(member_id, status, expire_time)
--       车主端「我的套餐」列表：where member_id=? and status=1 order by expire_time。
--       最左 member_id 等值定位，status 等值过滤，expire_time 提供有序结果 ——
--       不需要 filesort。
--
--   idx(member_id, package_type, status, expire_time)
--       结算时的两次定点查询，都是高频路径：
--         「他有没有生效中的折扣包」→ member_id=? and package_type=3 and status=1
--         「他有没有可用的电量包」  → member_id=? and package_type=1 and status=1
--       ★ 前两列的顺序是 (member_id, package_type) 而不是反过来：
--         会员包是**按人**攒起来的，一个人的包是几行；
--         而 package_type 只有 4 个值，选择性极低，
--         放最左等于扫全表里四分之一的行走索引，还不如全表扫描。
--         ——和 idx_order_connector_status 里「枪的选择性远高于状态」是同一条道理。
--
-- ★ 不建 package_id 单列索引：它是「这个套餐卖了多少份」的报表维度，
--   而那个查询属于 P3 对账，届时会连 create_time 一起用
--   （「本季度销量」），届时再按真实 SQL 建。
--   现在建的话，它是一条每次购买都要维护、却没有任何查询会选中的索引。

-- ============================================================================
-- 13. charging_order 充电订单（核心表）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charging_order`
(
    `id`               bigint         NOT NULL AUTO_INCREMENT,
    `order_no`         varchar(32)    NOT NULL COMMENT '业务单号，如 CO202609180001',
    `member_id`        bigint         NOT NULL,
    `vehicle_id`       bigint          DEFAULT NULL,
    `plate_no`         varchar(16)     DEFAULT NULL COMMENT '车牌快照：车辆可被删除/改牌，订单必须保留当时值',
    `station_id`       bigint         NOT NULL,
    `charger_id`       bigint         NOT NULL,
    `connector_id`     bigint         NOT NULL,
    `member_package_id` bigint         DEFAULT NULL COMMENT '★ 冻结的持有权益：member_package.id（不是 SKU）；NULL=余额直充',
    `tariff_template_id` bigint       NOT NULL COMMENT '★ 冻结的计费模板：见 station_tariff 的注释',
    `status`           tinyint        NOT NULL DEFAULT 1
        COMMENT '1待启动 2充电中 3充电结束待结算 4待支付 5已支付 6支付失败 7已退款 8已取消 9异常挂账',
    `start_time`       datetime(3)     DEFAULT NULL,
    `end_time`         datetime(3)     DEFAULT NULL,
    `duration_seconds` int             DEFAULT NULL,
    `start_meter_kwh`  decimal(12, 4)  DEFAULT NULL COMMENT '起始电表读数',
    `end_meter_kwh`    decimal(12, 4)  DEFAULT NULL,
    `total_energy_kwh` decimal(12, 4)  DEFAULT NULL COMMENT '以电表差为准',
    `start_soc`        tinyint          DEFAULT NULL,
    `end_soc`          tinyint          DEFAULT NULL,
    `energy_fee`       decimal(12, 2)  DEFAULT NULL COMMENT '电费合计',
    `service_fee`      decimal(12, 2)  DEFAULT NULL,
    `total_amount`     decimal(12, 2)  DEFAULT NULL COMMENT '应付 = 电费 + 服务费',
    `discount_amount`  decimal(12, 2)  NOT NULL DEFAULT 0.00,
    `pay_amount`       decimal(12, 2)  DEFAULT NULL COMMENT '实付 = 应付 - 优惠',
    `frozen_amount`    decimal(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '启动时冻结的预估金额',
    `refund_amount`    decimal(12, 2)  NOT NULL DEFAULT 0.00 COMMENT '结算后退回余额的部分',
    `pay_method`       tinyint          DEFAULT NULL COMMENT '1余额 2微信 3支付宝',
    `pay_time`         datetime(3)      DEFAULT NULL,
    `transaction_id`   varchar(64)      DEFAULT NULL COMMENT '第三方支付流水号',
    `refund_time`      datetime(3)      DEFAULT NULL,
    `stop_reason`      tinyint          DEFAULT NULL COMMENT '1用户主动 2充满自停 3余额不足 4设备故障 5超时 6运维干预',
    `settle_time`      datetime(3)      DEFAULT NULL COMMENT '结算完成时间（幂等标记）',
    `remark`           varchar(255)     DEFAULT NULL,
    `version`          int            NOT NULL DEFAULT 0 COMMENT '乐观锁：状态流转用',
    `create_time`      datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`      datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user`      bigint          DEFAULT NULL,
    `update_user`      bigint          DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_order_member_create` (`member_id`, `create_time`),
    KEY `idx_order_member_status` (`member_id`, `status`),
    KEY `idx_order_connector_status` (`connector_id`, `status`),
    KEY `idx_order_status_create` (`status`, `create_time`),
    KEY `idx_order_station_create` (`station_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电订单';
-- 索引逐条说明（每一條都对应一个真实查询，不是"多建几个总没坏处"）：
--
--   idx(member_id, create_time)
--       用户端"我的订单"按时间倒序。联合索引直接给出有序结果，
--       可以反向扫描，避免 filesort。
--
--   idx(member_id, status)
--       "我的进行中订单" —— where member_id=? and status in (1,2,3)。
--       这条**不能**被上面那条替代：上面那条第二列是时间，不是状态。
--
--   idx(connector_id, status)   ★ 幂等入口
--       设备重复上报"结束"事件时，第一件事就是问
--       "这把枪上还有 status ∈ {2,3} 的订单吗"。
--       顺序是 (connector_id, status) 而不是反过来 ——
--       枪的选择性远高于状态（几千把枪 vs 几个状态值）。
--
--   idx(status, create_time)
--       定时任务扫超时：where status=1 and create_time < now() - 15min。
--       状态在前：该场景下"待启动"只占极小比例，等值定位后按时间范围取；
--       写成 (create_time, status) 会退化成"扫全表所有时间点再过滤状态"。
--
--   idx(station_id, create_time)
--       站点维度的日报/对账。
--
-- ★ 不建 member_id 单列索引 —— 被 idx_order_member_create 的最左前缀覆盖，
--   建了就是一条永远不会被选中、但每次 insert 都要维护的索引。
--
-- ★★ member_package_id 指向**持有实例**，不是 SKU ★★
--
--   这一列原来叫 package_id，注释是「NULL=余额直充」，没有说清指向哪张表 ——
--   而它必须指向 member_package.id（车主买到的那一份），不能指向 charge_package.id。
--
--   反例：一个车主先后买了两个「150度电包」，手里有两份**同款**权益。
--   如果他这次充电消耗的是第二份，那么订单上写 charge_package.id 就丢失了
--   「消耗的是哪一份」这个信息 —— 结算时不知道去扣哪一行的 remaining_kwh，
--   只能随便挑一个，或者把两份都当成一份用。
--
--   这和 tariff_template_id 冻结的是同一个东西：
--   **「用哪个」这个决策在下单那一刻定下，之后不再重新解析。**
--   只不过那一个冻结的是价格条款，这一个冻结的是权益条款。
--
--   ★ 需要 SKU 维度的报表（"这个套餐卖了多少、用了多少"）时，
--     从 member_package.package_id join 过去，不要在这里冗余一份。
--
--   ★ 不建 member_package_id 单列索引：热路径上没有「按权益反查订单」的查询
--     （结算方向是 订单 → 权益，不是反过来）。P3 对账如果真需要，届时再建。
--
-- ★ 金额列**可空是刻意的**：0 和"还没算"是两件事。
--   结算任务扫 status=3 的订单时，若默认 0，就分不清
--   "电量确实是 0"（用户插上就拔了）和"还没结算"（任务挂了）。

-- ============================================================================
-- 14. charging_segment 计费分段
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charging_segment`
(
    `id`               bigint         NOT NULL AUTO_INCREMENT,
    `order_id`         bigint         NOT NULL,
    `seq`              int            NOT NULL COMMENT '第几段，从 1 开始',
    `period_type`      tinyint        NOT NULL COMMENT '1尖峰 2高峰 3平段 4低谷',
    `start_time`       datetime(3)    NOT NULL COMMENT '本段开始（含）',
    `end_time`         datetime(3)    NOT NULL COMMENT '本段结束（不含）',
    `duration_seconds` int            NOT NULL,
    `energy_kwh`       decimal(12, 4) NOT NULL,
    `energy_price`     decimal(10, 4) NOT NULL COMMENT '电费单价快照',
    `service_price`    decimal(10, 4) NOT NULL COMMENT '服务费单价快照',
    `energy_fee`       decimal(12, 2) NOT NULL,
    `service_fee`      decimal(12, 2) NOT NULL,
    `amount`           decimal(12, 2) NOT NULL,
    `create_time`      datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_segment_order_start` (`order_id`, `start_time`),
    KEY `idx_segment_order_seq` (`order_id`, `seq`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='计费分段';
-- ★★ uk(order_id, start_time) 是结算幂等的核心。★★
--
--    结算算法重跑时，重复插入相同分段会**撞唯一键**，
--    而不是把金额翻倍。
--
--    对比"先 delete 再 insert"的做法：后者在重试和并发下会丢数据 ——
--    A 删完还没插，B 也删一遍然后插进去，A 再插就重复了；
--    或者 A 删完崩溃，B 以为没数据也不插，分段全没了。
--    唯一键是数据库层面的保证，不依赖任何代码时序。
--
-- ★ 前提：拆分算法**必须跳过零长度分段**。
--   订单起止时间恰好落在时段边界上时会产生零长度段
--   （比如订单 10:00 开始，正好是尖峰段的起点，[10:00,10:00)），
--   它会撞上唯一键。这是实现时最容易踩的坑，写进 BillingEngine 的注释。

-- ============================================================================
-- 15. charging_order_event 订单状态流转事件
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charging_order_event`
(
    `id`             bigint      NOT NULL AUTO_INCREMENT,
    `order_id`       bigint      NOT NULL,
    `from_status`    tinyint      DEFAULT NULL,
    `to_status`      tinyint     NOT NULL,
    `event_type`     varchar(32) NOT NULL COMMENT '创建/启动/结束/结算/支付/退款/取消/重复事件丢弃',
    `source`         tinyint     NOT NULL DEFAULT 1 COMMENT '1用户端 2设备上报 3定时任务 4运维 5支付回调',
    `operator_id`    bigint       DEFAULT NULL,
    `detail`         varchar(500) DEFAULT NULL COMMENT '设备原始报文 / 异常信息',
    `idempotent_key` varchar(64)  DEFAULT NULL COMMENT '设备事件去重键：device_no + event_seq',
    `create_time`    datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_idempotent` (`idempotent_key`),
    KEY `idx_event_order_time` (`order_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单状态流转事件';
-- ★ "唯一索引不约束 NULL" 第三次被利用：
--   非设备来源的事件（用户端取消、定时任务结算）idempotent_key 为 NULL，
--   不受唯一约束；只有设备上报的事件才参与去重。
--
-- ★★ 这张表是可观测性资产，不是可有可无的日志。★★
--    只有 status 字段时，你只能看到终态；
--    有了它才能回答"2→3 是设备上报的、3→4 是结算任务写的、4→4 是重复事件被丢的"。
--    排查"这笔订单为什么金额不对"时，事件表是唯一能还原现场的东西。

-- ============================================================================
-- 16. charging_telemetry 遥测记录（数据量最大，P4 分表对象）
-- ============================================================================
CREATE TABLE IF NOT EXISTS `charging_telemetry`
(
    `id`           bigint         NOT NULL AUTO_INCREMENT,
    `order_id`     bigint          DEFAULT NULL COMMENT '空闲状态上报为 NULL',
    `connector_id` bigint         NOT NULL,
    `charger_id`   bigint         NOT NULL,
    `station_id`   bigint         NOT NULL,
    `report_time`  datetime(3)    NOT NULL COMMENT '设备上报时间（设备时钟）',
    `voltage`      decimal(8, 2)   DEFAULT NULL COMMENT '电压 V',
    `current_a`    decimal(8, 2)   DEFAULT NULL COMMENT '电流 A（★ 不叫 current：保留字风险）',
    `power_kw`     decimal(8, 2)   DEFAULT NULL,
    `soc`          tinyint         DEFAULT NULL,
    `energy_kwh`   decimal(12, 4)  DEFAULT NULL COMMENT '累计电量',
    `temperature`  decimal(6, 2)   DEFAULT NULL,
    `create_time`  datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间（服务端时钟）',
    PRIMARY KEY (`id`),
    KEY `idx_tel_order_time` (`order_id`, `report_time`),
    KEY `idx_tel_connector_time` (`connector_id`, `report_time`),
    KEY `idx_tel_report_time` (`report_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='遥测记录（P4 按 report_time 分表/分区）';
-- ★ 写多读少、只增不改的表，索引要**克制**。
--   每多一个索引，每次设备上报就多一次 B+ 树维护，
--   而这是每秒都在发生的事。只留 3 条，全部对应同一个查询模式
--   "按时间范围取某个对象的曲线"。
--   idx(report_time) 单独存在是为了 P4 的归档清理和分区裁剪。
--
-- ★ 注意 order_id 允许 NULL，但不能写成 where order_id = null（永远不匹配），
--   要用 IS NULL。
--
-- ★ P4 分表前提：**每条查询都必须带 report_time 范围**，
--   否则会扫全部分片。这条约束要从现在开始遵守，
--   等分表时再改就不是加索引能解决的了。
--
-- ★ 写入侧配 rewriteBatchedStatements=true + 批量 insert
--   （每 100 条或每 1 秒刷一次），不要一条一条 insert。

-- ============================================================================
-- 17. wallet 钱包
-- ============================================================================
CREATE TABLE IF NOT EXISTS `wallet`
(
    `id`              bigint         NOT NULL AUTO_INCREMENT,
    `member_id`       bigint         NOT NULL,
    `balance`         decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '可用余额（已扣掉冻结）',
    `frozen_amount`   decimal(12, 2) NOT NULL DEFAULT 0.00 COMMENT '冻结金额（在充订单占用）',
    `total_recharge`  decimal(14, 2) NOT NULL DEFAULT 0.00,
    `version`         int            NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `create_time`     datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`     datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_member` (`member_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='车主钱包';
-- ★ balance 与 frozen_amount 分两列，是"预冻结"模式的关键：
--   冻结**不能**让"可用余额"减少（那就变成直接扣款了），
--   界面上要同时看到"可用 / 冻结"两行。
--
-- ★ 钱包相关的更新一律用条件更新，**严禁"先 select 判断再 update"**：
--       -- 错误写法（TOCTOU：检查与使用之间存在时间窗口）
--       SELECT balance FROM wallet WHERE member_id = 1;   -- 读到 100
--       -- 此刻另一个线程扣掉了 90
--       UPDATE wallet SET balance = 100 - 80 WHERE member_id = 1;  -- 负数！
--
--       -- 正确写法：把判断放进 WHERE，影响行数为 0 就是余额不足
--       UPDATE wallet SET balance = balance - 80, frozen_amount = frozen_amount + 80
--       WHERE member_id = 1 AND balance >= 80;
--
--   条件更新是原子的：MySQL 在行锁保护下完成"判断 + 修改"，
--   不存在中间状态被别的线程看到的可能。

-- ============================================================================
-- 18. wallet_transaction 钱包流水
-- ============================================================================
CREATE TABLE IF NOT EXISTS `wallet_transaction`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT,
    `transaction_no` varchar(32)    NOT NULL,
    `member_id`      bigint         NOT NULL,
    `order_id`       bigint          DEFAULT NULL COMMENT '充值为 NULL',
    `type`           tinyint        NOT NULL COMMENT '1充值 2冻结 3解冻 4扣款 5退款 6套餐购买 7管理员调账',
    `amount`         decimal(12, 2) NOT NULL COMMENT '正入账负出账',
    `balance_before` decimal(12, 2) NOT NULL,
    `balance_after`  decimal(12, 2) NOT NULL,
    `frozen_before`  decimal(12, 2) NOT NULL DEFAULT 0.00,
    `frozen_after`   decimal(12, 2) NOT NULL DEFAULT 0.00,
    `remark`         varchar(255)    DEFAULT NULL,
    `create_time`    datetime(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_txn_no` (`transaction_no`),
    UNIQUE KEY `uk_txn_order_type` (`order_id`, `type`),
    KEY `idx_txn_member_time` (`member_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='钱包流水';
-- ★★ uk(order_id, type) 是资金侧的幂等闸门。★★
--
--    同一订单的"扣款"永远只能有一条。
--    支付回调、结算重试、定时补偿这**三条路径**都会尝试扣款，
--    这条唯一索引让"扣两次钱"在数据库层不可能发生 ——
--    不管那三条路径的代码写得对不对、有没有并发。
--
--    order_id 为 NULL 的充值流水不参与约束（唯一索引不约束 NULL），正合需要：
--    充值本来就可以有任意多笔。
--
-- ★ 存 before/after 快照（而不是只存变动额）是财务系统的通行做法：
--   对账时不用重放全部流水就能验证余额正确性。
--   只存变动额的话，想知道"3 月 1 日那天的余额是多少"就得从头累加。

-- ============================================================================
-- 19. alarm 设备告警
-- ============================================================================
CREATE TABLE IF NOT EXISTS `alarm`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT,
    `station_id`    bigint       NOT NULL,
    `charger_id`    bigint       NOT NULL,
    `connector_id`  bigint        DEFAULT NULL COMMENT '桩级告警为 NULL',
    `order_id`      bigint        DEFAULT NULL COMMENT '充电中故障关联的订单',
    `alarm_code`    varchar(32)  NOT NULL COMMENT '故障码，如 E-0x21',
    `alarm_level`   tinyint      NOT NULL COMMENT '1提示 2一般 3严重',
    `alarm_desc`    varchar(255)  DEFAULT NULL,
    `status`        tinyint      NOT NULL DEFAULT 0 COMMENT '0未处理 1处理中 2已处理 3已忽略',
    `occur_time`    datetime(3)  NOT NULL,
    `recover_time`  datetime(3)   DEFAULT NULL COMMENT '故障自动恢复时间',
    `handler_id`    bigint        DEFAULT NULL COMMENT '处理人 staff_id',
    `handle_time`   datetime(3)   DEFAULT NULL,
    `handle_remark` varchar(255)  DEFAULT NULL,
    `create_time`   datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`   datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_alarm_station_status_time` (`station_id`, `status`, `occur_time`),
    KEY `idx_alarm_charger_time` (`charger_id`, `occur_time`),
    KEY `idx_alarm_level_status_time` (`alarm_level`, `status`, `occur_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='设备告警';
-- 三条索引精确对应三类查询：
--   站点值班看板、单桩故障史、平台级"严重且未处理"。
-- 都带了 occur_time 或 status：**纯按 status 建索引选择性太低**
--   （未处理告警可能占绝大多数，走索引还不如全表扫描）。
--
-- ★ 重复告警去重放在应用层（同桩同码 5 分钟内只报一次），
--   **不做**"把时间截断到分钟"的唯一键 —— 那种唯一键极其脆弱：
--   换一个去重窗口就要改表结构，而且边界上（59 秒和 61 秒）仍然会重复。
--
-- ★ 与订单联动：充电中发生故障 → 回填 alarm.order_id
--   + 强制结束订单（stop_reason=4 设备故障）。

-- ============================================================================
-- 种子数据
-- ============================================================================
-- 全部用 INSERT IGNORE，让整个脚本可以反复执行而不会报主键冲突、
-- 也不会产生重复数据。配合 CREATE TABLE IF NOT EXISTS，这个脚本是幂等的。
-- ============================================================================

-- 管理员 admin / 123456
-- ★ 这是一段 BCrypt 密文（$2a$10$ 开头，固定 60 字符），不是 MD5。
--   老库用的是 DigestUtils.md5DigestAsHex，那在今天是不可接受的：
--   MD5 无盐、且 GPU 每秒能算几十亿次，一次拖库就等于明文泄露。
--   这段密文已用 BCryptPasswordEncoder.matches("123456", ...) 实测验证过。
INSERT IGNORE INTO `staff` (`id`, `username`, `password`, `name`, `phone`, `sex`, `id_number`, `role`,
                            `station_id`, `status`)
VALUES (1, 'admin', '$2a$10$.yHXk81HCQDHfjXvu6M5U.GhLiBhvefyOblJmfNmRfQjf32A/eCUO',
        '平台管理员', '13812312312', '1', '110101199001010047', 1, 0, 1);

INSERT IGNORE INTO `category` (`id`, `name`, `type`, `sort`)
VALUES (1, '直流快充套餐', 1, 1),
       (2, '交流慢充套餐', 1, 2),
       (3, '停车服务', 2, 3);

INSERT IGNORE INTO `station` (`id`, `code`, `name`, `operator`, `city`, `address`, `longitude`, `latitude`,
                              `service_phone`, `status`)
VALUES (1, 'ST-440300-0001', '科技园充电站', 'ChargeOps', '深圳市', '南山区科技园南路 1 号',
        113.943000, 22.528000, '0755-88886666', 1);

INSERT IGNORE INTO `charger` (`id`, `station_id`, `device_no`, `name`, `type`, `rated_power_kw`, `gun_count`,
                              `status`)
VALUES (1, 1, 'DEV-2026-0001', 'A区-01', 1, 120.00, 2, 1);

INSERT IGNORE INTO `connector` (`id`, `charger_id`, `station_id`, `gun_no`, `connector_type`, `rated_power_kw`,
                                `status`)
VALUES (1, 1, 1, 'A', 'GB/T 20234.3', 120.00, 1),
       (2, 1, 1, 'B', 'GB/T 20234.3', 120.00, 1);

-- ----------------------------------------------------------------------------
-- 计费模板：深圳 2026 工商业分时电价（示例值，非真实电价）
-- ----------------------------------------------------------------------------
-- ★★ 这 8 行是理解整个计费模块的入口，值得花几分钟看懂：★★
--
--   时段划分（尖峰 4h / 高峰 7h / 平段 5h / 低谷 8h，合计 24h）：
--     00:00-08:00  低谷  0.3188 + 0.3000    ← 凌晨，电网负荷最低，最便宜
--     08:00-10:00  平段  0.7188 + 0.3000
--     10:00-12:00  尖峰  1.2188 + 0.4000    ← 上午生产高峰，最贵
--     12:00-14:00  平段  0.7188 + 0.3000
--     14:00-19:00  高峰  1.0188 + 0.3500
--     19:00-21:00  尖峰  1.2188 + 0.4000    ← 晚高峰，也是最贵
--     21:00-23:00  高峰  1.0188 + 0.3500
--     23:00-24:00  平段  0.7188 + 0.3000
--
--   三个设计要点：
--   1) 每个时段是**半开区间** [start, end)，最后一段用 24:00:00 收尾。
--      MySQL 的 TIME 类型支持 '24:00:00'（范围到 838:59:59）。
--      如果写成 '00:00:00' 表示结束，23:00-24:00 会变成一个零长度段。
--   2) 电费和服务费**分开存**。这不是冗余：电费是电网收的、服务费是运营商收的，
--      对账时要分别和电网、和财务报表核对。合成一个"单价"就再也拆不开了。
--   3) 一条跨越多个时段的订单（比如 09:30 充到 11:30），会被拆成
--      平段[09:30,10:00) + 尖峰[10:00,11:30) 两段分别计价 ——
--      这正是 P1 的 BillingEngine 要做的事。
--
--   ★ 注意 session 里 22:00-24:00 和 00:00-08:00 是**两行**。
--     不要试图用一行 22:00-08:00 表达跨天：TIME 类型不支持，
--     而且语义混乱（到底是当天 22 点到次日 8 点，还是别的什么？）。
--     按绝对时间切分时，"跨天"天然被处理掉了。
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO `tariff_template` (`id`, `code`, `name`, `region`, `description`)
VALUES (1, 'SZ-2026-GYF', '深圳 2026 工商业分时电价', '深圳市',
        '尖峰 4h / 高峰 7h / 平段 5h / 低谷 8h，合计 24h');

INSERT IGNORE INTO `tariff_rule` (`template_id`, `period_type`, `start_time`, `end_time`, `energy_price`,
                                  `service_price`, `seq`)
VALUES (1, 4, '00:00:00', '08:00:00', 0.3188, 0.3000, 1),  -- 低谷
       (1, 3, '08:00:00', '10:00:00', 0.7188, 0.3000, 2),  -- 平段
       (1, 1, '10:00:00', '12:00:00', 1.2188, 0.4000, 3),  -- 尖峰
       (1, 3, '12:00:00', '14:00:00', 0.7188, 0.3000, 4),  -- 平段
       (1, 2, '14:00:00', '19:00:00', 1.0188, 0.3500, 5),  -- 高峰
       (1, 1, '19:00:00', '21:00:00', 1.2188, 0.4000, 6),  -- 尖峰
       (1, 2, '21:00:00', '23:00:00', 1.0188, 0.3500, 7),  -- 高峰
       (1, 3, '23:00:00', '24:00:00', 0.7188, 0.3000, 8);  -- 平段（★ 24:00:00 的写法）

INSERT IGNORE INTO `station_tariff` (`station_id`, `template_id`, `effective_from`, `status`)
VALUES (1, 1, '2026-01-01 00:00:00', 1);

INSERT IGNORE INTO `charge_package` (`name`, `category_id`, `package_type`, `station_id`, `price`, `energy_kwh`,
                                     `valid_days`, `description`)
VALUES ('150度电包', 1, 1, 0, 100.00, 150.00, 90, '全平台通用，90 天有效'),
       ('新站首充 50 度', 1, 1, 1, 20.00, 50.00, 30, '仅限科技园充电站');

INSERT IGNORE INTO `member` (`id`, `phone`, `nickname`, `status`)
VALUES (1, '13800000001', '测试车主', 1);

INSERT IGNORE INTO `wallet` (`member_id`, `balance`, `total_recharge`)
VALUES (1, 500.00, 500.00);

INSERT IGNORE INTO `vehicle` (`member_id`, `plate_no`, `plate_color`, `brand`, `battery_capacity_kwh`, `is_default`)
VALUES (1, '粤BD12345', 3, '比亚迪 汉 EV', 85.00, 1);

-- ----------------------------------------------------------------------------
-- 给测试车主（member 1）挂一份持有权益，供 P1c-3 的订单链路直接使用
-- ----------------------------------------------------------------------------
-- ★ 三个和别的种子数据不同的写法，每个都有理由：
--
--   1) 用 INSERT ... SELECT 而不是 VALUES —— 因为 package_id 必须是
--      charge_package 里真实存在的那一行，而上面那两条套餐是**自增 id**，
--      写死成 1 只在「全新库」这个前提下成立。
--      用子查询按「名称 + 站作用域」定位，才对得上任何建库顺序。
--
--   2) 固定 id = 1 —— 这是这个脚本幂等的依据（撞主键 → IGNORE）。
--      本表没有别的天然唯一键，不给固定 id 的话重跑一次就多一行。
--
--   3) 有效期用 NOW() 算而不是写死日期 —— 别的种子（计费模板的
--      effective_from='2026-01-01'）写死没问题，因为那是历史事实；
--      而这份权益是**给开发阶段用的**，写死日期的话过几个月再跑建库脚本，
--      拿到的东西一出生就是过期的，表现是「结算说没可用权益」，
--      而你会先去怀疑结算代码。
INSERT IGNORE INTO `member_package`
    (`id`, `member_id`, `package_id`, `package_type`, `package_name`,
     `total_kwh`, `remaining_kwh`, `effective_from`, `expire_time`, `status`)
SELECT 1, 1, `id`, `package_type`, `name`,
       150.00, 150.00,
       DATE_SUB(NOW(3), INTERVAL 1 DAY),
       DATE_ADD(NOW(3), INTERVAL 89 DAY),
       1
FROM `charge_package`
WHERE `name` = '150度电包'
  AND `station_id` = 0;

-- ============================================================================
-- 建库完成自检
-- ============================================================================
-- 期望：19 张表
SELECT COUNT(*) AS `表数量（期望 19）`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'chargeops';

-- 期望：8 行计费规则，时段合计 24 小时
SELECT COUNT(*)                                                AS `计费规则条数（期望 8）`,
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
