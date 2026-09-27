package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电站（对应 {@code station} 表）
 *
 * <h2>★ 一、{@code openTime} / {@code closeTime} 用 {@link String} 而不是 {@code LocalTime}</h2>
 *
 * <p>因为取值里存在 <b>{@code '24:00:00'}</b>，而 {@code LocalTime} 的上限是
 * {@code 23:59:59.999999999}，<b>装不下</b>。更本质的原因是
 * <b>MySQL 的 {@code TIME} 不是「一天中的时刻」而是有符号时长</b>
 * （{@code -838:59:59} 到 {@code 838:59:59}）—— 映射成 {@code LocalTime} 是范畴错误。
 *
 * <p>不用 {@code LocalTime.MAX} 当哨兵：它会序列化成 {@code "23:59:59"}，
 * 界面上就显示「本站 23:59:59 关门」这个迟早被人「修正」掉的谎言。
 * 也不用 {@code Integer} 分钟数：那会把「分钟」这个单位推到 API、日志、
 * 前端表单的每一处，而那些地方想看的都是 {@code "08:00:00"}
 * （转换见 {@link com.chargeops.utils.TimeCodec}）。
 *
 * <h2>★ 二、{@code close_time} 不能早于 {@code open_time} —— 跨夜营业表达不了</h2>
 *
 * <p>这是 DDL 的限制不是疏漏：没有「次日」这个标记位，
 * {@code 22:00 - 06:00} 只能靠改存储方式（营业时段表或标记列）才能表达，
 * 而那个改动应该由真实需求驱动，不是现在凭空加。
 *
 * <h2>★ 三、经纬度用 {@link BigDecimal}，绝不用 {@code double}</h2>
 *
 * <p>列类型 {@code decimal(10,6)}（约 0.11 米精度）。{@code double} 的二进制浮点
 * 无法精确表示大多数十进制小数，写进去再读出来会漂移 —— 在经纬度上是<b>几十米</b>
 * 的误差，表现为「导航把用户导到了马路对面的小区」。
 *
 * <h2>★ 四、{@code status} 有 3 个值，不要复用 {@code StatusConstant}</h2>
 *
 * <p>{@code 0停用 1启用 2维护中}。把「维护中」硬塞进 DISABLE 的语义里，
 * 读代码的人就再也无法从常量名看出真实含义。用
 * {@link com.chargeops.enumeration.StationStatus}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Station implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 站点编码，对外可见。唯一索引 {@code uk_station_code} */
    private String code;

    private String name;

    /** 运营商 */
    private String operator;

    private String province;

    private String city;

    private String district;

    private String address;

    /** 经度。{@code decimal(10,6)}，★ 绝不用 {@code double} */
    private BigDecimal longitude;

    /** 纬度。{@code decimal(10,6)}，★ 绝不用 {@code double} */
    private BigDecimal latitude;

    private String servicePhone;

    /**
     * 营业开始时间，形如 {@code "08:00:00"}。★ 是 {@code String} 不是 {@code LocalTime}
     *
     * <p>入库前由 Service 调 {@code TimeCodec.normalize} 补成 {@code "HH:mm:ss"}，
     * 免得库里同时存在 {@code "08:00"} 和 {@code "08:00:00"} 两种写法。
     */
    private String openTime;

    /**
     * 营业结束时间。{@code "24:00:00"} 表示营业到当天结束
     *
     * <p>★ 必须严格晚于 {@link #openTime}（按分钟数比较），所以表达不了跨夜营业 ——
     * 原因见类注释第二节。
     */
    private String closeTime;

    /** 停车费，元/小时 */
    private BigDecimal parkingFee;

    /**
     * {@code 0停用 1启用 2维护中}
     *
     * <p>★ 用 {@link Integer} 而不是 {@code int}：参与动态 SQL 的
     * {@code <if test="status != null">}。声明成 {@code int} 就永远非 null，
     * 「不传 status」和「传 status=0（停用）」在 SQL 里无法区分，
     * 而后者是合法的业务值。
     */
    private Integer status;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，setter 命名必须和 AutoFillConstant 一致
    //
    // 本表四列齐全，所以 StationMapper 的 insert/update 可以标 @AutoFill。
    // ★ 对照 Connector：那张表少 create_user/update_user 两列，标了会抛异常

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
