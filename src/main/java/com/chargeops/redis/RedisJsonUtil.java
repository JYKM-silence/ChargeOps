package com.chargeops.redis;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 序列化：对象 ↔ JSON 字符串
 *
 * <p><b>为什么存 JSON 字符串而不是 Java 序列化：</b>
 * 用 {@code RedisTemplate<String, Object>} 配 JDK 序列化的话，
 * Redis 里存的是二进制，{@code GET} 出来是一堆乱码 ——
 * 线上排查时想看「这个用户的会话里到底存了什么」都做不到。
 * 存 JSON 则是肉眼可读的，这对一个需要经常上 Redis 看数据的项目来说，
 * 价值远大于省下的那点序列化开销。
 *
 * <p><b>★ 和老库最关键的区别：不再自己 new 一个 ObjectMapper。</b>
 *
 * <p>老库的 {@code RedisJsonUtil} 里有一个 {@code private static final ObjectMapper}，
 * 它继承自项目的 {@code JacksonObjectMapper}（格式 {@code yyyy-MM-dd HH:mm}），
 * 然后在一个 static 块里又注册了一个模块，把 LocalDateTime 改成
 * {@code yyyy-MM-dd HH:mm:ss}。那段代码的注释写着：
 * <blockquote>存到 Redis 的逻辑过期时间会被截断秒级，这里覆盖成秒级精度</blockquote>
 * 也就是说 —— <b>项目里长期存在两个日期格式不同的 ObjectMapper，
 * 其中一个是为了给另一个打补丁而存在的</b>。任何一处改了格式，
 * 都要记得去另一处同步，而「记得」是不可靠的。
 *
 * <p>现在的做法是直接注入 Boot 自动配置好的那个 {@link ObjectMapper}。
 * 它已经被 {@code JacksonConfiguration} 里的 customizer 配置过了，
 * 格式是 {@code yyyy-MM-dd HH:mm:ss}（本来就带秒）。
 * 于是这里的 static 块整块消失，也不再需要「补丁」这个概念。
 *
 * <p>代价是这个类从静态工具变成了 Spring Bean，使用它的地方要注入。
 * 但 {@code CacheClient} 本来就是 Bean，多注入一个依赖没有任何额外成本 ——
 * 而换来的是「JSON 格式在整个应用里只有一个定义」。
 *
 * <p><b>Jackson 3 的另一个便宜：不用写 try/catch 了。</b>
 * Jackson 2 的 {@code writeValueAsString} 抛受检的 {@code JsonProcessingException}，
 * 所以老代码里每个方法都得包一层 try/catch 再转成 RuntimeException。
 * Jackson 3 的 {@code JacksonException} 继承自 {@code RuntimeException}，
 * 序列化失败会自己往上冒，由全局异常处理器兜住。
 * 上面那层 try/catch 的唯一效果其实是把原始异常包一层，
 * 反而让堆栈里多了一层没有信息量的「序列化为JSON失败」。
 */
@Component
public class RedisJsonUtil {

    private final ObjectMapper objectMapper;

    public RedisJsonUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对象转 JSON 字符串
     *
     * <p>失败时抛出的 {@code JacksonException} 是运行时异常，会一路冒到
     * {@code GlobalExceptionHandler}。这是有意的：写入缓存失败说明
     * 有个对象的结构是 Jackson 处理不了的（比如循环引用），
     * 这是个<b>编程错误</b>而不是运行时偶发故障，应该让它明显地失败，
     * 而不是吞掉之后让缓存静默地不生效 ——
     * 后者会表现成「数据库压力莫名变大」，很难联想到是序列化的问题。
     */
    public String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    /**
     * JSON 字符串转对象
     */
    public <T> T toBean(String json, Class<T> clazz) {
        return objectMapper.readValue(json, clazz);
    }

    /**
     * JSON 字符串转带泛型的对象
     *
     * <p>例如 {@code List<StationVO>} 这种类型，{@code Class<List>} 拿不到元素类型，
     * 必须用 {@link TypeReference} 保留泛型信息：
     * <pre>
     *   redisJsonUtil.toBean(json, new TypeReference&lt;List&lt;StationVO&gt;&gt;() {})
     * </pre>
     * 注意末尾那对空花括号不能少 —— 它不是语法噪声，
     * 而是在创建 TypeReference 的<b>匿名子类</b>。
     * 泛型信息只存在于子类的签名的元数据里，直接 {@code new TypeReference<>()}
     * 拿到的是一个擦除后的类型，反序列化出来全是 LinkedHashMap。
     */
    public <T> T toBean(String json, TypeReference<T> typeReference) {
        return objectMapper.readValue(json, typeReference);
    }
}
