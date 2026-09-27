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
 * <p> 比管理端 {@code ConnectorVO} 少了 {@code currentOrderId} / {@code version} /
 * {@code totalChargeCount}。{@code currentOrderId} 是「别人正在充的那笔订单 id」，
 * 车主端没有任何功能需要它，去掉它是「默认就安全」：不要求下游每一处都记得校验归属。
 * 另两个只有管理端用（乐观锁、运营指标）。
 *
 * <p> {@code available} 是快照不是预订：它只等价于 {@code status == 1}，为 true 之后
 * 仍可能建单失败（{@code ConnectorOccupiedException} —— 并发的另一笔先占走了枪，
 * 这是正常的竞争结果，不是这个字段算错了）。前端必须处理该分支。真正的保护是
 * {@code ConnectorMapper.occupy} 的条件更新加唯一索引 {@code uk_connector_current_order}。
 *
 * <p> 带桩名和桩编号是因为车主选枪时要把同一站的多把枪区分开，只给 id 认不出。
 * 管理端不补站名是因为它的列表永远带 stationId 过滤，而这里的列表是整个站的枪混在一起。
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

    /** 此刻是否空闲 —— 等价于 {@code status == 1}。快照不是预订，见类注释 */
    @Schema(description = "此刻是否空闲（快照，不保证下单成功）")
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
