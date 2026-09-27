package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 车牌颜色，对应 {@code vehicle.plate_color}
 *
 * <p> {@link #GREEN} / {@link #GRADIENT_GREEN} 是新能源车牌（大型 / 小型），
 * 政策不同，合并成一档以后就拆不开了。
 *
 * <p> 可空列，{@code null} 表达「不知道颜色」，所以没有 {@code UNKNOWN(0)} 这一档。
 * 这和 {@link Gender#UNKNOWN} 不同，那边 0 是 DDL 里的合法值域。
 */
public enum PlateColor {

    /** 蓝色 —— 普通燃油小型车 */
    BLUE(1, "蓝色"),

    /** 黄色 —— 大型车 / 营运车辆 */
    YELLOW(2, "黄色"),

    /** 绿色 —— 大型新能源车（黄绿双拼） */
    GREEN(3, "绿色"),

    /** 渐变绿色 —— 小型新能源车 */
    GRADIENT_GREEN(4, "渐变绿色");

    private final int code;

    private final String label;

    PlateColor(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 取值不在枚举里时抛，理由见 {@link Gender#of} */
    public static PlateColor of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("车牌颜色为空");
        }
        for (PlateColor color : values()) {
            if (color.code == code) {
                return color;
            }
        }
        throw new ParamErrorException("未知的车牌颜色：" + code);
    }
}
