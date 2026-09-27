-- ChargeOps 种子数据。全部用 INSERT IGNORE：被 baseline-on-migrate 跳过的老库上这个迁移会真的
-- 执行一次（V1 被跳过、V2 不跳），那些库里已经有同样的种子行，没有 IGNORE 就会撞主键让应用起不来。
-- 顺带的好处是开发时手工重放这个文件也是安全的。
-- 末尾权益种子里有 NOW() 这类相对时间，种子的绝对时间是「执行的那一刻」，不是写脚本的那一刻。

-- 管理员 admin / 123456
-- 这段是 BCrypt 密文（$2a$10$ 开头，固定 60 字符，不是 MD5），已用
-- BCryptPasswordEncoder.matches("123456", ...) 实测验证过。
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

-- 计费模板：深圳 2026 工商业分时电价（示例值，非真实电价）。8 条规则合计覆盖 24 小时，
-- 每段的类型见下面各行末尾的标注。
--
-- 时段是半开区间 [start, end)，最后一段用 24:00:00 收尾（MySQL 的 TIME 支持到 838:59:59）——
-- 若写成 '00:00:00' 表示结束，23:00-24:00 会变成一个零长度段。
-- 22:00-24:00 和 00:00-08:00 是两行，不要试图用一行 22:00-08:00 表达跨天：TIME 类型不支持，
-- 而且语义混乱；按绝对时间切分时「跨天」天然被处理掉了。
-- 电费和服务费分开存不是冗余：电费是电网收的、服务费是运营商收的，对账时要分别核对，
-- 合成一个单价就再也拆不开。跨多个时段的订单（09:30 充到 11:30）会被拆成
-- 平段[09:30,10:00) + 尖峰[10:00,11:30) 分别计价，见 BillingEngine。
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
       (1, 3, '23:00:00', '24:00:00', 0.7188, 0.3000, 8);  -- 平段（24:00:00 的写法）

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

-- 给测试车主（member 1）挂一份持有权益，供订单链路直接使用。
-- 用 INSERT ... SELECT 而不是 VALUES：package_id 必须是 charge_package 里真实存在的那一行，
-- 而上面那两条套餐是自增 id，写死成 1 只在「全新库」这个前提下成立，所以按名称 + 站作用域定位。
-- 固定 id = 1 是幂等的依据（撞主键 → IGNORE）：本表没有别的天然唯一键，不给固定 id 重跑就多一行。
-- 有效期用 NOW() 算而不是写死日期：这份权益是给开发阶段用的，写死日期的话过几个月再跑建库脚本，
-- 拿到的东西一出生就是过期的，表现是「结算说没可用权益」，而你会先去怀疑结算代码。
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
