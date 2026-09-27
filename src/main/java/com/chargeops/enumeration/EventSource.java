package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 订单事件的来源，对应 {@code charging_order_event.source}
 *
 * <p>★ 这一列决定两件和「谁能信」有关的事：<b>{@link #DEVICE} 的事件才带
 * {@code idempotent_key}</b>（设备会重传，要用 {@code device_no + event_seq}
 * 去重；用户点两次取消不会产生两个「取消」事件，它撞的是订单状态的条件更新），
 * 于是 {@code uk_event_idempotent} 只约束设备来源的行 —— 唯一索引不约束 NULL，
 * 这是它在本项目第四次被利用；以及<b>排查时「谁改的」有唯一答案</b>，
 * 一笔订单从 3 走到 4 是定时任务扫的还是用户点的，决定了出问题时该去看哪段代码。
 */
public enum EventSource {

    USER(1, "用户端"),

    DEVICE(2, "设备上报"),

    /** 定时任务（结算补偿、超时取消、支付超时挂账） */
    SCHEDULED(3, "定时任务"),

    /** 运维人工操作。★ 走这条路径必须留 {@code operator_id} */
    OPERATOR(4, "运维"),

    /** ★ 第三方支付回调。P1c 里没有真实接入，但枚举先留着 —— 它是 4 待支付存在的理由 */
    PAY_CALLBACK(5, "支付回调");

    private final int code;

    private final String label;

    EventSource(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** ★ 只有设备来源的事件参与 {@code idempotent_key} 去重 */
    public boolean needsIdempotentKey() {
        return this == DEVICE;
    }

    public static EventSource of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("事件来源为空");
        }
        for (EventSource source : values()) {
            if (source.code == code) {
                return source;
            }
        }
        throw new ParamErrorException("未知的事件来源：" + code);
    }
}
