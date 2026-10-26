package com.chargeops.service;

import com.chargeops.exception.BaseException;
import com.chargeops.exception.LoginRateLimitedException;
import com.chargeops.properties.LoginRateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 双维度登录限流：IP 窗口限制请求量，账号失败计数限制口令猜测。
 *
 * <p>IP 计数和失败计数通过 Lua 在 Redis 端原子递增并设置过期时间，避免并发请求把计数器拆成
 * 「INCR 成功、EXPIRE 未执行」的永久 key。账号和 IP 都先做 SHA-256，Redis key 不暴露原始标识。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginRateLimitService {

    private static final String IP_KEY_PREFIX = "login:limit:ip:";
    private static final String ACCOUNT_KEY_PREFIX = "login:limit:account:";
    private static final String TOO_MANY_REQUESTS = "登录请求过于频繁，请稍后再试";

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> INCREMENT_IF_BELOW_LIMIT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[1])
            if count >= limit then
              return -1
            end
            count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginRateLimitProperties properties;

    /**
     * 先占用一个 IP 请求额度，再检查账号失败冷却，最后执行业务登录。
     * 仅指定的认证失败会累计账号计数；禁用账号、参数错误和 Redis 故障不会伪装成密码错误。
     */
    public <T> T execute(String clientIp, String account,
                         Supplier<T> login,
                         Predicate<BaseException> isCredentialFailure) {
        if (!properties.isEnabled()) {
            return login.get();
        }

        String ipKey = IP_KEY_PREFIX + digest(normalize(clientIp));
        Long ipCount = redisTemplate.execute(INCREMENT_IF_BELOW_LIMIT, List.of(ipKey),
                Integer.toString(properties.getIpMaxAttempts()),
                Long.toString(properties.getIpWindow().toSeconds()));
        if (ipCount == null || ipCount < 0) {
            throw new LoginRateLimitedException(TOO_MANY_REQUESTS);
        }

        String accountKey = ACCOUNT_KEY_PREFIX + digest(normalize(account));
        String failures = redisTemplate.opsForValue().get(accountKey);
        if (failures != null && parseCount(failures) >= properties.getAccountMaxFailures()) {
            throw new LoginRateLimitedException("登录失败次数过多，请 "
                    + properties.getAccountWindow().toMinutes() + " 分钟后再试");
        }

        try {
            T result = login.get();
            try {
                redisTemplate.delete(accountKey);
            } catch (RuntimeException exception) {
                // 凭证已经验证成功，清理失败最多让旧计数等 TTL 到期，不应把成功登录变成失败。
                log.warn("登录成功，但清理账号失败计数失败，accountHash={}", accountKey.substring(ACCOUNT_KEY_PREFIX.length()), exception);
            }
            return result;
        } catch (BaseException exception) {
            if (isCredentialFailure.test(exception)) {
                redisTemplate.execute(INCREMENT_WITH_TTL, List.of(accountKey),
                        Long.toString(properties.getAccountWindow().toSeconds()));
            }
            throw exception;
        }
    }

    private static long parseCount(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // Redis key 被手工写坏时按未达到阈值处理；下一次失败会用 INCR 报出真实数据问题。
            return 0;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256", exception);
        }
    }
}
