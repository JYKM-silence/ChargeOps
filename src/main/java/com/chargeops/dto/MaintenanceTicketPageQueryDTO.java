package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 运维工单分页查询条件
 *
 * <p> 分页参数的约定见 {@link StationPageQueryDTO} 的类注释。
 *
 * <p> 三个条件都可选：管理端默认进来是「全部工单按时间倒序」，运维最常见的动作是从里面
 * 挑出待处理的那几条，所以 {@code status} 是筛选项而不是默认值。
 */
@Data
@Schema(description = "运维工单分页查询条件")
public class MaintenanceTicketPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "所属站点 id（不传则跨站点）", example = "1")
    private Long stationId;

    @Schema(description = "充电桩 id", example = "1")
    private Long chargerId;

    /** {@code 0待处理 1处理中 2已完成}。不传表示不筛 */
    @Schema(description = "状态", example = "0")
    private Integer status;

    /** {@code 1人工 2AI助手}。用来把 AI 建的工单单独捞出来看 */
    @Schema(description = "来源", example = "2")
    private Integer source;
}
