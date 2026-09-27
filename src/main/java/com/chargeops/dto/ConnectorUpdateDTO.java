package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 修改充电枪的请求体
 *
 * <h2>★★ 一、{@code version} 是必填的，这是全项目唯一一处硬性要求客户端回传</h2>
 *
 * <p>SQL 是 {@code UPDATE connector SET ... WHERE id = ? AND version = ?}
 * （见 {@code ConnectorMapper.xml} 的 update）。影响行数为 0 时再查一次，
 * 区分「这行被删了」和「别人先改了」—— 前者 {@code NotFoundException}，
 * 后者 {@code DataConflictException}。
 *
 * <p><b>★ 为什么服务端不能自己读一遍 version 再写。</b>
 * 那样写出来的代码看起来一模一样，但检查<b>永远通过</b>：读到的 version
 * 紧接着被写进 WHERE，中间没有别人插进来的窗口，于是「乐观锁」退化成一个
 * 没有语义的装饰。真正的并发窗口在「客户端读到数据」和「客户端提交修改」之间，
 * 而只有客户端知道那个版本号是多少 —— 所以 {@code ConnectorVO} 里有
 * {@code version}，前端拿到它、原样回传；{@code @NotNull} 保证忘了传
 * 是 400 而不是「静默全量覆盖」。
 *
 * <h2>★★ 二、{@code status} 的白名单：{@code 2已占用} / {@code 3充电中} 管理员设不了</h2>
 *
 * <p>这两个状态归订单状态机所有，校验是
 * {@code ConnectorStatus.isAdminSettable()}（在 Service 里，不在注解上 ——
 * 白名单是一个枚举方法，不是常量集合，写成 {@code @Min/@Max} 会漏掉中间两个值）。
 *
 * <p>手工设 {@code 3充电中} 会凭空造出一把「正在充电」却<b>没有订单</b>的枪：
 * 计费不会发生，但用户界面上它显示为占用、其他用户充不了电 ——
 * 一把永久卡死、没有任何办法解除的枪（订单状态机是唯一能改回它的人，
 * 而它不知道这笔订单存在）。
 *
 * <p>手工设 {@code 2已占用} 更直接：这个状态在业务上<b>定义</b>为
 * {@code current_order_id IS NOT NULL}，手工设它会造成
 * 「状态是占用但 {@code current_order_id} 是 NULL」——
 * 和唯一索引 {@code uk_connector_current_order} 的语义正面冲突。
 *
 * <h2>★ 三、没有 {@code chargerId}：枪不能换桩</h2>
 *
 * <p>把一把枪挪到另一根桩上，会让三处数据同时失真：老桩的 {@code gun_count}
 * 多算了一个、新桩的少算了一个，而 {@code connector.station_id}（冗余列）
 * 还指着老站点。更麻烦的是<b>历史</b>：{@code charging_order.connector_id}
 * 记录了「这笔订单用的是哪把枪」，枪换桩之后那个 id 指向的物理设备变了 ——
 * 一笔发生在 A 站的历史订单会显示成 B 站的设备。这不是「更新一下冗余列」
 * 能修的，因为历史不该被改写。
 *
 * <p>真要换，正确做法是删掉重加（新枪 id、新历史），老的枪随之退役。
 * 见 {@code ConnectorService.delete} 的守卫：有在途订单或有历史的枪不允许删 ——
 * 所以「换桩」这件事在数据上是<b>被设计成麻烦的</b>，因为它本来就该是。
 *
 * <h2>★ 四、没有 {@code stationId} 和 {@code currentOrderId}</h2>
 *
 * <p>{@code stationId} 是从 {@code charger} 派生的冗余列，
 * {@code currentOrderId} 归订单状态机 —— 理由同 {@code ConnectorDTO}。
 */
@Data
@Schema(description = "修改充电枪")
public class ConnectorUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁版本号，<b>必填</b>
     *
     * <p>★ 客户端从 {@code GET /api/admin/connector/{id}} 的响应里拿到、
     * 原样回传。见类注释第一节：服务端自己填这个值的话，
     * 检查就变成一个恒真的表达式。
     *
     * <p>★ 没有 {@code @Min(1)}：版本号从 <b>0</b> 开始（DDL 的 {@code DEFAULT 0}），
     * 加下界会让<b>刚创建的枪永远改不了</b> —— 而那正是最常被改的状态。
     */
    @Schema(description = "乐观锁版本号，从 GET 接口的响应里原样回传", example = "0")
    @NotNull(message = "版本号不能为空（请从查询接口获取后原样回传）")
    private Integer version;

    @Schema(description = "枪口号，同一充电桩内唯一；不传表示不修改", example = "A")
    @Size(max = 16, message = "枪口号不能超过 16 个字符")
    private String gunNo;

    @Schema(description = "接口类型；不传表示不修改", example = "GB/T 20234.3")
    @Size(max = 32, message = "接口类型不能超过 32 个字符")
    private String connectorType;

    @Schema(description = "额定功率，kW；不传表示不修改", example = "120.00")
    @DecimalMin(value = "0", inclusive = false, message = "额定功率必须大于 0")
    private BigDecimal ratedPowerKw;

    /**
     * ★ <b>只有 {@code 0离线 1空闲 4故障 5维护} 这四个值</b>，
     * {@code 2已占用} / {@code 3充电中} 会被 Service 用
     * {@code ConnectorStatus.isAdminSettable()} 拒绝。见类注释第二节。
     *
     * <p>枚举的全集是 6 个值，受限的是这里能传的 4 个 ——
     * 所以 {@code @Schema} 的文档里<b>必须</b>列全 6 个并标明哪两个不可传，
     * 否则前端会以为 {@code 2} 只是「暂时没用到」。
     */
    @Schema(description = "状态：0离线 1空闲 4故障 5维护（2已占用/3充电中由订单状态机管理，不可手工设置）；不传表示不修改",
            example = "1")
    private Integer status;
}
