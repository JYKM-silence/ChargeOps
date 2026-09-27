package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 新增充电枪的请求体
 *
 * 一、四个字段不在 DTO 里，每个都是一个已经踩过的坑
 *
 * <ol>
 * <li>没有 {@code stationId} —— 它是 DDL 明写的冗余列，值等于
 * {@code charger.station_id}，由 Service 从所属桩派生填入。
 * 让客户端能传它，就等于允许造出一把「挂在 A 站的桩下、
 * 但 {@code station_id} 写着 B 站」的枪，而
 * {@code idx_connector_station_status} 上的「某站所有枪」查询会真的把它查出来。</li>
 *
 * <li>没有 {@code currentOrderId} —— 这一列归订单状态机，它是唯一索引
 * {@code uk_connector_current_order} 的落点，也就是「一把枪同时只能有一个订单」
 * 这条铁律的实现。管理端能写它，就能凭空制造一个「已占用但没有订单」的枪口，
 * 而那把枪从此再也无法被任何订单使用（唯一键冲突），而且没有任何报错 ——
 * 用户只会发现「这把枪点不动」。</li>
 *
 * <li>没有 {@code totalChargeCount} —— 它是一条历史事实，不是一个可编辑属性，
 * 允许管理端设置它等于允许篡改统计数据。由结算在同一个事务里自增，
 * 语义见 {@code Connector.totalChargeCount}。</li>
 *
 * <li>没有 {@code version} —— 新建的枪版本恒为 0，由 Service 显式写入。
 * 让它由客户端指定，乐观锁第一次更新就失去意义
 * （客户端可以传一个比库里大的值，从此永远不冲突）。</li>
 * </ol>
 *
 * 二、{@code chargerId} 用普通字段而不是嵌套路径
 *
 * <p>接口是扁平的 {@code POST /api/admin/connector}，而不是
 * {@code POST /api/admin/charger/{chargerId}/connector}。
 *
 * <p>嵌套形状的问题不是审美，是事实来源变成了两个：路径里的
 * {@code chargerId} 和 body 里的 {@code chargerId} 可以不一致，于是必须写代码
 * 决定听谁的 —— 而无论听谁的，另一半调用方都会觉得自己的写法是对的。
 * 更要紧的是它表达不了后台真正需要的那个查询：「这个站点下所有枪」
 * （枪的列表是按 {@code chargerId} 或 {@code stationId} 过滤的，
 * 不是「从某根桩往下走」）。
 *
 * 三、{@code status} 不在创建请求里
 *
 * <p>枪的状态是设备上报的事实，不是管理员填的字段。新建的枪固定为
 * {@code 0 离线}：它还没有和任何硬件建立过连接，说它「空闲」需要一个
 * 从未发生过的握手。管理员仍然可以把坏枪标成 {@code 4故障} / {@code 5维护}，
 * 那条路径在 {@code ConnectorUpdateDTO} 上，并且受白名单约束。
 */
@Data
@Schema(description = "新增充电枪")
public class ConnectorDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Service 先 {@code ChargerMapper.getById} 确认桩存在，并从它身上取
     * {@code stationId} 填冗余列 —— 两个动作是同一次查询，所以「校验桩存在」
     * 不是额外成本。
     *
     * <p> 创建成功后必须在同一事务里调
     * {@code ConnectorMapper.refreshGunCount(chargerId)}，
     * 否则 {@code charger.gun_count} 从第一把枪开始就漂移。
     */
    @Schema(description = "所属充电桩 id", example = "1")
    @NotNull(message = "所属充电桩不能为空")
    private Long chargerId;

    /**
     * 枪口号，如 {@code "A"} / {@code "B"}
     *
     * <p> 与 {@code charger_id} 组成唯一键 {@code uk_connector_charger_gun}，
     * 约束范围是同一根桩之内：1 号桩有 A 枪、2 号桩也可以有 A 枪 ——
     * 用户面对的是「1 号桩的 A 枪」，不是一个全局唯一的枪口编号。
     *
     * <p>不用正则约束成单个字母：真实设备上有 {@code "A1"}、{@code "枪1"}、
     * {@code "CCS1"} 各种写法，把这个自由度锁死只会让对接现场改不动。
     * 真正的约束是「同一根桩内不重复」。
     */
    @Schema(description = "枪口号，同一充电桩内唯一", example = "A")
    @NotBlank(message = "枪口号不能为空")
    @Size(max = 16, message = "枪口号不能超过 16 个字符")
    private String gunNo;

    /**
     * 接口类型，自由文本，如 {@code "GB/T 20234.3"}（国标直流）、{@code "CCS2"}
     *
     * <p> 刻意不做成枚举：标准在演进（ChaoJi、国标 2024 版），
     * 而一个漏掉新标准的枚举会把「设备已经支持但代码不认识」变成入库失败。
     * 这和 {@code charger.type} 用枚举的判断相反，理由也相反：
     * 桩的「直流/交流」是分类（数量有限、逻辑要分支），
     * 而接口类型是一个标识（数量在增长、代码不关心具体值）。
     */
    @Schema(description = "接口类型，如 GB/T 20234.3", example = "GB/T 20234.3")
    @Size(max = 32, message = "接口类型不能超过 32 个字符")
    private String connectorType;

    /**
     * 额定功率 kW，{@code decimal(6,2)}
     *
     * <p> {@code NOT NULL} 且库无默认值 —— 和 {@code charger.rated_power_kw}
     * 完全一样，不传就是 500 而不是 400。见 {@code ChargerDTO} 类注释第一节。
     *
     * <p> 枪的额定功率可以小于桩的：一台 120kW 的双枪桩，每把枪单独用时
     * 拿满 120kW，两把同时用时各 60kW，所以这两列不是冗余关系，不能只存一个。
     * （真正的动态分配要等功率上报，本轮只存铭牌值。）
     */
    @Schema(description = "额定功率，kW", example = "120.00")
    @NotNull(message = "额定功率不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "额定功率必须大于 0")
    private BigDecimal ratedPowerKw;
}
