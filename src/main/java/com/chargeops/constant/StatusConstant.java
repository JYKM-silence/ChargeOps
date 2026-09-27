package com.chargeops.constant;

/**
 * 通用的「启用 / 禁用」状态常量。只用于账号、套餐这类二值开关，多状态的字段各建自己的枚举，
 * 别把「已占用」「维护中」硬塞进 DISABLE。
 */
public class StatusConstant {

    public static final Integer ENABLE = 1;

    public static final Integer DISABLE = 0;
}
