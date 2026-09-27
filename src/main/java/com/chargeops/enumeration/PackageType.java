package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电套餐类型
 *
 * <p>对应 {@code charge_package.package_type}：{@code 1电量包 2时长包 3服务费折扣包 4次卡}。
 *
 * <h2>★ 它决定了「哪些列必填」，这是这张表的要害</h2>
 *
 * <p>四种类型各自只用到一个专属列，其余三个必须是 NULL：
 * <pre>
 *   type=1 电量包        → energy_kwh       必填，其余三个必须为 NULL
 *   type=2 时长包        → duration_minutes 必填，其余三个必须为 NULL
 *   type=3 服务费折扣包  → service_discount 必填，其余三个必须为 NULL
 *   type=4 次卡          → times            必填，其余三个必须为 NULL
 * </pre>
 *
 * <p>MySQL 没有「条件必填」这种约束，这条规则<b>只能靠应用层守</b>。
 * ★「其余三个必须为 NULL」比「必填的那个要有」更重要：漏了必填最多是算不出东西、
 * 报错看得见；残留字段却<b>静默</b>—— 一个从「150 度电包」改成「30 天时长包」
 * 的套餐，如果 {@code energy_kwh} 还留着 150，应用套餐时就会白白送出 150 度电。
 *
 * <p>校验落在 {@code ChargePackageServiceImpl.assertTypeFields}，两个方向都查；
 * 更新时校验的是<b>合并后的行</b>而不是 DTO，因为只改价格的管理员不会传
 * {@code energyKwh}，那个值只能从库里来。
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
