package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 性别，对应 {@code member.gender}：{@code 0未知 1男 2女}
 *
 * <p> 不要和 {@code staff.sex} 互相套用：那边是旧系统沿袭下来的 {@code char(1)}，
 * {@code '0'女 '1'男}，没有「未知」。照抄过去会静默地把性别存反。
 */
public enum Gender {

    /** 未知 —— 注册时没填就是这一档，它是一个合法的终态，不是「没填」 */
    UNKNOWN(0, "未知"),

    /** 男 */
    MALE(1, "男"),

    /** 女 */
    FEMALE(2, "女");

    private final int code;

    private final String label;

    Gender(int code, String label) {
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
     * 按数据库里存的值取枚举
     *
     * <p> 取值不在枚举里时抛异常，不返回 {@code null} —— 静默的 null 会一路漂到
     * 前端显示成空白。但只读路径要 catch 后降级：一行脏数据不该让整个列表 500
     * （降级写法见 {@code StationServiceImpl.labelOf}）。写路径严格，读路径降级。
     */
    public static Gender of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("性别为空");
        }
        for (Gender gender : values()) {
            if (gender.code == code) {
                return gender;
            }
        }
        throw new ParamErrorException("未知的性别：" + code);
    }
}
