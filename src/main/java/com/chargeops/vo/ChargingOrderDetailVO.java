package com.chargeops.vo;

import com.chargeops.entity.ChargingOrder;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.enumeration.PayMethod;
import com.chargeops.enumeration.StopReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情
 *
 * <p> 金额三兄弟在这里必须同时出现，含义：{@code totalAmount} 应付 = Σ分段金额
 * （一分不多一分不少）、{@code discountAmount} 权益抵扣、{@code payAmount} 实付 =
 * 应付 − 抵扣（用户实际被扣的钱）。只给 {@code payAmount} 的话，用了权益的用户会算不清
 * 「我明明充了 30 度，为什么只扣了 12 块」—— 那看起来像系统算错了，而不是权益生效了。
 *
 * <p> {@code segments} 和 {@code events} 都带在这里，因为它们合起来才是
 * 「这一笔是怎么走到今天的」的完整答案。分成两个接口的话，用户看到金额不对时
 * 要点两次，而且两次之间数据可能已经变了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrderDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Integer status;

    private String statusLabel;

    // ---------- 位置与车辆（全部是快照） ----------

    private Long stationId;

    private Long chargerId;

    private Long connectorId;

    /**  车牌快照，不是当前车牌 */
    private String plateNo;

    // ---------- 时间 ----------

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    // ---------- 电量 ----------

    private BigDecimal startMeterKwh;

    private BigDecimal endMeterKwh;

    private BigDecimal totalEnergyKwh;

    private Integer startSoc;

    private Integer endSoc;

    // ---------- 金额（三兄弟，见类注释） ----------

    private BigDecimal energyFee;

    private BigDecimal serviceFee;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    // ---------- 支付 ----------

    private Integer payMethod;

    private String payMethodLabel;

    private LocalDateTime payTime;

    private LocalDateTime refundTime;

    private BigDecimal refundAmount;

    // ---------- 结束原因 ----------

    private Integer stopReason;

    private String stopReasonLabel;

    private LocalDateTime settleTime;

    // ---------- 明细与历史 ----------

    private List<ChargingSegmentVO> segments;

    private List<ChargingOrderEventVO> events;

    public static ChargingOrderDetailVO from(ChargingOrder order,
                                             List<ChargingSegmentVO> segments,
                                             List<ChargingOrderEventVO> events) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        return ChargingOrderDetailVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(status.getCode())
                .statusLabel(status.getLabel())
                .stationId(order.getStationId())
                .chargerId(order.getChargerId())
                .connectorId(order.getConnectorId())
                .plateNo(order.getPlateNo())
                .createTime(order.getCreateTime())
                .startTime(order.getStartTime())
                .endTime(order.getEndTime())
                .durationSeconds(order.getDurationSeconds())
                .startMeterKwh(order.getStartMeterKwh())
                .endMeterKwh(order.getEndMeterKwh())
                .totalEnergyKwh(order.getTotalEnergyKwh())
                .startSoc(order.getStartSoc())
                .endSoc(order.getEndSoc())
                .energyFee(order.getEnergyFee())
                .serviceFee(order.getServiceFee())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .payAmount(order.getPayAmount())
                .payMethod(order.getPayMethod())
                .payMethodLabel(order.getPayMethod() == null
                        ? null : PayMethod.of(order.getPayMethod()).getLabel())
                .payTime(order.getPayTime())
                .refundTime(order.getRefundTime())
                .refundAmount(order.getRefundAmount())
                .stopReason(order.getStopReason())
                .stopReasonLabel(order.getStopReason() == null
                        ? null : StopReason.of(order.getStopReason()).getLabel())
                .settleTime(order.getSettleTime())
                .segments(segments)
                .events(events)
                .build();
    }
}
