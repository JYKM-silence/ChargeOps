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
 * <p>{@code openTime} / {@code closeTime} 用 {@link String} 而不是 {@code LocalTime}：取值里有
 * {@code '24:00:00'}，装不下（MySQL 的 {@code TIME} 本来也是有符号时长）。
 *
 * <p>{@code close_time} 必须严格晚于 {@code open_time}（按分钟数比较），表达不了跨夜营业。
 *
 * <p>经纬度是 {@link BigDecimal}（{@code decimal(10,6)}），绝不用 {@code double}：
 * 二进制浮点写进去再读出来会漂移 —— 在经纬度上是几十米误差。
 *
 * <p>{@code status} 是 {@code 0停用 1启用 2维护中}，见 {@link com.chargeops.enumeration.StationStatus}。
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

    /** 经度。{@code decimal(10,6)}， 绝不用 {@code double} */
    private BigDecimal longitude;

    /** 纬度。{@code decimal(10,6)}， 绝不用 {@code double} */
    private BigDecimal latitude;

    private String servicePhone;

    /** 营业开始时间，形如 {@code "08:00:00"}；入库前由 Service 调 {@code TimeCodec.normalize}
     * 补成 {@code "HH:mm:ss"}，免得库里同时存在 {@code "08:00"} 和 {@code "08:00:00"} */
    private String openTime;

    /** 营业结束时间，{@code "24:00:00"} 表示营业到当天结束；必须严格晚于 {@link #openTime} */
    private String closeTime;

    /** 停车费，元/小时 */
    private BigDecimal parkingFee;

    /** {@code 0停用 1启用 2维护中}；用 {@link Integer} 而不是 {@code int}，
     * 「不传 status」和「传 status=0（停用）」在动态 SQL 里才分得开 */
    private Integer status;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充，setter 命名必须和 AutoFillConstant 一致
    // 本表四列齐全，StationMapper 的 insert/update 可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
