-- 权益购买金额快照：V1 的 member_package 不存价格，退款时「当时实际付了多少」全库无据可查
-- （PACKAGE_PURCHASE 流水的 order_id 和 buy_order_id 都是 NULL，两者之间没有 join 键）。
-- 不回填 charge_package.price —— 那是今天的售价，管理员改价后按老价买过的用户会被静默地按新价退款。
-- NULL=本列上线前买的（退款转人工），0.00=赠送套餐，两者下一步动作相反，所以刻意不写 DEFAULT。

ALTER TABLE `member_package`
    ADD COLUMN `pay_amount` decimal(12, 2) DEFAULT NULL
        COMMENT '购买时实付金额快照；NULL=本列上线前买的（无法退款），0.00=赠送套餐';

-- 「过期权益落库」任务没有 member_id，现有 idx_mp_member_status_expire 最左列用不上、只能扫全索引。

CREATE INDEX `idx_mp_status_expire` ON `member_package` (`status`, `expire_time`);

-- 顺带补上 wallet_transaction.type 的第 8 个取值，避免 V1 里那句列注释成为错话。

ALTER TABLE `wallet_transaction`
    MODIFY COLUMN `type` tinyint NOT NULL
        COMMENT '1充值 2冻结 3解冻 4扣款 5退款 6套餐购买 7管理员调账 8套餐退款';
