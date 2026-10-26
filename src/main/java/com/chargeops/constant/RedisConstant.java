package com.chargeops.constant;

/**
 * Redis key 常量。前缀规范是 {@code 业务:模块:标识}，便于 SCAN 批量检索，也是共享 Redis 上的分区边界。
 */
public class RedisConstant {

    /** {@code CacheClient} 内部重建缓存时会拼这个前缀，它必须存在 */
    public static final String LOCK_KEY_PREFIX = "lock:";

    /** 站点详情缓存：仅缓存按 id 查询的详情，不缓存分页结果，避免组合条件与分页参数造成复杂失效 */
    public static final String CACHE_STATION_PREFIX = "cache:station:";

    /** 启动充电时的枪口锁。只削峰，正确性由 {@code ConnectorMapper.occupy} 的条件更新保证。目前没有调用方，接线时用 {@code CacheClient.tryLock} */
    public static final String LOCK_CONNECTOR_START = "lock:connector:start:";

    /** 购买套餐时的重复提交锁。标识段用 {@code memberId} 而不是 {@code packageId}：要挡的是同一个车主连点两次。同样只削峰，买重了不是资金错误 */
    public static final String LOCK_MEMBER_PACKAGE_PURCHASE = "lock:member:package:purchase:";

    /** 智能客服的会话历史。key 里不放 sessionId：那是客户端传上来的不可信输入，拼进去就能枚举别人的聊天记录 */
    public static final String CHAT_SESSION_KEY = "chat:session:";
}
