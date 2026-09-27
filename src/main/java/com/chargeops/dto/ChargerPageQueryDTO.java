package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 充电桩分页查询条件
 *
 * <p>分页参数的约定（字段初始值、{@code @Max(100)} 的理由）
 * 见 {@link StationPageQueryDTO} 的类注释，这里不重复。
 *
 * 这个列表是跨站点的，所以 {@code stationId} 是可选的
 *
 * <p>管理端有两个不同的入口用同一个接口：站点详情页里的「本站充电桩」（带
 * {@code stationId}），和平台级的「所有充电桩」（不带，靠 {@code deviceNo}
 * 或名字找）。如果做成 {@code stationId} 必填，第二个入口就没法表达了 ——
 * 而它恰恰是运维最常用的那个（用户打电话报「DEV-2026-0042 充不上电」，
 * 运维不知道这个桩属于哪个站，只能按设备号全局搜）。
 *
 * <p>带 {@code stationId} 时走 {@code idx_charger_station_status}，
 * 不带时是全表扫 —— 可以接受，这是后台低 QPS 的管理操作，桩总数是万级。
 */
@Data
@Schema(description = "充电桩分页查询条件")
public class ChargerPageQueryDTO implements Serializable {

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

    /**  前缀匹配：运维报障时按设备号全局搜的主要入口 */
    @Schema(description = "设备编号（前缀匹配）", example = "DEV-2026")
    private String deviceNo;

    @Schema(description = "桩位名（模糊）", example = "A区")
    private String name;

    @Schema(description = "类型：1直流快充 2交流慢充", example = "1")
    private Integer type;

    @Schema(description = "状态：0离线 1在线空闲 2充电中 3故障 4维护中", example = "1")
    private Integer status;
}
