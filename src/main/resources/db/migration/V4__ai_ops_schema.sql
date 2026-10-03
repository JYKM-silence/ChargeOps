-- AI 运维助手的两张表。
--
-- 它们和 AI 没有强耦合：告警是设备上报的事实，工单是运维的处理记录，AI 只是消费和
-- 生成它们的一个入口。所以 AI 不启用时这两张表照常工作，人工也能建单。

-- 11. charger_alarm 充电桩告警
CREATE TABLE IF NOT EXISTS `charger_alarm`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT,
    `charger_id`   bigint       NOT NULL,
    `connector_id` bigint        DEFAULT NULL COMMENT '枪级告警才有值，桩级告警为 NULL',
    `alarm_code`   varchar(32)  NOT NULL COMMENT '故障码，如 E-021。说明文档按这个码查',
    `alarm_level`  tinyint      NOT NULL DEFAULT 2 COMMENT '1提示 2一般 3严重',
    `content`      varchar(255) NOT NULL COMMENT '设备上报的原文',
    `status`       tinyint      NOT NULL DEFAULT 0 COMMENT '0未恢复 1已恢复',
    `alarm_time`   datetime(3)  NOT NULL,
    `recover_time` datetime(3)   DEFAULT NULL,
    `create_time`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time`  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_alarm_charger_time` (`charger_id`, `alarm_time`),
    KEY `idx_alarm_code` (`alarm_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='充电桩告警';
-- idx_alarm_charger_time 服务「查某台桩最近的告警」：等值列在前、排序列在后，
-- ORDER BY alarm_time DESC LIMIT n 能直接走索引，不用把该桩的全部告警捞出来再排序。

-- 12. maintenance_ticket 运维工单
CREATE TABLE IF NOT EXISTS `maintenance_ticket`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT,
    `ticket_no`   varchar(32)  NOT NULL COMMENT '工单号，对外展示',
    `charger_id`  bigint       NOT NULL,
    `station_id`  bigint       NOT NULL COMMENT '冗余站点 id：工单列表几乎都按站点筛',
    `alarm_id`    bigint        DEFAULT NULL COMMENT '由哪条告警生成；人工建单为 NULL',
    `title`       varchar(128) NOT NULL,
    `content`     text COMMENT '处理建议。AI 生成时是模型的输出，人工建单时是描述',
    `source`      tinyint      NOT NULL DEFAULT 1 COMMENT '1人工 2AI助手',
    `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已完成',
    `create_time` datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `create_user` bigint        DEFAULT NULL,
    `update_user` bigint        DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    KEY `idx_ticket_charger_status` (`charger_id`, `status`),
    KEY `idx_ticket_station_time` (`station_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='运维工单';
