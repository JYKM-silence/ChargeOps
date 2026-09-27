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
 * <p> 只追加：没有 {@code update_time} / {@code version} / {@code create_user}，落库后不再修改 ——
 * 会计凭证可以被红冲，不能被编辑，否则「上个月的余额是多少」就失去答案了。
 *
 * <p> {@code uk_txn_order_type (order_id, type)} 是「一笔订单最多一条扣款流水」的数据库级保证；
 * 唯一索引不约束 NULL，所以 {@code orderId} 为空的充值 / 调账流水不受它约束。
 *
 * <p> 存 {@code balanceBefore/After} 和 {@code frozenBefore/After} 四个快照而不是只存变动额：
 * 对账时不用重放全部流水就能验证余额正确性。
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
