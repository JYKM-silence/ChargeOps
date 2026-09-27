package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电桩类型，对应 {@code charger.type}
 *
 * <p> 不是纯展示字段：套餐按桩类型分开，直流套餐不能在交流桩上核销。
 */
public enum ChargerType {

    /** 直流快充 —— 大功率，通常 60~240kW */
    DC_FAST(1, "直流快充"),

    /** 交流慢充 —— 小功率，通常 7~22kW */
    AC_SLOW(2, "交流慢充");

    private final int code;

    private final String label;

    ChargerType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static ChargerType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("充电桩类型为空");
        }
        for (ChargerType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的充电桩类型：" + code);
    }
}
