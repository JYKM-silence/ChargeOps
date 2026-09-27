package com.chargeops.mapper;

import com.chargeops.entity.Wallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钱包数据访问。
 *
 * <p>★★ <b>四个更新方法都返回 {@code int}，调用方必须检查。</b>
 * 返回 0 不是异常，是一个正常的业务结果：「余额不足」。
 * 不检查的话，它会静默地变成「以为扣了钱，其实没扣」。
 *
 * <p>★ 每个方法都收 {@code updateTime} 而不是靠 DDL 的
 * {@code ON UPDATE CURRENT_TIMESTAMP(3)}：项目约定时间一律 Java 侧生成，
 * 这样同一笔订单的订单表、流水表、钱包表用的是<b>同一个时刻</b>，
 * 对账时才能对齐。
 */
@Mapper
public interface WalletMapper {

    Wallet getByMemberId(@Param("memberId") Long memberId);

    /**
     * 开卡。已存在则什么都不做（返回 0）
     *
     * <p>★ 用 {@code INSERT IGNORE} 而不是「先查再插」：后者是 TOCTOU，
     * 两个并发的首次充值会同时查到「没有钱包」然后都插入，
     * 其中一个撞 {@code uk_wallet_member} 抛异常 —— 一次本可避免的失败。
     */
    int insertIgnore(Wallet wallet);

    /** 充值：可用余额和累计充值额一起加 */
    int recharge(@Param("memberId") Long memberId,
                 @Param("amount") BigDecimal amount,
                 @Param("updateTime") LocalDateTime updateTime);

    /**
     * 只加可用余额，不动 {@code total_recharge}
     *
     * <p>★ <b>钱往回流的三条路都走这里</b>，<b>不能</b>走 {@link #recharge}：
     * {@code total_recharge} 记的是「充过多少」，退款回退它会让
     * 「累计充值」这个数字随消费和退款来回跳，失去意义。
     *
     * <pre>
     *   订单退款       WalletService.refund        （order_id 非空，有唯一索引兜底）
     *   套餐退款       WalletService.refundPackage （order_id 为 NULL）
     *   管理员调账加钱 WalletService.adjust        （order_id 为 NULL）
     * </pre>
     *
     * <p>★★ <b>只有第一条有 {@code uk_txn_order_type} 的保护。</b>★★
     * 另外两条的 {@code order_id} 是 NULL，唯一索引不约束 NULL ——
     * 它们各自的幂等靠调用方（见 {@code WalletTransactionType} 里那张表）。
     * <b>加第四个调用方之前先回答「它重复执行会怎样」。</b>
     *
     * <p>★ <b>调用方负责保证 {@code amount > 0}。</b>这个方法本身没有任何守卫，
     * 传负数就是<b>扣钱</b>（{@code balance = balance + (-x)}）——
     * 它不会报错，只会让余额静默地变少。{@code adjust} 走负数是靠
     * <b>调用 {@link #deduct} 而不是这里</b>来保证的，
     * 因为 {@code deduct} 的 {@code WHERE balance >= ?} 才是那道闸门。
     */
    int credit(@Param("memberId") Long memberId,
               @Param("amount") BigDecimal amount,
               @Param("updateTime") LocalDateTime updateTime);

    /**
     * 下单冻结：可用余额转入冻结
     *
     * <p>★ 余额是否够的判据在 SQL 的 {@code WHERE} 里，不要在外面先查一次。
     * 返回 0 = 余额不足，调用方据此拒绝下单。
     */
    int freeze(@Param("memberId") Long memberId,
               @Param("amount") BigDecimal amount,
               @Param("updateTime") LocalDateTime updateTime);

    /** 解冻：冻结转回可用。取消订单、结算时释放多冻的部分都走它 */
    int releaseFrozen(@Param("memberId") Long memberId,
                      @Param("amount") BigDecimal amount,
                      @Param("updateTime") LocalDateTime updateTime);

    /** 直接扣可用余额（充值余额支付、套餐购买） */
    int deduct(@Param("memberId") Long memberId,
               @Param("amount") BigDecimal amount,
               @Param("updateTime") LocalDateTime updateTime);
}
