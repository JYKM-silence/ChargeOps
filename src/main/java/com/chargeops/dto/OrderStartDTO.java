package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 启动充电上报
 *
 * <p>★★ <b>本项目没有设备接入层，这个 DTO 是「设备上报」的替身。</b>★★
 * 真实系统里这份数据由充电桩通过 MQTT / TCP 长连接推上来、带设备证书，
 * 写入路径完全不经过 HTTP。本项目没有那套基础设施，所以这条路径挂在
 * <b>管理端</b>（{@code /api/admin/orders/**}）上，由运维代为录入。
 *
 * <p>★ <b>为什么不挂在车主端：</b>这些字段是「这台设备看到了什么」，
 * 车主能填的话，就等于让用户自己决定「我用了多少电」——
 * 那不是一个漏洞，那是免费充电。管理端至少有人为操作留痕
 * （{@code operator_id} 和 {@code charging_order_event}）。
 *
 * <p>★ 接上真实设备层时，字段可以原样保留（它描述的就是设备报文里必须有的
 * 东西），换掉的只是「谁来调」。
 */
@Data
@Schema(description = "启动充电上报（管理端代设备录入）")
public class OrderStartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 起始电表读数，<b>累计值</b>
     *
     * <p>★ 可空。设备没上报读数时留 NULL，<b>不要填 0</b> —— 填 0 会让
     * {@code totalEnergyKwh = end - 0 = 整个电表读数}，算出一笔几百度的订单。
     * 可空时结算会因为缺数据而进异常挂账，那是一个有人会去看的失败，
     * 而错误电量是一个不会报错的错误。
     */
    @Schema(description = "起始电表读数（累计值），可空；缺数据结算会挂账而不是算错")
    private BigDecimal startMeterKwh;

    @Schema(description = "起始电量百分比 0~100，可空")
    private Integer startSoc;
}
