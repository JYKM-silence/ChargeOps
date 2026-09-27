package com.chargeops.exception;

/**
 * 登录失败：查得到账号、密码也对，但整体就是不放行。账号不存在或密码错误请抛更具体的异常，
 * 否则日志里全是「登录失败」，分不清是哪一类。
 */
public class LoginFailedException extends BaseException {

    public LoginFailedException(String msg) {
        super(msg);
    }
}
