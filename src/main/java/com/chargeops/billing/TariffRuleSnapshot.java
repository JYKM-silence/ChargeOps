package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.utils.TimeCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一条计费规则的不可变快照，时段以「当日分钟数」表达。
 *
 * <p>这是 {@code tariff_rule} 一行在计费引擎里的样子。刻意不是实体类：引擎不认识
 * MyBatis，也不需要 {@code tariff_template_id} 和 {@code seq}。
 *
 * <p>用分钟数而不是 {@code LocalTime}：{@code '24:00:00'} 是合法值但不是
 * {@code LocalTime} 的定义域，用 int 表示它就是 {@code 1440}，不需要哨兵值或特判。
 * 入库仍是 {@code "HH:mm:ss"} 字符串（见 {@link TimeCodec}），转换只在 {@link #of} 一处。
 *
 * <p>区间半开：[start, end)，所以 {@code 08:00-10:00} 与 {@code 10:00-12:00} 相接而不重叠。
 *
 * <p>单行约束（起点早于终点、不越界、单价非负）在构造器里守死，{@link BillingEngine}
 * 不必对每行设防，{@link TariffValidator} 只管跨行的连续性。单价归一化到 4 位小数
 * （对齐 {@code decimal(10,4)}），进引擎的每个 {@code BigDecimal} 都有确定的 scale。
 */
public record TariffRuleSnapshot(
        int startMinute,
        int endMinute,
        PeriodType periodType,
        BigDecimal energyPrice,
        BigDecimal servicePrice) {

    /** 与 {@code tariff_rule.energy_price} / {@code service_price} 的列宽一致 */
    private static final int PRICE_SCALE = 4;

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
        // 允许 start == end 就是允许一条永远不生效、却让「24 小时覆盖」看起来成立的空规则
        if (startMinute >= endMinute) {
            throw new ParamErrorException("计费时段的起点必须早于终点："
                    + TimeCodec.toTimeString(startMinute) + " - " + TimeCodec.toTimeString(endMinute));
        }
        if (energyPrice == null || servicePrice == null) {
            throw new ParamErrorException("计费单价不能为空");
        }
        if (energyPrice.signum() < 0 || servicePrice.signum() < 0) {
            // 负单价是静默的：金额照样算得出来，只是符号反了
            throw new ParamErrorException("计费单价不能为负");
        }
        // 归一化而非拒绝：DTO 层已有 @Digits 把关，超精度值只可能来自库里或代码构造
        energyPrice = energyPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        servicePrice = servicePrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 从 {@code tariff_rule} 行的字段构造。参数类型与实体字段一一对应（{@code String} 时间、
     * {@code Integer} 时段码），Service 层的转换因此是一行直白调用。
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

    /** 人类可读的一行描述，如 {@code "尖峰 10:00:00-12:00:00"}。异常消息里只报分钟数管理员还得自己换算 */
    public String describe() {
        return periodType.getLabel() + " " + TimeCodec.toTimeString(startMinute)
                + "-" + TimeCodec.toTimeString(endMinute);
    }
}
