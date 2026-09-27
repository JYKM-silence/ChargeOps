package com.chargeops.exception;

/**
 * 未登录
 *
 * <p>理论上拦截器已经拦掉了所有未登录请求（返回 401），这个异常很少被抛到。
 * 它真正有用的场景是<b>放行的路径里也要知道当前用户</b> —— 例如某个接口允许匿名访问，
 * 但登录了的话要返回个性化数据，这时业务层用 {@code BaseContext.getCurrentId() == null}
 * 判断，需要明确表达「这里是匿名的」而抛这个异常。
 *
 * <p>如果发现自己到处在抛它，先怀疑拦截器路径配错了 ——
 * 正常请求根本不该走到业务层才发现没登录。
 */
public class UserNotLoginException extends BaseException {

    public UserNotLoginException() {
    }

    public UserNotLoginException(String msg) {
        super(msg);
    }
}
