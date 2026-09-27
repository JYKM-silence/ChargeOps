package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电桩状态，对应 {@code charger.status}
 *
 * <p> 桩状态是枪状态的汇总，不是独立事实，更新来源有两种：离线/在线由心跳决定
 * （{@code last_heartbeat_time} 超时即离线），是设备事实、人不该手工改；
 * 空闲/充电中由订单状态机在启动和结束时刷新。管理员手工能改的只有故障和维护中。
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
