package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 计费模板分页查询条件
 *
 * <p>分页参数的约定见 {@link StationPageQueryDTO} 的类注释。
 *
 * <h2>为什么没有 {@code tariff_rule} 的分页查询 DTO</h2>
 *
 * <p>规则<b>永远按 {@code templateId} 全量列出</b>，不分页 ——
 * 一个模板的时段就是 8 行左右，分页只会让「校验 24 小时覆盖」
 * 这件必须看全集的事变得没法做。
 * {@code TariffRuleMapper.listByTemplateId} 返回 {@code List} 而不是分页对象。
 *
 * <p>同理 {@code station_tariff} 也是按站点全量列出（一个站几条到十几条）。
 */
@Data
@Schema(description = "计费模板分页查询条件")
public class TariffTemplatePageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    /** 模板名称，<b>模糊匹配</b> */
    @Schema(description = "模板名称（模糊）", example = "深圳")
    private String name;

    /** 模板编码，<b>前缀匹配</b>。运维更常按编码找 */
    @Schema(description = "模板编码（前缀匹配）", example = "SZ-2026")
    private String code;

    /** 适用地区，<b>精确匹配</b>。走 {@code idx_tariff_template_region_status} */
    @Schema(description = "适用地区（精确）", example = "深圳市")
    private String region;

    /** {@code 1启用 0停用}，<b>精确匹配</b> */
    @Schema(description = "状态：1启用 0停用", example = "1")
    private Integer status;
}
