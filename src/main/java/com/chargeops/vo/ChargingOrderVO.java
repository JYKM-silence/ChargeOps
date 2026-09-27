package com.chargeops.vo;

import com.chargeops.entity.ChargingOrder;
import com.chargeops.enumeration.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表项
 *
 * <p> {@code status} 同时给出数字和中文标签，理由同 {@code WalletTransactionVO}：
 * 前端不该自己维护一份「1=待启动」的映射 —— 那份映射一旦和枚举漂移，
 * 界面上会出现用户看不懂的数字，而且不报错。
 *
 * <p> 没有 {@code memberId}、{@code tariffTemplateId}、{@code frozenAmount}。
 * 列表是给车主看的，冻结金额和计费模板是内部概念，暴露它们只会让用户问
 * 「为什么冻了我 127 块」—— 而那笔钱在结算后会精确地按实付扣除。
 *
 * <p> {@code plateNo} 读的是订单上的快照列，不是 JOIN {@code vehicle}
 * 拿的当前车牌。见 {@code ChargingOrder.plateNo} 的说明。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Integer status;

    private String statusLabel;

    private Long stationId;

    private String plateNo;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal totalEnergyKwh;

    /** 应付。 未结算时为 null */
    private BigDecimal totalAmount;

    /** 实付 = 应付 − 优惠。 未结算时为 null */
    private BigDecimal payAmount;

    public static ChargingOrderVO from(ChargingOrder order) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        return ChargingOrderVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(status.getCode())
                .statusLabel(status.getLabel())
                .stationId(order.getStationId())
                .plateNo(order.getPlateNo())
                .createTime(order.getCreateTime())
                .startTime(order.getStartTime())
                .endTime(order.getEndTime())
                .totalEnergyKwh(order.getTotalEnergyKwh())
                .totalAmount(order.getTotalAmount())
                .payAmount(order.getPayAmount())
                .build();
    }
}
