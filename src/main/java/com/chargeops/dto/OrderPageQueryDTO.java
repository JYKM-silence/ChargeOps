package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 我的订单分页查询条件
 *
 * <p> 只有分页和状态两个参数，没有 memberId —— 身份从 token 取。
 *
 * <p> {@code status} 是单值不是数组。车主端的「进行中」在业务上是
 * {@code status IN (1,2,3,4)}，但那是一个产品概念，应该由前端选一个
 * 「进行中」的 tab 映射过来，而不是让客户端自由拼任意状态组合 ——
 * 开放数组等于把「哪些状态算进行中」这个决定交给客户端，将来加了新状态，
 * 老版本客户端看不到它，用户就会觉得「我的订单消失了」。
 *
 * <p>要支持「进行中」这个 tab 时，在这里加一个布尔字段（{@code onlyActive}），
 * 由服务端映射成状态集合。
 */
@Data
@Schema(description = "我的订单分页查询条件")
public class OrderPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    /** 可空。见类注释：单值，不是数组 */
    @Schema(description = "订单状态，可空；见 OrderStatus")
    private Integer status;
}
