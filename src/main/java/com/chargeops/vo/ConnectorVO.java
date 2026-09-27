package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电枪信息
 *
 * <h2>★ 一、{@code version} 是这张 VO 存在的关键 —— 客户端要拿它做更新回传</h2>
 *
 * <p>{@code ConnectorUpdateDTO.version} 是 {@code @NotNull} 的，而客户端唯一的获取
 * 途径就是这里。少了它，枪的更新接口<b>在协议上不可用</b>：前端只能瞎填一个值，
 * 而 {@code UPDATE ... WHERE id = ? AND version = ?} 会因此匹配不到任何行，
 * 报出来的还是「数据已被他人修改」—— 一条完全误导的错误消息。
 *
 * <p>所以 {@code ConnectorVO.version} 出、{@code ConnectorUpdateDTO.version} 入，
 * 这两个字段是<b>成对</b>的，改动其中任何一个都必须同时改另一个。
 * 判断「某个字段该不该进 VO」的一般规则就在这里：
 * <b>如果客户端的某个合法操作需要这个值作为输入，它就必须出现在 VO 里。</b>
 *
 * <h2>★ 二、{@code currentOrderId} 保留，而 {@code currentOrderNo} 没有</h2>
 *
 * <p>{@code currentOrderId} 出现是因为「这把枪为什么点不动」是管理员必然会问的问题，
 * 而答案就在这个字段上（非空 = 有订单占着），它同时解释了
 * {@code uk_connector_current_order} 那条唯一索引的存在。
 *
 * <p>而订单号是 {@code charging_order} 表里的字段，补它就要 join 订单表 ——
 * <b>现在加一个永远为 null 的字段，比以后加要糟</b>：前端会为它写好渲染逻辑，
 * 然后一直看到空白。
 *
 * <h2>★ 三、{@code stationId} 保留，但列表里<b>不</b>补站名</h2>
 *
 * <p>和 {@code ChargerVO} 补 {@code stationName} 形成对照。枪的列表永远带
 * {@code chargerId} 或 {@code stationId} 过滤条件，调用方已经知道自己在看哪个站/哪根桩 ——
 * 补站名是给这一页数据回答一个它自己已经能回答的问题，换来的是一次多余的查询
 * 和一个 {@code PageHelper} 的坑。
 */
@Data
@Schema(description = "充电枪信息")
public class ConnectorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "充电枪 id")
    private Long id;

    @Schema(description = "所属充电桩 id")
    private Long chargerId;

    @Schema(description = "所属站点 id（冗余自充电桩）")
    private Long stationId;

    @Schema(description = "枪口号，同一充电桩内唯一")
    private String gunNo;

    @Schema(description = "接口类型，如 GB/T 20234.3")
    private String connectorType;

    @Schema(description = "额定功率，kW")
    private BigDecimal ratedPowerKw;

    @Schema(description = "状态：0离线 1空闲 2已占用 3充电中 4故障 5维护")
    private Integer status;

    @Schema(description = "状态文案，如「空闲」")
    private String statusLabel;

    @Schema(description = "当前占用/在充的订单 id；null 表示空闲")
    private Long currentOrderId;

    @Schema(description = "累计充电次数")
    private Integer totalChargeCount;

    /** ★ <b>客户端必须把它原样回传</b>给更新接口，见类注释第一节。从 0 开始 */
    @Schema(description = "乐观锁版本号，更新时原样回传")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
