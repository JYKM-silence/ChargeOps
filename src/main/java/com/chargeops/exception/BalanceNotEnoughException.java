package com.chargeops.exception;

/**
 * 余额不足
 *
 * <p>抛出的时机和 {@link ConnectorOccupiedException} 完全对称：执行冻结余额的条件更新
 * （{@code UPDATE wallet SET balance = balance - #{amt}, frozen_amount = frozen_amount + #{amt}
 * WHERE member_id = #{id} AND balance >= #{amt}}）之后，
 * 发现影响行数为 0，就抛这个异常。
 *
 * <p><b>为什么判断条件写在 SQL 的 WHERE 里，而不是 Java 里：</b>
 * 写成「先 select balance 出来在 Java 里比一下，再 update」的话，两个并发请求
 * 可能都读到 100 元、都判断「够」、都各扣 80 —— 结果余额变成 -60，
 * 而且这个过程没有任何异常。放进 WHERE 之后，数据库在行锁的保护下逐行判断，
 * 余额不够的行压根不会被更新。
 *
 * <p><b>为什么只冻结、不直接扣：</b>充电是个持续几十分钟的过程，启动时还不知道
 * 最终要花多少钱（取决于充了多少度、跨了哪些电价时段）。所以启动时按预估值冻结一笔，
 * 结束时按实际金额结算，多冻结的部分原路退回可用余额（预授权，和住酒店刷预授权同理）。
 */
public class BalanceNotEnoughException extends BaseException {

    public BalanceNotEnoughException() {
    }

    public BalanceNotEnoughException(String msg) {
        super(msg);
    }
}
