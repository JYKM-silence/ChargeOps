package com.chargeops.mapper;

import com.chargeops.entity.Wallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钱包数据访问。四个更新方法都返回 {@code int}，调用方必须检查：
 * 返回 0 不是异常，是「余额不足」这个正常的业务结果。
 * 时间一律由 Java 侧传进来，不用 DDL 的 {@code ON UPDATE}（订单表、流水表、钱包表要用同一个时刻）。
 */
@Mapper
public interface WalletMapper {

    Wallet getByMemberId(@Param("memberId") Long memberId);

    /** 开卡，已存在则什么都不做。用 {@code INSERT IGNORE} 而不是先查再插：并发的首次充值会双双查到「没有钱包」 */
    int insertIgnore(Wallet wallet);

    /** 充值：可用余额和累计充值额一起加 */
    int recharge(@Param("memberId") Long memberId,
                 @Param("amount") BigDecimal amount,
                 @Param("updateTime") LocalDateTime updateTime);

    /**
     * 只加可用余额，不动 {@code total_recharge}。调用方负责保证 {@code amount > 0}：
     * 这里没有任何守卫，传负数就是静默扣钱。三个调用方里只有订单退款有
     * {@code uk_txn_order_type} 保护，另外两条的 {@code order_id} 是 NULL，而唯一索引不约束 NULL。
     */
    int credit(@Param("memberId") Long memberId,
               @Param("amount") BigDecimal amount,
               @Param("updateTime") LocalDateTime updateTime);

    /** 下单冻结：判据在 SQL 的 WHERE 里，不要在外面先查一次。返回 0 = 余额不足 */
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
