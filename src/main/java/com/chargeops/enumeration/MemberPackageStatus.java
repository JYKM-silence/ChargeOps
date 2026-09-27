package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 持有权益的状态，对应 {@code member_package.status}
 *
 * <p> 1 生效中 / 4 已作废是人工写入的，2 已用尽 / 3 已过期是推导出来的。
 * status 是缓存，{@code expire_time} 和剩余量才是真相，涉及钱的判断不能只看它：
 * 写路径顺手刷新（结算的 UPDATE 里带 CASE 把用尽的那一刻落库），读路径兜底
 * （查可用权益的 SQL 同时带 {@code status = 1 AND expire_time > NOW()}）。
 *
 * <p> 4 是「已作废」不是「已退款」：退款是资金侧的事实，记在 wallet_transaction 里，
 * 作废原因由 {@code remark} 回答。
 */
public enum MemberPackageStatus {

    /** 正常可用。它不等于「现在能用」，还要看 expire_time 和剩余量 */
    ACTIVE(1, "生效中"),

    /** 剩余量归零，被结算的 UPDATE 顺手改过来 */
    EXHAUSTED(2, "已用尽"),

    /** 过了 expire_time，由读路径兜底判定 / 定时任务落库 */
    EXPIRED(3, "已过期"),

    /** 人为失效（退款、客服补偿、数据修复），唯一一个必须由人写入的状态 */
    VOIDED(4, "已作废");

    private final int code;

    private final String label;

    MemberPackageStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static MemberPackageStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("权益状态为空");
        }
        for (MemberPackageStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的权益状态：" + code);
    }
}
