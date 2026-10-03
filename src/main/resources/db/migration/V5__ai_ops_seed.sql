-- AI 运维助手的种子数据：告警 + 历史工单。
--
-- 时间全部写成 NOW() - INTERVAL n，不用固定日期：这两个表的价值就在于「最近」，
-- 写死日期的话过几天演示时查「最近告警」会一条都查不到，而种子数据本身没问题。
--
-- 历史工单是给「检索相似故障案例」用的。没有它们，模型只能凭空给建议；
-- 有了它们，模型能说出「上次这台桩同样的码是天线接头进水」。

INSERT IGNORE INTO `charger_alarm` (`id`, `charger_id`, `connector_id`, `alarm_code`, `alarm_level`, `content`,
                                    `status`, `alarm_time`, `recover_time`)
VALUES
    -- 当前未恢复的那条：留着它，问答演示时才有东西可查
    (1, 1, NULL, 'E-021', 3, '设备与后台通信中断，连续 3 次心跳无应答', 0, NOW() - INTERVAL 2 HOUR, NULL),

    (2, 1, NULL, 'E-073', 2, '功率模块散热器温度 87℃，超过 85℃ 阈值', 1, NOW() - INTERVAL 3 DAY,
     NOW() - INTERVAL 3 DAY + INTERVAL 4 HOUR),
    (3, 1, 2, 'E-030', 1, 'B 枪连接确认信号异常，启动充电失败', 1, NOW() - INTERVAL 1 DAY,
     NOW() - INTERVAL 1 DAY + INTERVAL 30 MINUTE),
    (4, 1, 1, 'E-060', 3, '直流输出电流超过设定值 110%，持续 3 秒', 1, NOW() - INTERVAL 10 DAY,
     NOW() - INTERVAL 10 DAY + INTERVAL 6 HOUR);

INSERT IGNORE INTO `maintenance_ticket` (`id`, `ticket_no`, `charger_id`, `station_id`, `alarm_id`, `title`,
                                         `content`, `source`, `status`, `create_time`, `update_time`)
VALUES
    (1, 'MT20260912001', 1, 1, 4, 'A区-01 直流输出过流',
     '现场检查功率模块均流正常，输出侧无短路痕迹。查阅后台记录发现同一时段该枪有多台车反复插拔，'
     '判断为车辆请求电流突变导致的保护动作。重启后试充正常，观察一周未复现。',
     1, 2, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY + INTERVAL 6 HOUR),

    (2, 'MT20260920001', 1, 1, NULL, 'A区-01 天线接头进水导致离线',
     '连续两天出现通信中断，每次重启后几小时复发。现场检查发现天线馈线接头密封圈老化，'
     '雨天进水导致驻波比升高。更换接头并重新做防水处理后，连续观察一周未再离线。'
     '同期该站另外两台桩有相同隐患，一并做了预防性更换。',
     1, 2, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 8 DAY + INTERVAL 5 HOUR),

    (3, 'MT20260929001', 1, 1, 2, 'A区-01 功率模块过热',
     '进风口滤网被柳絮堵死，散热片积尘严重，风扇转速正常但风量不足。'
     '清理滤网和散热片后温度回落到 62℃。已把该站列入柳絮季节每周清理名单。',
     1, 2, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY + INTERVAL 4 HOUR);
