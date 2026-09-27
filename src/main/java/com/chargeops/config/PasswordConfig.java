package com.chargeops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密器
 *
 * <p> 依赖只引 {@code spring-security-crypto}，不要引 Security starter：那会带来一整套过滤器链，
 * 所有接口突然都要求登录。{@code matches(raw, encoded)} 的参数顺序写反不抛异常，只会一直返回 false。
 */
@Configuration
public class PasswordConfig {

    /** 默认强度 10。线程安全（无可变状态，每次 encode 自己生成 salt），做成单例没问题 */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
