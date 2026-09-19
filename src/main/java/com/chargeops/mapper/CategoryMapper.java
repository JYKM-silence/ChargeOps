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
 * <h2>★ 一、六个读/写方法，其中 {@code listEnabled} 才是索引真正的消费方</h2>
 *
 * <table border="1">
 *   <caption>两条查询路径，服务两个完全不同的场景</caption>
 *   <tr><th>方法</th><th>场景</th><th>走索引吗</th></tr>
 *   <tr><td>{@link #listEnabled}</td><td>下拉框 / 选分类</td>
 *       <td><b>走</b> {@code idx_category_type_sort}</td></tr>
 *   <tr><td>{@link #page}</td><td>管理端分类列表</td><td>不走（动态条件）</td></tr>
 * </table>
 *
 * <p>DDL 明写了那条索引服务的查询：
 * 「列表查询固定是 {@code where type=? and status=1 order by sort}」——
 * 过滤两列 + 排序列正好按索引的列序摆放，全程不产生 filesort。
 *
 * <p>★ 而管理端的 {@code page} 是<b>动态条件</b>（name 模糊、type/status 可不传），
 * 用不上它。这<b>不是问题</b>：分类是配置表，十行量级，
 * 全表扫描 + filesort 的代价可以忽略 ——
 * 为它再建一条索引才是错的（一条永远只扫十行的索引）。
 * <b>把「哪条查询该走索引」写清楚，比给每条查询都加索引有价值。</b>
 *
 * <h2>★ 二、{@code uk_category_type_name} 是复合唯一，预检会很坑</h2>
 *
 * <p>唯一键是 {@code (type, name)}，不是 {@code (name)}。
 * 所以「这个名字是不是被占了」<b>必须带上 type 一起判</b>，
 * 只按 name 判会得出「另一个 type 下有一份，所以你不能用这个名字」——
 * 一个和 DDL 意图相反、而且看起来像 bug 的结果。
 *
 * <p>★ 本项目的做法是<b>不写预检</b>（理由见 {@code StationMapper} 的类注释：
 * TOCTOU + 用应用层重复数据库的保证），让 {@code uk_category_type_name}
 * 去挡，抛出的 {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler}
 * 翻译成「xxx已存在」。这样一来那个「带 type 一起判」的坑也<b>不存在了</b> ——
 * 数据库判的本来就是复合键。这是「不预检」除正确性之外的第二个收益。
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
     *
     * <p>★ {@code type} 可传可不传：传了就是某一类（正常用法），
     * 不传就返回全部启用分类（管理端的「全部」视图）。
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
     * <p>★ 这条 SQL <b>不碰 {@code type}</b>。它不是「记得排除」，
     * 而是 {@code CategoryUpdateDTO} 里根本没有那个字段 ——
     * type 决定 {@code charge_package} 的归属合法性，改它等于把一个分类
     * （连同挂着的套餐）搬到另一个语义域，而套餐那边不会有任何反应。
     * 完整论证见 {@code Category} 的类注释第二节。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Category category);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ <b>这是裸删除，不做任何守卫。</b>调用方必须先确认这个分类下
     * 没有套餐 —— {@code CategoryServiceImpl.delete} 会查一次
     * {@code ChargePackageMapper.countByCategoryId}，不为 0 就抛
     * {@code DataConflictException}。
     *
     * <p>守卫放在 Service 而不是这里，是因为它要读另一张表（{@code charge_package}），
     * 而 Mapper 层的职责是「一条 SQL」，不是「一个业务规则」。
     * 这个分工和 {@code StationMapper.deleteById} 完全一致。
     *
     * <p>★ 为什么硬删必须存在（而不是只给个 status=0 停用）：
     * {@code uk_category_type_name} 不看 {@code status}，
     * 一个错别字分类名下架之后仍然永久占用那个名字 ——
     * 没有硬删就只能去生产库手敲 DELETE。
     * 这和 {@code station} 是同一个理由，而 {@code member} 是<b>相反</b>的
     * （手机号是真实世界的标识，不存在建错的场景），
     * 两边各自的理由都写在代码里。
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
