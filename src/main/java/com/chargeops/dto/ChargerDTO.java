package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新增充电桩的请求体
 *
 * 一、{@code ratedPowerKw} 的 {@code @NotNull} 是【必须】的，不是保险
 *
 * <p>{@code charger.rated_power_kw} 是 {@code decimal(6,2) NOT NULL}，
 * 而且 DDL 没有给它默认值（整张表里唯一一列「既 NOT NULL 又无默认值」的）。
 * 后果不是「填个 0 了事」，而是不传就 500：全列 insert 把 null 写进
 * NOT NULL 列，MySQL 报 errno 1048，被翻译成「未知错误」。
 * 加了 {@code @NotNull} 之后，同一件事变成 400 +「额定功率不能为空」——
 * 同一份错误，一个能行动、一个不能。
 *
 * 二、{@code gunCount} 不在这里，而且永远不该出现在任何 DTO 里
 *
 * <p>{@code charger.gun_count} 是 DDL 明写的冗余列，事实来源是
 * {@code connector} 表。它的唯一写入路径是 {@code ConnectorService}
 * 在枪增删的同一事务里调 {@code refreshGunCount} 重算。
 * 让客户端传它，就等于给这个冗余列开第二条写入路径，而两条路径迟早不一致 ——
 * 不一致的表现是「列表页显示 4 把枪、点进去只有 3 把」，
 * 一个没有报错、只是数字不对的失败。
 *
 * <p>Service 在 create 时显式给 {@code 0}（不是 DDL 默认的 1）：
 * 刚建好的桩一把枪都没有。见 {@code ChargerMapper.xml} 的 insert 注释。
 *
 * 三、{@code status} 不在这里
 *
 * <p>新建的桩状态只能是 0 离线 —— 它还没和任何硬件建立连接，
 * 说它「在线空闲」需要一个事实支撑，而那个事实要等设备心跳上报。
 * 所以 create 时不接受 status，Service 固定写 0；之后管理员可以把有问题的桩
 * 手工设成故障 / 维护中，那条路径在 {@code ChargerUpdateDTO} 上。
 */
@Data
@Schema(description = "新增充电桩")
public class ChargerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属站点
     *
     * <p> Service 会先 {@code StationMapper.getById} 确认站点存在 ——
     * 否则一个建在不存在的站点下的桩会让 {@code charger.station_id} 指向空处，
     * 而 MySQL 外键在本项目里是关掉的。
     *
     * <p>这个校验不能省：查是为了给出「站点不存在」这个能解释的消息，
     * 不是为了保住完整性（外键关了，没有任何东西在数据库层挡它）。
     */
    @Schema(description = "所属站点 id", example = "1")
    @NotNull(message = "所属站点不能为空")
    private Long stationId;

    /**
     * 这是桩的物理标识（印在铭牌上、写在运维台账里），
     * 所以唯一性由数据库的 {@code uk_charger_device_no} 守，
     * 重复时由 {@code GlobalExceptionHandler} 翻成「DEV-2026-0001已存在」。
     */
    @Schema(description = "设备编号，唯一", example = "DEV-2026-0001")
    @NotBlank(message = "设备编号不能为空")
    @Size(max = 32, message = "设备编号不能超过 32 个字符")
    private String deviceNo;

    @Schema(description = "充电桩名称", example = "A区-01")
    @NotBlank(message = "充电桩名称不能为空")
    @Size(max = 64, message = "充电桩名称不能超过 64 个字符")
    private String name;

    /**  枚举定义见 {@code ChargerType} */
    @Schema(description = "类型：1直流快充 2交流慢充", example = "1")
    @NotNull(message = "充电桩类型不能为空")
    @Min(value = 1, message = "类型只能是 1直流快充、2交流慢充")
    @Max(value = 2, message = "类型只能是 1直流快充、2交流慢充")
    private Integer type;

    @Schema(description = "型号", example = "DC-120kW")
    @Size(max = 64, message = "型号不能超过 64 个字符")
    private String model;

    /**
     * 额定功率 kW，{@code decimal(6,2)}
     *
     * <p> 不额外加 {@code @DecimalMax}：列宽是硬约束（最大 {@code 9999.99}），
     * 超了 MySQL 会报 out of range 并翻译成明确的错误；再写一个业务上限
     * （比如「不超过 480kW」）是在猜一个还没定下来的业务规则。
     * {@code @NotNull} 的理由见类注释第一节。
     *
     * <p> {@code @DecimalMin} 用 {@code inclusive = false} 排掉 0：
     * 额定功率为 0 的桩不可能存在，而它会让「功率 × 时长」之类的推算出 0，
     * 看起来像设备不上报，不像数据错了。
     */
    @Schema(description = "额定功率，kW", example = "120.00")
    @NotNull(message = "额定功率不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "额定功率必须大于 0")
    private BigDecimal ratedPowerKw;

    /**  可空 —— 新装的桩还没上报过版本。这里填的只是初始值，事实来源是设备上报 */
    @Schema(description = "固件版本", example = "v1.2.3")
    @Size(max = 32, message = "固件版本不能超过 32 个字符")
    private String firmwareVersion;

    /**
     * 保留这个字段是为了数据迁移：从旧平台导入存量桩时，
     * 那份台账里带着「最后在线时间」，导入后要能原样保留，
     * 否则所有导入的桩都会被显示成「从未上线」。
     *
     * <p> 正常运行的桩不该由管理端设置它 —— 事实来源是设备心跳上报，
     * 管理员手工改它等于制造一条假的在线记录。
     */
    @Schema(description = "最后心跳时间，仅用于数据迁移；正常运行由设备上报", example = "2026-09-18 10:00:00")
    private LocalDateTime lastHeartbeatTime;
}
