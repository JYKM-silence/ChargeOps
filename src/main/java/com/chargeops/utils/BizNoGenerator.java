package com.chargeops.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业务单号生成：{@code CO202609200001}（订单）/ {@code WT202609200001}（流水）。
 *
 * <p> 用 Redis 的 {@code INCR} 而不是「时间戳 + 随机数」：后者在同一毫秒内并发会撞号，
 * 而撞号的后果是唯一索引抛异常、整笔订单回滚 —— 随负载升高而变频繁的失败。{@code INCR} 是原子的。
 *
 * <p> 格式和 DDL 注释里写的 {@code CO202609180001} 一致（前缀 + 日期 + 4 位序号），
 * 它是给人看的：客服拿到单号能一眼读出日期。
 *
 * <p> 序号会跳号（事务回滚、Redis 重启都会造成空洞），这是刻意的：单号只需唯一
 * 和可读，不需要连续，要求连续就得引入一把全局锁而没有任何业务价值。
 *
 * <p> key 带日期后缀自动分片，并设 2 天 TTL 让旧 key 自己消失 —— 否则每过一天多一个，
 * 永久堆积且没有任何清理时机。
 */
@Component
public class BizNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate stringRedisTemplate;

    public BizNoGenerator(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 充电订单号 */
    public String orderNo() {
        return next("CO", "chargeops:seq:order");
    }

    /** 钱包流水号 */
    public String transactionNo() {
        return next("WT", "chargeops:seq:txn");
    }

    private String next(String prefix, String keyPrefix) {
        String day = LocalDate.now().format(DAY);
        String key = keyPrefix + ":" + day;

        Long seq = stringRedisTemplate.opsForValue().increment(key);
        if (seq == null) {
            // 只可能在 pipeline/事务里发生，这里是普通调用，真发生了说明配置有问题
            throw new IllegalStateException("生成单号失败：Redis INCR 返回 null，key=" + key);
        }
        if (seq == 1L) {
            // 只在 key 刚创建时设一次。 不设的话这些 key 永久堆积
            stringRedisTemplate.expire(key, KEY_TTL);
        }
        return prefix + day + String.format("%04d", seq);
    }
}
