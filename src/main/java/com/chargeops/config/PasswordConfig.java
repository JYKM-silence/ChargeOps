package com.chargeops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密器
 *
 * <p>把 {@link BCryptPasswordEncoder} 交给容器管理，需要加密或校验密码的地方注入即可。
 *
 * <h2>★ 依赖只引 {@code spring-security-crypto}，不要引 Security starter</h2>
 *
 * <p>换成 {@code spring-boot-starter-security} 会连带引入一整套过滤器链，
 * 效果是<b>所有接口突然都要求登录</b>：默认配置生成一个随机密码打印在启动日志里，
 * 任何请求先跳 {@code /login}。对纯后端 + 自研 JWT 的项目，这套东西既用不上，
 * 还必须写一个全放行的 {@code SecurityFilterChain} 才能关掉，
 * 等于多一层和本项目鉴权模型重复的机制。本项目要的只是「一个 BCrypt 实现」。
 *
 * <h2>BCrypt 的几个使用要点</h2>
 *
 * <p><b>1. {@code matches(rawPassword, encodedPassword)} 的参数顺序别写反</b>，
 * 反了不抛异常，只会一直返回 false。
 *
 * <p><b>2. 强度参数不要随手调大。</b>默认 10，每 +1 计算耗时翻倍，
 * 调到 14 每次登录多花约一秒 —— 而登录接口<b>没有做限流</b>，
 * 等于给攻击者一个成本极低的 CPU 打满手段。该不该调大，取决于
 * 「密码泄露后爆破的成本」，不是「数字看起来够不够安全」。
 *
 * <p><b>3. 它<b>不能</b>用来加密手机号这类需要还原的数据。</b>
 * 车主表里的手机号是登录账号，要能查能显示，用 BCrypt 存就没法用了 ——
 * 那类数据要的是可逆加密 + 密钥管理，或者脱敏存储。
 */
@Configuration
public class PasswordConfig {

    /**
     * BCrypt 加密器
     *
     * <p>用默认强度 10，理由见类注释第 2 点。要调整强度就
     * {@code new BCryptPasswordEncoder(12)}，但请连同「登录接口限流」一起考虑。
     *
     * <p>它是线程安全的（内部无可变状态，每次 encode 自己生成随机 salt），
     * 做成单例 Bean 没问题。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
