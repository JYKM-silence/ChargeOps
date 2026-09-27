package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 建单请求（车主端）
 *
 * <p> 没有 memberId：只从 token 取。客户端没有表达
 * 「给别人的账号建单」的能力。
 *
 * <p> 没有金额、没有电价、没有电量：这三样全部由服务端决定 ——
 * 电价从 {@code station_tariff} 解析后冻结，电量等设备上报。
 * 让客户端传金额等于把定价权交给客户端。
 *
 * <p> {@code stationId} / {@code chargerId} / {@code connectorId} 三个都要传，
 * 虽然从枪能反查出桩和站。理由是这个三元组是用户扫码内容的直接翻译：
 * 二维码里就带着这三层，前端原样传过来，服务端校验它们互相自洽
 * （枪属于桩、桩属于站）。少传一层的话，服务端要么自己查（多一次查询、
 * 还要处理「查不到」），要么信任前端的另一层（那就等于没校验层级关系）。
 */
@Data
@Schema(description = "建单请求")
public class OrderCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "站点不能为空")
    @Schema(description = "站点 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long stationId;

    @NotNull(message = "充电桩不能为空")
    @Schema(description = "充电桩 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long chargerId;

    @NotNull(message = "充电枪不能为空")
    @Schema(description = "充电枪 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long connectorId;

    /**
     * 可空是刻意的：扫码即充是真实存在的用法，用户没绑车也能充。
     * 正因为车是可选的，它的车牌要快照进订单 —— 见
     * {@code ChargingOrder.plateNo}。
     */
    @Schema(description = "车辆 id，可空（扫码即充）")
    private Long vehicleId;

    /**
     * 传 {@code 2 微信} 或 {@code 3 支付宝} 会被明确拒绝，
     * 不是静默降级成余额。一个「微信支付」按钮点了之后从余额扣钱，
     * 是比没有这个按钮严重得多的错误。
     */
    @Schema(description = "支付方式：1余额（默认，唯一可用）", example = "1")
    private Integer payMethod;
}
