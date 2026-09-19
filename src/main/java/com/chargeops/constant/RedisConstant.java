package com.chargeops.constant;

/**
 * Redis key 常量
 *
 * <p><b>统一前缀规范：{@code 业务:模块:标识}</b>，例如
 * {@code cache:station:1}、{@code lock:connector:start:42}。
 *
 * <p>这个规范不是形式主义，它对应两件具体的事：
 * <ol>
 *   <li><b>能用 SCAN 批量检索</b>。线上排查时经常要问「跟这把枪相关的 key 都有哪些」，
 *       前缀成体系的话一条 {@code SCAN 0 MATCH lock:connector:* COUNT 1000} 就能回答。
 *       如果 key 长得五花八门（{@code connectorLock42}、{@code CONN_42_LOCK}），
 *       就只能靠猜和一个个 GET。</li>
 *   <li><b>不会误删别的业务的 key</b>。Redis 是共享的，同一个实例上可能还有
 *       别的应用的库。前缀是天然的分区边界。</li>
 * </ol>
 *
 * <p><b>为什么别名不叫 RedisConstant 之外的东西：</b>这是老库沿用下来的名字，
 * 保持它可以让两份代码对照着看时少一层心智转换。名字本身没有信息量，
 * 但改动它是纯粹的迁移成本，没有收益。
 */
public class RedisConstant {

    // ==================== 分布式锁 ====================

    /**
     * 锁的通用前缀
     *
     * <p>{@code CacheClient} 内部重建缓存时会拼这个前缀，所以它必须存在。
     */
    public static final String LOCK_KEY_PREFIX = "lock:";

    /**
     * 启动充电时的枪口锁前缀，完整 key = {@code lock:connector:start:{connectorId}}
     *
     * <p><b>它只用来削峰，不用来保证正确性。</b>这一点必须说清楚，
     * 否则很容易写出「加了锁就安全了」的错误代码：
     * 锁会因为超时自动释放，也可能因为实例时钟漂移提前失效，
     * 真正防止「两个用户抢到同一把枪」的是数据库上的
     * {@code connector} 条件更新（{@code WHERE status=1 AND current_order_id IS NULL}）
     * 和唯一索引 {@code uk_connector_current_order}。
     *
     * <p>锁的价值在于：把 1000 个并发请求里 999 个挡在数据库之前，
     * 让它们快速失败，而不是全部冲到数据库上排队等行锁。
     */
    public static final String LOCK_CONNECTOR_START = "lock:connector:start:";

    // ==================== 智能客服 ====================

    /**
     * 智能客服的会话历史，完整 key = {@code chat:session:{memberId}}
     *
     * <p><b>key 里只有 memberId，没有 sessionId。这不是省事，是安全设计。</b>
     * memberId 来自 JWT 载荷，是服务端签发、不可伪造的；
     * 而 sessionId 只能由客户端生成后传上来，属于不可信输入。
     * 如果把 sessionId 也拼进 key，攻击者只要猜到（或暴力枚举到）另一个车主的
     * sessionId，就能读到对方跟客服的完整聊天记录 ——
     * 里面很可能有车牌、订单号、充电站位置这类信息。
     *
     * <p>代价是一个车主同一时刻只能有一段会话。对「App 里的充电客服」
     * 这个场景来说够用：用户关掉半小时再回来，等于开一段新的。
     */
    public static final String CHAT_SESSION_KEY = "chat:session:";

    // ==================== 缓存 ====================
    //
    // P1 会往这里加站点/套餐/计费模板的缓存 key。
    // 现在故意不预埋，理由和 MessageConstant 里那段一样：
    // 一个从没被写过的 key 常量，无法验证它的前缀拼得对不对、
    // 也无法判断 TTL 该给多少。等到真正实现那个查询时再加，
    // 那时才有足够信息决定「缓存什么、什么时候失效」。
}
