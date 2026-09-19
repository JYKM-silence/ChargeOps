package com.chargeops.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与校验工具
 *
 * <p><b>这是一个相对老库完全重写的类，因为 jjwt 从 0.9.1 到 0.12.x 是破坏性变更：</b>
 * <pre>
 *   0.9.1   Jwts.builder().setClaims(m).signWith(alg, byte[]).setExpiration(d)
 *           Jwts.parser().setSigningKey(byte[]).parseClaimsJws(t).getBody()
 *
 *   0.12.x  Jwts.builder().claims(m).issuedAt(d).expiration(d).signWith(SecretKey)
 *           Jwts.parser().verifyWith(SecretKey).build().parseSignedClaims(t).getPayload()
 * </pre>
 * 几个变化值得注意，因为它们不是单纯的改名：
 * <ul>
 *   <li>{@code signWith} 从「算法 + 密钥字节」变成「只给密钥」，算法由密钥长度推导。
 *       好处是再也不会出现「用 HS256 的算法配一把 512 位的密钥」这种错配</li>
 *   <li>{@code parser()} 不再是「直接可用的解析器」，而是一个 builder，
 *       必须显式 {@code build()}。目的是强制调用方在解析前把校验策略配全，
 *       避免出现「忘了设密钥就直接解析」却没人发现的情况</li>
 *   <li>{@code getBody()} 改名 {@code getPayload()}，
 *       {@code parseClaimsJws} 改名 {@code parseSignedClaims} ——
 *       后者的新名字更诚实：它只接受**签名过的** JWT，
 *       而 {@code parseClaimsJwt}（无签名）在新版里被明确标成了危险 API</li>
 * </ul>
 *
 * <p><b>★ 最容易炸的一点：密钥长度。</b>
 * {@link Keys#hmacShaKeyFor} 会校验 HS256 要求密钥至少 256 位，也就是 <b>32 字节</b>，
 * 不够就抛 {@code WeakKeyException}。
 * 而演示用的默认密钥往往只有 6~7 个字符（一个短单词），
 * 直接沿用必然启动即炸 —— 而且报的是 <b>500 不是 401</b>，
 * 因为异常抛在签发 token 的时候，跟鉴权拦截器毫无关系。
 * 看到 500 就去看拦截器是很自然的误判方向，所以这里宁可多写一段显式校验，
 * 把「哪个配置项有问题」直接写进异常消息。
 *
 * <p><b>为什么密钥是 String 而不是 SecretKey：</b>
 * 密钥来自配置文件（{@code chargeops.jwt.*-secret-key}），本质是字符串。
 * 如果让调用方传 SecretKey，那「字符串怎么变成 SecretKey」这件事就散落到各个调用点，
 * 每处都可能用不同的编码（有人用 UTF-8 有人用默认编码），
 * 结果就是「同一个密钥签出来的 token 校验不过」这种最难查的问题。
 * 转换只在这里做一次。
 */
public class JwtUtil {

    /**
     * HS256 要求的最小密钥字节数
     *
     * <p>256 位 = 32 字节。这是 HMAC-SHA256 的数学要求，不是可以商量的配置项：
     * 密钥短于输出长度时，HMAC 的安全性会明显低于其理论强度。
     * 所以 jjwt 从 0.10 开始把它从「建议」变成了「强制」。
     */
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
                // ★ 顺序有意义：先放自定义 claims，再设标准字段。
                // 反过来的话，如果自定义 claims 里恰好有个叫 "exp" 的键，
                // 它会把下面刚设好的过期时间覆盖掉 —— 而这个 token 会「永远有效」，
                // 且表面上完全正常（能签发、能校验、能用），只有过期策略失效了。
                .claims(claims)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                // 只给密钥，算法由密钥长度推导（32~47 字节 → HS256，≥64 → HS512 等）
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 JWT
     *
     * <p>签名不对、已过期、格式错误都会抛异常，调用方统一按「鉴权失败」处理即可 ——
     * 不需要（也不应该）区分具体是哪种，那属于给攻击者的信息泄露。
     *
     * @param secretKey 密钥
     * @param token     待校验的 token
     * @return 载荷
     * @throws io.jsonwebtoken.JwtException 签名无效 / 已过期 / 格式错误
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser()
                // verifyWith 取代了老版的 setSigningKey。
                // 名字的改动是有意为之：它强调的是「这个密钥是用来**验证签名**的」，
                // 而不是「这是把钥匙」。签发的密钥和验证的密钥在非对称算法里是不同的两把，
                // 老名字容易让人以为它们是一回事。
                .verifyWith(hmacKey(secretKey))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 把配置里的字符串密钥转成 {@link SecretKey}，并给出明确的长度校验
     *
     * <p><b>为什么在 jjwt 已经会校验的情况下还要自己判一次：</b>
     * jjwt 抛出的 {@code WeakKeyException} 消息是
     * {@code "The signing key's size is 48 bits which is not secure enough for the HS256 algorithm."} ——
     * 它告诉你「密钥不够长」，但<b>不告诉你这个密钥是从哪个配置项读来的</b>。
     *
     * <p>而本项目的 JWT 密钥有四个来源：管理端/车主端各一套，
     * 每套又能被环境变量覆盖。看到「48 bits」之后，还是得去翻配置找是哪一条。
     * 这里补一句带上下文的消息，把这一步省掉。
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
