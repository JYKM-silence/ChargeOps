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
 * <p>★ <b>只追加</b>：没有 update / delete。分段是账单的原始凭据，
 * 要改只能红冲（作废重算）—— 和 {@link WalletTransaction} 同一条约束。
 *
 * <h2>★ {@code uk_segment_order_start (order_id, start_time)} 是结算幂等的核心</h2>
 *
 * <p>重跑结算时，重复插入相同分段会<b>撞唯一键</b>而不是把金额翻倍。
 * 对比「先 delete 再 insert」：后者在重试和并发下会丢数据（A 删完还没插，
 * B 也删一遍，A 再插就重复了）。<b>唯一键是数据库层面的保证，不依赖代码时序。</b>
 *
 * <h2>★ {@code energy_price} / {@code service_price} 是单价快照，不是外键</h2>
 *
 * <p>抄的是<b>计价当时</b>从 {@link com.chargeops.billing.TariffRuleSnapshot}
 * 读到的值：规则是整体替换的（每保存一次模板就删一次、插一次），
 * 引用 {@code tariff_rule.id} 会让「电价调整不影响历史订单」依赖那张规则行不被删。
 *
 * <h2>★ {@code amount} 单独存是为了能对账</h2>
 *
 * <p>{@code amount = energy_fee + service_fee} 看起来冗余，但
 * {@code WHERE amount <> energy_fee + service_fee} 必须返回 0 行 ——
 * 只存两个分量的话，「总额」就只是某段代码里的一个中间变量。
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

    /** 本段开始（含） */
    private LocalDateTime startTime;

    /** 本段结束（不含）。★ 半开区间，见 {@code TariffRuleSnapshot} 的类注释 */
    private LocalDateTime endTime;

    private Integer durationSeconds;

    private BigDecimal energyKwh;

    /** 电费单价快照，{@code decimal(10,4)} */
    private BigDecimal energyPrice;

    /** 服务费单价快照，{@code decimal(10,4)} */
    private BigDecimal servicePrice;

    private BigDecimal energyFee;

    private BigDecimal serviceFee;

    /** ★ 恒等于 {@code energyFee + serviceFee}。见类注释 */
    private BigDecimal amount;

    /**
     * ★ 由调用方显式给，不标 {@code @AutoFill}
     *
     * <p>本表<b>没有</b> {@code create_user} / {@code update_user} 列，标了插件会反射
     * 找不到 setter 直接抛 {@code IllegalStateException}；而且结算是一次批量插入，
     * 所有分段本来就该是<b>同一个时刻</b>，由调用方传一个 {@code now}
     * 比让插件每行各取一次时钟更准确。
     */
    private LocalDateTime createTime;
}
