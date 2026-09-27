package com.chargeops.constant;

/**
 * Redis key 常量
 *
 * <p><b>统一前缀规范：{@code 业务:模块:标识}</b>，例如
 * {@code cache:station:1}、{@code lock:connector:start:42}。
 *
 * <p>这个规范对应两件具体的事：<b>能用 SCAN 批量检索</b>（排查时要问
 * 「跟这把枪相关的 key 都有哪些」，前缀成体系的话一条
 * {@code SCAN 0 MATCH lock:connector:* COUNT 1000} 就能回答）；
 * 以及 <b>不会误删别的业务的 key</b> —— Redis 是共享的，前缀是天然的分区边界。
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
     * <p><b>它只用来削峰，不用来保证正确性。</b>锁会因为超时自动释放，也可能因为
     * 实例时钟漂移提前失效，真正防止「两个用户抢到同一把枪」的是
     * {@code ConnectorMapper.occupy} 那条条件更新
     * （{@code WHERE id = ? AND status = 1 AND current_order_id IS NULL}）
     * 和它背后的唯一索引 {@code uk_connector_current_order}。
     * 锁的价值是把 1000 个并发请求里 999 个挡在数据库之前，让它们快速失败。
     *
     * <p>★ <b>这个常量目前没有任何调用方</b> —— 也就是说那 999 个请求现在
     * 全部会打到数据库上。这不影响正确性（occupy 的裁决是可靠的），
     * 只影响峰值下的表现。要接线时用 {@code CacheClient.tryLock} /
     * {@code unlock}，并按上面那句话写注释：削峰，不是保证。
     */
    public static final String LOCK_CONNECTOR_START = "lock:connector:start:";

    /**
     * 购买套餐时的重复提交锁前缀，完整 key = {@code lock:member:package:purchase:{memberId}}
     *
     * <p>★ <b>它和上面那把锁有同一个性质：只削峰，不用来保证正确性。</b>
     * 锁会因为超时自动释放，也可能因为实例时钟漂移失效；而且
     * {@code MemberPackageServiceImpl.purchase} 是在事务方法内部释放它的 ——
     * <b>释放发生在事务提交之前</b>，所以「锁已经放开了、钱还没提交」这个窗口
     * 真实存在。之所以可以接受，是因为<b>买重了不是资金错误</b>：
     * 两条 {@code PACKAGE_PURCHASE} 流水各自对应一份权益，钱是对得上的
     * （见 {@code WalletTransactionType.PACKAGE_PURCHASE}）。
     * ★ 但如果哪天有人想让套餐购买变成「必须只买一次」（比如限购一次的新人券），
     * 这把锁<b>不足以</b>承担那个要求 —— 那时需要的是数据库层的约束，
     * 不是把锁的 TTL 调大。
     *
     * <p>★ <b>key 的标识段是 {@code memberId} 而不是 {@code packageId}：</b>
     * 要挡的是「同一个车主连点两次」，而不是「两个车主同时买同一款套餐」——
     * 后者完全正常，不该被拦。用 packageId 做 key 会让抢购场景下
     * 1000 个车主抢 1 个套餐时 999 个被锁挡在门外。
     */
    public static final String LOCK_MEMBER_PACKAGE_PURCHASE = "lock:member:package:purchase:";

    // ==================== 智能客服 ====================

    /**
     * 智能客服的会话历史，完整 key = {@code chat:session:{memberId}}
     *
     * <p><b>key 里只有 memberId，没有 sessionId。这不是省事，是安全设计。</b>
     * memberId 来自 JWT 载荷，是服务端签发、不可伪造的；而 sessionId 只能由客户端生成后
     * 传上来，属于不可信输入。把 sessionId 也拼进 key，攻击者只要猜到（或暴力枚举到）
     * 另一个车主的 sessionId，就能读到对方跟客服的完整聊天记录 ——
     * 里面很可能有车牌、订单号、充电站位置这类信息。
     *
     * <p>代价是一个车主同一时刻只能有一段会话。对「App 里的充电客服」这个场景够用。
     */
    public static final String CHAT_SESSION_KEY = "chat:session:";
}
