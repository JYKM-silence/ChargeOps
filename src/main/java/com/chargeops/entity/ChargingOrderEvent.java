package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态流转事件（对应 {@code charging_order_event} 表）
 *
 * <h2>★ 这张表是可观测性资产，不是可有可无的日志</h2>
 *
 * <p>只有 {@code charging_order.status} 时你只能看到<b>终态</b>，
 * 有这张表才能回答「2→3 是设备上报的、3→4 是结算任务写的、
 * 4→4 是重复事件被丢的」—— 排查「金额为什么不对」时它是<b>唯一</b>能还原现场的东西。
 * 所以<b>每一次状态变化都必须写一行</b>，包括那些「什么都没做」的
 * （{@link com.chargeops.enumeration.OrderEventType#DUPLICATE_DROPPED}）。
 *
 * <h2>★ {@code idempotent_key}：唯一索引不约束 NULL，第四次被利用</h2>
 *
 * <p>值是 {@code device_no + event_seq}。非设备来源的事件这一列为 NULL、
 * <b>不受唯一约束</b>，只有设备上报的事件才参与去重。
 * ★ 前提是「设备有单调递增的事件序号」—— 只有 {@code device_no} 没有序号的话，
 * 同一设备短时间内两次真实事件会被误判成重复，那比不去重严重得多。
 *
 * <h2>★ {@code from_status} 可空</h2>
 *
 * <p>「创建」事件没有前一个状态。用 {@code 0} 或 {@code -1} 去填会制造一个
 * 不存在的状态值，让「按 from_status 统计流转」的查询多一个要特判的桶。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrderEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long orderId;

    /** 流转前的状态。★ {@code NULL} = 这是「创建」事件。见类注释 */
    private Integer fromStatus;

    private Integer toStatus;

    /** {@code create / start / end / settle / pay / refund / cancel / abnormal / duplicate_dropped} */
    private String eventType;

    /** {@code 1用户端 2设备上报 3定时任务 4运维 5支付回调} */
    private Integer source;

    /** 操作人 id。★ {@code source = 4 运维} 时必须有值 */
    private Long operatorId;

    /**
     * 设备原始报文 / 异常信息
     *
     * <p>★ <b>进 9 异常挂账时这一列必须有原因</b>，否则「订单为什么挂在这里」
     * 就只能靠人翻日志，而日志的保留期通常比订单短。
     */
    private String detail;

    /** ★ 设备事件去重键：{@code device_no + event_seq}。见类注释 */
    private String idempotentKey;

    /** ★ 由调用方显式给，本表没有 {@code create_user} / {@code update_user}，用不了 {@code @AutoFill} */
    private LocalDateTime createTime;
}
