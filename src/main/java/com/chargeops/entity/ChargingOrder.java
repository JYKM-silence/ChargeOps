package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电订单（对应 {@code charging_order} 表）
 *
 * <p> {@code plate_no} / {@code tariff_template_id} / {@code member_package_id} / {@code frozen_amount}
 * 四列都是快照：电价会调、车牌会改、权益会被别单用掉、预估金额是启动时算的，结算时去查当前值
 * 会让历史订单静默变样。{@code plate_no} 尤其要读这一列，不要 JOIN {@code vehicle}。
 *
 * <p> {@code total_amount} 必须写 {@code BillingResult.totalAmount()}（已舍入分段金额之和），
 * 用「总电量 × 综合单价」重算会偶尔差一分，且事后无法还原它当初是怎么算的。
 *
 * <p> 幂等判据是 {@code status}，不是 {@code settle_time}；状态流转的 UPDATE 不标 {@code @AutoFill} ——
 * 那是条件更新，影响行数有业务含义（0 = 被别人抢先了），且会把「设备上报」写成登录用户名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private Long memberId;

    /**  可空：没有绑定车辆也能充电（扫码即充） */
    private Long vehicleId;

    /**  车牌快照。读这里，不要 JOIN {@code vehicle} */
    private String plateNo;

    private Long stationId;

    private Long chargerId;

    private Long connectorId;

    /** 冻结的持有权益：{@code member_package.id}，不是 {@code charge_package.id}；{@code NULL} = 余额直充 */
    private Long memberPackageId;

    /**  冻结的计费模板，结算按这个 id 计价，不查 {@code station_tariff} */
    private Long tariffTemplateId;

    /** {@code 1待启动 2充电中 3待结算 4待支付 5已支付 6支付失败 7已退款 8已取消 9异常挂账 */
    private Integer status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private BigDecimal startMeterKwh;

    private BigDecimal endMeterKwh;

    /**  以电表差为准（{@code end_meter_kwh - start_meter_kwh}），不是设备报的累计值 */
    private BigDecimal totalEnergyKwh;

    private Integer startSoc;

    private Integer endSoc;

    /** 电费合计 = Σ 分段电费 */
    private BigDecimal energyFee;

    /** 服务费合计 = Σ 分段服务费 */
    private BigDecimal serviceFee;

    /**  应付 = {@code energyFee + serviceFee}，绝不能重算 */
    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    /**  启动时冻结的预估金额。结算时先全额解冻，再按 {@code payAmount} 扣 */
    private BigDecimal frozenAmount;

    /** 结算后退回余额的部分（结算失败时剩余冻结的红冲依据） */
    private BigDecimal refundAmount;

    /** {@code 1余额 2微信 3支付宝}。 本项目只有 1 能走通 */
    private Integer payMethod;

    private LocalDateTime payTime;

    /** 第三方支付流水号。 余额支付恒为 NULL */
    private String transactionId;

    private LocalDateTime refundTime;

    /** {@code 1用户主动 2充满自停 3余额不足 4设备故障 5超时 6运维干预} */
    private Integer stopReason;

    /**  结算完成时间。它是事实记录，幂等判据是 status */
    private LocalDateTime settleTime;

    private String remark;

    /** 状态流转走条件更新，这一列留给资料编辑类场景 */
    private Integer version;

    // ---------- 审计四列 ----------

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
