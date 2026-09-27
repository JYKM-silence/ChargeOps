package com.chargeops.exception;

/**
 * 账号不存在
 *
 * <p> 对外它和密码错误合并成同一句话（见 {@code MessageConstant.MEMBER_LOGIN_FAILED}）：
 * 保留两个类型是为了服务端日志能区分，不代表对外也该区分 —— 提示的差别可以用来枚举用户名。
 */
public class AccountNotFoundException extends BaseException {

    public AccountNotFoundException() {
    }

    public AccountNotFoundException(String msg) {
        super(msg);
    }
}
