package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 分类类型
 *
 * <p>对应 {@code category.type}：{@code 1充电套餐分类 2增值服务分类}。
 *
 * <h2>★ 拆出这个字段，消费方就多了一份责任</h2>
 *
 * <p>老库的分类表是 {@code uk(name)} <b>全局唯一</b>，新库改成了
 * {@code uk(type, name)} 复合唯一 ——「『快充』既可能是套餐分类也可能是服务分类，
 * 全局唯一会误伤」。但一个「充电套餐」只能挂在 {@link #CHARGE_PACKAGE} 的分类下，
 * 而 {@code charge_package.category_id} 是裸的 {@code bigint}、全项目没有外键，
 * <b>没有任何 DDL 约束能表达这件事</b>。
 *
 * <p>所以指错 type <b>不会撞任何约束</b>，后果是静默的：一个充电套餐被挂到
 * 「停车服务」分类下，列表页显示正常、套餐自己一切正常，只有用户在充电套餐页里
 * 怎么都找不到它（那个页面按 {@code type=1} 过滤）。
 * <b>拆 type 本来是为了消除歧义，不在消费方校验就等于只增加了歧义。</b>
 * 落地在 {@code ChargePackageServiceImpl.requireCategory}：非
 * {@link #CHARGE_PACKAGE} 抛 {@code CATEGORY_TYPE_MISMATCH}。
 *
 * <h2>★ 分类的 {@code status} 和 {@code type} 是两件事</h2>
 *
 * <p>{@code type} 是<b>业务事实</b>，改了它挂在下面的套餐就跑到别的语义域里去了，
 * 所以 {@code CategoryUpdateDTO} 里<b>没有</b> {@code type} 字段；
 * {@code status} 是<b>运营开关</b>，随时可改且<b>不影响存量数据</b> ——
 * 停用一个分类之后，已经挂在上面的套餐照常能改价、照常能卖。
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
