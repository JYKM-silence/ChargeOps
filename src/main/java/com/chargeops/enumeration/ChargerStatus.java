package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电桩状态
 *
 * <p>对应 {@code charger.status}：{@code 0离线 1在线空闲 2充电中 3故障 4维护中}。
 *
 * <h2>桩的状态和枪的状态是两回事</h2>
 *
 * <p>桩的状态是<b>枪状态的汇总</b>，不是独立事实：一台双枪桩，
 * 一把枪在充电、另一把空闲，桩应该显示成「充电中」。
 * 两者不同步的表现是用户在小程序上看到「桩空闲」，
 * 走过去发现两把枪都插着 —— 这是充电桩产品里最常见的投诉之一。
 *
 * <p>所以桩状态的更新有两种来源，别搞混：
 * <ul>
 *   <li><b>离线/在线</b> —— 由心跳决定（{@code last_heartbeat_time} 超时即离线），
 *       是设备上报的事实，人不该手工改</li>
 *   <li><b>空闲/充电中</b> —— 由订单状态机在启动和结束时顺带刷新</li>
 * </ul>
 * 管理员手工能改的只有「故障」和「维护中」这两个。
 */
public enum ChargerStatus {

    /** 离线 —— 心跳超时，设备侧失联 */
    OFFLINE(0, "离线"),

    /** 在线空闲 —— 至少一把枪可用 */
    IDLE(1, "在线空闲"),

    /** 充电中 —— 至少一把枪正在充电 */
    CHARGING(2, "充电中"),

    /** 故障 —— 设备上报的错误状态 */
    FAULT(3, "故障"),

    /** 维护中 —— 运维主动置位，不参与派单 */
    MAINTENANCE(4, "维护中");

    private final int code;

    private final String label;

    ChargerStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ChargerStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("充电桩状态为空");
        }
        for (ChargerStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的充电桩状态：" + code);
    }
}
