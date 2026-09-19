package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电桩类型
 *
 * <p>对应 {@code charger.type}：{@code 1直流快充 2交流慢充}。
 *
 * <h2>为什么这个区分不是「分类标签」，而是业务事实</h2>
 *
 * <p>它直接影响三件事，所以不能当纯展示字段处理：
 * <ul>
 *   <li><b>计费</b>：直流桩的功率通常是交流桩的 10~20 倍，
 *       同样的服务费单价下，用户对「充 1 小时花了多少」的预期完全不同</li>
 *   <li><b>套餐</b>：{@code category} 表用 1直流快充套餐 / 2交流慢充套餐
 *       把套餐按桩类型分开，买了直流套餐的车主不能用交流桩核销</li>
 *   <li><b>电量分摊</b>：P1b 的计费引擎目前假设「全程恒功率、电量按时间正比」，
 *       这个假设对交流慢充基本成立，对直流快充（80% SoC 后降功率）不成立。
 *       等 P1d 拿到真实曲线后，分摊策略可能要按桩类型区分</li>
 * </ul>
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
