package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分类（对应 {@code category} 表）
 *
 * <p>字段类型的两条约定见 {@link Staff} 的类注释。
 * 这张表只有五行业务字段，但有三处值得写下来。
 *
 * <h2>★ 一、{@code uk_category_type_name} 是<b>复合</b>唯一，不是全局唯一</h2>
 *
 * <p>老库写的是 {@code uk(name)}，新库改成了 {@code uk(type, name)}。
 * DDL 里的理由：「『快充』既可能是套餐分类也可能是服务分类，全局唯一会误伤」。
 *
 * <p>直接后果有两个，都容易踩：
 * <ul>
 *   <li>同一个名字<b>可以</b>在 {@code type=1} 和 {@code type=2} 下各存一份，
 *       这是想要的行为，不是重复数据</li>
 *   <li>因此「这个名字是不是已经被占了」<b>不能只按 name 查</b> ——
 *       必须带上 type。漏了之后表现是「明明没重名却报已存在」
 *       （其实是另一个 type 下有一份），排查起来很绕</li>
 * </ul>
 *
 * <p>★ 重名由数据库挡，Service 里<b>不写预检</b>，理由见 {@code StationMapper}
 * 的类注释（TOCTOU + 用应用层重复数据库的保证）。
 * 抛出的 {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler}
 * 翻译成「xxx已存在」。
 *
 * <h2>★ 二、{@code type} 是业务事实，不是标签 —— 所以不可改</h2>
 *
 * <p>它决定 {@code charge_package} 的归属合法性：
 * 一个充电套餐只能挂在 {@code type=1} 的分类下。
 * 允许改 type 等于允许把一个分类（连同下面挂着的套餐）
 * 从一个语义域搬到另一个，而套餐那边不会有任何反应。
 *
 * <p>所以 {@code CategoryUpdateDTO} 里<b>没有</b> {@code type} 字段 ——
 * 和 {@code ChargePackageUpdateDTO} 排除 {@code packageType} / {@code stationId}
 * 是同一个手法：<b>让不该能改的东西在接口形状上就改不了</b>，
 * 而不是靠 Service 里的一句 if。
 *
 * <h2>★ 三、索引 {@code (type, status, sort)} 是为下拉框选的，不是为管理端分页</h2>
 *
 * <p>DDL 明写了这个索引服务的查询：
 * 「列表查询固定是 {@code where type=? and status=1 order by sort}」——
 * 三个列正好按「过滤 → 过滤 → 排序」的顺序摆，全程走索引不产生 filesort。
 *
 * <p>而管理端的 {@code GET /api/admin/category/page} 是<b>动态条件</b>
 * （name 模糊、status 可不传、type 可不传），用不上这条索引。
 * ★ 但这<b>不是问题</b>：分类是配置表，十行量级，
 * 分页查询走全表扫描 + filesort 也无所谓 ——
 * 为它再建一条索引才是错的（一条永远只扫十行的索引）。
 *
 * <p>所以 {@code CategoryController} 上额外有第六个接口 {@code GET /list}，
 * 它才是那条索引真正的消费方。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 分类名称
     *
     * <p>★ 唯一性受 {@code uk_category_type_name (type, name)} 约束，
     * <b>范围是「同 type 内」</b>，见类注释第一节。
     *
     * <p>★ 和 {@code charge_package} 一样，唯一键<b>不看 status</b>：
     * 一个错别字分类名下架之后仍然永久占用那个名字，
     * 想用正确的名字只能先把它硬删掉。这是硬删接口必须存在的原因之一。
     */
    private String name;

    /** {@code 1充电套餐分类 2增值服务分类} —— 见 {@link com.chargeops.enumeration.CategoryType} */
    private Integer type;

    /** 展示排序，小的在前。★ 不是唯一键，重复值由 {@code id} 兜底成稳定的全序 */
    private Integer sort;

    /**
     * {@code 1启用 0停用}
     *
     * <p>★ 语义是「<b>还出不出现在新建时的下拉框里</b>」，
     * 不是「存量数据失效」。停用一个分类<b>不会</b>让
     * 已经挂在上面的套餐失效或不可编辑 ——
     * 和「停用计费模板不影响已有绑定」是同一个道理。
     */
    private Integer status;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，四列齐全所以可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
