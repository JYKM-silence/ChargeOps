package com.chargeops.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与校验工具（jjwt 0.12.x 的 API）
 *
 * <p><b>★ 最容易炸的一点：密钥长度。</b>{@link Keys#hmacShaKeyFor} 要求 HS256 的密钥
 * 至少 32 字节，不够就抛 {@code WeakKeyException}。演示用的默认密钥往往只有 6~7 个字符，
 * 直接沿用必然启动即炸 —— 而且报的是 <b>500 不是 401</b>，因为异常抛在签发 token 的时候，
 * 跟鉴权拦截器毫无关系，看到 500 就去看拦截器是很自然的误判方向。
 * 所以 {@link #hmacKey} 宁可自己显式校验一遍，把「哪个配置项有问题」写进异常消息。
 *
 * <p><b>为什么参数是 String 而不是 SecretKey：</b>密钥来自配置
 * （{@code chargeops.jwt.*-secret-key}），本质是字符串。让调用方传 SecretKey 的话，
 * 「字符串怎么变成 SecretKey」就散落到各个调用点，每处可能用不同编码，
 * 结果是「同一个密钥签出来的 token 校验不过」这种最难查的问题。转换只在这里做一次。
 */
public class JwtUtil {

    /** HS256 要求的最小密钥字节数（256 位），jjwt 强制的下限 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 生成 JWT
     *
     * @param secretKey 密钥，UTF-8 编码后必须 ≥ 32 字节
     * @param ttlMillis 有效期（毫秒），从当前时刻算起
     * @param claims    自定义载荷，例如 {@code {"staffId": 1}}
     * @return 紧凑格式的 token（形如 {@code aaa.bbb.ccc}）
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SecretKey key = hmacKey(secretKey);
        long now = System.currentTimeMillis();

        return Jwts.builder()
                // ★ 顺序有意义：先放自定义 claims，再设标准字段。反过来的话，
                //   自定义 claims 里恰好有个叫 "exp" 的键就会把刚设好的过期时间覆盖掉 ——
                //   这个 token 会「永远有效」，且表面上完全正常（能签发、能校验、能用）。
                .claims(claims)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                // 只给密钥，算法由密钥长度推导（32~47 字节 → HS256）
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 JWT
     *
     * <p>签名不对、已过期、格式错误都会抛异常，调用方统一按「鉴权失败」处理即可 ——
     * 不需要（也不应该）区分是哪种，那属于给攻击者的信息泄露。
     *
     * @param secretKey 密钥
     * @param token     待校验的 token
     * @return 载荷
     * @throws io.jsonwebtoken.JwtException 签名无效 / 已过期 / 格式错误
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser()
                .verifyWith(hmacKey(secretKey))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 把配置里的字符串密钥转成 {@link SecretKey}，并给出明确的长度校验
     *
     * <p><b>★ 为什么 jjwt 已经会校验了还要自己判一次：</b>它的
     * {@code WeakKeyException} 只说「密钥不够长」，<b>不说这个密钥是从哪个配置项读来的</b>。
     * 本项目的 JWT 密钥有四个来源（管理端/车主端各一套，每套还能被环境变量覆盖），
     * 看到「48 bits」之后还得去翻配置找是哪一条 —— 这里补一句带上下文的消息省掉这一步。
     */
    private static SecretKey hmacKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥没有配置。检查 application.yml 里的 chargeops.jwt.*-secret-key，"
                            + "或对应的环境变量是否为空。");
        }

        byte[] bytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT 密钥太短：当前 %d 字节，HS256 要求至少 %d 字节。"
                            + "看起来你沿用了一串演示用的短密钥，"
                            + "请换成一串至少 32 个字符的随机值（不要用中文，一个汉字占 3 字节会掩盖真实长度）。",
                    bytes.length, MIN_SECRET_BYTES));
        }

        return Keys.hmacShaKeyFor(bytes);
    }
}
