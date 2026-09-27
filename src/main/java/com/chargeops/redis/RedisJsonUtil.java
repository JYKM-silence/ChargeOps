package com.chargeops.redis;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 序列化：对象 ↔ JSON 字符串
 *
 * <p>存 JSON 字符串而不是 JDK 序列化，是为了让 Redis 里的数据肉眼可读 ——
 * 线上排查时能直接看到存了什么，这对经常要上 Redis 看数据的项目，
 * 价值大于省下的那点序列化开销。
 *
 * <p><b>★ 用注入的 Boot 自动配置 {@link ObjectMapper}，不要自己 new。</b>
 * 它已经被 {@code JacksonConfiguration} 的 customizer 配过，
 * 格式 {@code yyyy-MM-dd HH:mm:ss}。自己另建一个的话，项目里就会有两个
 * 日期格式不同的 ObjectMapper，改一处要记得同步另一处，而「记得」不可靠。
 * 代价是这个类从静态工具变成 Spring Bean —— 调用方本来也是 Bean，没有额外成本。
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
     * <p>Jackson 3 抛的 {@code JacksonException} 是运行时异常，会一路冒到
     * {@code GlobalExceptionHandler}。这是有意的：序列化失败说明有个对象的结构
     * Jackson 处理不了（比如循环引用），是<b>编程错误</b>不是偶发故障，应该明显失败；
     * 吞掉的话缓存静默不生效，表现成「数据库压力莫名变大」，很难联想到序列化。
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
     * JSON 字符串转带泛型的对象，如 {@code new TypeReference<List<StationVO>>() {}}
     *
     * <p><b>★ 末尾那对空花括号不能少</b> —— 它是在创建 TypeReference 的
     * <b>匿名子类</b>：泛型信息只存在于子类签名的元数据里，
     * 直接 {@code new TypeReference<>()} 拿到的是擦除后的类型，反序列化出来全是 LinkedHashMap。
     */
    public <T> T toBean(String json, TypeReference<T> typeReference) {
        return objectMapper.readValue(json, typeReference);
    }
}
