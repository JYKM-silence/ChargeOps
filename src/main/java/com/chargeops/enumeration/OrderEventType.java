package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 订单事件类型，对应 {@code charging_order_event.event_type}
 *
 * <p>★ 这一列是 {@code varchar(32)} 而不是 {@code tinyint}，和其它枚举不一样 ——
 * 理由是<b>这张表给人看</b>：排查一笔金额不对的订单时，直接
 * {@code SELECT * FROM charging_order_event WHERE order_id = ?}
 * 看到的就是 {@code settle} / {@code duplicate_dropped}，不用开着枚举定义查数字。
 * 代价是<b>没有数据库层的取值约束</b>，所以 {@link #of} 读到未知字符串时
 * 抛异常而不是返回 null。
 *
 * <h2>★ {@link #DUPLICATE_DROPPED} 是一条被记录下来的「什么都没做」</h2>
 *
 * <p>「设备报了两次结束，第二次被丢了」和「设备只报了一次」在排查时是
 * <b>完全不同的两个结论</b>：前者说明设备或网关有重传，后者说明链路正常。
 * 只看到「订单结束了」这一条事实，两者无法区分 —— 这条记录的成本是一行。
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

    /** ★ 见类注释：一次被丢弃的重复事件。它是一次「什么都没做」的留痕 */
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
