package com.chargeops.vo;

import com.chargeops.entity.Connector;
import com.chargeops.enumeration.ConnectorStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 充电枪信息（车主端）
 *
 * <h2>★★ 一、{@code currentOrderId} 在<b>车主端被去掉了</b> ★★</h2>
 *
 * <p>{@code ConnectorVO}（管理端）有这个字段，因为「这把枪为什么点不动」
 * 是运维必然会问的问题。而车主端拿到它只有一个后果：
 * <b>知道某把枪上正在充电的那笔订单的 id</b>。
 *
 * <p>★ 订单 id 本身不是机密，但它是 {@code GET /api/member/orders/{id}}
 * 的入参 —— 那个接口有归属校验（{@code getByIdAndMember}），所以拿别人的 id
 * 查不到东西。**安全上没有问题**，但这是一个没必要存在的字段：
 * 车主端没有任何一个功能需要它。去掉它是「默认就安全」那条原则的应用 ——
 * 不要依赖下游每一处都记得「这个 id 不是我的」。
 *
 * <p>★ 同理去掉的还有 {@code version} 和 {@code totalChargeCount}：
 * 前者只有管理端的乐观锁更新要用（见 {@code ConnectorVO} 类注释第一节），
 * 后者是运营指标。
 *
 * <h2>★★ 二、{@code available} 是<b>快照</b>，不是<b>预订</b> ★★</h2>
 *
 * <p>它是 {@code status == 1 空闲} 的一个方便写法。前端拿它做两件事：
 * 灰掉不能点的枪、把能点的排在前面。
 *
 * <p>★ <b>它不保证下单成功。</b>从「看到 available = true」到「提交建单」
 * 之间有真实的几秒到几十秒，而并发的另一笔请求完全可能先占走这把枪。
 * 那时候建单会抛 {@code ConnectorOccupiedException}（文案「该充电枪正在使用中，
 * 请换一把」），<b>那是正常的竞争结果，不是这个字段算错了</b>。
 *
 * <p>★★ 所以前端<b>必须</b>处理「available = true 但下单失败」这个分支，
 * 不能把按钮置灰当成保护。真正的保护只有一条：
 * {@code ConnectorMapper.occupy} 的
 * {@code WHERE status = 1 AND current_order_id IS NULL}
 * 加唯一索引 {@code uk_connector_current_order} —— 那是数据库在裁决。
 *
 * <h2>★ 三、带上了充电桩的名字和编号</h2>
 *
 * <p>车主选枪时需要区分同一站里的多把枪，而「A区-01 的 2 号枪」是现场
 * 唯一能对上号的描述。只给 {@code connectorId} 的话，用户站在桩前面
 * 不知道哪把是自己选的。
 *
 * <p>★ 和管理端 {@code ConnectorVO} 那句「不补站名」不冲突：那里的列表
 * <b>永远带 stationId 过滤</b>，调用方已经知道自己看的是哪个站；
 * 而这里的列表是<b>整个站下所有桩的枪混在一起</b>，不补桩名就没法区分。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberConnectorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long stationId;

    private Long chargerId;

    /** 所属充电桩的编号，如 {@code A区-01} */
    private String chargerDeviceNo;

    /** 所属充电桩的名称 */
    private String chargerName;

    /** 桩类型文案：1直流快充 2交流慢充 */
    private String chargerTypeLabel;

    /** 枪口号，同一充电桩内唯一 */
    private String gunNo;

    /** 接口类型，如 GB/T 20234.3 */
    private String connectorType;

    /** 额定功率，kW */
    private BigDecimal ratedPowerKw;

    @Schema(description = "状态：0离线 1空闲 2已占用 3充电中 4故障 5维护")
    private Integer status;

    @Schema(description = "状态文案，如「空闲」")
    private String statusLabel;

    /**
     * 此刻是否可以发起充电 —— 等价于 {@code status == 1 空闲}
     *
     * <p>★★ <b>它是快照，不是预订。</b>为 true 之后仍可能下单失败。
     * 完整说明见类注释第二节。
     */
    @Schema(description = "此刻是否空闲（★ 快照，不保证下单成功）")
    private Boolean available;

    /**
     * @param chargerDeviceNo 桩编号，查不到传 null
     * @param chargerName     桩名称，查不到传 null
     * @param chargerTypeLabel 桩类型文案，查不到传 null
     */
    public static MemberConnectorVO from(Connector connector,
                                         String chargerDeviceNo,
                                         String chargerName,
                                         String chargerTypeLabel) {
        ConnectorStatus status = ConnectorStatus.of(connector.getStatus());
        return MemberConnectorVO.builder()
                .id(connector.getId())
                .stationId(connector.getStationId())
                .chargerId(connector.getChargerId())
                .chargerDeviceNo(chargerDeviceNo)
                .chargerName(chargerName)
                .chargerTypeLabel(chargerTypeLabel)
                .gunNo(connector.getGunNo())
                .connectorType(connector.getConnectorType())
                .ratedPowerKw(connector.getRatedPowerKw())
                .status(status.getCode())
                .statusLabel(status.getLabel())
                .available(status == ConnectorStatus.IDLE)
                .build();
    }
}
