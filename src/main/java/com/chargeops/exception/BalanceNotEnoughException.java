package com.chargeops.exception;

/**
 * 余额不足
 *
 * <p> 抛在「冻结余额的条件更新影响行数为 0」之后。判断条件必须写在 SQL 的 WHERE 里
 * 而不是 Java 里：先 select 出来比一下再 update 的话，两个并发请求会都读到够、
 * 各扣一次，余额变成负数而整个过程没有任何异常。
 */
public class BalanceNotEnoughException extends BaseException {

    public BalanceNotEnoughException() {
    }

    public BalanceNotEnoughException(String msg) {
        super(msg);
    }
}
