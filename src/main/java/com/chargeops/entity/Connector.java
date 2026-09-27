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
 * <h2>★ 一、这张表的实体<b>绝不能</b>标 {@code @AutoFill}</h2>
 *
 * <p>「所有写操作都该标 {@code @AutoFill}」是个很自然的直觉，但这张表
 * <b>没有 {@code create_user} / {@code update_user} 两列</b>。
 * {@link com.chargeops.mybatis.AutoFillInterceptor} 是<b>反射</b>找
 * {@code setCreateUser(Long)} 的，找不到会<b>直接抛 {@code IllegalStateException}</b>，
 * 不是静默跳过 —— 标错了不是「填不上就不填」，而是所有写操作当场炸掉。
 * 所以 Mapper 的 {@code insert} / {@code update} 不标，两个时间列由 Service 显式
 * {@code set}（DDL 省掉这两列是因为「谁创建的」答案在桩上，不在枪上）。
 * <b>约定有测试守着</b>：{@code ConnectorMapperTest} 用反射断言
 * {@code setCreateUser(Long)} 抛 {@code NoSuchMethodException}。
 *
 * <h2>★ 二、{@code uk_connector_current_order} —— 全库最关键的一行</h2>
 *
 * <p>{@code current_order_id} 非空表示这把枪正被某个订单占用。唯一索引建在它上面，
 * 而 <b>MySQL 的唯一索引不约束 NULL</b> —— 这条特性在这里被正面利用：
 * 任意多把空闲枪可以并存，但一旦 1 号枪写入 {@code current_order_id = 1001}，
 * 第二个想写 1001 的请求在<b>数据库层</b>必然失败。
 * 它把「两个用户同时启动同一把枪」从「靠代码写对」变成「数据库不允许错」，
 * {@code ConnectorMapperTest} 里有一条测试专门验证这把锁真的存在。
 *
 * <h2>★ 三、{@code version} 是乐观锁，客户端必须回传</h2>
 *
 * <p>更新走 {@code WHERE id = ? AND version = ?}，影响行数为 0 就是有人先改了。
 * <b>服务端自己读一遍 version 再写的话检查永远通过，那是装饰性的乐观锁</b>，
 * 所以 {@code ConnectorVO} / {@code ConnectorUpdateDTO} 里也都要有这个字段。
 *
 * <h2>★ 四、管理员能设的 {@code status} 只有 4 个值</h2>
 *
 * <p>{@code 2已占用} / {@code 3充电中} 归订单状态机所有：手工设 3 会凭空造出
 * 一把「充电中」却没有订单的枪，手工设 2 和 {@code uk_connector_current_order}
 * 的语义打架。白名单见
 * {@link com.chargeops.enumeration.ConnectorStatus#isAdminSettable()}。
 *
 * <h2>★ 五、{@code stationId} 是冗余列</h2>
 *
 * <p>值等于 {@code charger.station_id}，冗余一份可以免去每次 join {@code charger}。
 * 由 Service 派生填入，<b>绝不出现在任何 DTO 里</b> ——
 * 让客户端能传它就等于给冗余列开了第二条写入路径。
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
     * <p>★ 唯一索引 {@code uk_connector_current_order} 建在它上面，见类注释第二节。
     * 由订单状态机写入，P1a 不碰。
     */
    private Long currentOrderId;

    /**
     * 累计充电次数
     *
     * <p>由 {@code ChargingOrderServiceImpl.settle} 在<b>结算的同一个事务里</b>自增
     * （{@code ConnectorMapper.incrementChargeCount}）。★ 写成 SQL 侧的
     * {@code SET total_charge_count = total_charge_count + 1}，<b>不要先读出来再加一
     * 写回去</b> —— 后者在并发下会丢更新。
     *
     * <p>★★ <b>它数的是「结算成功的次数」，不是「车充过电的次数」。</b>
     * 经 {@code end()} 电表异常路径转入 {@code 9 异常挂账} 的订单永远走不到结算，
     * 因此不计入，而且没有东西能把 9 改回来。这是已知的取舍：
     * 那种订单的电量本身就算不出来，没有依据说它「充过一次」。
     * 要改成数「物理上充过几次」，正确的挂载点是 {@code end()} 的
     * {@code markEnded} 之后 —— 那里是「不可回滚的物理事实」，也是同样的一次性语义。
     *
     * <p>★ <b>它不是可编辑属性</b>，不出现在任何 DTO 里，见 {@code ConnectorDTO}。
     */
    private Integer totalChargeCount;

    /** 乐观锁版本号，从 0 开始。★ 更新时必须由客户端回传，见类注释第三节 */
    private Integer version;

    // ==================== 审计两列 ====================
    // ★ 没有 create_user / update_user —— 见类注释第一节。
    //    由 Service 显式 set，Mapper 的写方法不标 @AutoFill（标了会抛异常）。

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
