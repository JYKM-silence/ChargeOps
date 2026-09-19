package com.chargeops.redis;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逻辑过期缓存的包装对象
 *
 * <p><b>它解决的是「缓存击穿」：</b>
 * 某个热点 key 在过期的一瞬间，所有并发请求都发现缓存没了，
 * 于是一起冲向数据库 —— 一个 key 的过期能引发几百个并发查询。
 *
 * <p><b>它的做法是把「过期」从 Redis 手里拿回来自己做：</b>
 * Redis 里这个 key 物理上<b>永不设置过期时间</b>，过期时间记录在下面这个
 * {@link #expireTime} 字段里。请求进来时：
 * <ol>
 *   <li>读出数据，检查 {@code expireTime}</li>
 *   <li>没过期 → 直接返回</li>
 *   <li>过期了 → 尝试抢锁，<b>只有抢到锁的那个线程</b>去查数据库重建缓存；
 *       抢不到锁的线程<b>直接返回旧数据</b>，不等、不查库</li>
 * </ol>
 * 所以数据库在任何时刻都只承受一个查询，其余请求拿到的是「稍微旧一点但可用」的数据。
 *
 * <p><b>代价是数据可能不新鲜</b>：在重建完成之前，用户看到的是上一版数据。
 * 这在充电桩场景里要分情况：
 * <ul>
 *   <li>适合 —— <b>计费模板</b>（尖峰平谷时段表，一天改一次都算频繁）、
 *       <b>套餐列表</b>。旧几分钟完全无所谓</li>
 *   <li>不适合 —— <b>枪的实时状态</b>。用户看到的必须是「此刻」这把枪空不空，
 *       返回旧数据会让他点了启动才发现被占用。这类数据应该走
 *       {@code queryWithPassThrough} + 短 TTL，或者干脆不缓存</li>
 * </ul>
 * 换句话说：逻辑过期是拿「一致性」换「数据库不被打穿」，
 * 只在你确实能接受短暂不一致时才用。
 */
@Data
public class RedisData {

    /** 缓存的实际数据 */
    private Object data;

    /** 逻辑过期时间 */
    private LocalDateTime expireTime;
}
