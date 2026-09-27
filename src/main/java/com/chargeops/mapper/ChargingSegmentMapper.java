package com.chargeops.mapper;

import com.chargeops.entity.ChargingSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计费分段数据访问
 *
 * <p>★★ <b>只有两个方法，而且没有 update / delete。</b>★★
 *
 * <p>分段是账单的<b>原始凭据</b>：{@code charging_order.total_amount}
 * 就是它们的和。能被修改的凭据不是凭据 —— 要改只能红冲（作废重算），
 * 而那是运维动作，不是这个 Mapper 的接口。
 *
 * <h2>★★ 结算幂等靠 {@code uk_segment_order_start}，不靠先查 ★★</h2>
 *
 * <p>结算算法重跑时，重复插入相同分段<b>撞唯一键</b>，而不是把金额翻倍。
 * 调用方拿到 {@code DuplicateKeyException} 时的正确反应见
 * {@code ChargingOrderServiceImpl.settle} —— 那里订单侧的状态条件更新
 * <b>先</b>跑，正常情况下这条唯一键根本不该被触发。
 */
@Mapper
public interface ChargingSegmentMapper {

    /**
     * 批量写入分段
     *
     * <p>★ 用一条多值 {@code INSERT} 而不是循环调用：分段数不多（跨天最多几十段），
     * 一次往返足够，而且「要么全进去要么一条都不进」不依赖事务边界兜底。
     *
     * <p>★ <b>列表不能为空</b> —— {@code <foreach>} 展开后
     * {@code INSERT INTO ... VALUES} 后面没有值，是 SQL 语法错误。
     * 走到这里时列表必然非空：{@code BillingEngine} 对零长度窗口会抛异常。
     *
     * <p>★ {@code createTime} 由调用方统一给同一个时刻。
     */
    void batchInsert(@Param("segments") List<ChargingSegment> segments);

    /**
     * 某笔订单的全部分段，按 {@code seq} 升序
     *
     * <p>★ 按 {@code seq} 而不是 {@code start_time} 排：{@code seq} 是引擎产出的
     * <b>顺序</b>（从 1 连续递增），而 {@code start_time} 万一有脏数据，
     * 排序会静默地把账单展示成错乱的顺序 ——
     * 展示顺序应该由引擎给的序号决定，不该依赖一个可以被写错的时间列。
     *
     * <p>★ 不分页：一笔订单的分段数以「十」计，账单要一次看全。
     */
    List<ChargingSegment> listByOrderId(@Param("orderId") Long orderId);
}
