package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 钱包流水，对应 {@code wallet_transaction} 表。
 *
 * <p>★ 这张表是<b>只追加</b>的：没有 {@code update_time}、没有 {@code version}，
 * 任何字段落库后都不再修改 —— 会计凭证可以被红冲，不能被编辑，
 * 否则「上个月的余额是多少」这个问题就失去答案了。
 *
 * <p>★ 存了 {@code balanceBefore/After} 和 {@code frozenBefore/After} 四个快照
 * 而不是只存变动额：对账时不用重放全部流水就能验证余额正确性。
 *
 * <p>★ 同样没有 {@code create_user}，同样不能用 {@code @AutoFill}；
 * {@code orderId} 为 NULL 是合法状态，与 {@code uk_txn_order_type} 的配合
 * 见 {@link WalletTransactionType}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 业务流水号，{@code uk_txn_no} 保证唯一。对外展示用，不暴露自增 id */
    private String transactionNo;

    private Long memberId;

    /** NULL = 不属于任何订单（充值 / 调账） */
    private Long orderId;

    /** 见 {@link com.chargeops.enumeration.WalletTransactionType} */
    private Integer type;

    /** 正入账、负出账 */
    private BigDecimal amount;

    private BigDecimal balanceBefore;

    private BigDecimal balanceAfter;

    private BigDecimal frozenBefore;

    private BigDecimal frozenAfter;

    private String remark;

    private LocalDateTime createTime;
}
