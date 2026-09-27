package com.chargeops.vo;

import com.chargeops.entity.Wallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 车主端钱包视图
 *
 * <p> 可用余额和冻结金额分两个字段返回，不做成一个"总额" —— 界面上要同时
 * 看到"可用 / 冻结"两行。合成一个数的话，用户会以为被冻结的钱已经花掉了。
 *
 * <p> 没有开过卡的车主返回 {@code 0}，而不是 404。「还没充过钱」和「钱包不存在」
 * 对用户是同一件事。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal balance;

    private BigDecimal frozenAmount;

    /**
     * 累计充值额
     *
     * <p> 它不等于历史充值之和 —— 退款不减它，消费也不减它。展示时要说清是
     * "累计充值"，说成"总收入"会让用户对不上账。
     */
    private BigDecimal totalRecharge;

    /** 没开过卡时的零钱包 */
    public static WalletVO empty() {
        return WalletVO.builder()
                .balance(BigDecimal.ZERO)
                .frozenAmount(BigDecimal.ZERO)
                .totalRecharge(BigDecimal.ZERO)
                .build();
    }

    public static WalletVO from(Wallet wallet) {
        if (wallet == null) {
            return empty();
        }
        return WalletVO.builder()
                .balance(wallet.getBalance())
                .frozenAmount(wallet.getFrozenAmount())
                .totalRecharge(wallet.getTotalRecharge())
                .build();
    }
}
