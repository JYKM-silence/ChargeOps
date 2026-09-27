package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置，对应 application.yml 里的 {@code chargeops.jwt.*}
 *
 * <p> 管理端用 {@code admin-secret-key} + 请求头 {@code token}，车主端用 {@code member-secret-key} +
 * 请求头 {@code authentication}，两套密钥必须不同 —— 一套的话泄露一处就等于泄露管理端。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.jwt")
@Data
public class JwtProperties {

    /** 管理端签名密钥，UTF-8 编码后必须 ≥ 32 字节（HS256 的要求） */
    private String adminSecretKey;

    private long adminTtl;

    private String adminTokenName;

    /** 车主端签名密钥，必须和管理端不同，且 ≥ 32 字节 */
    private String memberSecretKey;

    private long memberTtl;

    /** 车主端从哪个请求头取 token，老库沿用 {@code authentication} */
    private String memberTokenName;
}
