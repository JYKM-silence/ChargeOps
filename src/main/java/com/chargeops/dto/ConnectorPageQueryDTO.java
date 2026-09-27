package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 充电枪分页查询条件
 *
 * <p>分页参数的约定见 {@link StationPageQueryDTO} 的类注释。
 *
 * <h2>★ 枪的列表路径是扁平的，不嵌套在充电桩下面</h2>
 *
 * <p>{@code GET /api/admin/connector/page?chargerId=1}，而不是
 * {@code GET /api/admin/charger/1/connector/page}。
 *
 * <p>理由是后台实际需要的三种视图，嵌套形状只能表达其中一种：
 * 某个桩下有哪些枪（带 {@code chargerId}），某个站下所有枪（带
 * {@code stationId}），以及「用户说他扫的那把枪坏了，只知道枪口号『B』」
 * （带 {@code gunNo}）。
 *
 * <p>还有一层：嵌套路径会让「这把枪属于哪个桩」有<b>两个事实来源</b>
 * （路径里的和请求体里的 chargerId）。两者不一致时以谁为准？
 * 无论怎么答，都是给未来埋一个需要解释的规则。扁平路径下只有一个来源。
 */
@Data
@Schema(description = "充电枪分页查询条件")
public class ConnectorPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    /** ★ 走 {@code idx_connector_station_status} */
    @Schema(description = "所属站点 id", example = "1")
    private Long stationId;

    @Schema(description = "所属充电桩 id", example = "1")
    private Long chargerId;

    @Schema(description = "枪口号（模糊）", example = "A")
    private String gunNo;

    @Schema(description = "状态：0离线 1空闲 2已占用 3充电中 4故障 5维护", example = "1")
    private Integer status;
}
