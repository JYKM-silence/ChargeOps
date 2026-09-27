package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 持有权益的状态
 *
 * <p>对应 {@code member_package.status}：
 * {@code 1生效中 2已用尽 3已过期 4已作废}。
 *
 * <h2>★ 前两个和后两个是<b>不同性质</b>的取值</h2>
 *
 * <pre>
 *   人工写入（状态迁移）      推导出来的（事实的投影）
 *   ────────────────────    ──────────────────────────
 *   1 生效中                 2 已用尽   ← 剩余量归零
 *   4 已作废                 3 已过期   ← 过了 expire_time
 * </pre>
 *
 * <p><b>所以判断「这份权益现在能不能用」时，只看 status 是错的。</b>
 * 项目里的做法是两条路径并存：<b>写路径顺手刷新</b>（结算的 UPDATE 里带一个
 * {@code CASE WHEN remaining <= 0 THEN 2 ELSE status END}，用尽的那一刻就落库）、
 * <b>读路径兜底</b>（查「可用权益」的 SQL 同时带
 * {@code status = 1 AND expire_time > NOW()}）—— 这样哪怕定时任务一次都没跑、
 * 状态列全是陈旧的 1，也不会把一份过期的权益发出去。
 *
 * <p>★ 那为什么不干脆把 status 列删掉、只在读的时候算：车主端的列表要显示
 * 「已用尽 / 已过期」的灰态，而运营看「本月过期了多少份」时一条
 * {@code WHERE status = 3} 比一坨时间函数直观得多。
 *
 * <p><b>结论：status 是缓存，{@code expire_time} 和剩余量才是真相。</b>
 * 任何涉及钱的判断都不能只看 status。
 *
 * <h2>★ 为什么 4 是「已作废」而不是「已退款」</h2>
 *
 * <p>退款是资金侧的事实，记录在 {@code wallet_transaction} 里；权益侧只关心
 * 「这份权益还能不能用」。混进退款状态会导致一个问题：一笔部分退款该算什么状态？
 * 作废的原因（退款、客服补偿、数据修复）由 {@code remark} 和资金流水回答。
 */
public enum MemberPackageStatus {

    /** 正常可用。★ 注意：它**不等于**「现在能用」，还要看 expire_time 和剩余量 */
    ACTIVE(1, "生效中"),

    /** 剩余量归零，被结算的 UPDATE 顺手改过来 */
    EXHAUSTED(2, "已用尽"),

    /** 过了 expire_time，由读路径兜底判定 / 定时任务落库 */
    EXPIRED(3, "已过期"),

    /** 人为失效（退款、客服补偿、数据修复）—— 唯一一个必须由人写入的状态 */
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
