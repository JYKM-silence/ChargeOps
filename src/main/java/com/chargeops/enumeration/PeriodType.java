package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 分时电价的时段类型（尖峰平谷），对应 {@code tariff_rule.period_type}
 *
 * <p> 换算只认 {@link #code}，绝不能用 {@code ordinal()}：{@code SHARP_PEAK.ordinal()}
 * 是 0 而库里存的是 1，差一，且错了不会报错。
 *
 * <p> 编号是价格降序，和一天中的先后无关，要按时间排序请用 {@code start_time}
 * （尖峰在种子里出现了两次）。
 */
public enum PeriodType {

    /** 尖峰 —— 最贵 */
    SHARP_PEAK(1, "尖峰"),

    /** 高峰 —— 次贵 */
    PEAK(2, "高峰"),

    /** 平段 —— 中间价 */
    FLAT(3, "平段"),

    /** 低谷 —— 最便宜 */
    VALLEY(4, "低谷");

    /** 数据库里存的值。换算只认它，不认 ordinal */
    private final int code;

    /** 中文标签，用于日志和报错信息 */
    private final String label;

    PeriodType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 越界时抛异常而不是返回 null：合法值只有 1~4，越界说明 {@code tariff_rule} 被人手工改过 */
    public static PeriodType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("计费时段类型为空");
        }
        for (PeriodType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的计费时段类型：" + code);
    }
}
