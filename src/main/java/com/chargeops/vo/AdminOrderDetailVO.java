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
 * 订单详情（管理端）
 *
 * 一、它是 {@link ChargingOrderDetailVO} 的超集，但仍然是另一个类
 *
 * <p>两个类的字段理由（金额三兄弟为什么必须同时出现、{@code segments} 和
 * {@code events} 为什么合成一个接口）见 {@code ChargingOrderDetailVO} 的类注释，
 * 这里不重复。这一侧多出来的字段只有七个，而它们全部是「运维要判断下一步做什么」
 * 所必需的：
 *
 * <table border="1">
 * <caption>管理端详情比车主端多出来的字段</caption>
 * <tr><th>字段</th><th>运维拿它做什么</th></tr>
 * <tr><td>{@code memberId} / {@code memberPhone} / {@code memberNickname}</td>
 * <td>「这是谁的单」 —— 挂账了要联系车主，退款了要通知到人。
 * 车主侧这三个是多余的（他当然知道是自己）</td></tr>
 * <tr><td>{@code stationName}</td>
 * <td>「这个单在哪」 —— 挂账/故障时要派人去现场，
 * 而 {@code stationId} 对人不构成任何信息</td></tr>
 * <tr><td>{@code frozenAmount}</td>
 * <td>「还压着多少钱」 —— 判断该催付款（钱还冻着）还是该退款
 * （钱已经扣了）的唯一直接依据</td></tr>
 * <tr><td>{@code tariffTemplateId}</td>
 * <td>「按哪套电价算的」 —— 金额对不上时的第一个怀疑对象。
 * 它在下单那一刻被冻结，所以拿它去比对现在的模板能看出
 * 「这单之后电价被调过」</td></tr>
 * <tr><td>{@code memberPackageId}</td>
 * <td>「抵扣用的是哪份权益」 —— 车主来问「我那份套餐为什么少了 30 度」
 * 时要能指回那一行</td></tr>
 * </table>
 *
 * 二、{@code events} 在管理端比在车主端重要得多 
 *
 * <p>车主看事件流水是「看热闹」；运维看它是唯一的排查依据。
 * 一笔卡在 {@code 9 异常挂账} 的订单，它「为什么变成 9」的全部答案就在
 * {@code events} 的 {@code abnormal} 那一行里（{@code detail} 字段带着
 * 具体原因：「结束电表读数小于起始读数，已转入异常挂账：起始 100，结束 80」）。
 *
 * <p> {@code operatorId} 这一侧要保留（车主端 {@code ChargingOrderEventVO}
 * 刻意去掉了它）：一笔由 {@code source = 4 运维} 改动的订单，出问题时第一个要问的
 * 就是「是谁录的读数」。没有这个字段，四个设备上报替身接口的操作就无法追溯 ——
 * 而它们收的是电表读数，等于决定了这笔单收多少钱。
 *
 * 三、不返回 {@code updateUser}
 *
 * <p>{@code charging_order.update_user} 的含义是「最后一次人工编辑这一行」，
 * 而它的取值有三种来源（用户点的 / 设备上报的 / 定时任务扫的），
 * 其中后两种在 {@code BaseContext} 为空时会写成 NULL。所以它
 * 不是一个可以依赖的审计字段，真正的答案在 {@code events} 里 ——
 * 见 {@code ChargingOrderMapper} 类注释第二节。把它返回给前端只会诱发误用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrderDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Integer status;

    private String statusLabel;

    // ---------- 归属（管理端独有） ----------

    private Long memberId;

    /** 由 Service 补齐。 可能为 null（车主行已不在） */
    private String memberPhone;

    /** 由 Service 补齐。 可能为 null */
    private String memberNickname;

    // ---------- 位置 ----------

    private Long stationId;

    /** 由 Service 补齐。 可能为 null（站点已删） */
    private String stationName;

    private Long chargerId;

    private Long connectorId;

    /**  车牌快照，不是当前车牌 */
    private String plateNo;

    // ---------- 冻结的计价依据（管理端独有） ----------

    /**  下单时冻结的计费模板 id。拿它和「现在的模板」比对能看出电价被调过 */
    private Long tariffTemplateId;

    /**  抵扣用的权益。可能为 null（没用权益、或建单时没选到） */
    private Long memberPackageId;

    // ---------- 时间 ----------

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private LocalDateTime settleTime;

    // ---------- 电量 ----------

    private BigDecimal startMeterKwh;

    private BigDecimal endMeterKwh;

    private BigDecimal totalEnergyKwh;

    private Integer startSoc;

    private Integer endSoc;

    // ---------- 金额 ----------

    private BigDecimal energyFee;

    private BigDecimal serviceFee;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    /**  当前还冻着的金额（管理端独有） */
    private BigDecimal frozenAmount;

    // ---------- 支付与退款 ----------

    private Integer payMethod;

    private String payMethodLabel;

    private LocalDateTime payTime;

    private LocalDateTime refundTime;

    private BigDecimal refundAmount;

    // ---------- 结束原因 ----------

    private Integer stopReason;

    private String stopReasonLabel;

    // ---------- 明细与历史 ----------

    private List<ChargingSegmentVO> segments;

    private List<ChargingOrderEventVO> events;

    public static AdminOrderDetailVO from(ChargingOrder order,
                                          List<ChargingSegmentVO> segments,
                                          List<ChargingOrderEventVO> events,
                                          String memberPhone,
                                          String memberNickname,
                                          String stationName) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        return AdminOrderDetailVO.builder()
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
                .tariffTemplateId(order.getTariffTemplateId())
                .memberPackageId(order.getMemberPackageId())
                .createTime(order.getCreateTime())
                .startTime(order.getStartTime())
                .endTime(order.getEndTime())
                .durationSeconds(order.getDurationSeconds())
                .settleTime(order.getSettleTime())
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
                .frozenAmount(order.getFrozenAmount())
                .payMethod(order.getPayMethod())
                .payMethodLabel(order.getPayMethod() == null
                        ? null : PayMethod.of(order.getPayMethod()).getLabel())
                .payTime(order.getPayTime())
                .refundTime(order.getRefundTime())
                .refundAmount(order.getRefundAmount())
                .stopReason(order.getStopReason())
                .stopReasonLabel(order.getStopReason() == null
                        ? null : StopReason.of(order.getStopReason()).getLabel())
                .segments(segments)
                .events(events)
                .build();
    }
}
