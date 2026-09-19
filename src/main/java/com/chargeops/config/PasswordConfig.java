package com.chargeops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密器
 *
 * <p>把 {@link BCryptPasswordEncoder} 交给容器管理，需要加密或校验密码的地方注入即可。
 *
 * <h2>★ 为什么只引 {@code spring-security-crypto} 而不是整个 Security starter</h2>
 *
 * <p>pom 里那一行依赖是：
 * <pre>
 *   &lt;dependency&gt;
 *       &lt;groupId&gt;org.springframework.security&lt;/groupId&gt;
 *       &lt;artifactId&gt;spring-security-crypto&lt;/artifactId&gt;
 *   &lt;/dependency&gt;
 * </pre>
 * 它<b>没有版本号</b>——由 Spring Boot 的 BOM 管理（Boot 4.1.1 通过
 * {@code spring-security-bom:7.1.1} 传递管理这个坐标）。
 *
 * <p>如果换成 {@code spring-boot-starter-security}，会连带引入
 * 一整套过滤器链，效果是<b>所有接口突然都要求登录</b>：
 * 默认配置会生成一个随机密码打印在启动日志里，任何请求都先跳转到
 * {@code /login} 页面。对纯后端 + 自研 JWT 的项目来说，
 * 这套东西既用不上又必须先关掉（写一个 {@code SecurityFilterChain} 全放行），
 * 多一层和本项目鉴权模型重复的机制。
 *
 * <p><b>本项目要的只是「一个 BCrypt 实现」，不是「一套安全框架」。</b>
 * 引一个工具类库和引一个框架，是两回事。
 *
 * <h2>BCrypt 的几个使用要点</h2>
 *
 * <p><b>1. 密文必须存 {@code varchar(100)} 以上。</b>
 * BCrypt 的密文固定 60 个字符（形如 {@code $2a$10$...}），
 * 老库 DDL 里写的是 {@code varchar(100)}，是够的。
 * 有人按「密码长度」拍脑袋写 {@code varchar(20)}，结果每次插入都被截断，
 * 表现为「注册成功但永远登录不上」。
 *
 * <p><b>2. 校验用 {@code matches(rawPassword, encodedPassword)}，参数顺序别写反。</b>
 * 反了不会抛异常，只会一直返回 false。
 * 而且注意第一个参数是<b>明文</b>——BCrypt 不是「解密后比较」，
 * 它用密文里存着的 salt 重新算一遍哈希再比。所以它是单向的，
 * 「忘记密码」功能只能重置不能找回，这一点面试验证时经常被追问。
 *
 * <p><b>3. 强度参数不要随手调大。</b>
 * 默认强度 10（2^10 = 1024 轮）。每 +1 计算耗时翻倍。
 * 调到 14 会让每次登录多花约一秒 —— 而登录接口<b>没有做限流</b>，
 * 等于给了攻击者一个成本极低的 CPU 打满手段。
 * 强度该不该调大，取决于「密码泄露后爆破的成本」，而不是「数字看起来够不够安全」。
 *
 * <p><b>4. 它<b>不能</b>用来加密手机号这类需要还原的数据。</b>
 * 车主表里的手机号是登录账号、需要能查能显示，用 BCrypt 存就没法用了。
 * 那类数据要的是「可逆加密 + 密钥管理」或者「脱敏存储」，不是哈希。
 */
@Configuration
public class PasswordConfig {

    /**
     * BCrypt 加密器
     *
     * <p>用默认强度 10，理由见类注释第 3 点。
     * 要调整强度就 {@code new BCryptPasswordEncoder(12)}，
     * 但请连同「登录接口限流」一起考虑。
     *
     * <p>BCryptPasswordEncoder 是<b>线程安全</b>的（内部没有可变状态，
     * 每次 encode 都自己生成随机 salt），所以做成单例 Bean 完全没问题 ——
     * 不需要担心「两个用户同时登录会不会互相干扰 salt」。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
