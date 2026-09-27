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
 * <p> 把「查缓存 → 未命中查库 → 回写缓存」连同穿透/雪崩/击穿防护、事务时序一起封起来，
 * 调用方只需要传一个「怎么查数据库」的函数。
 *
 * <p> 统一用 {@link StringRedisTemplate} + JSON 字符串，数据在 Redis 里肉眼可读；
 * 需要 Redis 原生结构的场景（zset、GEO）另开 RedisTemplate，不要硬塞进这个类。
 */
@Component
@Slf4j
public class CacheClient {

    /** 空值缓存的过期时间：2 分钟。防穿透用，比正常缓存短得多 —— 它缓存的是「不存在」这个随时会变的事实 */
    private static final long CACHE_NULL_TTL = 2L;

    /** 互斥锁默认过期时间 10 秒。太短业务没跑完锁就没了，太长持锁进程崩了谁都进不来 */
    private static final long LOCK_TTL = 10L;

    /**
     * 缓存重建用的线程池。固定 10 个线程是有意的：它同时是个限流器 —— 大量 key 同时逻辑过期时，
     * 不限量的线程会一起查库，恰好是这套方案要避免的。
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 释放锁的 Lua 脚本：比对值再删。两步必须原子（Redis 执行 Lua 期间不插入其它命令），
     * 否则中间锁正好过期被别人抢走，我们就把别人的锁删了。
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

    // ---------- 基础读写 ----------

    /** 写缓存必须带 TTL：永不过期的缓存一旦失效逻辑漏了一处就永久返回错误数据，带 TTL 最坏也能自愈 */
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, redisJsonUtil.toJson(value), ttl, unit);
    }

    /** 删除缓存（立即执行），Cache Aside 的手动失效点。在 {@code @Transactional} 方法里不能用它，要用 {@link #deleteAfterCommit} */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 删除缓存，但推迟到事务提交之后才真正执行。直接删的话，事务还没提交时别的线程会读到旧值并回填，
     * 提交后缓存里躺着旧值、再没有机制删它；回滚时 afterCommit 不执行，数据库没变缓存也不该动。
     *
     * @param keys 要删除的 key，可传多个：一个实体变了往往要失效好几个缓存
     */
    public void deleteAfterCommit(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        runAfterCommit(() -> stringRedisTemplate.delete(Arrays.asList(keys)));
    }

    /**
     * 把一个动作推迟到事务提交后执行；当前没有事务时立即执行。Redis 不参与数据库事务，
     * 放在提交前做的话数据库一回滚，Redis 那边的改动回不来。
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
                    // 只记日志、不往外抛：事务已提交，抛了也回滚不了数据库，
                    // 只会让用户看到「明明成功了却报错」的假失败。缓存由 TTL 兜底。
                    log.error("事务提交后的缓存操作失败，缓存可能不一致，等待 TTL 到期自愈", e);
                }
            }
        });
    }

    // ---------- 缓存穿透：空值缓存 ----------

    /**
     * 查询缓存，解决缓存穿透：把「数据库里不存在」也缓存起来，写一个空串作标记。
     * 判断「命中空值」必须用 {@code json != null} 而不是 {@code !hasText(json)}：后者对空串
     * 也返回 false，区分不出「key 不存在」（要查库）和「key 存在但值是空串」（绝不能查库）。
     *
     * @param keyPrefix  key 前缀，如 {@code cache:station:}
     * @param dbFallback 缓存未命中时查数据库的函数
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
     * 写入缓存（逻辑过期方式）：key 物理上不过期，过期时间记在 {@link RedisData} 里。
     * TTL 加 0~30% 随机偏移：同一时刻写入、TTL 又相同的 key 会集体过期，
     * 重建请求在同一瞬间涌向数据库（缓存雪崩）。
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
     * 查询缓存，解决缓存击穿：热点 key 过期后由抢到锁的线程异步重建，其他线程直接返回旧数据兜底。
     *
     * <p> 与 {@link #queryWithPassThrough} 的关键差别：这里不缓存空值，缓存里没有就返回 null，
     * 因此依赖预热 —— key 从没被写入过的话，所有请求都拿到 null 且永远不会有线程去重建它。
     */
    public <R> R queryWithLogicalExpire(String keyPrefix, Long id, TypeReference<R> type,
                                        Function<Long, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 缓存不存在：不做空值缓存，直接返回 null
        if (!StringUtils.hasText(json)) {
            return null;
        }

        // 2. 反序列化 RedisData。data 声明的是 Object，实际类型是 LinkedHashMap，
        //    不能直接强转，只能走「转回 JSON 字符串再转目标类型」两趟
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
                // 双重检查：从「发现过期」到「抢到锁」之间可能已经有别的线程重建完了
                String json2 = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.hasText(json2)) {
                    RedisData redisData2 = redisJsonUtil.toBean(json2, RedisData.class);
                    if (redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                        unlock(lockKey, token);
                        return redisJsonUtil.toBean(redisJsonUtil.toJson(redisData2.getData()), type);
                    }
                }

                // 还是过期状态：自己负责重建。开新线程异步执行，当前请求先返回旧数据。
                // token 必须当参数传进去：重建在下一个线程里跑，用不了 ThreadLocal。
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R newR = dbFallback.apply(id);
                        setWithLogicalExpire(key, newR, ttl, unit);
                    } finally {
                        unlock(lockKey, token);
                    }
                });
            } catch (Exception e) {
                // 异常也要解锁，否则要等 10 秒自动过期，这 10 秒内没人重建缓存
                unlock(lockKey, token);
                throw new RuntimeException("缓存重建失败", e);
            }
        }

        // 5. 没抢到锁（其他线程正在重建）：返回旧数据兜底
        return r;
    }

    // ---------- 分布式锁 ----------

    /**
     * 抢锁：{@code SET key value NX EX} 一条命令完成，不会出现「抢到锁但没设过期」。
     * 值存随机 UUID 而不是固定的 "1"：A 的锁过期被 B 抢到后，A 删除时比对 "1" == "1" 成功，删掉的是 B 的锁。
     *
     * @return 抢锁成功返回本次的 token（释放时要原样传回），失败返回 {@code null}
     */
    public String tryLock(String key) {
        return tryLock(key, LOCK_TTL);
    }

    /**
     * 抢锁（自定义过期时间）。结算任务要遍历一批订单，可能跑几十秒到几分钟，默认的 10 秒会在业务跑完前过期。
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
     * 释放锁：用 Lua 脚本保证「比对值 + 删除」是原子操作。必须把 {@link #tryLock} 返回的 token
     * 原样传回来，不能像 Redisson 那样按线程绑定 —— 缓存重建就是请求线程抢锁、异步重建线程解锁。
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
