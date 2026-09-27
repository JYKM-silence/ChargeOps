package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 车牌颜色
 *
 * <p>对应 {@code vehicle.plate_color}：{@code 1蓝 2黄 3绿 4渐变绿}。
 *
 * <h2>★ 为什么这个字段值得建枚举，而不是存一个 1-4 的裸数字</h2>
 *
 * <p>因为它<b>对业务有实际含义</b>，不是纯展示标签：
 * {@link #GREEN} / {@link #GRADIENT_GREEN} 是新能源车牌，这两种车才能享受
 * 新能源相关的套餐和电价政策；而 {@link #YELLOW} 里有相当一部分是营运车辆
 * （网约车、货车），充电行为特征和私家车完全不同。
 * 一个 {@code 3} 读不出上面任何一条，{@code PlateColor.GREEN} 可以。
 *
 * <h2>★ 「绿」和「渐变绿」为什么要分成两档</h2>
 *
 * <p>这是真实的车牌分类：小型新能源车是<b>渐变绿</b>（上白下绿的渐变色），
 * 大型新能源车（公交、货车）是<b>黄绿双拼</b>，数据库里归到 {@link #GREEN}。
 * 两者在新政下的路权和补贴政策不同，合并成一档就没法再拆开了 ——
 * 而拆分一个已经存了数据的枚举，比现在还多写一行要贵得多。
 *
 * <h2>★ 可空，且 {@code null} 与 {@code 0} 都不是合法值</h2>
 *
 * <p>{@code plate_color} 是表里少数几个<b>没有</b> {@code NOT NULL} 的列，
 * 车主可以只填车牌不填颜色。所以没有 {@code UNKNOWN(0)} 这一档：
 * 「不知道颜色」由 {@code null} 表达。这和 {@link Gender#UNKNOWN} 不同 ——
 * 那边 {@code 0} 是 DDL 里就存在的合法值域。
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
