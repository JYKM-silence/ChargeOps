package com.chargeops.redis;

import com.chargeops.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具类
 *
 * <p>把「查缓存 → 未命中查库 → 回写缓存」封装起来，各 Service 只需传入一个
 * 「怎么查数据库」的函数。变化的只有那一行 SQL，不变的是穿透防护、
 * 雪崩防护、事务时序这些容易写漏的部分。
 *
 * <p>统一用 {@link StringRedisTemplate} + JSON 字符串，数据在 Redis 里肉眼可读。
 * 代价是拿不到 Redis 原生的数据结构（zset 排行榜、GEO 附近的站点），
 * 那几个场景另开 RedisTemplate 直接操作，不要硬塞进这个类。
 */
@Component
@Slf4j
public class CacheClient {

    /**
     * 空值缓存的过期时间：2 分钟
     *
     * <p>防缓存穿透用。比正常缓存（30 分钟）短得多，因为空值缓存的是「不存在」
     * 这个随时会变的事实：设长了会出现「明明刚加了站点，接口还是说没有」。
     * 但它绝对不能没有 —— 没有它，攻击者反复请求一个不存在的 id，
     * 每个请求都会打到数据库。
     */
    private static final long CACHE_NULL_TTL = 2L;

    /**
     * 互斥锁的默认过期时间：10 秒
     *
     * <p>太短 —— 业务没跑完锁就过期，别的线程抢到，锁等于没加；
     * 太长 —— 持锁进程崩了只能等过期，期间谁都进不来。
     * 只能按「这个业务最长跑多久」估：缓存重建是毫秒级的，10 秒绰绰有余。
     * 充电业务（结算、批量遥测入库）要按分钟给，用 {@link #tryLock(String, long)} 显式指定。
     */
    private static final long LOCK_TTL = 10L;

    /**
     * 缓存重建用的线程池
     *
     * <p>固定大小而不是虚拟线程是有意的：它不只提供「有个地方跑」，
     * 本身还是个<b>限流器</b>，保证同一时刻正在重建的缓存数量有上限。
     * 换成不限量的虚拟线程，大量 key 同时逻辑过期时会有几百个线程同时查库 ——
     * 恰好是这个方案要避免的场景。
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 释放锁的 Lua 脚本：先比对值，是自己的锁才删。
     *
     * <p>比对和删除必须原子（Redis 执行 Lua 期间不插入其他命令），否则中间锁
     * 正好过期被抢走，我们就把别人的锁删了。比对值本身也是必须的：锁会在
     * 业务超时后自动过期并被其他线程抢到，直接 {@code DEL} 删的是<b>别人的锁</b>，
     * 会有两个线程同时重建缓存。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisJsonUtil redisJsonUtil;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedisJsonUtil redisJsonUtil) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisJsonUtil = redisJsonUtil;
    }

    // ==================== 基础读写 ====================

    /**
     * 普通写入缓存（带过期时间）
     *
     * <p><b>★ 写缓存必须带 TTL。</b>永不过期的缓存一旦失效逻辑漏了一处，
     * 就会永久返回错误数据，要等人发现、手动 DEL 才能恢复；
     * 带 TTL 最坏情况也能自愈。
     */
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, redisJsonUtil.toJson(value), ttl, unit);
    }

    /**
     * 删除缓存（立即执行）
     *
     * <p>管理端增删改数据后主动调用（Cache Aside：先更库、再删缓存）。
     *
     * <p><b>★ 在 {@code @Transactional} 方法里不能调用这个方法</b>，
     * 要用 {@link #deleteAfterCommit} —— 直接删会让缓存里永久残留旧值。
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 删除缓存，但推迟到事务提交之后才真正执行
     *
     * <p><b>★ 为什么不能直接 delete：</b>{@code @Transactional} 方法体执行时事务
     * <b>还没提交</b>，删缓存后别的线程会读到旧值并回填：
     * <pre>
     *   线程A：① 更新数据库（未提交）  ② 删除缓存
     *   线程B：③ 缓存没有，查库 → ④ 读到 A 未提交的【旧值】→ ⑤ 写回缓存
     *   线程A：⑥ 事务提交   ← 缓存里躺着旧值，没有任何机制再删它
     * </pre>
     * 所以「先删缓存再更库」和「先更库再删缓存」都不安全，真正管用的是把删除
     * 推迟到提交之后：提交前缓存一直是旧值（那本来就是提交前的正确值），
     * 提交后立刻删，不存在回填窗口。事务回滚时 afterCommit 不执行 —— 数据库没变，
     * 缓存也不该动。
     *
     * @param keys 要删除的 key，可以传多个。一个实体变了往往要失效好几个缓存
     *             （站点信息改了既要删站点详情，也要删该城市的站点列表），
     *             放在一次调用里，调用方至少能在一个地方看到全部关联 key。
     */
    public void deleteAfterCommit(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        runAfterCommit(() -> stringRedisTemplate.delete(Arrays.asList(keys)));
    }

    /**
     * 把一个动作推迟到事务提交后执行；当前没有事务时立即执行
     *
     * <p>用在「写数据库 + 同步更新 Redis」的场景：Redis 不参与数据库事务，
     * 放在提交前执行的话，一旦数据库回滚，Redis 那边的改动回不来 ——
     * 数据库说「这事没发生」，Redis 说「发生了」，两边永久不一致。
     * 例如结算完成后「更新钱包余额」+「把站点空闲枪数写回缓存」，
     * 后者必须在数据库确实提交之后再做。
     */
    public void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务（比如某个没加 @Transactional 的方法），直接跑
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    task.run();
                } catch (Exception e) {
                    // ★ 只记日志、不往外抛，是有意为之：事务已提交，此时抛异常
                    // 回滚不了数据库，只会让用户看到「明明操作成功了却报错」的假失败。
                    // 缓存的过期由 TTL 兜底，宁可短暂不一致，也不能给错误提示。
                    log.error("事务提交后的缓存操作失败，缓存可能不一致，等待 TTL 到期自愈", e);
                }
            }
        });
    }

    // ==================== 缓存穿透：空值缓存 ====================

    /**
     * 查询缓存，解决缓存穿透（数据库不存在的数据，缓存空值）
     *
     * <p>穿透：请求的数据库里压根不存在，缓存永不命中，每次都落到数据库 ——
     * 攻击者拿一堆不存在的 id 请求就能让缓存层形同虚设。
     * 解法是把「不存在」也当成一种结果缓存起来，存一个空字符串 {@code ""} 作标记。
     *
     * <p><b>★ 判断「命中空值」必须是 {@code json != null}，不能是
     * {@code !StringUtils.hasText(json)}：</b>{@code hasText} 对空串也返回 false，
     * 用它区分不出「key 不存在」（要查库）和「key 存在但值是空串」
     * （防穿透标记，绝不能查库）。
     *
     * @param keyPrefix  key 前缀，如 {@code cache:station:}
     * @param id         业务标识，完整 key = keyPrefix + id
     * @param type       返回类型（带泛型用 {@code new TypeReference<List<StationVO>>() {}}）
     * @param dbFallback 缓存未命中时查数据库的函数
     * @param ttl        缓存过期时间
     * @param unit       时间单位
     */
    public <R> R queryWithPassThrough(String keyPrefix, Long id, TypeReference<R> type,
                                      Function<Long, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 缓存命中且有值
        if (StringUtils.hasText(json)) {
            return redisJsonUtil.toBean(json, type);
        }

        // 2. key 存在但值是空串：防穿透标记，数据库里也没有
        if (json != null) {
            return null;
        }

        // 3. 缓存未命中：查数据库
        R r = dbFallback.apply(id);

        // 4. 数据库也没有：缓存空值
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 数据库有：写入缓存
        set(key, r, ttl + ThreadLocalRandom.current().nextInt((int) (ttl * 0.3) + 1), unit);
        return r;
    }

    /**
     * 写入缓存（逻辑过期方式）
     *
     * <p>key 物理上不过期，过期时间记在 {@link RedisData#getExpireTime()} 里。
     * 方案取舍见 {@link RedisData} 的注释。
     *
     * <p><b>★ TTL 加 0~30% 随机偏移：</b>同一时刻写入、TTL 又相同的 key 会集体过期，
     * 重建请求在同一瞬间涌向数据库（缓存雪崩）。偏移让过期时间错开，
     * 也让「同一批缓存同时进入逻辑过期状态」的概率变低，避免大量 key 同时抢锁重建。
     */
    public void setWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);

        long seconds = unit.toSeconds(ttl)
                + ThreadLocalRandom.current().nextInt((int) (unit.toSeconds(ttl) * 0.3) + 1);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));

        stringRedisTemplate.opsForValue().set(key, redisJsonUtil.toJson(redisData));
    }

    /**
     * 查询缓存，解决缓存击穿（热点 key 过期瞬间大量请求打穿数据库）
     *
     * <p>key 物理不过期，过期后由抢到锁的线程异步重建，其他线程直接返回旧数据兜底 ——
     * <b>谁都不等</b>。
     *
     * <p><b>★ 与 {@link #queryWithPassThrough} 的关键差别：这里不缓存空值</b>，
     * 缓存里没有就直接返回 null，因此<b>依赖预热</b> —— key 从没被写入过的话，
     * 所有请求都拿到 null 且永远没有线程去重建它。只适用于「启动时就能确定
     * 要缓存哪些」的场景（计费模板、站点基础信息）；用错的表现是
     * 「接口一直返回 null，但数据库里明明有数据」。
     */
    public <R> R queryWithLogicalExpire(String keyPrefix, Long id, TypeReference<R> type,
                                        Function<Long, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 缓存不存在：不做空值缓存，直接返回 null
        if (!StringUtils.hasText(json)) {
            return null;
        }

        // 2. 反序列化 RedisData。
        // ★ RedisData.data 声明的是 Object，反序列化后实际类型是 LinkedHashMap，
        //   不能直接强转成目标类型，所以要走「先转回 JSON 字符串、再转成目标类型」
        //   两趟 —— 泛型擦除下唯一可靠的做法。
        RedisData redisData = redisJsonUtil.toBean(json, RedisData.class);
        R r = redisJsonUtil.toBean(redisJsonUtil.toJson(redisData.getData()), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 3. 未过期：直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 4. 已过期：尝试抢锁，只有拿到锁的线程负责重建
        String lockKey = RedisConstant.LOCK_KEY_PREFIX + key;
        String token = tryLock(lockKey);
        if (token != null) {
            try {
                // ★ 双重检查：拿到锁后必须再查一次缓存。「发现过期 → 抢锁」之间
                //   可能已经有别的线程重建完成了，不检查就会白重建一次。
                String json2 = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.hasText(json2)) {
                    RedisData redisData2 = redisJsonUtil.toBean(json2, RedisData.class);
                    if (redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                        unlock(lockKey, token);
                        return redisJsonUtil.toBean(redisJsonUtil.toJson(redisData2.getData()), type);
                    }
                }

                // 还是过期状态：自己负责重建。开新线程异步执行，
                // 当前请求先返回旧数据，不阻塞用户 —— 用户宁可要 200ms 的旧数据，
                // 也不要 2 秒的新数据。
                //
                // ★ token 必须作为参数传进去：重建在下一个线程里跑，用不了 ThreadLocal，
                //   而且此时锁可能已经超时，解锁只能靠 token 比对确认还是自己的锁。
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R newR = dbFallback.apply(id);
                        setWithLogicalExpire(key, newR, ttl, unit);
                    } finally {
                        unlock(lockKey, token);
                    }
                });
            } catch (Exception e) {
                // 出现异常也要释放锁，否则这把锁要等 10 秒自动过期，
                // 这 10 秒内没有任何线程去重建缓存
                unlock(lockKey, token);
                throw new RuntimeException("缓存重建失败", e);
            }
        }

        // 5. 没抢到锁（其他线程正在重建）或重建进行中：返回旧数据兜底
        return r;
    }

    // ==================== 分布式锁 ====================

    /**
     * 抢锁：{@code SET key value NX EX} 一条命令完成，不会出现「抢到锁但没设过期」
     *
     * <p><b>★ 值存随机 UUID 而不是固定的 "1"：</b>释放锁要靠这个值区分锁是不是自己的。
     * 用固定值的话，A 的锁过期被 B 抢到后，A 删除时比对 "1" == "1" 成功，删掉的是 B 的锁。
     *
     * @return 抢锁成功返回本次的 token（释放锁时要原样传回），失败返回 {@code null}
     */
    public String tryLock(String key) {
        return tryLock(key, LOCK_TTL);
    }

    /**
     * 抢锁（自定义过期时间）
     *
     * <p>结算任务要遍历一批订单，可能跑几十秒到几分钟，默认的 10 秒会在业务跑完前过期。
     *
     * @param ttlSeconds 锁的自动过期时间（秒）
     */
    public String tryLock(String key, long ttlSeconds) {
        String token = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, ttlSeconds, TimeUnit.SECONDS);
        // success 可能为 null（连接异常时），用 Boolean.TRUE.equals 避免拆箱 NPE
        return Boolean.TRUE.equals(success) ? token : null;
    }

    /**
     * 释放锁：用 Lua 脚本保证「比对值 + 删除」是一个原子操作
     *
     * <p>★ 必须把 {@link #tryLock} 返回的 token 原样传回来，不像 Redisson 那样
     * 按线程绑定 —— 因为锁<b>可能在另一个线程里释放</b>（缓存重建就是请求线程
     * 抢锁、异步重建线程解锁）。
     *
     * @param token tryLock 返回的 token，不是自己的锁就不会删掉
     */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }
}
