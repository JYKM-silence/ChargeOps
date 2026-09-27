package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 修改充电桩的请求体
 *
 * 一、三个字段被刻意排除，而且理由各不相同
 *
 * <p>「让不能改成为编译期事实」是本项目对一个反复出现的问题的统一回答。
 * 运行期检查会被绕过（某个 Service 顺手 set 一下就有了），
 * 而字段不在 DTO 里，就没有任何调用方能传进来。
 *
 * <ol>
 * <li>没有 {@code stationId} —— 把一根桩挪到别的站点，
 * 它每一把枪的 {@code station_id}（DDL 明写的冗余列）都还指着老站点，
 * 而「某站点所有枪」的查询直接走那一列。要修必须 charger 和它的全部
 * connector 在同一个事务里一起改，那是一个独立的「搬迁」操作，
 * 不是「按主键更新非空字段」顺带能做的事。
 * 代价：桩登记错站点时只能删掉重建。</li>
 *
 * <li>没有 {@code gunCount} —— 冗余列不能有第二条写入路径。
 * 唯一路径是 {@code ConnectorService} 调 {@code refreshGunCount}。</li>
 *
 * <li>没有 {@code lastHeartbeatTime} —— 创建时可以传（数据迁移），
 * 改的时候不能。「最后在线时间」是只增不减的事实：允许管理端设置它，
 * 就是允许伪造一条「这台桩刚刚还在线」的记录，而运维恰恰靠这个字段
 * 判断桩是不是掉线了。它在下一次心跳时会自然更新，不需要手工干预。</li>
 * </ol>
 *
 * <p> 这三条由 {@code ChargerMapper.xml} 的 update 语句独立保证。
 * DTO 里没有字段是第一道锁，{@code <set>} 里没有对应列是第二道 ——
 * 任何一道单独失效都不会造成写入。
 *
 * 二、{@code type} 可以改，这不是遗漏
 *
 * <p>直流桩改成交流桩听起来不该发生，但它确实会发生：老站改造时用的是
 * 同一台设备的编号，只是换了个模块。而且改 {@code type} 不影响任何别处的数据 ——
 * {@code charger.type} 没有冗余副本，也没有别的表引用它。
 *
 * <p>反过来说，这正是判断「一个字段该不该可改」的通用标准：
 * 改了之后，别处的数据还对不对？对，就可以改（type）；
 * 不对，就不能改（stationId、gunCount）。
 */
@Data
@Schema(description = "修改充电桩")
public class ChargerUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 允许改，但代价是：如果这个编号已被外部系统（运维台账、
     * 设备厂商的云平台）记下来，改完就对不上了。这是数据层面的问题，
     * 不是代码能解决的 —— 所以这里不禁止，只在 Swagger 里说清楚它是什么。
     */
    @Schema(description = "设备编号，唯一；不传表示不修改", example = "DEV-2026-0001")
    @Size(max = 32, message = "设备编号不能超过 32 个字符")
    private String deviceNo;

    @Schema(description = "充电桩名称；不传表示不修改", example = "A区-01")
    @Size(max = 64, message = "充电桩名称不能超过 64 个字符")
    private String name;

    @Schema(description = "类型：1直流快充 2交流慢充；不传表示不修改", example = "1")
    @Min(value = 1, message = "类型只能是 1直流快充、2交流慢充")
    @Max(value = 2, message = "类型只能是 1直流快充、2交流慢充")
    private Integer type;

    @Schema(description = "型号；不传表示不修改", example = "DC-120kW")
    @Size(max = 64, message = "型号不能超过 64 个字符")
    private String model;

    @Schema(description = "额定功率，kW；不传表示不修改", example = "120.00")
    @DecimalMin(value = "0", inclusive = false, message = "额定功率必须大于 0")
    private BigDecimal ratedPowerKw;

    /**
     * 和充电枪不同，桩的状态没有白名单限制：{@code 2充电中} 在桩上
     * 不像在枪上那样有并发含义 —— 桩的「充电中」是派生事实（它下面有枪在充），
     * 手工设错了不破坏任何约束，只是列表页显示不对，
     * 下一次心跳或订单状态变化会纠正它。
     *
     * <p>枪不一样：枪的 {@code 2已占用} / {@code 3充电中} 直接和唯一索引
     * {@code uk_connector_current_order} 的语义绑定，所以那里必须挡。
     * 见 {@code ConnectorUpdateDTO}。
     */
    @Schema(description = "状态：0离线 1在线空闲 2充电中 3故障 4维护中；不传表示不修改", example = "1")
    @Min(value = 0, message = "状态超出取值范围")
    @Max(value = 4, message = "状态超出取值范围")
    private Integer status;

    @Schema(description = "固件版本；不传表示不修改", example = "v1.2.3")
    @Size(max = 32, message = "固件版本不能超过 32 个字符")
    private String firmwareVersion;
}
