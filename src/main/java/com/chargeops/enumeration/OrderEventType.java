package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 订单事件类型，对应 {@code charging_order_event.event_type}
 *
 * <p> 这一列是 {@code varchar(32)} 而不是 tinyint，因为这张表是给人排查用的，
 * 直接 SELECT 就能看懂，不用对着枚举定义查数字。
 */
public enum OrderEventType {

    CREATE("create"),

    /** 设备上报启动成功 */
    START("start"),

    /** 设备上报停止 */
    END("end"),

    /** 结算完成，金额已定 */
    SETTLE("settle"),

    PAY("pay"),

    REFUND("refund"),

    CANCEL("cancel"),

    /** 转入异常挂账 */
    ABNORMAL("abnormal"),

    /** 一条被丢弃的重复事件。「设备报了两次」和「设备只报了一次」靠它区分 */
    DUPLICATE_DROPPED("duplicate_dropped");

    private final String code;

    OrderEventType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static OrderEventType of(String code) {
        if (code == null) {
            throw new ParamErrorException("订单事件类型为空");
        }
        for (OrderEventType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new ParamErrorException("未知的订单事件类型：" + code);
    }
}
