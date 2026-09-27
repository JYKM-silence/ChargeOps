package com.chargeops.exception;

/**
 * 账号被禁用
 *
 * <p> 用状态而不是直接删账号：删了记录，所有指向他的 {@code create_user} /
 * {@code update_user} 就变成孤儿，查「这单是谁操作的」会查不到人。
 */
public class AccountLockedException extends BaseException {

    public AccountLockedException() {
    }

    public AccountLockedException(String msg) {
        super(msg);
    }
}
