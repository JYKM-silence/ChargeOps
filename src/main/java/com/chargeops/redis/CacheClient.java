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
 * <p>把「查缓存 → 未命中查库 → 回写缓存」这个固定套路封装起来，
 * 各 Service 只需传入一个「怎么查数据库」的函数。这是模板方法模式的变体 ——
 * 变化的只有那一行 SQL，不变的是穿透防护、雪崩防护、事务时序这些容易写漏的部分。
 *
 * <p><b>统一用 {@link StringRedisTemplate} + JSON 字符串</b>，
 * 数据在 Redis 里肉眼可读。代价是拿不到 Redis 原生的数据结构
 * （zset 排行榜、GEO 附近的站点），那几个场景另开 RedisTemplate 直接操作，
 * 不要硬塞进这个类 —— 一个类同时提供「通用缓存」和「特定数据结构操作」，
 * 会很快变成一个什么都往里放的杂物间。
 *
 * <p><b>迁移说明（从上一版实现到 ChargeOps）：</b>
 * 这个类<b>逻辑上零改动</b>，只有三处机械替换：
 * <ol>
 *   <li>{@code RedisConstant} 所在的包 → {@code com.chargeops.constant}</li>
 *   <li>{@code com.fasterxml.jackson.core.type.TypeReference}
 *       → {@code tools.jackson.core.type.TypeReference}（Jackson 3 迁移）</li>
 *   <li>{@code RedisJsonUtil.toJson(...)} 由静态调用改成注入的 Bean 调用</li>
 * </ol>
 * 这三处之外，缓存穿透、逻辑过期、分布式锁的实现一行都不用动。
 * <b>能整块搬过来，是因为它一开始就没有和餐饮业务耦合</b> ——
 * 它不认识「菜品」「订单」，只认识「key 前缀 + id + 查库函数」。
 * 这是「基础设施和业务分离」在需要换壳时最直接的回报。
 */
@Component
@Slf4j
public class CacheClient {

    /**
     * 空值缓存的过期时间：2 分钟
     *
     * <p>防缓存穿透用。为什么是 2 分钟而不是跟正常缓存一样 30 分钟：
     * 空值缓存是在缓存一个「不存在」的事实，而这个事实随时可能因为
     * 新增数据而失效。设短一点，数据补上之后最多 2 分钟就能被查到；
     * 设长了会出现「明明刚加了站点，接口还是说没有」的灵异现象。
     *
     * <p>而它又绝对不能没有：不缓存空值的话，攻击者只要反复请求
     * 一个不存在的 id，每个请求都会打到数据库 —— 缓存就形同虚设了。
     */
    private static final long CACHE_NULL_TTL = 2L;

    /**
     * 互斥锁的默认过期时间：10 秒
     *
     * <p>这个值是个权衡，两个方向都会出事：
     * <ul>
     *   <li><b>太短</b> —— 业务还没跑完锁就自动过期了，别的线程能抢到锁，
     *       锁等于没加，还是并发重建</li>
     *   <li><b>太长</b> —— 持锁的进程崩了，这把锁只能等过期才释放，期间谁都进不来</li>
     * </ul>
     * 所以它只能按「这个业务最长跑多久」来估。缓存重建是毫秒级的，
     * 10 秒绰绰有余。充电相关的业务（结算、批量遥测入库）要按分钟给，
     * 那时候用 {@link #tryLock(String, long)} 显式指定。
     *
     * <p>Redisson 的看门狗机制就是专门解决这个两难 ——
     * 后台线程定期给锁续期，业务没结束锁就不会过期。
     * 本项目不做那么重，但要知道这个方案的存在，
     * 面试被问「锁超时了业务还没跑完怎么办」时要能答上来。
     */
    private static final long LOCK_TTL = 10L;

    /**
     * 缓存重建用的线程池
     *
     * <p><b>为什么是固定大小而不是虚拟线程：</b>
     * 逻辑过期方案里，重建是异步做的 —— 当前请求先返回旧数据，
     * 重建在另一个线程里跑。这个线程池的作用不只是「有个地方跑」，
     * 更是一个<b>限流器</b>：它保证同一时刻正在重建的缓存数量有上限。
     * 如果换成虚拟线程（每个任务一个新线程、不限制数量），
     * 一旦大量 key 同时逻辑过期，就会出现几百个线程同时查库 ——
     * 恰好是我们想避免的那个场景。
     *
     * <p>所以这里的「有界」是有意的，不是随手写的 10。
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 释放锁的 Lua 脚本：先比对值，是自己的锁才删。
     *
     * <p><b>为什么要用 Lua：</b>「判断值是不是我的」和「删除 key」是两步操作，
     * 分开发两条命令的话，中间这一小段时间锁可能正好过期、被别的线程抢走，
     * 我们就把别人的锁删了。
     * Redis 执行 Lua 脚本时不会插入其他命令，这两步是原子的。
     *
     * <p><b>为什么必须比对值：</b>锁只有 10 秒过期时间，业务执行超过 10 秒的话，
     * 锁会自动过期并被其他线程抢到。此时如果直接 {@code DEL}，
     * 删掉的是<b>别人的锁</b>，导致同时有两个线程在重建缓存 —— 锁就白加了。
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
     * <p><b>★ 写缓存必须带 TTL，这不是可选项。</b>
     * 永不过期的缓存一旦数据源变了而失效逻辑漏了一处，
     * 就会永久返回错误数据 —— 而且要等有人发现、有人手动 DEL 才能恢复。
     * 带 TTL 的话，最坏情况也能自愈。TTL 是缓存系统最后的兜底手段。
     */
    public void set(String key, Object value, Long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, redisJsonUtil.toJson(value), ttl, unit);
    }

    /**
     * 删除缓存（立即执行）
     *
     * <p>管理端增删改数据后主动调用，保证缓存一致性（Cache Aside 模式：先更库、再删缓存）。
     *
     * <p><b>注意：在 {@code @Transactional} 方法里调用这个方法是不安全的</b>，
     * 应该用 {@link #deleteAfterCommit}。原因见那个方法的注释 —— 这不是风格问题，
     * 是一个会导致缓存里永久残留旧值的真实时序漏洞。
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 删除缓存，但推迟到事务提交之后才真正执行
     *
     * <p><b>为什么不能直接 delete —— 这是「先更库再删缓存」里最容易踩的坑：</b>
     * {@code @Transactional} 方法体里的代码执行时，数据库事务<b>还没提交</b>。
     * 假设这样一个时序：
     * <pre>
     *   线程A                          线程B
     *   ────────────────────────────   ────────────────────────────
     *   ① 更新数据库（未提交）
     *   ② 删除缓存
     *                                  ③ 查这个 key，缓存没有
     *                                  ④ 查数据库，因为A还没提交，
     *                                     读到的是【旧值】
     *                                  ⑤ 把旧值写回缓存
     *   ⑥ 事务提交
     * </pre>
     * 第 ⑥ 步提交完之后，缓存里躺着的是<b>旧值</b>，
     * 而且没有任何机制会再去删它 —— 要等 TTL 到期（可能 30 分钟）才能恢复。
     * 这就是为什么「先删缓存再更库」「先更库再删缓存」两种顺序都说不上安全，
     * 真正解决问题的是<b>把删除这个动作推迟到提交之后</b>：
     *
     * <p>推迟之后，缓存从「A 开始改数据」到「A 提交完成」这段时间里一直有效，
     * 读到的还是旧值 —— 但那本来就是提交前的正确值，没有问题。
     * 提交之后立刻删，不存在回填旧值的窗口。
     *
     * <p>事务<b>回滚</b>时 afterCommit 不会执行 —— 这正是我们要的：
     * 数据库没变，缓存也不该动。
     *
     * @param keys 要删除的 key，可以传多个。
     *             <b>为什么支持多个</b>：一个实体变了往往要失效好几个缓存，
     *             比如站点信息改了，既要删「站点详情」也要删「该城市的站点列表」。
     *             如果只能一次删一个，调用方很容易漏掉其中一个，
     *             而漏掉的表现是「改完了但列表页还是旧的」。
     *             把它们放在一次调用里，至少让调用者在一个地方看到全部关联的 key。
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
     * <p>用在「写数据库 + 同步更新 Redis」的场景：Redis 的操作不参与数据库事务，
     * 放在事务提交前执行的话，一旦数据库回滚，Redis 那边的改动就回不来了 ——
     * 数据库说「这事没发生」，Redis 说「发生了」，两边永久不一致。
     *
     * <p>充电业务里这个模式的用处比餐饮多得多，例如：
     * 结算完成后要「更新钱包余额」+「把站点空闲枪数写回缓存」，
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
                    // ★ 这里只记日志、不往外抛，是有意为之。
                    // 事务已经提交了，此时抛异常也回滚不了数据库，
                    // 只会把异常抛给调用方，让用户看到「明明操作成功了却报错」的假失败。
                    // 缓存的过期由 TTL 兜底，宁可短暂不一致，也不能给用户一个错误的失败提示。
                    log.error("事务提交后的缓存操作失败，缓存可能不一致，等待 TTL 到期自愈", e);
                }
            }
        });
    }

    // ==================== 缓存穿透：空值缓存 ====================

    /**
     * 查询缓存，解决缓存穿透（数据库不存在的数据，缓存空值）
     *
     * <p><b>穿透是什么：</b>请求的数据在数据库里压根不存在，
     * 所以缓存永远不会命中，每次请求都落到数据库上。
     * 正常业务里这没什么，但攻击者可以用它 ——
     * 拿一堆不存在的 id 疯狂请求，缓存层完全不起作用，数据库独自承压。
     *
     * <p><b>解法是「把不存在也当一种结果缓存起来」</b>，
     * 存一个空字符串 {@code ""} 作为标记。下次同样的请求进来，
     * 看到 key 存在但值是空的，就知道「数据库里也没有」，直接返回 null，
     * 不再去查数据库。
     *
     * <p><b>这里有个细节容易写错：</b>判断「缓存命中空值」必须是
     * {@code json != null}，不能是 {@code !StringUtils.hasText(json)}。
     * 因为 {@code hasText} 对空字符串返回 false，
     * 用它判断就区分不出「key 不存在」（json == null）和
     * 「key 存在但值是空串」（防穿透标记）—— 这两个的处理方式完全不同：
     * 前者要查库，后者绝不能查库。
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

        // 1. 缓存命中且有值：直接返回
        if (StringUtils.hasText(json)) {
            return redisJsonUtil.toBean(json, type);
        }

        // 2. key 存在但值是空串：这是防穿透标记，说明数据库里也没有，直接返回 null
        if (json != null) {
            return null;
        }

        // 3. 缓存未命中（key 不存在）：查数据库
        R r = dbFallback.apply(id);

        // 4. 数据库也没有：缓存空值，防止不存在的 id 反复穿透到数据库
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
     * <p>key 物理上不设置过期时间，过期时间记录在 {@link RedisData#getExpireTime()} 里。
     * 关于这个方案本身的取舍（拿一致性换数据库不被打穿），见 {@link RedisData} 的注释。
     *
     * <p><b>TTL 为什么加随机偏移：</b>如果一批 key 是在同一时刻写入的、
     * TTL 又完全相同，它们会在同一时刻<b>集体过期</b>，
     * 重建请求在同一瞬间涌向数据库 —— 这就是缓存雪崩。
     * 加上 0~30% 的随机偏移，过期时间自然错开，重建请求也就摊平了。
     *
     * <p>在逻辑过期方案里这个偏移还有个额外作用：它让「同一批缓存同时进入
     * 逻辑过期状态」的概率变低，从而避免大量 key 同时触发抢锁重建。
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
     * <p>思路：key 物理不过期，过期后由抢到锁的线程异步重建，
     * 其他线程直接返回旧数据兜底 —— <b>谁都不等</b>。
     *
     * <p><b>和 {@link #queryWithPassThrough} 的关键差别在这里不缓存空值：</b>
     * 缓存里没有数据时直接返回 null。
     * 这意味着这个方法<b>依赖预热</b> —— 如果某个 key 从来没被写入过，
     * 那所有请求都会拿到 null，而且永远不会有线程去重建它。
     * 所以它只适用于「数据在系统启动时就能确定要缓存哪些」的场景，
     * 比如计费模板、站点基础信息。
     * 用错场景的表现是「接口一直返回 null，但数据库里明明有数据」。
     */
    public <R> R queryWithLogicalExpire(String keyPrefix, Long id, TypeReference<R> type,
                                        Function<Long, R> dbFallback, Long ttl, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 缓存不存在：逻辑过期方案不做空值缓存，直接返回 null
        if (!StringUtils.hasText(json)) {
            return null;
        }

        // 2. 反序列化 RedisData。
        // ★ 注意这里有个容易踩的点：RedisData.data 声明的是 Object，
        // 反序列化之后实际类型是 LinkedHashMap，不能直接强转成目标类型。
        // 所以要「先转回 JSON 字符串、再转成目标类型」走两趟 ——
        // 看起来笨，但这是泛型擦除下唯一可靠的做法。
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
                // ★ 双重检查：拿到锁之后必须再查一次缓存。
                // 因为「发现过期 → 抢锁」之间可能已经有别的线程重建完成了，
                // 不检查的话会白重建一次（这次重建的结果和缓存里的一样，浪费）。
                // 这个检查和单例模式里的双重检查锁定是同一个模式。
                String json2 = stringRedisTemplate.opsForValue().get(key);
                if (StringUtils.hasText(json2)) {
                    RedisData redisData2 = redisJsonUtil.toBean(json2, RedisData.class);
                    if (redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                        unlock(lockKey, token);
                        return redisJsonUtil.toBean(redisJsonUtil.toJson(redisData2.getData()), type);
                    }
                }

                // 还是过期状态：自己负责重建。
                // 开新线程异步执行，当前请求先返回旧数据，不阻塞用户 ——
                // 用户宁可要 200ms 的旧数据，也不要 2 秒的新数据。
                //
                // ★ token 必须作为参数传进去：重建在下一个线程里跑，用不了 ThreadLocal，
                // 而且这个线程的锁可能已经超时过期了，解锁时必须靠 token 比对
                // 确认这把锁还是自己的（详见 UNLOCK_SCRIPT 的注释）。
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
                // 期间所有请求都拿不到锁（虽然它们本来就返回旧数据，影响有限，
                // 但会导致这 10 秒内没有任何线程去重建缓存）
                unlock(lockKey, token);
                throw new RuntimeException("缓存重建失败", e);
            }
        }

        // 5. 没抢到锁（其他线程正在重建）或重建进行中：返回旧数据兜底
        return r;
    }

    // ==================== 分布式锁 ====================

    /**
     * 抢锁：{@code SET key value NX EX} 是原子操作，对应 Redis 的 SETNX + 过期
     *
     * <p><b>值存一个随机 UUID 而不是固定的 "1"：</b>
     * 释放锁的时候要靠这个值区分「这把锁是不是我加的」。
     * 用固定值的话所有线程的值都一样，比对就没有意义了 ——
     * 线程 A 的锁过期后被 B 抢到，A 执行删除时比对"1" == "1" 成功，
     * 于是把 B 的锁删了。
     *
     * <p>和过期时间一起通过一条 {@code SET key value NX EX} 写入，
     * 保证「抢锁」和「设过期」这两步不会被拆开 ——
     * 拆开写（先 SETNX 再 EXPIRE）的话，如果进程在两条命令之间挂了，
     * 这把锁就永远不过期，成为死锁。
     *
     * @return 抢锁成功返回本次的 token（释放锁时要原样传回），失败返回 {@code null}
     */
    public String tryLock(String key) {
        return tryLock(key, LOCK_TTL);
    }

    /**
     * 抢锁（自定义过期时间）
     *
     * <p>过期时间的权衡见 {@link #LOCK_TTL} 的注释。
     * 充电业务里需要自定义 TTL 的场景：结算任务要遍历一批订单，
     * 可能跑几十秒到几分钟，用默认的 10 秒会导致锁在业务跑完之前就过期。
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
     * <p>必须把 {@link #tryLock} 返回的 token 原样传回来 ——
     * 这里不像 Redisson 那样按线程自动绑定，是因为锁<b>可能在另一个线程里释放</b>
     * （缓存重建就是这样：请求线程抢锁、异步重建线程解锁）。
     * 这个设计是从使用场景倒推出来的，不是随手写成这样的。
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
