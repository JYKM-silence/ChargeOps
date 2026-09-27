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
 * 订单列表项（管理端）
 *
 * 一、为什么它是另一个类，而不是给 {@link ChargingOrderVO} 加字段 
 *
 * <p>{@code ChargingOrderVO} 的 javadoc 明写着「没有 {@code memberId}、
 * {@code tariffTemplateId}、{@code frozenAmount}」，理由是那些是内部概念，
 * 暴露给车主只会让用户问「为什么冻了我 127 块」。
 *
 * <p>而管理端恰恰需要这三个：运维要回答「这是谁的单」（车主）、
 * 「用哪套电价算的」（模板）、「现在冻着多少钱」（退款/挂账的判断依据）。
 * 也就是说同一个订单、给两种人看，需要的字段是相反的。
 *
 * <p> 把它做成「一个 VO 加几个字段，车主端不返回就行」会有两个后果：
 * <ul>
 * <li>「车主端不返回」变成一句要靠人记得的话，而不是结构保证 ——
 * 而 {@code ChargingOrderVO} 现在的安全性恰恰是结构给的；</li>
 * <li>两个端的字段需求从此绑在一起，加一个管理端字段就要重新回答
 * 一遍「车主能不能看」。</li>
 * </ul>
 * 所以是两个类。重复的那十来个字段是有意的代价 ——
 * 它们各自带着不同的理由存在，而不是同一份东西抄了两遍。
 *
 * 二、{@code frozenAmount} 在这一侧必须出现
 *
 * <p>它是「这笔单还压着车主多少钱」的唯一直接答案。
 * 一个停在 {@code 4 待支付} 或 {@code 6 支付失败} 的订单，钱是冻着的 ——
 * 运维要判断「该催付款还是该挂账」，看的就是这个数。
 * 车主侧看不到它是对的，运维侧看不到它是缺一个判断依据。
 *
 * 三、{@code memberPhone} / {@code stationName} 是 Service 补齐的
 *
 * <p>不是 JOIN 出来的，理由同 {@code ChargerVO.stationName}：
 * 批量 {@code listByIds} 一次查完，避免逐行回表。
 * 它们的取值可能是 {@code null}（车主注销、站点被删）——
 * 界面上显示成「—」而不是让整行数据消失，所以这两个字段可空是设计，不是 bug。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Integer status;

    private String statusLabel;

    // ---------- 归属（管理端独有） ----------

    /** 下单的车主。 车主端列表里没有这个字段 */
    private Long memberId;

    /** 由 Service 批量补齐。 可能为 null（车主行已不在） */
    private String memberPhone;

    /** 由 Service 批量补齐。 可能为 null */
    private String memberNickname;

    // ---------- 位置 ----------

    private Long stationId;

    /** 由 Service 批量补齐。 可能为 null（站点已删） */
    private String stationName;

    private Long chargerId;

    private Long connectorId;

    /**  车牌快照，不是当前车牌 */
    private String plateNo;

    // ---------- 时间 ----------

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**  结算完成时间。支付失败兜底扫描的账龄判据就是它 */
    private LocalDateTime settleTime;

    // ---------- 电量与金额 ----------

    private BigDecimal totalEnergyKwh;

    /** 应付 = Σ分段金额。 未结算时为 null */
    private BigDecimal totalAmount;

    /** 权益抵扣。 未结算时为 null */
    private BigDecimal discountAmount;

    /** 实付 = 应付 − 抵扣。 未结算时为 null */
    private BigDecimal payAmount;

    /**  当前还冻着的金额。结算/取消/挂账之后是 0.00，未结算且钱冻着时是冻结额 */
    private BigDecimal frozenAmount;

    public static AdminOrderVO from(ChargingOrder order) {
        return from(order, null, null, null);
    }

    /**
     * @param memberPhone  车主手机号，查不到传 null
     * @param memberNickname 车主昵称，查不到传 null
     * @param stationName  站点名，查不到传 null
     */
    public static AdminOrderVO from(ChargingOrder order,
                                    String memberPhone,
                                    String memberNickname,
                                    String stationName) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        return AdminOrderVO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(status.getCode())
                .statusLabel(status.getLabel())
                .memberId(order.getMemberId())
                .memberPhone(memberPhone)
                .memberNickname(memberNickname)
                .stationId(order.getStationId())
                .stationName(stationName)
                .chargerId(order.getChargerId())
                .connectorId(order.getConnectorId())
                .plateNo(order.getPlateNo())
                .createTime(order.getCreateTime())
                .startTime(order.getStartTime())
                .endTime(order.getEndTime())
                .settleTime(order.getSettleTime())
                .totalEnergyKwh(order.getTotalEnergyKwh())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .payAmount(order.getPayAmount())
                .frozenAmount(order.getFrozenAmount())
                .build();
    }
}
