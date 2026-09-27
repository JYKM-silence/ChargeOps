package com.chargeops.redis;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 序列化：对象 ↔ JSON 字符串
 *
 * <p> 用注入的 {@link ObjectMapper}，不要自己 new —— 项目里出现两个日期格式不同的 ObjectMapper 时，
 * 「改一处记得同步另一处」是不可靠的。
 */
@Component
public class RedisJsonUtil {

    private final ObjectMapper objectMapper;

    public RedisJsonUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 序列化失败是编程错误（比如循环引用）不是偶发故障，所以不吞异常 —— 吞掉的话缓存静默不生效，表现成「数据库压力莫名变大」 */
    public String toJson(Object obj) {
        return objectMapper.writeValueAsString(obj);
    }

    public <T> T toBean(String json, Class<T> clazz) {
        return objectMapper.readValue(json, clazz);
    }

    /**
     * JSON 转带泛型的对象，如 {@code new TypeReference<List<StationVO>>() {}}。末尾那对空花括号不能少：
     * 泛型信息只存在于匿名子类签名的元数据里，直接 {@code new TypeReference<>()} 拿到的是擦除后的类型。
     */
    public <T> T toBean(String json, TypeReference<T> typeReference) {
        return objectMapper.readValue(json, typeReference);
    }
}
