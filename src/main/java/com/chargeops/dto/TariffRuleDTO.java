package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 计费时段规则（内嵌在 {@link TariffTemplateDTO} / {@link TariffTemplateUpdateDTO} 里）
 *
 * <h2>★★ 一、{@code startTime} / {@code endTime} 是 {@code String}，且末段必须以 {@code "24:00:00"} 收尾</h2>
 *
 * <p>两条从 DDL 继承的约定，任何一条破了都会造成<b>静默</b>的计费错误：
 *
 * <ol>
 *   <li><b>半开区间 {@code [startTime, endTime)}。</b>{@code 08:00-10:00} 和
 *       {@code 10:00-12:00} <b>相接而不重叠</b> —— 10:00 属于后者，不属于前者。
 *       这就是 {@code TariffValidator} 里那个
 *       {@code prev.endMinute == next.startMinute} 判断能同时证明
 *       「无缺口」和「无重叠」的原因。</li>
 *
 *   <li><b>最后一段的 {@code endTime} 必须写 {@code "24:00:00"}，不能写
 *       {@code "00:00:00"}。</b>写成后者的话，{@code 23:00-24:00} 会变成一个
 *       <b>零长度段</b>：它既不产生切点，也不被任何时刻命中 ——
 *       表现为「23 点到 24 点之间没有电价」，也就是
 *       <b>订单金额莫名其妙少了</b>。</li>
 * </ol>
 *
 * <p>★ 也正是 {@code "24:00:00"} 这个值决定了这两个字段用 {@code String}
 * 而不是 {@code LocalTime}（后者上限是 {@code 23:59:59.999999999}）。
 * 更本质的说法：<b>MySQL 的 {@code TIME} 不是「一天中的时刻」，
 * 而是有符号时长（±838:59:59）</b>，这才是 {@code '24:00:00'} 合法的原因。
 *
 * <h2>★★ 二、这里【不】校验 24 小时覆盖，也不校验无重叠</h2>
 *
 * <p>那两条都是<b>跨行</b>的性质：单看一行 {@code [08:00, 12:00)} 是完美的，
 * 它和 {@code [08:00, 10:00)} 的重叠只有在看见两行时才存在，字段级注解做不到。
 * 校验在 {@code TariffValidator.validate(List<TariffRuleSnapshot>)}，由
 * {@code TariffTemplateServiceImpl} 在保存模板时调用 —— <b>一次看到全集</b>
 * 才校验得了，这正是 {@code rules} 必须内嵌在模板 DTO 里（而不是另开一个
 * 「加一条规则」的接口）的原因。挡在写入口，是因为缺口一旦进了库，
 * 受影响的是<b>所有用这个模板的在途订单</b>。
 *
 * <h2>★ 三、两个单价分列，绝不合并成「综合单价」</h2>
 *
 * <p>{@code energyPrice} 是电网收的、{@code servicePrice} 是运营商收的，
 * 对账时要分别和电网账单、和财务报表核对 —— 先合成再拆开是做不到的，
 * 那正是这两列存在的理由。
 *
 * <p>两者都允许为 {@code 0}：电费为 0 出现在政府补贴园区，服务费为 0 是
 * 「免服务费」活动。所以是 {@code @DecimalMin("0")} 而不是排他。
 */
@Data
@Schema(description = "计费时段规则")
public class TariffRuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ 数值和 {@link com.chargeops.enumeration.PeriodType} 一一对应，
     * 但<b>绝不要用 {@code ordinal()}</b> —— {@code SHARP_PEAK.ordinal() == 0}
     * 而库里的值是 {@code 1}，差一。这个差一不会抛异常，只会让尖峰和高峰
     * 在某个报表里互换，而报表看起来完全正常。
     */
    @Schema(description = "时段类型：1尖峰 2高峰 3平段 4低谷", example = "1")
    @NotNull(message = "时段类型不能为空")
    @Min(value = 1, message = "时段类型只能是 1尖峰、2高峰、3平段、4低谷")
    @Max(value = 4, message = "时段类型只能是 1尖峰、2高峰、3平段、4低谷")
    private Integer periodType;

    /**
     * ★ 零填充的 {@code HH:mm:ss} 的字典序等于时间序，所以 SQL 里
     * {@code ORDER BY start_time} 不需要任何转换（{@code "24:00:00"} 也自然
     * 排在最后）。这个性质要求格式<b>统一</b>，所以 Service 会把 {@code "08:00"}
     * 归一化补成 {@code "08:00:00"} 再入库。
     */
    @Schema(description = "时段开始（含），HH:mm:ss", example = "08:00:00")
    @NotBlank(message = "时段开始时间不能为空")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$",
            message = "时段开始时间格式应为 HH:mm 或 HH:mm:ss，且不能为 24:00:00")
    private String startTime;

    /** ★ 允许 {@code "24:00:00"}，且<b>最后一段必须是它</b>。见类注释第一节 */
    @Schema(description = "时段结束（不含），HH:mm:ss；最后一段必须为 24:00:00", example = "12:00:00")
    @NotBlank(message = "时段结束时间不能为空")
    @Pattern(regexp = "^(([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?|24:00:00)$",
            message = "时段结束时间格式应为 HH:mm、HH:mm:ss，或 24:00:00")
    private String endTime;

    /** 电费单价，元/kWh。{@code decimal(10,4)} —— 四位小数是电网账单的精度，不是拍脑袋 */
    @Schema(description = "电费单价，元/kWh", example = "1.2000")
    @NotNull(message = "电费单价不能为空")
    @DecimalMin(value = "0", message = "电费单价不能为负数")
    private BigDecimal energyPrice;

    /**
     * 服务费单价，元/kWh，{@code decimal(10,4)}
     *
     * <p>★ 允许省略，Service 会补 {@code 0}（DDL 默认值也是 0，但全列插入下
     * {@code DEFAULT} 不生效，所以必须由 Java 侧给出）。
     */
    @Schema(description = "服务费单价，元/kWh；不传按 0 处理", example = "0.4000")
    @DecimalMin(value = "0", message = "服务费单价不能为负数")
    private BigDecimal servicePrice;

    /**
     * ★ 它<b>不是</b>约束：{@code uk_tariff_rule_tpl_start (template_id, start_time)}
     * 才是。这个字段只影响前端怎么排 —— 而查询本来就按 {@code start_time} 排
     * （字典序等于时间序），所以它通常等于段序号。允许省略，Service 按数组下标填。
     */
    @Schema(description = "展示顺序，从 0 开始；不传则按数组下标填", example = "0")
    private Integer seq;
}
