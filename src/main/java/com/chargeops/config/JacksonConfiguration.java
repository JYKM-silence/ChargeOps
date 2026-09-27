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
 * JSON 序列化配置：日期时间格式，以及「前端多传字段不要报错」
 *
 * <h2>★ 只提供一个 {@link JsonMapperBuilderCustomizer} Bean，不要去动消息转换器</h2>
 *
 * <p>往 MVC 里手塞一个 Jackson 转换器（老库的 {@code extendMessageConverters} 写法）
 * 有两个具体的后果：Socket、WebClient、RestTemplate 这些<b>不走 MVC 的地方</b>
 * 用的仍是另一个 ObjectMapper，日期格式和接口返回的不一致；而在 Boot 4 里
 * 那个类叫 {@code MappingJackson2HttpMessageConverter}（Jackson 2 时代的），
 * 压在一个 Jackson 3 的自动配置上 —— Jackson 2 根本不在依赖里，
 * 加载时要么直接炸，要么更糟：静默不生效。
 *
 * <p>用 customizer 的话，Boot 构建 ObjectMapper 时会依次应用它，于是
 * <b>MVC 用的、手工注入用的、Redis 序列化用的都是同一个实例</b>，
 * 格式只定义一次，不存在「两处不一致」的可能性。
 *
 * <p>★ Jackson 3 只把 <b>core 和 databind</b> 搬到了 {@code tools.jackson}，
 * <b>注解仍在 {@code com.fasterxml.jackson.annotation}</b> —— 代码里的
 * {@code @JsonFormat}、{@code @JsonIgnore} 一行都不用改，只有 import
 * {@code ObjectMapper} / {@code TypeReference} 的地方才需要动。
 */
@Configuration
public class JacksonConfiguration {

    /**
     * 日期时间格式
     *
     * <p><b>★ 秒必须有（{@code yyyy-MM-dd HH:mm} 在充电场景里是实打实的 bug）：</b>
     * 计费分段按秒算时长，起止时间截断到分钟后两个分段可能变成完全相同的值 ——
     * 而 {@code charging_segment} 上有唯一索引 {@code uk(order_id, start_time)}，
     * 插入直接失败。设备遥测的采样间隔也可能小于 1 分钟。
     *
     * <p>库里对应 {@code datetime(3)}，这里的取舍是<b>秒要有、毫秒不暴露给前端</b>
     * （前端展示用不到毫秒，少三位让日志和接口文档都干净）。
     */
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日期格式，用于 {@link LocalDate} */
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 时间格式，用于 {@link LocalTime}
     *
     * <p>{@code tariff_rule.start_time/end_time} 存的就是 {@code time} 类型
     * （尖峰平谷时段边界），映射成 {@code LocalTime}。不注册格式的话 Jackson 会输出
     * {@code 10:00:00.000} 带毫秒的形式，而计费时段全部落在整点上。
     *
     * <p><b>★ 这个格式表示不了 {@code 24:00:00}</b>（LocalTime 上限是 23:59:59）。
     * 库里那个「到当天结束」的哨兵值在 Java 侧有专门处理，
     * 见 tariff_rule 建表语句 —— 不要在实体类里硬套 {@code LocalTime} 去接它。
     */
    public static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer chargeOpsJsonCustomizer() {
        return builder -> {
            // 注册 JSR-310（java.time）类型的读写格式。
            // Jackson 3 把 jackson-datatype-jsr310 合并进了 databind，
            // 依赖里不需要（也没有）那个 artifact，直接 import
            // tools.jackson.databind.ext.javatime.* 即可。
            SimpleModule javaTimeModule = new SimpleModule("chargeops-java-time")
                    .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER))
                    .addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER))
                    .addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER))
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER))
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER))
                    .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

            builder.addModule(javaTimeModule);

            // 前端传了 DTO 里没有的字段时忽略，不报错。
            // 这是个**向后兼容**开关：后端删掉某个字段而前端没同步更新时，
            // 不关它就是一个 400，前端同学看到的是「昨天还好好的」。
            // 代价是**拼错字段名不会被发现**（发 {"phome":"..."} 会被当成没传，
            // 最后报「手机号不能为空」，容易去怀疑参数校验而不是拼写）。
            // 在前后端分离 + 前端迭代快的项目里这个取舍划算。
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
