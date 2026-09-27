package com.chargeops.exception;

/**
 * 账号不存在
 *
 * <p><b>安全提示：不能靠这个异常对外的文案判断「用户名是否注册过」。</b>
 * 现在账号不存在和密码错误分开抛、两句话，调用方（或攻击者）可以通过提示的差别
 * 枚举出哪些用户名是有效的。对运维后台这种内部系统风险可控，但同一个套路迁到
 * 车主端就需要注意了 —— 手机号是不是注册过属于个人信息。
 *
 * <p>正确做法是登录失败一律返回同一句话（「账号或密码错误」），只在服务端日志里
 * 区分真实原因。保留两个异常类型是为了日志可读，不代表对外也该区分。
 */
public class AccountNotFoundException extends BaseException {

    public AccountNotFoundException() {
    }

    public AccountNotFoundException(String msg) {
        super(msg);
    }
}
