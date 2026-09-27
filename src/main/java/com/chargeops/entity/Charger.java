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
 * <h2>★ 一、{@code ratedPowerKw} 是 NOT NULL 且库里没有默认值</h2>
 *
 * <p>这条要写进 DTO 的 {@code @NotNull} 里。漏了的话，创建桩时不给功率
 * 会一路走到数据库，报一个 {@code SQLIntegrityConstraintViolationException} ——
 * 那是 <b>HTTP 500</b>，而它明明是一个可以指名的入参缺失（应该是 400）。
 *
 * <p>列类型 {@code decimal(6,2)}，{@link BigDecimal}：{@code 7.35} 这种非整数功率
 * 用 {@code double} 存会变成 {@code 7.3499999...}，而前端会把它原样显示出来。
 *
 * <h2>★ 二、{@code gunCount} 是冗余列，必须由 Service 主动维护</h2>
 *
 * <p>它等于「本桩下 {@code connector} 的行数」。保留冗余是为了列表展示
 * （显示「A区-01（2 枪）」时不用每行带一个子查询）。代价是<b>它会漂移</b>：
 * 枪的新增/删除必须在<b>同一个事务里</b>调 {@code ConnectorMapper.refreshGunCount}。
 * 不维持的话，表现是「页面上显示 2 枪，点进去只有 1 把」，
 * 而且会一直漂到有人手工数枪才会被发现。
 *
 * <h2>★ 三、{@code type} 不是分类标签，是业务事实</h2>
 *
 * <p>{@code 1直流快充 2交流慢充}。它影响计费预期、套餐适用范围
 * （直流套餐不能用在交流桩上）、以及电量分摊策略 —— 见
 * {@link com.chargeops.enumeration.ChargerType} 的类注释。
 *
 * <h2>★ 四、{@code status} 是枪状态的汇总</h2>
 *
 * <p>桩本身没有「独立意志」：它不空闲是因为它的枪都在忙。
 * 这个字段在 P1a 只读不写，由 P1d 的设备上报驱动。
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

    /**
     * 额定功率 kW。★ <b>NOT NULL 且库无默认值</b>（{@code decimal(6,2)}）
     *
     * <p>创建时不给值就是 500 而不是 400 —— DTO 层必须拦。
     */
    private BigDecimal ratedPowerKw;

    /**
     * 枪数。★ <b>冗余自 {@code connector}，由 Service 在同事务内刷新</b>
     *
     * <p>它<b>不应该出现在 CreateDTO / UpdateDTO 里</b>——
     * 写进 DTO 就等于给客户端开了一条让冗余列失真的路径。
     */
    private Integer gunCount;

    /** {@code 0离线 1在线空闲 2充电中 3故障 4维护中} */
    private Integer status;

    /**
     * 最后一次心跳时间
     *
     * <p>离线巡检用：{@code WHERE last_heartbeat_time < NOW() - INTERVAL 5 MINUTE
     * AND status <> 0}。索引 {@code idx_charger_heartbeat} 把时间放在第一列，
     * 因为范围条件必须放在最左列。P1a 不写这个字段，留给 P1d 的设备上报。
     */
    private LocalDateTime lastHeartbeatTime;

    private String firmwareVersion;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，所以 Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
