package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车主钱包，对应 {@code wallet} 表。
 *
 * <p> {@code balance}（可用，已扣掉冻结）和 {@code frozenAmount}（冻结）必须分两列 ——
 * 冻结不能让可用余额减少，否则就叫直接扣款了；本单可动用 = {@code balance} + 本单冻结额。
 *
 * <p> 所有写操作都是条件更新（如 {@code AND balance >= ?}），影响行数为 0 就是「余额不足」；
 * 「先 select 判断再 update」在并发下两个线程会读到同一个余额、各扣一次。
 *
 * <p> 本表没有 {@code create_user} / {@code update_user} 列，{@code WalletMapper.insert} 不能标 {@code @AutoFill}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wallet implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 一个车主一个钱包，{@code uk_wallet_member} 保证 */
    private Long memberId;

    /** 可用余额（已扣掉冻结） */
    private BigDecimal balance;

    /** 冻结金额（在充订单占用） */
    private BigDecimal frozenAmount;

    /** 累计充值额。 退款不减它 —— 它记录的是"充过多少"，不是"现在有多少" */
    private BigDecimal totalRecharge;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
