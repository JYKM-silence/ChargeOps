package com.chargeops.exception;

/**
 * 登录失败
 *
 * <p>用在「查得到账号、密码也对，但整体就是不放行」的情况，
 * 例如第三方登录换取用户信息失败、账号状态异常等。单纯账号不存在或密码错误
 * 请抛更具体的异常，否则日志里全是「登录失败」，排查时分不清是哪一类。
 */
public class LoginFailedException extends BaseException {

    public LoginFailedException(String msg) {
        super(msg);
    }
}
