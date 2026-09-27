package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 新增分类的请求体
 *
 * <h2>★★ 一、{@code sort} / {@code status} 的字段初始值不是风格问题，是必需的</h2>
 *
 * <p>{@code CategoryMapper.xml} 的 insert 是<b>全列插入</b>，
 * 传 null 就是往 {@code NOT NULL} 列里显式写 NULL（MySQL 直接报 errno 1048），
 * SQL 的 {@code DEFAULT} 不会生效 —— 完整论证和踩坑记录见
 * {@code ChargePackageDTO} 的类注释第一节。所以默认值只能由这里给：
 * {@code sort = 0}（默认排序位）、{@code status = 1}（启用）。
 *
 * <p>★ 但字段初始值<b>只挡得住「JSON 里没有这个键」</b>，
 * 挡不住显式的 {@code {"status": null}}（会走 setter 覆盖掉初始值）。
 * 所以 Service 里必须再兜一次底，用的是<b>同一批常量</b> ——
 * 这就是它们是 {@code public static final} 而不是内联字面量的原因。
 *
 * <h2>★ 二、{@code type} <b>必须</b>给，而且是这个类唯一没有默认值的字段</h2>
 *
 * <p>它没有合理的默认值：{@code 1} 和 {@code 2} 是两个完全不同的语义域
 * （充电套餐分类 / 增值服务分类），猜错会把分类建到错的域里。
 * 而 {@code category.type} 是 {@code NOT NULL} 且没有 DDL 默认值。
 *
 * <p>★ 少了这个 {@code @NotNull} 的后果（实测踩过）：请求一路走到 Service、
 * 穿过所有校验、最后撞在 insert 上，返回 <b>HTTP 500 +「未知错误」</b> ——
 * 调用方既不知道是哪个字段，也不知道是参数问题还是服务坏了。
 * 一个 400 +「分类类型不能为空」才是这条请求应得的回答。
 *
 * <h2>★ 三、{@code type} 只在**新增**时可给，改的时候给不了</h2>
 *
 * <p>见 {@code CategoryUpdateDTO}：那个类里没有这个字段。
 * 理由是这个字段决定 {@code charge_package} 的归属合法性 ——
 * 改它等于把一个分类（连同下面挂着的套餐）搬进另一个语义域，
 * 而套餐那边不会有任何反应。
 */
@Data
@Schema(description = "新增分类")
public class CategoryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code sort} 的默认值：{@code 0} = 默认排序位。★ 公开常量而非内联字面量，Service 兜底要用同一个值 */
    public static final Integer DEFAULT_SORT = 0;

    /** {@code status} 的默认值：{@code 1} = 启用。★ 同上 */
    public static final Integer DEFAULT_STATUS = 1;

    /**
     * 分类名称
     *
     * <p>★ 唯一性受 {@code uk_category_type_name (type, name)} 约束，
     * <b>范围是「同 type 内」</b> —— 同一个名字可以在两个 type 下各存一份，
     * 重名提示只在同 type 内出现。见 {@code Category} 的类注释第一节。
     *
     * <p>★ 代价：唯一键<b>不看 {@code status}</b>，
     * 所以「快充 」这种带尾空格的错别字下架之后仍然永久占用那个名字，
     * 想用正确的名字只能先把它硬删掉。这是硬删接口必须存在的原因之一。
     */
    @Schema(description = "分类名称，同一类型内唯一", example = "快充")
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 32, message = "分类名称不能超过 32 个字符")
    private String name;

    @Schema(description = "类型：1充电套餐分类 2增值服务分类", example = "1")
    @NotNull(message = "分类类型不能为空")
    @Min(value = 1, message = "分类类型只能是 1充电套餐分类、2增值服务分类")
    @Max(value = 2, message = "分类类型只能是 1充电套餐分类、2增值服务分类")
    private Integer type;

    /** ★ 不是唯一键，重复值由 {@code id} 兜底成稳定的全序 */
    @Schema(description = "排序值，小的在前", example = "0", defaultValue = "0")
    private Integer sort = DEFAULT_SORT;

    /**
     * ★ 默认启用：新建一个分类却看不见它（因为默认停用）是个坏体验。
     * 语义是「还出不出现在下拉框里」，不是「存量数据失效」——
     * 停用不会让已挂在上面的套餐不可编辑。
     */
    @Schema(description = "状态：1启用 0停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status = DEFAULT_STATUS;
}
