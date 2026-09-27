package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 分时电价的时段类型（尖峰平谷）
 *
 * <p>对应 {@code tariff_rule.period_type} 和 {@code charging_segment.period_type}，
 * 数据库里的取值是 {@code 1尖峰 2高峰 3平段 4低谷}。
 *
 * <h2>★ 不依赖 {@code ordinal()}</h2>
 *
 * <p>常量的声明顺序刻意写成了和价格降序一致，但代码里<b>绝不能用
 * {@code ordinal()} 去换算数据库值</b>：{@code SHARP_PEAK.ordinal()} 是 0，
 * 而库里存的是 1，差一。
 * 可怕之处在于<b>错了也不会报错</b>：哪天有人调换两个常量的声明顺序
 * （编译器完全不会提醒），所有靠 ordinal 换算的地方会一起错位，
 * 而金额算出来仍然「像模像样」，只是每一段都用错了价格。
 * 所以每个常量显式携带 {@link #code}，换算只走那个字段。
 *
 * <h2>★ 编号是价格降序，不是时间顺序</h2>
 *
 * <p>1 尖峰最贵、4 低谷最便宜，和一天中的先后<b>没有关系</b>。
 * 要按时间排序请用 {@code start_time}：种子里尖峰出现了两次
 * （10:00-12:00 与 19:00-21:00）、平段出现了三次，编号本身不构成时序。
 */
public enum PeriodType {

    /** 尖峰 —— 最贵。种子里是 10:00-12:00 和 19:00-21:00 */
    SHARP_PEAK(1, "尖峰"),

    /** 高峰 —— 次贵。种子里是 14:00-19:00 和 21:00-23:00 */
    PEAK(2, "高峰"),

    /** 平段 —— 中间价。种子里是三段，合计 8 小时 */
    FLAT(3, "平段"),

    /** 低谷 —— 最便宜。种子里是 00:00-08:00，电网负荷最低 */
    VALLEY(4, "低谷");

    /** 数据库里存的值。★ 换算只认它，不认 ordinal */
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

    /**
     * 按数据库值反查枚举
     *
     * <p><b>★ 越界时抛异常，而不是返回 {@code null}。</b>返回 null 的话，
     * 调用方（计费引擎）会在后面某处得到一个 null 的时段类型，症状是
     * 「某一段的费用算成了 0」或者一个看不出原因的 NPE ——
     * 两者都指向不了「数据库里那个值不合法」。这个值只可能是 1~4，
     * 越界说明 {@code tariff_rule} 被人手工改过，应该立刻知道。
     */
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
