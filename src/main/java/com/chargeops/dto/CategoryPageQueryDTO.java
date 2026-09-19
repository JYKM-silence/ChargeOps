package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 分类分页查询条件（管理端）
 *
 * <p>分页参数的两条约定（字段初始值给默认、{@code pageSize} 必须有上限）
 * 见 {@code StationPageQueryDTO} 的类注释 —— 那里是这套写法的正本。
 *
 * <h2>★ 一、{@code name} 模糊、{@code type} / {@code status} 精确</h2>
 *
 * <p>和别的域一致：能走索引的用精确匹配，给人看的名字用模糊匹配。
 *
 * <h2>★★ 二、这个类存在，为什么不干脆用一个「查全部启用分类」的接口</h2>
 *
 * <p>因为这是<b>两个不同的场景</b>，需要的两条 SQL 完全不同：
 *
 * <table border="1">
 *   <caption>管理端分页 vs 下拉框</caption>
 *   <tr><th></th><th>管理端 {@code /page}</th><th>下拉框 {@code /list}</th></tr>
 *   <tr><td>条件</td><td>全动态（可模糊搜名字、可含停用的）</td>
 *       <td>固定 {@code type=? and status=1}</td></tr>
 *   <tr><td>分页</td><td>要</td><td>不要（十行量级）</td></tr>
 *   <tr><td>走索引</td>
 *       <td>不（动态条件用不上 {@code idx_category_type_sort}）</td>
 *       <td><b>走</b>（列序正好对上）</td></tr>
 * </table>
 *
 * <p>★ 用分页接口当下拉框的数据源会带来两个问题：
 * <ol>
 *   <li>下拉框必须自己处理翻页 —— 而它其实只需要全部十条</li>
 *   <li>它默认会返回<b>停用的分类</b>，前端还得再过滤一次 ——
 *       而「停用的不该出现在下拉框里」这条规则应该由后端保证，
 *       因为它是<b>业务规则</b>，不是展示偏好</li>
 * </ol>
 *
 * <p>所以两个接口并存，各自服务的查询在 {@code CategoryMapper} 里
 * 也是两个方法（{@code page} / {@code listEnabled}）。
 * 这不叫重复，叫<b>两个不同的查询</b>。
 */
@Data
@Schema(description = "分类分页查询条件")
public class CategoryPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /** ★ {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    /** 分类名称，<b>模糊匹配</b> */
    @Schema(description = "分类名称（模糊）", example = "快充")
    private String name;

    /**
     * {@code 1充电套餐分类 2增值服务分类}，<b>精确匹配</b>
     *
     * <p>★ 不传 = 两个 type 都返回。这是管理端想要的
     * （「所有分类」视图），和下拉框的语义不同 ——
     * 下拉框永远明确知道自己要哪个 type。
     */
    @Schema(description = "类型：1充电套餐分类 2增值服务分类", example = "1")
    private Integer type;

    /** {@code 1启用 0停用}，<b>精确匹配</b>。★ 不传 = 启用和停用都返回 */
    @Schema(description = "状态：1启用 0停用", example = "1")
    private Integer status;
}
