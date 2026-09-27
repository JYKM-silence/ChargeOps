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
 * 一、这里没有 {@code type} —— 而且是刻意的
 *
 * <p>{@code type} 决定 {@code charge_package} 的归属合法性：
 * 一个充电套餐只能挂在 {@code type = 1} 的分类下
 * （由 {@code ChargePackageServiceImpl.requireCategory} 校验）。
 *
 * <p>允许改 {@code type} 意味着可以把这个分类连同下面挂着的所有套餐
 * 从「充电套餐分类」搬到「增值服务分类」，而套餐那边不会有任何反应。
 * 更糟的是下一次保存套餐时它会报错（{@code requireCategory} 抛
 * 「分类类型不匹配」），于是管理员看到「昨天还好好的套餐今天改不了价」，
 * 而原因是他昨天动了分类 —— 这两件事在时间上和界面上都隔得很远。
 *
 * <p> 这和 {@code ChargePackageUpdateDTO} 排除 {@code packageType} /
 * {@code stationId} 是同一个手法：让不该能改的东西在接口形状上就改不了，
 * 而不是靠 Service 里的一句 if —— 那种写法依赖每个人都记得加。
 * 要把分类挪到另一个 type，正确做法是新建一个（唯一键是复合的，
 * 同名可以在另一个 type 下存在），然后把套餐逐个改过去，
 * 那是一次显式的、看得见的迁移，而不是一次请求里的静默搬家。
 *
 * 二、{@code name} 的改动会撞复合唯一键
 *
 * <p>{@code uk_category_type_name (type, name)} —— 改成同 type 下已存在的
 * 名字会撞键，由 {@code GlobalExceptionHandler} 翻成「xxx已存在」。
 * Service 不预检，理由见 {@code StationMapper} 的类注释。
 *
 * 三、停用分类不会影响已挂在上面的套餐
 *
 * <p>{@code status = 0} 的含义是「不再出现在新建时的下拉框里」，
 * 不是「这个分类作废了」：已挂在这个分类下的套餐照常能卖、照常能改价，
 * {@code requireCategory} 只校验 type，不校验 status。
 *
 * <p> 为什么不校验 status（这是一个具体的取舍）：如果校验了，
 * 「停用一个分类」会连带让下面的套餐无法编辑 —— 管理员想改一个套餐的价格，
 * 会先收到「分类已停用」，于是他的下一步是去启用那个本来想停用的分类，
 * 整个操作反过来了。这跟「停用计费模板不影响已有绑定」是同一个道理：
 * 停用管的是「新建」，不是「存量」。
 */
@Data
@Schema(description = "修改分类")
public class CategoryUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 只判 {@code @Size}，不加 {@code @NotBlank} ——
     * 局部更新下不传就是「不改」，加 {@code @NotBlank} 会强制
     * 每次请求都必须带上名字，那就不叫局部更新了。
     * （这和 {@code MemberUpdateDTO} 里 {@code nickname} 的处理一致。）
     */
    @Schema(description = "分类名称，同一类型内唯一", example = "快充")
    @Size(max = 32, message = "分类名称不能超过 32 个字符")
    private String name;

    /**  调整分类在下拉框里的位置靠它 */
    @Schema(description = "排序值，小的在前", example = "0")
    private Integer sort;

    /**  停用只影响下拉框，不影响存量套餐 —— 见类注释第三节 */
    @Schema(description = "状态：1启用 0停用", example = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status;
}
