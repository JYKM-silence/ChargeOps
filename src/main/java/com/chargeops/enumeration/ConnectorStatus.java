package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电枪状态，对应 {@code connector.status}
 *
 * <p> 2 已占用和 3 充电中是订单存在性在枪上的投影，不是独立字段，所以管理端
 * 必须拒绝手工设置：手工设成 3 会造出一把没有订单的「充电中」的枪，用户扫码启动时
 * 条件更新不匹配，得到「该枪正在使用中」，而实际没人在用。
 *
 * <p> 离线是通信问题，故障是设备问题，合到一档就分不清现场该带万用表还是网线。
 */
public enum ConnectorStatus {

    /** 离线 —— 心跳超时，通信中断。管理员可设置 */
    OFFLINE(0, "离线"),

    /** 空闲 —— 可被扫码启动。管理员可设置 */
    IDLE(1, "空闲"),

    /**  已占用 —— 有订单占着但还没开始充电。归订单状态机所有，管理员不可设置 */
    OCCUPIED(2, "已占用"),

    /**  充电中 —— 正在输电。归订单状态机所有，管理员不可设置 */
    CHARGING(3, "充电中"),

    /** 故障 —— 设备上报的错误。管理员可设置 */
    FAULT(4, "故障"),

    /** 维护 —— 运维主动置位，不参与派单。管理员可设置 */
    MAINTENANCE(5, "维护");

    private final int code;

    private final String label;

    ConnectorStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 是否允许管理端手工设置。「哪些值归订单状态机所有」只有这一处定义，别在 Service 里另写一份 */
    public boolean isAdminSettable() {
        return this != OCCUPIED && this != CHARGING;
    }

    public static ConnectorStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("充电枪状态为空");
        }
        for (ConnectorStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的充电枪状态：" + code);
    }
}
