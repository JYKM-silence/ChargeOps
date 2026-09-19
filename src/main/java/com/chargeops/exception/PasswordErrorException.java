package com.chargeops.exception;

/**
 * 密码错误
 *
 * <p>注意「密码错误」和「账号不存在」分开抛的取舍，见
 * {@link AccountNotFoundException} 的注释 —— 对外可能应该合并成一句话。
 */
public class PasswordErrorException extends BaseException {

    public PasswordErrorException() {
    }

    public PasswordErrorException(String msg) {
        super(msg);
    }
}
