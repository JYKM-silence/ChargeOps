package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 运维工单状态，对应 {@code maintenance_ticket.status}
 *
 * <p> 没有状态机、没有条件更新守卫，因为这里不涉及钱：转错了改回来就行。
 * 订单那边要 CAS 是因为「已支付」不能退回到「充电中」，工单没有这种单向门。
 *
 * <p> {@code DONE} 是唯一有下游依赖的一档：{@code MaintenanceTicketMapper.listResolvedByAlarmCode}
 * 只取 status = 2 的工单喂给 AI 当历史案例。别的状态不改这个过滤，改这一档要同时想清楚
 * 「未完成的工单被当成结论引用」这件事。
 */
public enum TicketStatus {

    /** 待处理 —— 刚创建，包括 AI 建的 */
    PENDING(0, "待处理"),

    /** 处理中 —— 已经有人在跟 */
    PROCESSING(1, "处理中"),

    /** 已完成 —— 结论写在 {@code content} 里，可以被当历史案例召回 */
    DONE(2, "已完成");

    private final int code;

    private final String label;

    TicketStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static TicketStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("工单状态为空");
        }
        for (TicketStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的工单状态：" + code);
    }
}
