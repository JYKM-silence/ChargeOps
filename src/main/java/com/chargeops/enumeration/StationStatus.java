package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电站状态，对应 {@code station.status}
 *
 * <p> 三态，不能塞进 {@code StatusConstant} 的二值开关：停用是运营决策，
 * 维护中是临时状态，合到一档前端就分不出「永久关闭」和「今天在检修」。
 */
public enum StationStatus {

    /** 停用 —— 运营上不再对外，用户端不可见 */
    DISABLED(0, "停用"),

    /** 启用 —— 正常对外营业 */
    ENABLED(1, "启用"),

    /** 维护中 —— 临时状态，用户端应显示为不可用但在修 */
    MAINTENANCE(2, "维护中");

    private final int code;

    private final String label;

    StationStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static StationStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("站点状态为空");
        }
        for (StationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的站点状态：" + code);
    }
}
