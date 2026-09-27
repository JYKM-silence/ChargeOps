package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 分类类型，对应 {@code category.type}
 *
 * <p> 充电套餐只能挂在 {@link #CHARGE_PACKAGE} 的分类下，但
 * {@code charge_package.category_id} 是裸的 bigint、没有外键，指错 type 不会撞任何
 * 约束，只表现为用户在套餐页里找不到它。校验在
 * {@code ChargePackageServiceImpl.requireCategory}。
 */
public enum CategoryType {

    /** 充电套餐分类 —— {@code charge_package.category_id} 只能指向这一档 */
    CHARGE_PACKAGE(1, "充电套餐分类"),

    /** 增值服务分类 —— 停车、洗车、代客充电等，P1c 没有消费方 */
    VALUE_ADDED(2, "增值服务分类");

    private final int code;

    private final String label;

    CategoryType(int code, String label) {
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
    public static CategoryType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("分类类型为空");
        }
        for (CategoryType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的分类类型：" + code);
    }
}
