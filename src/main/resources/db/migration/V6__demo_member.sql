-- 车主端演示账号：13900000001 / 123456 与 13900000002 / 123456
--
-- 为什么补这两行：H5 登录页把 13900000001 / 123456 写成「演示账号」并预填
-- （web/packages/h5/src/views/LoginView.vue），README 的「演示账号」表也列了这两个号。
-- 但 V2 里唯一的车主是 13800000001，而且 password 是 NULL —— 它无法用密码登录。
-- 于是一个全新库上点「演示账号」必然得到「手机号或密码错误」，看起来像登录接口坏了。
--
-- ★ 必须给显式主键，而且必须 < 2e12（测试的最低下界）。★
--   测试直接打开发库，收尾是 `DELETE FROM member WHERE id >= 2e12`（WalletServiceImplTest）
--   以及 4e12 / 5e12 的同类语句，都是**一个方向开口**的。当前开发库的 AUTO_INCREMENT
--   已经因为测试插入显式 id 涨到 5e12 了，此时不写 id 插进来的演示账号会落进清理区间，
--   下一次 `mvn test` 就把它删掉 —— 表现就是「昨天还能登录，今天又不存在了」。
--   2 / 3 是 V2 之外最小的两个自然主键，不会撞上任何测试区间。
--
-- 密文直接复用 V2 里 admin 那条：同一个明文 123456，BCrypt 的盐和代价因子都存在
-- 密文里，不需要为这两个账号重新算一次。格式 $2a$10$，和 BCryptPasswordEncoder 匹配。
-- 用 INSERT IGNORE 和 V2 保持一致：主键 / phone 有唯一性约束，重复执行只会被忽略。

INSERT IGNORE INTO `member` (`id`, `phone`, `password`, `nickname`, `status`)
VALUES (2, '13900000001', '$2a$10$.yHXk81HCQDHfjXvu6M5U.GhLiBhvefyOblJmfNmRfQjf32A/eCUO', '演示车主', 1),
       (3, '13900000002', '$2a$10$.yHXk81HCQDHfjXvu6M5U.GhLiBhvefyOblJmfNmRfQjf32A/eCUO', '演示车主B', 1);

-- 给一份余额：没有它，登录后的第一步「充值 → 建单」就卡住，演示账号等于半个。
-- 用 INSERT ... SELECT 按 phone 取 id，不写死主键 —— 和生产库里成员 id 的分布解耦。
-- INSERT IGNORE：uk_wallet_member 保证重复执行不会多一行。
INSERT IGNORE INTO `wallet` (`member_id`, `balance`, `total_recharge`)
SELECT `id`, 500.00, 500.00 FROM `member`
WHERE `phone` IN ('13900000001', '13900000002');
