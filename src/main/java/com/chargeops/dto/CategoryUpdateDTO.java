package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改分类的请求体
 *
 * <h2>★★ 一、这里<b>没有</b> {@code type} —— 而且是刻意的</h2>
 *
 * <p>{@code type} 决定 {@code charge_package} 的归属合法性：
 * 一个充电套餐只能挂在 {@code type = 1} 的分类下
 * （由 {@code ChargePackageServiceImpl.requireCategory} 校验）。
 *
 * <p>允许改 {@code type} 意味着可以把这个分类<b>连同下面挂着的所有套餐</b>
 * 从「充电套餐分类」搬到「增值服务分类」，而<b>套餐那边不会有任何反应</b>：
 * 那些套餐还在，{@code category_id} 还指着这个分类，
 * 只是它们突然出现在了一个「增值服务」分类里。
 *
 * <p>★ 更糟的是<b>下一次保存套餐时它会报错</b> ——
 * {@code requireCategory} 会抛「分类类型不匹配」。于是管理员看到一个
 * 「昨天还好好的套餐今天改不了价」的现象，而原因是他昨天动了分类。
 * 这两件事在时间上和界面上都隔得很远。
 *
 * <p>★ 这和 {@code ChargePackageUpdateDTO} 排除 {@code packageType} /
 * {@code stationId} 是同一个手法：<b>让不该能改的东西在接口形状上就改不了</b>，
 * 而不是靠 Service 里的一句 if —— 那种写法依赖每个人都记得加。
 *
 * <p>★ 要把分类挪到另一个 type，正确的做法是新建一个（唯一键是复合的，
 * 同名可以在另一个 type 下存在），然后把套餐逐个改过去。
 * 那是一个<b>显式的、逐步的、看得见</b>的迁移，
 * 而不是一次请求里的静默搬家。
 *
 * <h2>★ 二、{@code name} 的改动会撞复合唯一键</h2>
 *
 * <p>{@code uk_category_type_name (type, name)} —— 改成同 type 下已存在的
 * 名字会撞键，由 {@code GlobalExceptionHandler} 翻成「xxx已存在」。
 * Service 不预检，理由见 {@code StationMapper} 的类注释。
 *
 * <h2>★ 三、停用分类<b>不会</b>影响已挂在上面的套餐</h2>
 *
 * <p>这是 {@code status} 的语义问题，值得写清楚，因为它很容易被理解反。
 *
 * <p>{@code status = 0} 的含义是「<b>不再出现在新建时的下拉框里</b>」，
 * 不是「这个分类作废了」。所以：
 * <ul>
 *   <li>已挂在这个分类下的套餐<b>照常能卖、照常能改价</b></li>
 *   <li>{@code requireCategory} <b>只校验 type，不校验 status</b></li>
 * </ul>
 *
 * <p>★ 为什么 {@code requireCategory} 不校验 status（这是一个具体的取舍）：
 * 如果校验了，那么「停用一个分类」会连带让下面的套餐无法编辑 ——
 * 管理员想改一个套餐的价格，会先收到「分类已停用」，
 * 于是他的下一步是去启用那个本来想停用的分类。整个操作反过来了。
 *
 * <p>这跟「停用计费模板不影响已有绑定」是同一个道理：
 * <b>停用管的是「新建」，不是「存量」。</b>
 */
@Data
@Schema(description = "修改分类")
public class CategoryUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分类名称
     *
     * <p>★ 只判 {@code @Size}，<b>不加 {@code @NotBlank}</b> ——
     * 局部更新下不传就是「不改」，加 {@code @NotBlank} 会强制
     * 每次请求都必须带上名字，那就不叫局部更新了。
     * （这和 {@code MemberUpdateDTO} 里 {@code nickname} 的处理一致。）
     */
    @Schema(description = "分类名称，同一类型内唯一", example = "快充")
    @Size(max = 32, message = "分类名称不能超过 32 个字符")
    private String name;

    /** 展示排序，小的在前。★ 现在可以改它来调整下拉框里的位置 */
    @Schema(description = "排序值，小的在前", example = "0")
    private Integer sort;

    /** {@code 1启用 0停用}。★ 停用只影响下拉框，不影响存量套餐 —— 见类注释第三节 */
    @Schema(description = "状态：1启用 0停用", example = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status;
}
