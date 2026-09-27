package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电套餐类型，对应 {@code charge_package.package_type}
 *
 * <p> 四种类型各只用一个专属列，其余三个必须为 NULL：
 * 1 电量包→energy_kwh、2 时长包→duration_minutes、
 * 3 服务费折扣包→service_discount、4 次卡→times。
 * MySQL 没有条件必填约束，只能靠应用层守，「其余三个为 NULL」比「必填的有值」
 * 更要紧：残留字段是静默的，从「150 度电包」改成「30 天时长包」的套餐
 * 会白白送出 150 度电。校验在 {@code ChargePackageServiceImpl.assertTypeFields}，
 * 更新时校验的是合并后的行而不是 DTO。
 */
public enum PackageType {

    /** 电量包 —— 「100 元充 150 度」 */
    ENERGY(1, "电量包"),

    /** 时长包 —— 「30 元充 2 小时」 */
    DURATION(2, "时长包"),

    /** 服务费折扣包 —— 「服务费八折」，按 {@code service_discount} 打折 */
    SERVICE_DISCOUNT(3, "服务费折扣包"),

    /** 次卡 —— 「50 元充 5 次」，与电量时长无关 */
    TIMES(4, "次卡");

    private final int code;

    private final String label;

    PackageType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static PackageType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("套餐类型为空");
        }
        for (PackageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的套餐类型：" + code);
    }
}
