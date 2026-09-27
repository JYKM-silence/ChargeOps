package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.entity.Category;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分类数据访问
 *
 * <h2>★ 一、只有 {@code listEnabled} 是 {@code idx_category_type_sort} 的消费方</h2>
 *
 * <p>它的条件是「过滤两列 + 排序列」，正好按索引列序摆放，全程不产生 filesort。
 *
 * <p>★ 管理端的 {@code page} 是<b>动态条件</b>，用不上那条索引 ——
 * 而这<b>不是问题</b>：分类是十行量级的配置表，全表扫描 + filesort 的代价
 * 可以忽略，为它再建一条索引才是错的。
 *
 * <h2>★ 二、{@code uk_category_type_name} 是复合唯一</h2>
 *
 * <p>唯一键是 {@code (type, name)}，不是 {@code (name)}。所以「这个名字
 * 是不是被占了」<b>必须带上 type 一起判</b>，只按 name 判会得出
 * 「另一个 type 下有一份，所以你不能用这个名字」—— 一个和 DDL 意图相反的结果。
 *
 * <p>★ 本项目<b>不写预检</b>（TOCTOU，理由见 {@code StationMapper} 的类注释），
 * 让唯一键去挡、由 {@code GlobalExceptionHandler} 翻译成「xxx已存在」——
 * 那个「带 type 一起判」的坑也就不存在了，数据库判的本来就是复合键。
 */
@Mapper
public interface CategoryMapper {

    /**
     * 按主键查
     *
     * <p>{@code null} 表示不存在。调用方必须处理 —— 管理端按 id 查要 404，
     * 套餐那边要在保存前确认分类存在且 type 匹配。
     */
    Category getById(@Param("id") Long id);

    /**
     * 查某个类型下<b>已启用</b>的全部分类，按 {@code sort} 升序
     *
     * <p>这是给「选分类」用的：新建套餐时的下拉框、将来的增值服务选择页。
     * 只返回 {@code status = 1} —— 停用的分类不该出现在「让你选一个」的列表里。
     * {@code type} 可传可不传：不传就是管理端的「全部启用」视图。
     *
     * <p>★ {@code sort} 不是唯一键，重复值靠 {@code id} 兜底成稳定的全序 ——
     * 没有这个兜底，两个 sort 相同的分类在两次请求里顺序可能不同。
     */
    List<Category> listEnabled(@Param("type") Integer type);

    /**
     * 新增分类
     *
     * <p>{@link AutoFill} 填四个审计字段（本表四列齐全），
     * {@code useGeneratedKeys} 回填自增主键。
     *
     * <p>★ {@code sort} 和 {@code status} 都是 {@code NOT NULL} 且走全列插入，
     * 传 null 会 errno 1048 —— 默认值由 {@code CategoryDTO} 的
     * 字段初始值提供，Service 再兜一次。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Category category);

    /**
     * 按主键更新非空字段
     *
     * <p>★ 这条 SQL <b>不碰 {@code type}</b> —— {@code CategoryUpdateDTO} 里
     * 根本没有那个字段。改 type 等于把一个分类（连同挂着的套餐）
     * 搬到另一个语义域，而套餐那边不会有任何反应。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Category category);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ <b>这是裸删除，不做任何守卫。</b>调用方
     * （{@code CategoryServiceImpl.delete}）必须先查
     * {@code ChargePackageMapper.countByCategoryId} —— 守卫要读另一张表，
     * 而 Mapper 层的职责是「一条 SQL」，不是「一个业务规则」。
     *
     * <p>★ 硬删必须存在（而不是只给个 {@code status = 0} 停用）：
     * {@code uk_category_type_name} 不看 {@code status}，
     * 一个错别字分类名下架之后仍然永久占用那个名字。
     */
    @Delete("DELETE FROM category WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /**
     * 管理端分页查询
     *
     * <p><b>★ 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，
     * 中间不能插入任何别的查询。</b>见 {@code StationMapper.page}。
     *
     * <p>★ 这个方法<b>不做富化</b>（不补「该分类下有 N 个套餐」）。
     * 将来要补的话，那条 COUNT 必须放在分页查询<b>之后</b>，
     * 否则它会吃掉 {@code startPage} 放进 ThreadLocal 的那对参数 ——
     * 表现是主查询没有 LIMIT（返回全表）而 COUNT 被 LIMIT 10。
     */
    List<Category> page(CategoryPageQueryDTO query);
}
