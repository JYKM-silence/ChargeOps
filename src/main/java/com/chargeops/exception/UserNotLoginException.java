package com.chargeops.exception;

/**
 * 未登录
 *
 * <p> 拦截器已经拦掉了所有未登录请求（401），它真正有用的场景是放行路径上也要知道当前用户
 * （允许匿名访问，但登录了要返回个性化数据）。如果发现自己到处在抛它，先怀疑拦截器路径配错了。
 */
public class UserNotLoginException extends BaseException {

    public UserNotLoginException() {
    }

    public UserNotLoginException(String msg) {
        super(msg);
    }
}
