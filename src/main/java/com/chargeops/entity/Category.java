package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分类（对应 {@code category} 表）—— {@code charge_package.category_id} 指向它
 *
 * <p>字段类型的两条约定见 {@link Staff} 的类注释。
 *
 * <p>唯一键是 {@code (type, name)} 而不是全局 name：「快充」既可能是套餐分类
 * 也可能是服务分类，所以同一个名字在 {@code type=1} 和 {@code type=2} 下各存一份
 * 是想要的行为，不是重复数据。反过来，判重必须带上 type —— 漏了的表现是
 * 「明明没重名却报已存在」（其实是另一个 type 下有一份）。
 *
 * <p>{@code type} 不可改（{@code CategoryUpdateDTO} 里没有这个字段）：
 * 它决定 {@code charge_package} 的归属合法性 —— 一个充电套餐只能挂在
 * {@code type=1} 的分类下，改 type 等于把分类连同下面的套餐从一个语义域
 * 搬到另一个，而套餐那边不会有任何反应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 分类名称。 唯一性受 {@code uk_category_type_name} 约束，范围是「同 type 内」；唯一键不看 status，下架的名字仍被占用 */
    private String name;

    /** {@code 1充电套餐分类 2增值服务分类} —— 见 {@link com.chargeops.enumeration.CategoryType} */
    private Integer type;

    /** 展示排序，小的在前。 不是唯一键，重复值由 {@code id} 兜底成稳定的全序 */
    private Integer sort;

    /** {@code 1启用 0停用}。 语义是「还出不出现在新建时的下拉框里」，不是「存量数据失效」——停用不会让已挂在上面的套餐失效或不可编辑 */
    private Integer status;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充，四列齐全所以可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
