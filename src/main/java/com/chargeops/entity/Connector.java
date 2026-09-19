package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电枪（对应 {@code connector} 表）
 *
 * <h2>★★ 一、这张表的实体<b>绝不能</b>标 {@code @AutoFill} —— 全 P1a 最容易犯的错</h2>
 *
 * <p>「所有写操作都该标 {@code @AutoFill}」是一个很自然的直觉，
 * 但这张表<b>没有 {@code create_user} / {@code update_user} 两列</b>
 * （只有两个时间列，见 DDL）。
 *
 * <p>{@link com.chargeops.mybatis.AutoFillInterceptor} 是<b>反射</b>找
 * {@code setCreateUser(Long)} / {@code setUpdateUser(Long)} 的，
 * 找不到会<b>直接抛 {@code IllegalStateException}</b>，不是静默跳过 ——
 * 所以标错了不是「填不上就不填」，而是所有写操作当场炸掉。
 *
 * <p>正确的做法：{@code ConnectorMapper} 的 {@code insert} / {@code update}
 * <b>不标</b> {@code @AutoFill}，{@code createTime} / {@code updateTime}
 * 由 {@code ConnectorServiceImpl} 显式 {@code set} 之后再调 mapper。
 *
 * <p>为什么 DDL 故意省掉这两列：枪是设备的一部分，
 * 「谁创建的」这个问题的答案在桩上，不在枪上 ——
 * 每把枪都存一遍管理员 id 是纯冗余，而冗余列会漂移。
 *
 * <p><b>这个约定有一条测试守着：</b>{@code ConnectorMapperTest} 里用反射断言
 * {@code Connector.class.getMethod("setCreateUser", Long.class)} 抛
 * {@code NoSuchMethodException}。谁哪天给实体加字段而不加列，它会立刻失败。
 *
 * <h2>★★ 二、{@code currentOrderId} 与 {@code uk_connector_current_order} —— 全库最关键的一行</h2>
 *
 * <p>{@code current_order_id} 非空表示这把枪正被某个订单占用。
 * 表上有唯一索引 {@code uk_connector_current_order (current_order_id)}，
 * 而 <b>MySQL 的唯一索引不约束 NULL</b> —— 这条特性在这里被正面利用：
 *
 * <ul>
 *   <li>任意多把空闲枪（{@code current_order_id IS NULL}）可以并存，互不冲突</li>
 *   <li>但一旦 1 号枪写入 {@code current_order_id = 1001}，
 *       第二个想写 1001 的请求在<b>数据库层</b>必然失败</li>
 * </ul>
 *
 * <p>它把「两个用户同时启动同一把枪」从「靠代码写对」变成「数据库不允许错」。
 * P1a 从不写这一列（它归 P1c 的订单状态机），但整个并发故事都建立在它上面 ——
 * 所以 {@code ConnectorMapperTest} 里有一条测试专门验证这把锁真的存在。
 *
 * <h2>★ 三、{@code version} 是乐观锁</h2>
 *
 * <p>更新走 {@code UPDATE ... WHERE id = #{id} AND version = #{version}}，
 * 影响行数为 0 就是有人先改了。客户端必须回传它读到的 version ——
 * <b>服务端自己读一遍 version 再写的话，检查永远通过，那是装饰性的乐观锁</b>。
 *
 * <p>所以 {@code ConnectorVO} 里有 {@code version} 字段，
 * 而 {@code Connector} 实体和 {@code ConnectorUpdateDTO} 里也都要有。
 *
 * <h2>★ 四、管理员能设的 {@code status} 只有 4 个值</h2>
 *
 * <p>{@code 0离线 1空闲 2已占用 3充电中 4故障 5维护}，
 * 其中 <b>{@code 2已占用} 和 {@code 3充电中} 归订单状态机所有</b>。
 * 手工设 {@code 3} 会凭空造出一把「充电中」却没有订单的枪；
 * 手工设 {@code 2} 直接和 {@code uk_connector_current_order} 的语义打架。
 * 白名单见 {@link com.chargeops.enumeration.ConnectorStatus#isAdminSettable()}。
 *
 * <h2>★ 五、{@code stationId} 是冗余列</h2>
 *
 * <p>值等于 {@code charger.station_id}。枪的查询几乎都带站点维度
 * （「这个站有哪些枪」），冗余一份可以免去每次 join {@code charger}。
 *
 * <p>由 Service 从桩派生填入，<b>绝不出现在任何 DTO 里</b> ——
 * 让客户端能传它，就等于给冗余列开了第二条写入路径，
 * 而两条路径迟早会不一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Connector implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long chargerId;

    /** ★ 冗余自 {@code charger.station_id}，由 Service 派生，不出现在 DTO 里 */
    private Long stationId;

    /** 枪口号 A/B/C。与 {@code charger_id} 组成唯一键 {@code uk_connector_charger_gun} */
    private String gunNo;

    /** 接口类型，如 {@code "GB/T 20234.3"}。自由文本，不做枚举 */
    private String connectorType;

    /** 额定功率 kW，{@code decimal(6,2)}，NOT NULL 且库无默认值（同 Charger） */
    private BigDecimal ratedPowerKw;

    /**
     * {@code 0离线 1空闲 2已占用 3充电中 4故障 5维护}
     *
     * <p>★ 管理员只能改其中的白名单，见类注释第四节
     */
    private Integer status;

    /**
     * 当前占用 / 在充的订单 id；空闲时为 {@code NULL}
     *
     * <p>★ 唯一索引 {@code uk_connector_current_order} 就建在它上面。
     * <b>P1c 的订单状态机写入，P1a 不碰。</b>
     */
    private Long currentOrderId;

    /** 累计充电次数。由 P1c 在订单结算时自增 */
    private Integer totalChargeCount;

    /**
     * 乐观锁版本号，从 0 开始
     *
     * <p>★ 更新时必须由客户端回传，写进 {@code WHERE id = ? AND version = ?}。
     * 见类注释第三节。
     */
    private Integer version;

    // ==================== 审计两列 ====================
    // ★★ 没有 create_user / update_user —— 见类注释第一节。
    //    由 Service 显式 set，Mapper 的写方法不标 @AutoFill。
    //    标了的话 AutoFillInterceptor 反射找不到 setCreateUser(Long)，
    //    会抛 IllegalStateException，所有写操作当场炸掉。

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
