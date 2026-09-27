package com.chargeops.redis;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存的包装对象
 *
 * <p>解决缓存击穿：key 在 Redis 里<b>不设置物理过期时间</b>，过期时间记在
 * {@link #expireTime} 字段里自己判断。过期后只有抢到锁的线程去查库重建，
 * 其余线程直接返回旧数据 —— 数据库任何时刻都只承受一个查询。
 *
 * <p><b>★ 代价是数据可能不新鲜，用时必须分清场景：</b>
 * <ul>
 *   <li>适合 —— 计费模板（尖峰平谷时段表）、套餐列表，旧几分钟无所谓</li>
 *   <li>不适合 —— 枪的实时状态。用户看到的必须是「此刻」这把枪空不空，
 *       返回旧数据会让他点了启动才发现被占用。这类数据走
 *       {@code queryWithPassThrough} + 短 TTL，或者干脆不缓存</li>
 * </ul>
 */
@Data
public class RedisData {

    /** 缓存的实际数据 */
    private Object data;

    /** 逻辑过期时间 */
    private LocalDateTime expireTime;
}
