package com.chargeops.vo;

import com.chargeops.entity.WalletTransaction;
import com.chargeops.enumeration.WalletTransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车主端钱包流水视图
 *
 * <p>★ {@code type} 同时给出数字和中文标签：前端不该自己维护一份「1=充值 2=冻结」
 * 的映射 —— 那份映射一旦和枚举漂移，账单上会出现用户看不懂的数字，而且不报错。
 *
 * <p>★ {@code amount} 是<b>有向</b>的（正入账负出账），前端直接按符号着色即可，
 * 不要按 type 猜方向。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransactionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transactionNo;

    private Long orderId;

    private Integer type;

    private String typeLabel;

    private BigDecimal amount;

    private BigDecimal balanceAfter;

    private String remark;

    private LocalDateTime createTime;

    public static WalletTransactionVO from(WalletTransaction txn) {
        return WalletTransactionVO.builder()
                .transactionNo(txn.getTransactionNo())
                .orderId(txn.getOrderId())
                .type(txn.getType())
                .typeLabel(WalletTransactionType.of(txn.getType()).getLabel())
                .amount(txn.getAmount())
                .balanceAfter(txn.getBalanceAfter())
                .remark(txn.getRemark())
                .createTime(txn.getCreateTime())
                .build();
    }
}
