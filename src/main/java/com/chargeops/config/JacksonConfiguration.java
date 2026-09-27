package com.chargeops.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化配置：日期时间格式，以及忽略前端多传的字段
 *
 * <p> 只注册 {@link JsonMapperBuilderCustomizer}，不要去动消息转换器：手工塞一个 Jackson 转换器
 * 只作用于 MVC，RestTemplate 这些不走 MVC 的地方用的仍是另一个 ObjectMapper，日期格式会和接口返回的不一致。
 */
@Configuration
public class JacksonConfiguration {

    /**
     * 日期时间格式。秒必须有：分段时长按秒算，截断到分钟会让两个分段的 start_time 变成同一个值，
     * 而 {@code charging_segment} 上有唯一索引 {@code uk(order_id, start_time)}，插入直接失败。
     */
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日期格式，用于 {@link LocalDate} */
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 时间格式，用于 {@link LocalTime}。它表示不了 {@code 24:00:00}，库里「到当天结束」的哨兵值不要用 LocalTime 去接 */
    public static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer chargeOpsJsonCustomizer() {
        return builder -> {
            // Jackson 3 把 jackson-datatype-jsr310 并进了 databind，直接用 tools.jackson.databind.ext.javatime.*
            SimpleModule javaTimeModule = new SimpleModule("chargeops-java-time")
                    .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER))
                    .addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER))
                    .addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER))
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER))
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER))
                    .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

            builder.addModule(javaTimeModule);

            // 忽略 DTO 里没有的字段，后端删字段而前端没同步时不至于 400；代价是拼错字段名也发现不了
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
