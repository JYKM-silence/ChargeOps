package com.chargeops.mapper;

import com.chargeops.entity.ChargingOrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单事件数据访问
 *
 * <p>★★ <b>只追加，没有 update / delete。</b>★★ 事件是「发生过什么」的记录，
 * 改写它等于伪造历史 —— 一次错误的流转应该被记录成
 * 「又一次流转把它改回来了」，而不是被抹掉。
 *
 * <h2>★ {@code insert} 撞 {@code uk_event_idempotent} 时怎么办</h2>
 *
 * <p>设备重传同一个事件时，第二行的 {@code idempotent_key} 撞唯一键抛
 * {@code DuplicateKeyException}。★ <b>但那时整个事务已经被标记为回滚了</b>
 * （Spring 在 RuntimeException 上标 rollback-only），
 * 所以<b>不能在 Service 里 catch 它然后继续提交</b>。
 *
 * <p>正确的顺序是：<b>先用订单状态的条件更新判断这一步做没做过</b>
 * （返回值 0 = 做过了），做过了就写一条 {@code duplicate_dropped} 事件
 * 然后正常返回；唯一索引只在「状态判断漏了」时才会被触发，
 * 那一刻应该整笔失败并告警。也就是说 {@code uk_event_idempotent} 和
 * {@code wallet_transaction.uk_txn_order_type} 是同一个角色 ——
 * <b>最后一道防线，不是第一道</b>。
 */
@Mapper
public interface ChargingOrderEventMapper {

    /**
     * 记一条事件
     *
     * <p>★ <b>不标 {@code @AutoFill}</b>：本表没有 {@code create_user} /
     * {@code update_user} 列，标了插件会反射找不到 setter 直接抛异常。
     * 操作人记录在 {@code operator_id} —— 它记的是「谁触发了这次流转」，
     * 设备上报时为 NULL。
     */
    int insert(ChargingOrderEvent event);

    /**
     * 某笔订单的完整流转历史，按时间升序
     *
     * <p>★ <b>升序</b>，和 {@code listByMember} 的倒序相反：排查时要读的是
     * 「先发生什么、后发生什么」。末尾补 {@code id ASC} 保证全序 ——
     * 同一毫秒的两条事件（比如「取消」和它引发的「解冻流水」）
     * 必须有一致的相对顺序。
     */
    List<ChargingOrderEvent> listByOrderId(@Param("orderId") Long orderId);
}
