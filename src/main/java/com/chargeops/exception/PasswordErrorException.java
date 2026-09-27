package com.chargeops.exception;

/**
 * 密码错误。和「账号不存在」分开抛的取舍见 {@link AccountNotFoundException}。
 */
public class PasswordErrorException extends BaseException {

    public PasswordErrorException() {
    }

    public PasswordErrorException(String msg) {
        super(msg);
    }
}
