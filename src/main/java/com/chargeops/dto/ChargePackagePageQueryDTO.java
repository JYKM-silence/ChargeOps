package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 充电套餐分页查询条件
 *
 * <p>分页参数的约定见 {@link StationPageQueryDTO} 的类注释。
 *
 * {@code stationId} 的语义和别处不一样：{@code 0} 是一个有意义的值
 *
 * <p>它表示「全平台通用」，不是「没填」。由此带来一个必须明确的取舍：
 * 按 {@code stationId=1} 查时，要不要把全平台套餐（{@code stationId=0}）也带出来？
 *
 * <p>本项目的答案是不带（精确匹配）：这个接口是管理端的，
 * 管理员要看的是「科技园站配了哪些套餐」，那是一份可编辑的清单，
 * 全平台套餐不在这个站的管理范围内，混进来会让人以为它属于本站。
 * 而车主端要的是另一个语义「我能买哪些套餐」，那里是
 * {@code WHERE station_id IN (0, ?)} —— 两个需求不同，所以是两个查询，
 * 不是同一个查询加一个开关。把「要不要带全平台」做成一个布尔参数，
 * 会让这个接口的语义依赖调用方传对那个参数，
 * 而传错的后果是管理员看到一个错误的清单。
 */
@Data
@Schema(description = "充电套餐分页查询条件")
public class ChargePackagePageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "套餐名称（模糊）", example = "电包")
    private String name;

    /**  走 {@code idx_pkg_category_status_sort} */
    @Schema(description = "分类 id", example = "1")
    private Long categoryId;

    @Schema(description = "类型：1电量包 2时长包 3服务费折扣包 4次卡", example = "1")
    private Integer packageType;

    /**  不传和传 {@code 0} 的语义不同：不传是「不限站点」，传 {@code 0} 是「只要全平台套餐」 */
    @Schema(description = "限定站点 id；0 表示全平台套餐（不传则不限）", example = "0")
    private Long stationId;

    @Schema(description = "状态：1上架 0下架", example = "1")
    private Integer status;
}
