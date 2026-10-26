package com.chargeops.exception;

/** 登录请求触发 IP 限流或账号失败次数冷却。 */
public class LoginRateLimitedException extends BaseException {

    public LoginRateLimitedException(String message) {
        super(message);
    }
}
