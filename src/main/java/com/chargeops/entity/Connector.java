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
 * <p>本表少 {@code create_user} / {@code update_user} 两列，实体绝不能标 {@code @AutoFill}：
 * {@link com.chargeops.mybatis.AutoFillInterceptor} 反射找不到 setter 会直接抛
 * {@code IllegalStateException}，所有写操作当场炸掉。
 *
 * <p>{@code uk_connector_current_order} 建在 {@code current_order_id} 上，用的正是
 * 「唯一索引不约束 NULL」：空闲枪可以有任意多把，同一个订单 id 却写不进第二把枪。
 *
 * <p>{@code version} 是乐观锁，更新走 {@code WHERE id = ? AND version = ?}，客户端必须回传；
 * {@code status} 的 {@code 2已占用} / {@code 3充电中} 归订单状态机，管理员只能设白名单。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Connector implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long chargerId;

    /**  冗余自 {@code charger.station_id}，由 Service 派生，不出现在 DTO 里 */
    private Long stationId;

    /** 枪口号 A/B/C。与 {@code charger_id} 组成唯一键 {@code uk_connector_charger_gun} */
    private String gunNo;

    /** 接口类型，如 {@code "GB/T 20234.3"}。自由文本，不做枚举 */
    private String connectorType;

    /** 额定功率 kW，{@code decimal(6,2)}，NOT NULL 且库无默认值（同 Charger） */
    private BigDecimal ratedPowerKw;

    /** {@code 0离线 1空闲 2已占用 3充电中 4故障 5维护}，管理员只能改白名单，见
     * {@link com.chargeops.enumeration.ConnectorStatus#isAdminSettable()} */
    private Integer status;

    /** 当前占用 / 在充的订单 id，空闲时为 {@code NULL}；唯一索引 {@code uk_connector_current_order} 建在它上面 */
    private Long currentOrderId;

    /** 累计充电次数 —— 数的是「结算成功的次数」，不是「车充过电的次数」（转 {@code 9 异常挂账} 的不计入）。
     * 由结算事务内 {@code SET total_charge_count = total_charge_count + 1} 自增；历史事实，不是可编辑属性，不在任何 DTO 里 */
    private Integer totalChargeCount;

    private Integer version;

    // ---------- 审计两列 ----------
    // 没有 create_user / update_user，写方法绝不能标 @AutoFill（标了会抛异常）；
    // 两个时间列由 Service 显式 set

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
