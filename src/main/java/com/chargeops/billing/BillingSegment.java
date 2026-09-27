package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个计费分段 —— 与 {@code charging_segment} 表<b>逐列对应</b>
 *
 * <p>字段顺序和名字都对齐 DDL，只少三列：{@code id}（自增）、
 * {@code order_id}（落库时补）、{@code create_time}（库默认值）。
 *
 * <p><b>★ 为什么让引擎的输出形状直接等于表的形状：</b>落库方拿到
 * {@link BillingResult} 后要做的就是把 record 列表逐条转实体再 {@code batchInsert}。
 * 如果引擎输出的是「几个金额的聚合」，落库方就得重新拆一遍 ——
 * 而重拆的那份代码和引擎里的必然逐渐不一致，分段数或边界差一点，
 * {@code uk(order_id, start_time)} 就会在重跑结算时炸掉。同形是把
 * 「两份实现保持一致」变成「只有一份实现」。
 *
 * <h2>★ 金额三列的关系是恒等式，不是巧合</h2>
 *
 * <p>{@code amount == energyFee + serviceFee}，且三个值都是<b>已经舍入到分的结果</b>，
 * 没有一个是「先加后舍」来的。对账 SQL
 * {@code WHERE amount <> energy_fee + service_fee} 必须返回 0 行。
 *
 * <h2>★ 电费与服务费始终分列，绝不合并</h2>
 *
 * <p>电费是电网收的、服务费是运营商收的，对账时要分别跟电网账单和财务报表核对 ——
 * 那正是这两列存在的理由，合成一个「综合单价」再拆开是做不到的。
 *
 * @param seq             第几段，从 1 开始。对齐 {@code charging_segment.seq} 的注释
 * @param periodType      本段所属的计费时段类型
 * @param startTime       本段开始（含），整秒
 * @param endTime         本段结束（不含），整秒
 * @param durationSeconds 本段秒数，恒 &gt; 0 —— 零长度分段在切分时就被排除了
 * @param energyKwh       本段分摊到的电量，保留 4 位小数，恒 &ge; 0
 * @param energyPrice     电费单价快照，保留 4 位小数
 * @param servicePrice    服务费单价快照，保留 4 位小数
 * @param energyFee       {@code = round2(energyKwh × energyPrice)}，保留 2 位小数
 * @param serviceFee      {@code = round2(energyKwh × servicePrice)}，保留 2 位小数
 * @param amount          {@code = energyFee + serviceFee}，不再舍入
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
