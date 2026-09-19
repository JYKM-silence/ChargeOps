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
 * JSON 序列化配置
 *
 * <p>这个类要解决两件事：日期时间格式，以及「前端多传字段不要报错」。
 * 但更值得看的是<b>它的写法为什么和老库完全不同</b>。
 *
 * <h2>老库怎么做的，为什么现在不能那么做</h2>
 * 老库 {@code WebMvcConfiguration extends WebMvcConfigurationSupport}，
 * 然后覆盖 {@code extendMessageConverters}，手工 {@code new} 一个
 * {@code MappingJackson2HttpMessageConverter} 塞到 {@code converters} 的 0 号位。
 *
 * <p>那套写法有三个问题，任何一个单独出现都还能忍，凑在一起就成了一个
 * 「能跑但没人说得清为什么能跑」的状态：
 * <ol>
 *   <li><b>它覆盖的是 Spring MVC 的默认消息转换器，而不是 Jackson 的配置。</b>
 *       结果是 Socket、WebClient、RestTemplate 等**不走 MVC 的地方**用的还是
 *       另一个 ObjectMapper，日期格式和接口返回的不一样。在同一个项目里
 *       存在两个格式不同的 JSON 配置，是后面很多「奇怪的不一致」的源头</li>
 *   <li><b>老库里存在<a>两个</a> ObjectMapper。</b>
 *       {@code JacksonObjectMapper} 用 {@code yyyy-MM-dd HH:mm}（**没有秒**），
 *       而 {@code RedisJsonUtil} 的 static 块里又注册了一个模块把它改成
 *       {@code yyyy-MM-dd HH:mm:ss}。为什么要改？因为逻辑过期时间存进 Redis
 *       再取出来，秒被截掉了，缓存的过期判断会出错。
 *       这就是「两处各写一份格式」必然导致的补丁 —— 一份改了，另一份要跟着打补丁，
 *       而没人会记得还有第二处</li>
 *   <li><b>Boot 4 里 {@code MappingJackson2HttpMessageConverter} 已经换了主角。</b>
 *       它是 Jackson 2 时代的类，Bean 名称里的 2 就是证据。Boot 4 默认用
 *       Jackson 3，MVC 里的转换器是 {@code JacksonJsonHttpMessageConverter}
 *       （注意：不带 2）。还去 new 老类，等于在一个 Jackson 3 的自动配置上面
 *       又盖了一个 Jackson 2 的转换器 —— 而 Jackson 2 根本不在依赖里，
 *       这个类加载时会直接炸，或者更糟：静默地不去生效</li>
 * </ol>
 *
 * <h2>现在的做法</h2>
 * 只提供一个 {@link JsonMapperBuilderCustomizer} Bean。
 * Boot 的 Jackson 自动配置在构建 ObjectMapper 时会依次应用所有 customizer，
 * 于是：<b>MVC 用的、我们手工注入用的、Redis 序列化用的，都是同一个 ObjectMapper 实例</b>。
 * 格式只定义一次，不存在「两处不一致」的可能性 ——
 * 问题不是被修好了，是被结构消掉了。
 *
 * <p>顺带，{@code WebMvcConfiguration} 里那 10 行 {@code extendMessageConverters}
 * 可以整块删掉。
 *
 * <h2>★ Jackson 3 的一个关键细节：注解包没变</h2>
 * Jackson 3 只把 <b>core 和 databind</b> 搬到了 {@code tools.jackson}，
 * 而<b>注解仍然在 {@code com.fasterxml.jackson.annotation}</b>：
 * <pre>
 *   tools.jackson.databind.ObjectMapper        ← 变了
 *   tools.jackson.core.type.TypeReference      ← 变了
 *   com.fasterxml.jackson.annotation.JsonFormat    ← 没变
 *   com.fasterxml.jackson.annotation.JsonIgnore    ← 没变
 * </pre>
 * 所以代码里的 {@code @JsonFormat}、{@code @JsonIgnore} 一行都不用改，
 * 只有 import 了 {@code ObjectMapper} / {@code TypeReference} 的地方才需要动。
 * 迁移时按「注解不用管，databind 要改」这个原则去找，能少改一大半文件。
 */
@Configuration
public class JacksonConfiguration {

    /**
     * 日期时间格式
     *
     * <p><b>★ 为什么必须带秒。</b>老库用的是 {@code yyyy-MM-dd HH:mm}，连秒都截掉了。
     * 在餐饮场景里这个精度够了（下单时间差一秒没人关心），
     * 但充电桩场景里它是<b>实打实的 bug</b>：
     * <ul>
     *   <li>计费分段是按秒算时长的，两个分段的起止时间被截断到分钟之后，
     *       可能变成完全相同的值 —— 而 {@code charging_segment} 上有
     *       唯一索引 {@code uk(order_id, start_time)}，插入直接失败</li>
     *   <li>设备遥测的采样间隔可以小于 1 分钟，截断后同一分钟内的多条上报
     *       在接口返回里看起来时间是同一个</li>
     * </ul>
     * 数据库那边对应的是 {@code datetime(3)}（毫秒精度），
     * 所以这里的取舍是：<b>秒必须有，毫秒不暴露给前端</b> ——
     * 前端展示用不到毫秒，而少三位能让日志和接口文档都干净不少。
     */
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日期格式，用于 {@link LocalDate} */
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 时间格式，用于 {@link LocalTime}
     *
     * <p>这个类型在充电业务里不是可有可无的：{@code tariff_rule.start_time/end_time}
     * 存的就是 {@code time} 类型（尖峰平谷时段的边界），映射成 Java 的 {@code LocalTime}。
     * 没有为它注册格式的话，Jackson 会输出成 {@code 10:00:00.000} 这种带毫秒的形式 ——
     * 而计费时段恰好全部落在整点上，前端拿到的每个值尾巴上都挂一串没用的 .000。
     *
     * <p>注意这个格式<b>无法表示 {@code 24:00:00}</b>（Java 的 LocalTime 上限是 23:59:59）。
     * 数据库里那个「到当天结束」的哨兵值在 Java 侧是有专门处理的，
     * 见 tariff_rule 建表语句里的说明 —— 不要在实体类里硬套 LocalTime 去接它。
     */
    public static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer chargeOpsJsonCustomizer() {
        return builder -> {
            // 注册 JSR-310（java.time）类型的读写格式。
            //
            // Jackson 3 把 jackson-datatype-jsr310 这个独立模块**合并进了 databind**，
            // 所以依赖里不需要（也没有）那个 artifact，直接就能 import 到
            // tools.jackson.databind.ext.javatime.* 下面的序列化器。
            // 从 Jackson 2 迁过来时四处找 jackson-datatype-jsr310 是白费功夫。
            SimpleModule javaTimeModule = new SimpleModule("chargeops-java-time")
                    .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER))
                    .addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER))
                    .addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER))
                    .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER))
                    .addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER))
                    .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

            builder.addModule(javaTimeModule);

            // 前端传了 DTO 里没有的字段时不报错，忽略即可。
            //
            // 为什么要显式关掉：这是个**向后兼容**的开关。前端先发了
            // {"phone":"...","smsCode":"..."}，后来后端把 smsCode 字段删了，
            // 前端如果没同步更新，开着的服务会在反序列化时抛异常，
            // 表现为一个 400 —— 而前端同学看到的是「昨天还好好的」。
            // 关掉它，这类问题退化成「那个字段被忽略」，
            // 双方都有时间从容地更新，而不是线上直接报错。
            //
            // 代价是**拼错字段名不会被发现**：前端发 {"phome":"..."}，
            // 后端静默当成没传，然后报「手机号不能为空」，
            // 排查时容易去怀疑参数校验而不是字段名拼写。
            // 这个取舍在「前后端分离 + 前端迭代快」的项目里是划算的。
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
