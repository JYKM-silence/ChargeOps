package com.chargeops.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次计费的结果：分段明细 + 四个总额。
 *
 * <p>四个总额都是从已舍入的分段加出来的，没有第二个独立算出来的数，所以
 * {@code Σ分段 == 总额} 是恒等式（见 {@link BillingEngine}）。落库时
 * {@code charging_order.total_amount} 写 {@link #totalAmount()}，不要写「总电量 × 综合单价」。
 */
public record BillingResult(
        LocalDateTime startTime,
        LocalDateTime endTime,
        List<BillingSegment> segments,
        BigDecimal totalEnergyKwh,
        BigDecimal totalEnergyFee,
        BigDecimal totalServiceFee,
        BigDecimal totalAmount) {

    /** 拷贝成不可变列表：结算后分段又被外面改掉是无法调试的状态 */
    public BillingResult {
        segments = List.copyOf(segments);
    }

    /** 分段数。{@code charging_segment} 的行数，也是批量插入的批大小 */
    public int segmentCount() {
        return segments.size();
    }
}
