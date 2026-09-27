package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个计费分段 —— 与 {@code charging_segment} 表逐列对应。
 *
 * <p>字段顺序和名字对齐 DDL，只少 {@code id}（自增）、{@code order_id}（落库时补）、
 * {@code create_time}（库默认值）。{@code durationSeconds} 恒 &gt; 0（零长度分段在切分时
 * 就排除了），{@code energyKwh} 恒 &ge; 0。
 *
 * <p>{@code amount} 恒等于 {@code energyFee + serviceFee}，三者都已经是舍入后的值。
 * 电费与服务费分列是为了账单能分开显示，两者的舍入契约见 {@link BillingEngine}。
 */
public record BillingSegment(
        int seq,
        PeriodType periodType,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int durationSeconds,
        BigDecimal energyKwh,
        BigDecimal energyPrice,
        BigDecimal servicePrice,
        BigDecimal energyFee,
        BigDecimal serviceFee,
        BigDecimal amount) {
}
