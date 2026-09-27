package com.chargeops.constant;

/**
 * 密码相关常量
 */
public class PasswordConstant {

    /**
     * 新建运维人员时的初始密码
     *
     * <p>注意这是「初始」密码而不是「默认」密码，两者安全含义不同：
     * 初始密码只用于账号创建，且应当强制首次登录修改；默认密码则意味着「不说话就一直用它」。
     * 本项目目前没做强制改密，这个常量在设计上是一笔待还的技术债。
     */
    public static final String DEFAULT_PASSWORD = "123456";
}
