package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.utils.TimeCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一条计费规则的<b>不可变快照</b>，以「当日分钟数」表达时段
 *
 * <p>这是 {@code tariff_rule} 一行在计费引擎里的样子。刻意<b>不是实体类</b>：
 * 引擎不认识 MyBatis、不认识 {@code tariff_template_id}、不认识 {@code seq} ——
 * 它只需要「哪个时间区间、什么时段、两个单价」。
 *
 * <h2>★ 为什么用分钟数而不是 {@code LocalTime}</h2>
 *
 * <p>{@code tariff_rule.end_time} 的合法值里有 {@code '24:00:00'}，
 * 而 {@code LocalTime} 的上限是 {@code 23:59:59.999999999} ——
 * {@code 24:00:00} 在那个类型的定义域之外。用 {@code int} 分钟数表示，
 * {@code 24:00:00} 就是 {@code 1440}，<b>不需要任何哨兵值或特判</b>，
 * 「最后一段怎么收尾」这个问题自然消失。
 *
 * <p>入库用的仍然是 {@code "HH:mm:ss"} 字符串（见 {@link TimeCodec}），
 * 转换只发生在 {@link #of} 这一个地方。
 *
 * <h2>★ 区间是半开的：[start, end)</h2>
 *
 * <p>{@code start} 含、{@code end} 不含。所以 {@code 08:00-10:00} 和
 * {@code 10:00-12:00} 相接而不重叠 —— 一个时刻恰好属于一条规则。
 * 这条约定是「相邻相接不算重叠」的全部依据（DDL 明文要求的行为），
 * 也是 {@link TariffValidator} 能用一个 {@code ==} 同时证明无缺口、无重叠的原因。
 *
 * <h2>★ 每行自己的合法性在这里守，不留给校验器</h2>
 *
 * <p>构造器直接拒绝「起点不小于终点」「越界」「负单价」。
 * 这样做的价值是<b>让非法状态无法被构造出来</b>：
 * 只要手上有一个 {@code TariffRuleSnapshot}，它的单行约束就是成立的，
 * {@link BillingEngine} 不必再对每一行设防，{@link TariffValidator}
 * 也只需要管跨行的连续性。
 *
 * <p>代价是<b>报错只报第一条</b>（构造在第一个坏行就中断了），
 * 没法像连续性那样一次列全。可以接受：管理员一次填错多个字段的常见程度，
 * 远低于一条时段规则本身写反。
 *
 * <p><b>单价归一化到 4 位小数</b>：对齐 {@code decimal(10,4)} 的列宽。
 * 这样进引擎的每个 {@code BigDecimal} 都有确定的 scale，
 * 中间过程不需要再考虑「这个值几位小数」。
 */
public record TariffRuleSnapshot(
        int startMinute,
        int endMinute,
        PeriodType periodType,
        BigDecimal energyPrice,
        BigDecimal servicePrice) {

    /** 与 {@code tariff_rule.energy_price} / {@code service_price} 的列宽一致 */
    private static final int PRICE_SCALE = 4;

    /**
     * 紧凑构造器：单行约束在这里一次性守死
     *
     * <p>注意 {@code periodType} 用的是枚举而不是 {@code Integer} ——
     * 库里存的 {@code period_type} 是 {@code 1~4}，而 {@code PeriodType} 的
     * 枚举序号从 0 开始，两者差一。先转成枚举，引擎里就不可能再有人
     * 误用 {@code ordinal()}。
     */
    public TariffRuleSnapshot {
        if (periodType == null) {
            throw new ParamErrorException("计费时段类型不能为空");
        }
        if (startMinute < 0 || startMinute > TimeCodec.MINUTES_PER_DAY) {
            throw new ParamErrorException("计费时段起点越界（0 ~ 1440 分钟）：" + startMinute);
        }
        if (endMinute < 0 || endMinute > TimeCodec.MINUTES_PER_DAY) {
            throw new ParamErrorException("计费时段终点越界（0 ~ 1440 分钟）：" + endMinute);
        }
        // ★ 必须是严格小于。允许 start == end 就等于允许零长度规则，
        //   而零长度规则在切分时既不产生切点也不被任何时刻命中 ——
        //   它是一条永远不生效、却会让「24 小时覆盖」看起来成立的空规则。
        if (startMinute >= endMinute) {
            throw new ParamErrorException("计费时段的起点必须早于终点："
                    + TimeCodec.toTimeString(startMinute) + " - " + TimeCodec.toTimeString(endMinute));
        }
        if (energyPrice == null || servicePrice == null) {
            throw new ParamErrorException("计费单价不能为空");
        }
        if (energyPrice.signum() < 0 || servicePrice.signum() < 0) {
            // 负单价意味着「充电越多退钱越多」，没有任何业务场景需要它，
            // 而它在计算上是静默的（金额照样算得出来，只是符号反了）
            throw new ParamErrorException("计费单价不能为负");
        }
        // 归一化而非拒绝：DTO 层已有 @Digits(fraction=4) 把关，
        // 能走到这里的超精度值只可能来自库里或代码构造，静默对齐比抛异常合适
        energyPrice = energyPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        servicePrice = servicePrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 从 {@code tariff_rule} 行的字段构造
     *
     * <p>参数类型刻意与实体字段一一对应（{@code String} 时间、{@code Integer} 时段码），
     * 让 Service 层的转换是一行直白的调用，不必先做一轮手工预处理。
     *
     * @param startTime {@code "HH:mm:ss"} 或 {@code "HH:mm"}
     * @param endTime   {@code "HH:mm:ss"} 或 {@code "HH:mm"}；{@code "24:00:00"} 表示当日结束
     */
    public static TariffRuleSnapshot of(String startTime, String endTime, Integer periodTypeCode,
                                        BigDecimal energyPrice, BigDecimal servicePrice) {
        return new TariffRuleSnapshot(
                TimeCodec.toMinuteOfDay(startTime),
                TimeCodec.toMinuteOfDay(endTime),
                PeriodType.of(periodTypeCode),
                energyPrice,
                servicePrice);
    }

    /** 本时段的时长（分钟） */
    public int durationMinutes() {
        return endMinute - startMinute;
    }

    /** {@code start >= end} 在构造时已被拒绝，所以长度恒为正 */
    public boolean contains(int minuteOfDay) {
        return startMinute <= minuteOfDay && minuteOfDay < endMinute;
    }

    /**
     * 人类可读的一行描述，例如 {@code "尖峰 10:00:00-12:00:00"}
     *
     * <p>异常消息里要能一眼看出「是哪一条规则出的问题」。只报分钟数
     * （{@code "600-720"}）的话，管理员还得自己换算一遍才能对上界面上的那行 ——
     * 而报错信息的存在意义正是省掉这一步。
     */
    public String describe() {
        return periodType.getLabel() + " " + TimeCodec.toTimeString(startMinute)
                + "-" + TimeCodec.toTimeString(endMinute);
    }
}
