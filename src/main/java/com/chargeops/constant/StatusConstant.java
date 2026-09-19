package com.chargeops.constant;

/**
 * 通用的「启用 / 禁用」状态常量
 *
 * <p><b>只用于「账号、套餐」这类只有两态的开关。</b>
 * 充电枪（connector）有 6 种状态、充电订单有 9 种状态、
 * <b>充电站（station）有 3 种状态</b>，那些不要复用这两个常量 ——
 * 一旦把「已占用」或「维护中」硬塞进 DISABLE 的语义里，
 * 读代码的人就再也无法从常量名看出真实含义了。
 *
 * <p>那些多状态字段的正确做法是各建一个枚举，
 * 并且让枚举值直接对应数据库里存的数字，避免出现
 * 「数据库存 3 表示充电中，Java 里枚举 ordinal 是 2」这种要减一的转换。
 */
public class StatusConstant {

    /** 启用 */
    public static final Integer ENABLE = 1;

    /** 禁用 */
    public static final Integer DISABLE = 0;
}
