package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费分段（对应 {@code charging_segment} 表）
 *
 * <p> 只追加：没有 update / delete，分段是账单的原始凭据，要改只能红冲（作废重算）。
 *
 * <p> {@code uk_segment_order_start (order_id, start_time)} 是结算幂等的核心：重跑结算时重复插入
 * 相同分段会撞唯一键，而不是把金额翻倍。
 *
 * <p> {@code energy_price} / {@code service_price} 是单价快照不是外键 —— 模板规则是整体替换的，
 * 引用 {@code tariff_rule.id} 会让「电价调整不影响历史订单」依赖那张规则行不被删。
 *
 * <p> {@code amount} 单独存是为了能对账：{@code WHERE amount <> energy_fee + service_fee} 必须返回 0 行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingSegment implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long orderId;

    /** 第几段，从 1 开始。对齐 {@code BillingSegment.seq()} */
    private Integer seq;

    /** {@code 1尖峰 2高峰 3平段 4低谷} */
    private Integer periodType;

    private LocalDateTime startTime;

    /** 本段结束（不含）。 半开区间，见 {@code TariffRuleSnapshot} 的类注释 */
    private LocalDateTime endTime;

    private Integer durationSeconds;

    private BigDecimal energyKwh;

    private BigDecimal energyPrice;

    private BigDecimal servicePrice;

    private BigDecimal energyFee;

    private BigDecimal serviceFee;

    /**  恒等于 {@code energyFee + serviceFee}。见类注释 */
    private BigDecimal amount;

    /** 由调用方显式给，不标 {@code @AutoFill}：本表没有 {@code create_user} / {@code update_user} 列，标了插件会反射找不到 setter 直接抛异常 */
    private LocalDateTime createTime;
}
