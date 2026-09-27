package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费规则 —— 一条尖峰/平/谷时段（对应 {@code tariff_rule} 表）
 *
 * <p>本表只有 {@code create_time} / {@code update_time} 两列，同 {@link Connector}，
 * 写方法绝不能标 {@code @AutoFill}（反射找不到 setter 会直接抛 {@code IllegalStateException}）。
 *
 * <p>区间是半开的 {@code [startTime, endTime)}：{@code 08:00-10:00} 和 {@code 10:00-12:00} 相接
 * 而不重叠，10:00 属于后者；最后一段的 {@code endTime} 必须是 {@code '24:00:00'} 而不是
 * {@code '00:00:00'} —— 后者会让 {@code 23:00-24:00} 变成零长度段，表现为「23 点到 24 点
 * 之间没有电价」。两个时间列是 {@link String} 就是因为 {@code LocalTime} 装不下 {@code '24:00:00'}。
 *
 * <p>{@code uk_tariff_rule_tpl_start (template_id, start_time)} 只保证起点不重复；
 * 同一模板内不可重叠由 {@link com.chargeops.billing.TariffValidator#validate} 在保存时校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TariffRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long templateId;

    /** {@code 1尖峰 2高峰 3平段 4低谷} —— 见 {@link com.chargeops.enumeration.PeriodType} */
    private Integer periodType;

    /** 时段开始（含），形如 {@code "00:00:00"} */
    private String startTime;

    /** 时段结束（不含）。最后一段必须是 {@code "24:00:00"}，不能写 {@code "00:00:00"} */
    private String endTime;

    /** 电费单价，元/kWh。{@code decimal(10,4)} */
    private BigDecimal energyPrice;

    /** 服务费单价，元/kWh。{@code decimal(10,4)}，默认 0 */
    private BigDecimal servicePrice;

    /** 展示顺序。{@code uk(template_id, start_time)} 才是约束，这个字段只管排序 */
    private Integer seq;

    // ---------- 审计两列 ----------
    // 没有 create_user / update_user，写方法绝不能标 @AutoFill（标了会抛异常）

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
