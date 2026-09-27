package com.chargeops.redis;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存的包装对象：key 在 Redis 里不设物理过期，过期时间记在 {@code expireTime} 里自己判断。
 * 适合计费模板、套餐列表这类旧几分钟无所谓的；枪的实时状态不适合 —— 那种走
 * {@code queryWithPassThrough} + 短 TTL。
 */
@Data
public class RedisData {

    private Object data;

    private LocalDateTime expireTime;
}
