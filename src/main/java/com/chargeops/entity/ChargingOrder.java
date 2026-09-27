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
 * <h2>★ 一、四个「快照列」是这张表的核心设计，不是冗余</h2>
 *
 * <pre>
 *   plate_no            车牌快照     车辆可被删除或改牌，订单必须保留当时值
 *   member_package_id   ★ 冻结的权益  指向 member_package.id（不是 SKU）
 *   tariff_template_id  ★ 冻结的计费模板
 *   frozen_amount       启动时冻结的预估金额
 * </pre>
 *
 * <p><b>{@code tariff_template_id} 是这四列里最重要的。</b>
 * 电价是电网按地区发布的、会调整。如果订单在<b>结算时</b>才去查
 * 「这个站点现在用哪个价」，电价一调，已经在充的订单会被按新价结算 ——
 * 用户看到的金额和启动时预估的不一样，而且没法解释。
 * 冻结模板 id 之后，这笔订单用哪个价是<b>一个存下来的事实</b>，
 * 不依赖查询时刻的 {@code station_tariff} 状态。
 *
 * <p>★ <b>{@code plate_no} 要读这一列，不要 JOIN {@code vehicle} 取车牌。</b>
 * 车辆被删掉之后 JOIN 会返回 NULL，改了牌之后 JOIN 会返回<b>新车牌</b>
 * （历史订单显示的内容被改写）。两者都是静默的，
 * 只有把订单导出来和用户对质时才会发现。
 *
 * <h2>★ 二、金额三兄弟的定义，一个都不能混</h2>
 *
 * <pre>
 *   total_amount    应付 = 电费 + 服务费        ← BillingEngine.totalAmount()，绝不重算
 *   discount_amount 优惠（权益抵扣）
 *   pay_amount      实付 = 应付 − 优惠
 * </pre>
 *
 * <p>★ {@code total_amount} <b>必须</b>写 {@code BillingResult.totalAmount()}
 * ——那个值定义为「已舍入分段金额之和」（见
 * {@link com.chargeops.billing.BillingResult} 的类注释）。
 * 用「总电量 × 综合单价」重算会偶尔差一分，而那种差异出现在财务报表上时，
 * 已经没人能还原它当初是怎么算的。
 *
 * <h2>★ 三、幂等判据是 {@code status}，不是 {@code settle_time}</h2>
 *
 * <p>★ 见 {@link com.chargeops.enumeration.OrderStatus} 的类注释第一节。
 * {@code settle_time} 是「结算发生在什么时候」这个<b>事实</b>，
 * 它反过来可以用来发现「结算了但 status 没走」这类不一致。
 *
 * <h2>★ 四、审计四列齐全 → 可标 {@code @AutoFill}，但状态流转的 UPDATE 不标</h2>
 *
 * <p>那些语句是条件更新，影响行数有业务含义（0 = 被别人抢先了），
 * 而 {@code @AutoFill} 会往 SET 子句里塞 {@code update_user} ——
 * 一个运维触发的流转和一个用户触发的流转走的是<b>不同来源</b>，
 * 让插件统一填成 {@code BaseContext} 里的当前登录人，
 * 会把「设备上报」写成某个用户的名字。见 {@code ChargingOrderMapper}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 业务单号，形如 {@code CO202609180001}。唯一索引 {@code uk_order_no} */
    private String orderNo;

    private Long memberId;

    /** ★ 可空：没有绑定车辆也能充电（扫码即充） */
    private Long vehicleId;

    /** ★ 车牌<b>快照</b>。见类注释第一节：读这里，不要 JOIN {@code vehicle} */
    private String plateNo;

    private Long stationId;

    private Long chargerId;

    private Long connectorId;

    /**
     * ★ 冻结的持有权益：{@code member_package.id}，<b>不是</b> {@code charge_package.id}
     *
     * <p>SKU 是「150 度电包」这个商品；持有权益是「张三手上那份，
     * 还剩 87 度，2026-12-18 到期」。订单要冻结的是后者 ——
     * 冻结 SKU 的话，同一份权益会被两笔订单同时用掉。
     *
     * <p>{@code NULL} = 余额直充，没有用权益。
     */
    private Long memberPackageId;

    /** ★ 冻结的计费模板。见类注释第一节：结算按这个 id 计价，不查 {@code station_tariff} */
    private Long tariffTemplateId;

    /** {@code 1待启动 2充电中 3待结算 4待支付 5已支付 6支付失败 7已退款 8已取消 9异常挂账 */
    private Integer status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private BigDecimal startMeterKwh;

    private BigDecimal endMeterKwh;

    /** ★ 以<b>电表差</b>为准（{@code end_meter_kwh - start_meter_kwh}），不是设备报的累计值 */
    private BigDecimal totalEnergyKwh;

    private Integer startSoc;

    private Integer endSoc;

    /** 电费合计 = Σ 分段电费 */
    private BigDecimal energyFee;

    /** 服务费合计 = Σ 分段服务费 */
    private BigDecimal serviceFee;

    /** ★ 应付 = {@code energyFee + serviceFee}，绝不能重算。见类注释第二节 */
    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    /** ★ 实付 = 应付 − 优惠 */
    private BigDecimal payAmount;

    /** ★ 启动时冻结的预估金额。结算时先全额解冻，再按 {@code payAmount} 扣 */
    private BigDecimal frozenAmount;

    /** 结算后退回余额的部分（结算失败时剩余冻结的红冲依据） */
    private BigDecimal refundAmount;

    /** {@code 1余额 2微信 3支付宝}。★ 本项目只有 1 能走通 */
    private Integer payMethod;

    private LocalDateTime payTime;

    /** 第三方支付流水号。★ 余额支付恒为 NULL */
    private String transactionId;

    private LocalDateTime refundTime;

    /** {@code 1用户主动 2充满自停 3余额不足 4设备故障 5超时 6运维干预} */
    private Integer stopReason;

    /** ★ 结算完成时间。见类注释第三节：它是事实记录，幂等判据是 status */
    private LocalDateTime settleTime;

    private String remark;

    /** 乐观锁。★ 状态流转走条件更新，这一列留给资料编辑类场景 */
    private Integer version;

    // ==================== 审计四列 ====================

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
