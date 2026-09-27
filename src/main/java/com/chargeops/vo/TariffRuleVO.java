package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费时段规则信息
 *
 * <h2>★ 一、只有 {@code GET /api/admin/tariff-template/{id}} 会用到它</h2>
 *
 * <p>规则的<b>列表</b>接口没有 —— 规则总是作为模板的一部分被读取和写入。这是
 * {@code TariffRuleMapper} 里没有单个 insert/update/delete 的同一件事在 API 面上的
 * 表现：<b>规则没有独立的存在意义。</b>分页接口返回的模板列表里也不含 {@code rules}
 * （见 {@code TariffTemplateVO} 的说明）—— 一页 10 个模板就是 80 条规则。
 *
 * <h2>★ 二、{@code periodTypeLabel} 和 {@code seq} 的用途不同</h2>
 *
 * <p>{@code periodTypeLabel}（「尖峰」「平段」）是给界面用的。
 *
 * <p>{@code seq} 是展示顺序，但它<b>不决定查询结果的顺序</b>：
 * 决定顺序的是 {@code TariffRuleMapper.listByTemplateId} 的 {@code ORDER BY start_time} ——
 * 零填充的 {@code HH:mm:ss} 字典序恰好等于时间序，{@code "24:00:00"} 也自然排在最后。
 * 返回 {@code seq} 只是为了让编辑页把它原样回传，不是为了让前端自己排序。
 *
 * <h2>★ 三、两个单价分列返回</h2>
 *
 * <p>{@code energyPrice}（电网收的）和 {@code servicePrice}（运营商收的）不合并成
 * 「综合单价」：合并之后对账就做不了，要分别和电网账单、和财务报表核对，
 * 而先加后拆是做不到的。见 {@code TariffRule} 类注释第五节。
 */
@Data
@Schema(description = "计费时段规则信息")
public class TariffRuleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "规则 id")
    private Long id;

    @Schema(description = "所属模板 id")
    private Long templateId;

    @Schema(description = "时段类型：1尖峰 2高峰 3平段 4低谷")
    private Integer periodType;

    @Schema(description = "时段类型文案，如「尖峰」")
    private String periodTypeLabel;

    /**
     * 时段开始（含）
     *
     * <p>★ 原样返回字符串，不做格式化 —— 零填充的 {@code HH:mm:ss} 是「字典序 ==
     * 时间序」这条性质的前提，转换一次就可能破坏它（比如转成 {@code LocalTime}
     * 再序列化回来，{@code "24:00:00"} 就没了）。见 {@code TariffRule} 第二节。
     */
    @Schema(description = "时段开始（含），HH:mm:ss")
    private String startTime;

    @Schema(description = "时段结束（不含）；最后一段为 24:00:00")
    private String endTime;

    @Schema(description = "电费单价，元/kWh")
    private BigDecimal energyPrice;

    @Schema(description = "服务费单价，元/kWh")
    private BigDecimal servicePrice;

    @Schema(description = "展示顺序（编辑时原样回传即可）")
    private Integer seq;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
