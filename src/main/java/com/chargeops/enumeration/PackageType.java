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
 *   type=1 电量包        → energy_kwh      必填，其余三个为 NULL
 *   type=2 时长包        → duration_minutes 必填，其余三个为 NULL
 *   type=3 服务费折扣包  → service_discount 必填，其余三个为 NULL
 *   type=4 次卡          → times           必填，其余三个为 NULL
 * </pre>
 *
 * <p>MySQL 没有「条件必填」这种约束，所以这条规则<b>只能靠应用层守</b>
 * （DDL 注释里明写了这一点）。
 *
 * <h2>★ 为什么「其余三个必须为 NULL」这一半比「必填的那个要有」更重要</h2>
 *
 * <p>漏了必填，最多是这个套餐算不出东西来 —— 报错，看得见。
 * 但残留字段是<b>静默的</b>：一个从「150 度电包」改成「30 天时长包」的套餐，
 * 如果 {@code energy_kwh} 还留着 150，那么 P1c 应用套餐时读到它，
 * 就会在一个时长包上白白送出 150 度电。金额对不上，但每一步都没报错。
 *
 * <p>所以校验必须写成两个方向，不能只写「必填的字段在不在」。
 * 落地在 {@code ChargePackageServiceImpl.assertTypeFields}，
 * 而且更新时它校验的是<b>合并后的行</b>（DTO 的非空值覆盖到库里读出的行上），
 * 不是校验 DTO —— 因为只改价格的管理员不会传 {@code energyKwh}，
 * 那个字段的值只能从库里来。
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
