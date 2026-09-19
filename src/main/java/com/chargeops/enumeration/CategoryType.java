package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 分类类型
 *
 * <p>对应 {@code category.type}：{@code 1充电套餐分类 2增值服务分类}。
 *
 * <h2>★★ 这个字段不是为了「分类一下好看」，它是消费方必须自己声明的东西</h2>
 *
 * <p>老库的分类表是 {@code uk(name)} <b>全局唯一</b>，新库改成了
 * {@code uk(type, name)} 复合唯一。DDL 里写了原因：
 * 「『快充』既可能是套餐分类也可能是服务分类，全局唯一会误伤」。
 *
 * <p>但拆出 {@code type} 之后有个<b>新的责任落到消费方头上</b>：
 * 一个「充电套餐」只能挂在 {@link #CHARGE_PACKAGE} 的分类下，
 * 而 {@code charge_package.category_id} 是裸的 {@code bigint}，
 * <b>没有任何 DDL 约束能表达这件事</b> —— 全项目没有外键。
 *
 * <p>所以指错 type <b>不会撞任何约束</b>。后果是静默的：
 * 一个充电套餐被挂到「停车服务」分类下，列表页显示正常、
 * 套餐自己一切正常，只有用户在充电套餐页里怎么都找不到它 ——
 * 因为那个页面按 {@code type=1} 过滤。
 * <b>拆 type 本来是为了消除歧义，不在消费方校验就等于只增加了歧义。</b>
 *
 * <p>落地在 {@code ChargePackageServiceImpl.requireCategory}：
 * 查出来的分类 type 必须是 {@link #CHARGE_PACKAGE}，否则抛
 * {@code CATEGORY_TYPE_MISMATCH}。
 *
 * <h2>★ 分类的 {@code status} 和 {@code type} 是两件事，别混</h2>
 *
 * <ul>
 *   <li>{@code type} 是<b>业务事实</b>：这个分类是什么。改了它，
 *       挂在下面的套餐就跑到别的语义域里去了。所以
 *       {@code CategoryUpdateDTO} 里<b>没有</b> {@code type} 字段 ——
 *       和 {@code ChargePackageUpdateDTO} 排除 {@code packageType} 同一个理由</li>
 *   <li>{@code status} 是<b>运营开关</b>：这个分类现在还出不出现在下拉框里。
 *       它随时可改，而且改它<b>不影响存量数据</b> ——
 *       停用一个分类之后，已经挂在上面的套餐照常能改价、照常能卖</li>
 * </ul>
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
