package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电桩（对应 {@code charger} 表）
 *
 * <p>桩是「物理设备」，枪（{@link Connector}）是「设备上的一个接口」。
 * 一根桩可以有多把枪，一把枪对应一次充电 ——
 * 所以订单挂在枪上，不挂在桩上。
 *
 * <p>{@code gunCount} 是冗余列（等于本桩下 {@code connector} 的行数），只为列表展示：
 * 枪的新增/删除必须在同一个事务里调 {@code ConnectorMapper.refreshGunCount}，
 * 而且它不能进 CreateDTO / UpdateDTO —— 那是让冗余列失真的第二条写入路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Charger implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long stationId;

    /** 设备编号，设备报文里的唯一标识。唯一索引 {@code uk_charger_device_no} */
    private String deviceNo;

    /** 桩位名，如「A区-01」。业务上的展示名，不参与任何约束 */
    private String name;

    /** {@code 1直流快充 2交流慢充} —— 见 {@link com.chargeops.enumeration.ChargerType} */
    private Integer type;

    private String model;

    /** 额定功率 kW。 NOT NULL 且库无默认值（{@code decimal(6,2)}）—— DTO 层必须拦，否则是 500 而不是 400 */
    private BigDecimal ratedPowerKw;

    /** 枪数。 冗余自 {@code connector}，由 Service 在同事务内刷新 */
    private Integer gunCount;

    /** {@code 0离线 1在线空闲 2充电中 3故障 4维护中} */
    private Integer status;

    /** 最后一次心跳时间，离线巡检用：{@code WHERE last_heartbeat_time < NOW() - INTERVAL 5 MINUTE AND status <> 0} */
    private LocalDateTime lastHeartbeatTime;

    private String firmwareVersion;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，所以 Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
