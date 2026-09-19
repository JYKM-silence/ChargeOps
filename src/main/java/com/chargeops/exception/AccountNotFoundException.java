package com.chargeops.exception;

/**
 * 账号不存在
 *
 * <p><b>安全提示：这个异常对外的文案和 {@link PasswordErrorException} 不应该被用来
 * 判断「用户名是否注册过」。</b>
 * 现在的实现是分开的两个异常、两句话，调用方（或攻击者）可以通过
 * 「提示账号不存在」和「提示密码错误」的差别，枚举出哪些用户名是有效的。
 * 对运维后台这种内部系统风险可控，但同一个套路迁到车主端就需要注意了 ——
 * 手机号是不是注册过属于个人信息。
 *
 * <p>正确做法是登录失败一律返回同一句话（「账号或密码错误」），
 * 只在服务端日志里区分真实原因。这里保留两个异常类型是为了日志可读，
 * 不代表对外也该区分。
 */
public class AccountNotFoundException extends BaseException {

    public AccountNotFoundException() {
    }

    public AccountNotFoundException(String msg) {
        super(msg);
    }
}
