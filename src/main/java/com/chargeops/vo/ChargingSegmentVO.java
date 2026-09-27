package com.chargeops.vo;

import com.chargeops.entity.ChargingSegment;
import com.chargeops.enumeration.PeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费分段（账单明细的一行）
 *
 * <p>★ 把<b>单价</b>也返回给前端。这看起来多余 —— 金额都算好了，用户看总额就行。
 * 但这是账单：用户会拿计算器核对「2.5 度 × 1.2 元 = 3 元」。只给金额的话，
 * 对不上时用户无法自己定位是哪一段错了，只能来问客服 ——
 * 而客服也得把这个查询再跑一遍。
 *
 * <p>★ {@code periodTypeLabel} 给中文（尖峰 / 高峰 / 平段 / 低谷）。
 * 分时电价的意义就在于「我知道为什么这段贵」，只给数字等于没给。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingSegmentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer seq;

    private Integer periodType;

    private String periodTypeLabel;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private BigDecimal energyKwh;

    private BigDecimal energyPrice;

    private BigDecimal servicePrice;

    private BigDecimal energyFee;

    private BigDecimal serviceFee;

    /** ★ 恒等于 {@code energyFee + serviceFee} */
    private BigDecimal amount;

    public static ChargingSegmentVO from(ChargingSegment segment) {
        return ChargingSegmentVO.builder()
                .seq(segment.getSeq())
                .periodType(segment.getPeriodType())
                .periodTypeLabel(PeriodType.of(segment.getPeriodType()).getLabel())
                .startTime(segment.getStartTime())
                .endTime(segment.getEndTime())
                .durationSeconds(segment.getDurationSeconds())
                .energyKwh(segment.getEnergyKwh())
                .energyPrice(segment.getEnergyPrice())
                .servicePrice(segment.getServicePrice())
                .energyFee(segment.getEnergyFee())
                .serviceFee(segment.getServiceFee())
                .amount(segment.getAmount())
                .build();
    }
}
