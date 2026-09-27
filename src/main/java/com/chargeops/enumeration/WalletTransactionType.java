package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 钱包流水类型，对应 {@code wallet_transaction.type}
 *
 * <p> {@code uk_txn_order_type (order_id, type)} 只保护 order_id 必非空的四种
 * （FREEZE / UNFREEZE / DEDUCT / REFUND）。唯一索引不约束 NULL，其余四种
 * order_id 恒为 NULL，加新类型前先回答「它的 order_id 是什么」，
 * 没想清楚就写下去的症状是一条本该幂等的流水会安静地写下第二条。
 */
public enum WalletTransactionType {

    RECHARGE(1, "充值"),
    FREEZE(2, "冻结"),
    UNFREEZE(3, "解冻"),
    DEDUCT(4, "扣款"),
    REFUND(5, "退款"),

    /**
     * 用余额买套餐，扣的是 {@code balance}
     *
     * <p> order_id 恒为 NULL：直购模型下没有购买单，扣钱和拿到权益在同一个事务里。
     * 代价是不受 {@code uk_txn_order_type} 保护，真正的重复提交会产生两条流水、
     * 两份权益，好在钱对得上，属于一次真实的重复购买而不是资金错误。
     */
    PACKAGE_PURCHASE(6, "套餐购买"),

    /**
     * 管理端作废一份套餐权益时退回去的钱，和 {@link #REFUND} 是两回事，不要合并
     *
     * <p> 金额取 {@code member_package.pay_amount}（卖出时的价，不是 SKU 现价）；
     * 只退完全没用过的整份权益，不按剩余量折算。幂等靠
     * {@code MemberPackageMapper.voidPackage} 的条件更新兜着，不依赖流水。
     */
    PACKAGE_REFUND(8, "套餐退款"),

    /** 人工调账。唯一一个背后没有业务对象的类型，必须留 remark 说明原因。金额可正可负，0 被拒绝 */
    ADMIN_ADJUST(7, "管理员调账");

    private final int code;

    private final String label;

    WalletTransactionType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static WalletTransactionType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("流水类型为空");
        }
        for (WalletTransactionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的流水类型：" + code);
    }
}
