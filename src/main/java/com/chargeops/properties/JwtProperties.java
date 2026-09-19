package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置，对应 application.yml 里的 {@code chargeops.jwt.*}
 *
 * <p><b>平台有两套完全独立的 JWT 配置，不要合并：</b>
 * <table border="1">
 *   <tr><th></th><th>管理端</th><th>车主端</th></tr>
 *   <tr><td>身份</td><td>staff.id（运维/管理员）</td><td>member.id（车主）</td></tr>
 *   <tr><td>请求头</td><td>token</td><td>authentication</td></tr>
 *   <tr><td>路径</td><td>/api/admin/**</td><td>/api/member/**</td></tr>
 * </table>
 *
 * <p><b>为什么要两套密钥而不是一套：</b>
 * 一套密钥意味着「管理端的 token 和车主端的 token 用的是同一把钥匙」，
 * 那么只要有一处泄露了密钥（车主端小程序被打包逆向、或者某个环境变量被误打进日志），
 * 攻击者就能自己签发一个 {@code staffId=1} 的 token 直接拿到管理员权限。
 * 两套密钥把这个影响面隔开了：车主端的密钥即使泄露，也只能伪造车主身份。
 *
 * <p>这是「权限隔离不能只靠路径前缀」的一个具体例子 ——
 * 路径前缀是拦不住伪造 token 的，因为拦截器校验的是签名，签名对了它就放行。
 *
 * <p><b>字段上故意不写默认值</b>，理由和 LlmProperties、StorageProperties 一致：
 * 字段上给了默认值的话，生产环境忘配环境变量时会静默用一个假密钥跑起来，
 * 表现是「所有 token 校验都失败」或「能签发但重启后全失效」，
 * 而不是明确地报「配置项没配」。默认值统一写在 yml 的 {@code ${VAR:默认}} 里。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.jwt")
@Data
public class JwtProperties {

    // ==================== 管理端（运维人员） ====================

    /** 管理端签名密钥，UTF-8 编码后必须 ≥ 32 字节 */
    private String adminSecretKey;

    /** 管理端 token 有效期（毫秒） */
    private long adminTtl;

    /** 管理端从哪个请求头取 token */
    private String adminTokenName;

    // ==================== 车主端 ====================

    /** 车主端签名密钥，必须与管理端**不同**，且 ≥ 32 字节 */
    private String memberSecretKey;

    /** 车主端 token 有效期（毫秒） */
    private long memberTtl;

    /**
     * 车主端从哪个请求头取 token
     *
     * <p>老库用的是 {@code authentication}（沿袭某个小程序模板的习惯）。
     * 这个值本身不重要，重要的是它和前端（或 Postman 集合）必须一致 ——
     * 不一致的表现是「登录成功了，但下一个请求 401」，
     * 而两边都觉得自己是对的。
     */
    private String memberTokenName;
}
