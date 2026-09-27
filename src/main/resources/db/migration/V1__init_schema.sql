-- ChargeOps 新能源充电桩运营平台 V1 初始化建表：MySQL 8.4 / utf8mb4_0900_ai_ci / InnoDB / 不使用外键。
-- 已应用的迁移永远不要修改（validate-on-migrate 会让它变成一次启动失败），要改表结构就加 V4__xxx.sql。
-- 本文件只含 DDL：建库见 sql/00_create_database.sql，种子数据在 V2__seed_data.sql，自检查询在 sql/verify.sql。
-- 保留 CREATE TABLE IF NOT EXISTS 是为了让被 baseline 跳过的老库万一被手工执行一次也不会炸。
--
-- 全库 19 张表，几条贯穿全库的约定：
--   时间列一律 datetime(3)，不用 timestamp：后者有 2038 上限，且会依赖会话时区做隐式换算。
--   不用外键：InnoDB 的外键检查会在父表上加共享锁，而这里是「枪状态高频更新 + 订单高频写入」，
--     会显著扩大锁范围；完整性靠 Java 层校验 + 唯一索引 + 条件更新，孤儿数据由对账巡检兜底。
--   金额三档精度：单价 decimal(10,4)（工商业分时电价就是 4 位小数）、电量 decimal(12,4)、
--     总额/余额 decimal(12,2)。Java 侧全程 BigDecimal，只在最终一步 setScale(2, HALF_UP)，
--     中间分段不舍入 —— 否则各段各舍一次，Σ分段 ≠ 订单总额。
--   审计四列的数据库默认值只是兜底，Java 侧的 AutoFillAspect 仍然会填，见 AutoFill 注解。
--   每个索引都对应一条真实查询：「先建索引，等慢了再说」在每秒都在写的遥测表上是错的。

-- 1. member 车主
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
-- 登录按手机号等值查，唯一索引一份成本同时满足查询和约束两件事。

-- 2. staff 运维人员
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
-- uk_staff_username 让重名在 DB 层失败，GlobalExceptionHandler 的 DuplicateKeyException 处理直接复用。
-- station_id 用 0 而不是 NULL 表示「平台级」：`where station_id = null` 永远不匹配，要写 IS NULL。
-- idx_staff_station_status 服务「某站点的在用人员」这类列表。

-- 3. vehicle 车辆
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
-- uk(member_id, plate_no) 一次解决约束和「查我的车辆」（最左前缀），所以不再单独建 member_id 索引。
-- idx_vehicle_plate 服务反向场景：按车牌反查车主。

-- 4. category 分类
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
-- 唯一键带上 type：同一个名字既可能是充电套餐分类也可能是增值服务分类，老库的 uk(name) 会误伤。
-- idx(type, status, sort) 对应 where type=? and status=1 order by sort，过滤和排序都走索引。

-- 5. station 充电站
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
-- 按距离排序用「idx_station_lat_lng 上的包围盒 SQL + Java 侧 Haversine」，不要在 ORDER BY 里写
-- 三角函数表达式（那必然全表扫描）；站点规模再大再上 Redis GEO。

-- 6. charger 充电桩
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
-- idx_charger_heartbeat 服务离线巡检（where last_heartbeat_time < now() - interval 5 minute）：
-- 范围条件必须放在最左列，所以这条索引第一列就是时间。

-- 7. connector 充电枪
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
-- uk_connector_current_order 是「枪口互斥」的最后一道防线：MySQL 的唯一索引不约束 NULL，
-- 所以任意多把空闲枪（current_order_id IS NULL）可以并存，但 1 号枪一旦写入 current_order_id=1001，
-- 第二个想写 1001 的请求在数据库层必然失败 —— 把「两个用户同时启动同一把枪」从靠代码写对
-- 变成数据库不允许错。
--
-- 不用 synchronized：单实例有效、多实例失效。CacheClient.tryLock 只用于前置削峰，
-- 正确性由这条唯一索引和条件更新保证。

-- 8. tariff_template 计费模板头
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
-- idx(region, status) 服务「某地区有哪些可用模板」，运维新建站点时的选择列表。

-- 9. tariff_rule 计费规则（尖峰平谷时段）
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
-- uk(template_id, start_time) 保证同一模板内某个起始时刻只属于一个时段。不加 end_time：
-- 那样 [08:00,12:00) 和 [08:00,10:00) 就能并存了，唯一键会失去意义；无重叠由 TariffValidator 校验。
--
-- 两条约定：时段是半开区间 [start_time, end_time)，一天用 24:00:00 收尾（若用 00:00:00 表示结束，
-- 23:00-24:00 会变成零长度段）；一个模板必须完整、无重叠地覆盖 24 小时 —— 不校验的话某个时刻
-- 没有对应时段，那一段的能量就没有价格，表现是「订单金额莫名其妙少了」。

-- 10. station_tariff 站点与模板绑定（带生效期）
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
-- 本表只负责「此刻该用哪个模板」的决策，真正计费用的是订单上冻结的 tariff_template_id。
-- 电价会被电网调整，若在结算时才去查「现在这个站点用哪个价」，已经在充的订单会按新价结算，
-- 用户看到的金额和启动时预估的不一样，而且没法解释。所以模板 id 在下单那一刻就冻结进 charging_order。
-- idx_station_tariff_lookup 就是「某站点此刻生效的模板」这条查询的形状。

-- 11. charge_package 充电套餐（扁平 SKU）
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
-- 充电套餐是「花 100 元买 150 度电」这种单一权益，没有子项，所以不用老库 setmeal + setmeal_dish
-- 那套嵌套结构（硬套会造出一张永远只有一行子记录的关系表）。代价是 energy_kwh / duration_minutes /
-- times 这几个「按 package_type 二选一」的列只能靠应用层校验，MySQL 没有条件必填的约束能力。
-- station_id=0 而不是 NULL：唯一索引不约束 NULL，留 NULL 的话「全平台套餐」可以任意重名，约束就废了。
-- idx_pkg_category_status_sort 对应车主端「某分类下的在售套餐」列表。

-- 12. member_package 车主持有权益（SKU 的实例）
CREATE TABLE IF NOT EXISTS `member_package`
(
    `id`                bigint         NOT NULL AUTO_INCREMENT,
    `member_id`         bigint         NOT NULL COMMENT '持有人',
    `package_id`        bigint         NOT NULL COMMENT 'SKU：charge_package.id',
    `buy_order_id`      bigint          DEFAULT NULL COMMENT '恒为 NULL：直购模型不建购买单，此列是预留（见 MemberPackage.buyOrderId）',
    `package_type`      tinyint        NOT NULL COMMENT '购买时冻结的 SKU 类型：1电量包 2时长包 3服务费折扣包 4次卡',
    `package_name`      varchar(64)    NOT NULL COMMENT '购买时的 SKU 名称快照',
    `total_kwh`         decimal(10, 2)  DEFAULT NULL COMMENT 'type=1 总电量',
    `remaining_kwh`     decimal(10, 2)  DEFAULT NULL COMMENT 'type=1 剩余电量；结算只动这一列',
    `total_minutes`     int             DEFAULT NULL COMMENT 'type=2 总时长（分钟）',
    `remaining_minutes` int             DEFAULT NULL COMMENT 'type=2 剩余时长（分钟）',
    `total_times`       int             DEFAULT NULL COMMENT 'type=4 总次数',
    `remaining_times`   int             DEFAULT NULL COMMENT 'type=4 剩余次数',
    `service_discount`  decimal(5, 4)   DEFAULT NULL COMMENT 'type=3 服务费折扣快照，0.8000=八折',
    `effective_from`    datetime(3)    NOT NULL COMMENT '生效时间（含）',
    `expire_time`       datetime(3)    NOT NULL COMMENT '过期时间（不含）；永久用 9999-12-31 哨兵，不用 NULL',
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

-- charge_package 是 SKU（可售的东西），本表是持有实例（某个车主买到的那一份）。
-- 四种 package_type 里有三种的属性 SKU 表根本表达不了：「还剩多少分钟 / 几次」「这个车主
-- 此刻有没有生效中的折扣」都是持有属性。SKU 只能回答「花 100 元能买什么」。
--
-- package_type / package_name / total_* 这些列是快照，和 charging_order 冻结 tariff_template_id
-- 是同一条铁律：已经卖出去的东西，条款不能被售后修改。charge_package 是可编辑的，
-- 若核销时去查 SKU，管理员把 150 度改成 100 度的那一刻，所有已经付了 150 度钱的用户会静默地
-- 少掉 50 度，而那时已经无从追溯是哪次改动引入的。
-- 只快照计费要用到的和用户会看到的（名称、总/剩余量、折扣、有效期），描述图片这类纯展示字段不快照。

-- uk_mp_locked_order 是「唯一索引不约束 NULL」的第四次利用（前三次在 connector.current_order_id、
-- charging_order_event.idempotent_key、wallet_transaction.(order_id, type)）：
-- 任意多份未锁定的权益并存，一旦把某份写成 locked_order_id = 1001，第二笔订单想锁同一份权益
-- 在数据库层必然失败。反过来也约束了「一笔订单最多锁定一个消耗型权益实例」。
--
-- 折扣包（type=3）永远不写这个字段，恒为 NULL：它不是消耗品，是「有效期内自动适用」的修饰符，
-- 结算时问「这个车主此刻有没有生效的折扣包」，有就打折、不扣任何量。给它加锁没有意义，
-- 反而会因为「一笔订单同时用了电量包和折扣包」而撞上唯一键。
--
-- 结算幂等靠把判断和修改放进同一条 UPDATE：
--     UPDATE member_package
--        SET remaining_kwh = remaining_kwh - #{kwh}, locked_order_id = NULL,
--            status = CASE WHEN remaining_kwh - #{kwh} <= 0 THEN 2 ELSE status END
--      WHERE id = #{id} AND locked_order_id = #{orderId};
-- 第一次执行后 locked_order_id 变成 NULL，第二次必然匹配不上。不要写成「先 SELECT 判断
-- locked_order_id 是不是这笔订单，再 UPDATE」—— 那是 TOCTOU，并发下两个线程都能通过检查、
-- 然后各扣一次。取消订单走另一个分支：只清 locked_order_id，remaining_* 不变。
--
-- expire_time 用 9999-12-31 哨兵而不是 NULL：查询是 where expire_time > now()，而 NULL 参与
-- 任何比较都不成立，永久有效的权益会被漏掉，表现是「明明买了永久套餐，结算时说没有」。
-- 这一列故意没有 DEFAULT：忘了填应该是响亮的报错，而不是静默地拿到一个「永久有效」。
--
-- status 的 2/3 是推导出来的状态（剩余量归零 / 过时间点），由定时任务或读路径惰性刷新。
-- 判断「这个包现在能不能用」时不要只看 status，还要看 expire_time 和剩余量。
--
-- idx(member_id, package_type, status, expire_time) 的前两列顺序不能反：会员包是按人攒起来的，
-- 一个人的包只有几行，而 package_type 只有 4 个值，放最左等于扫全表四分之一的行走索引。
-- 不建 package_id 单列索引：它是报表维度，现在建的话是一条每次购买都要维护、却没有查询会选中的索引。

-- 13. charging_order 充电订单（核心表）
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
    `member_package_id` bigint         DEFAULT NULL COMMENT '冻结的持有权益：member_package.id（不是 SKU）；NULL=余额直充',
    `tariff_template_id` bigint       NOT NULL COMMENT '冻结的计费模板：见 station_tariff 的注释',
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
-- idx(member_id, create_time)：车主端「我的订单」按时间倒序，联合索引直接给出有序结果，不用 filesort。
-- idx(member_id, status)：「我的进行中订单」（status in (1,2,3)），不能被上面那条替代。
-- idx(connector_id, status) 是幂等入口：设备重复上报「结束」时先问「这把枪上还有 status ∈ {2,3}
--   的订单吗」。顺序不能反 —— 枪的选择性远高于状态。
-- idx(status, create_time) 有三个消费者，都靠「状态在前」：
--   1) 定时任务扫待启动超时：where status=1 and create_time < now() - 15min
--   2) 站点删除守卫 where status in (1,2,3,4,6,9) and station_id=?（ChargingOrderMapper.countByStation）
--   3) 桩删除守卫，同一条件换成 charger_id=?（countByCharger）
--   写成 (create_time, status)、或让 idx(station_id, create_time) 来服务后两条，都会退化成
--   「扫完这个站的全部历史订单再逐个过滤状态」。
-- idx(station_id, create_time) 服务的是站点维度的报表，不是上面那两条删除守卫 ——
--   守卫只要一个 COUNT，走它会把整站历史读一遍。
-- 不建 member_id 单列索引：被 idx_order_member_create 的最左前缀覆盖。
--
-- plate_no / tariff_template_id / member_package_id 都是冻结的快照：车牌会被改，订单必须保留
--   当时的值；模板冻结的理由见 station_tariff；member_package_id 指向 member_package.id
--   （持有实例）而不是 charge_package.id —— 同一个车主可能买过两份同款权益，写 SKU 就丢掉了
--   「消耗的是哪一份」，结算时不知道该扣哪一行的 remaining_kwh。要 SKU 维度的报表就从
--   member_package.package_id join 过去，不要在这里冗余一份。
--
-- 金额列可空是刻意的：0 和「还没算」是两件事。结算任务扫 status=3 的订单时若默认 0，
--   就分不清「电量确实是 0」和「还没结算」。

-- 14. charging_segment 计费分段
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
-- uk(order_id, start_time) 是结算幂等的核心：结算算法重跑时重复插入相同分段会撞唯一键，
-- 而不是把金额翻倍。对比「先 delete 再 insert」：后者在重试和并发下会丢数据，
-- 唯一键是数据库层面的保证，不依赖任何代码时序。
-- 前提是拆分算法必须跳过零长度分段（订单起止时间恰好落在时段边界上会产生 [10:00,10:00)），
-- 否则会撞上唯一键 —— 见 BillingEngine。

-- 15. charging_order_event 订单状态流转事件
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
-- 「唯一索引不约束 NULL」第三次被利用：非设备来源的事件（用户端取消、定时任务结算）
-- idempotent_key 为 NULL，不受唯一约束，只有设备上报的事件才参与去重。
-- 排查「这笔订单为什么金额不对」时，这张表是唯一能还原现场的东西：2→3 是设备上报的、
-- 3→4 是结算任务写的、4→4 是重复事件被丢的。

-- 16. charging_telemetry 遥测记录（数据量最大，P4 分表对象）
CREATE TABLE IF NOT EXISTS `charging_telemetry`
(
    `id`           bigint         NOT NULL AUTO_INCREMENT,
    `order_id`     bigint          DEFAULT NULL COMMENT '空闲状态上报为 NULL',
    `connector_id` bigint         NOT NULL,
    `charger_id`   bigint         NOT NULL,
    `station_id`   bigint         NOT NULL,
    `report_time`  datetime(3)    NOT NULL COMMENT '设备上报时间（设备时钟）',
    `voltage`      decimal(8, 2)   DEFAULT NULL COMMENT '电压 V',
    `current_a`    decimal(8, 2)   DEFAULT NULL COMMENT '电流 A（不叫 current：保留字风险）',
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
-- 写多读少、只增不改的表，索引要克制：每多一个索引，每次设备上报就多一次 B+ 树维护，
-- 而这是每秒都在发生的事。三条索引对应同一个查询模式「按时间范围取某个对象的曲线」，
-- idx(report_time) 单独存在是为了 P4 的归档清理和分区裁剪。
-- P4 分表的前提是每条查询都必须带 report_time 范围，否则会扫全部分片 —— 这条约束现在就要遵守。
-- 写入侧配 rewriteBatchedStatements=true + 批量 insert（每 100 条或每 1 秒刷一次）。

-- 17. wallet 钱包
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
-- balance（可用，冻结时就已经扣掉）与 frozen_amount 分两列，是「预冻结」模式的关键：
-- 冻结不能让可用余额减少（那就变成直接扣款了），界面上要同时看到「可用 / 冻结」两行。
-- 订单上的 charging_order.frozen_amount 是这一笔占用的金额，钱包这一列是未结算订单的合计。
--
-- 钱包相关的更新一律用条件更新，严禁「先 select 判断再 update」（TOCTOU：检查与使用之间
-- 存在时间窗口，并发下两个线程都能通过检查，然后各扣一次）：
--     UPDATE wallet SET balance = balance - 80, frozen_amount = frozen_amount + 80
--      WHERE member_id = 1 AND balance >= 80;   -- 影响行数为 0 就是余额不足

-- 18. wallet_transaction 钱包流水
CREATE TABLE IF NOT EXISTS `wallet_transaction`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT,
    `transaction_no` varchar(32)    NOT NULL,
    `member_id`      bigint         NOT NULL,
    `order_id`       bigint          DEFAULT NULL COMMENT '充值和套餐购买为 NULL（唯一索引不约束 NULL，见 uk_txn_order_type）',
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
-- uk(order_id, type) 是资金侧的幂等闸门：支付回调、结算重试、定时补偿三条路径都会尝试扣款，
-- 这条唯一索引让「扣两次钱」在数据库层不可能发生，不依赖那三条路径的代码写得对不对。
--
-- order_id 为 NULL 的流水完全不参与约束（唯一索引不约束 NULL），目前是充值（type=1）和
-- 套餐购买（type=6）—— 后者不是「正合需要」而是代价：直购模型没有购买单，所以那条路没有
-- 数据库级的幂等保证。受保护的只有 FREEZE / UNFREEZE / DEDUCT / REFUND 四种。
--
-- 存 before/after 快照而不是只存变动额：对账时不用重放全部流水就能验证余额正确性。

-- 19. alarm 设备告警
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
-- 三条索引精确对应三类查询：站点值班看板、单桩故障史、平台级「严重且未处理」。
-- 都带了 occur_time 或 status：纯按 status 建索引选择性太低（未处理告警可能占绝大多数）。
-- 重复告警去重放在应用层（同桩同码 5 分钟内只报一次），不做「把时间截断到分钟」的唯一键——
-- 那种唯一键换一个去重窗口就要改表结构，而且在边界上（59 秒和 61 秒）仍然会重复。
-- 充电中发生故障时回填 alarm.order_id 并强制结束订单（stop_reason=4）。
