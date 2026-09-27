package com.chargeops.service;

import com.chargeops.dto.CategoryDTO;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.dto.CategoryUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.CategoryVO;

import java.util.List;

/**
 * 分类服务
 *
 * <p>形状沿用 {@link StationService}，本域特有的只有三点。
 *
 * <p>★ 一、{@link #page}（管理端动态条件分页）和 {@link #listEnabled}
 * （下拉框，固定 {@code type + status=1}）是两条 SQL，不是一个接口 ——
 * 对照表见 {@code CategoryPageQueryDTO}。{@link #listEnabled} 是
 * {@code idx_category_type_sort} 这条索引<b>唯一真正的消费方</b>，
 * 用 {@link #page} 当下拉框的数据源不只是多写几行前端代码，
 * 它还绕开了这条索引，并且<b>把停用的分类发给用户</b>。
 *
 * <p>★ 二、{@link #delete} 是本域唯一有守卫的方法：分类下面还挂着套餐时
 * 不能删。卫守在 Service 层（{@code CategoryMapper.deleteById} 是裸删除），
 * 因为它要读另一张表。级联删除看起来方便，但删掉一个分类会带走
 * 「下面那些套餐属于什么」这个信息，而套餐可能正在售出 ——
 * {@code station → charger} 是同形的守卫。
 *
 * <p>★ 三、{@link #update} 改不了 {@code type}：不是「记得排除」，
 * 而是 {@code CategoryUpdateDTO} 里没有这个字段。type 是业务事实
 * （这个分类是什么），status 是运营开关（它还出不出现），
 * 前者改了会让下面的套餐跑到别的语义域。见 {@code CategoryType}。
 */
public interface CategoryService {

    /**
     * 新增分类
     *
     * @return 新分类的 id
     * @throws com.chargeops.exception.ParamErrorException    {@code type} 不是合法值
     * @throws org.springframework.dao.DuplicateKeyException 同一 type 下分类名已存在
     */
    Long create(CategoryDTO categoryDTO);

    /**
     * 修改分类（名称 / 排序 / 状态）
     *
     * <p>★ <b>禁用分类不会让已挂在上面的套餐失效。</b>
     * 「停用」的语义是「不再出现在新建套餐的下拉框里」，
     * 不是「存量数据作废」。见 {@code ChargePackageServiceImpl.requireCategory}。
     *
     * @throws com.chargeops.exception.NotFoundException      分类不存在
     * @throws org.springframework.dao.DuplicateKeyException  改名后撞上同 type 下的另一个分类
     */
    void update(Long id, CategoryUpdateDTO categoryUpdateDTO);

    /**
     * 删除分类（硬删）
     *
     * <p>★ 常规的退役手段是 {@code status = 0 停用}；硬删只用于「名字建错了、
     * 下面一个套餐都没有」的场景 —— 但必须支持，因为
     * {@code uk_category_type_name} 不看 {@code status}。
     * {@code member} 是<b>相反</b>的决定（手机号是真实世界的标识）。
     *
     * @throws com.chargeops.exception.NotFoundException   分类不存在
     * @throws com.chargeops.exception.DataConflictException 该分类下还有充电套餐
     */
    void delete(Long id);

    /**
     * 按 id 查详情
     *
     * @throws com.chargeops.exception.NotFoundException 分类不存在
     */
    CategoryVO getById(Long id);

    /**
     * 管理端分页查询
     *
     * <p>{@code type} / {@code status} 都是精确匹配，不传就是「全部」。
     */
    PageResult page(CategoryPageQueryDTO query);

    /**
     * 查某个类型下<b>已启用</b>的全部分类，按 {@code sort} 升序
     *
     * <p>给下拉框用：新建套餐时选「充电套餐分类」（{@code type=1}），
     * 将来的增值服务页选 {@code type=2}。
     *
     * <p>★ <b>不分页。</b>分类是配置表，十行量级 ——
     * 套分页只会让前端必须处理「下拉框还得翻页」这个不存在的问题。
     *
     * <p>★ {@code type} 可以传 {@code null}（返回全部启用分类），
     * 但正常用法一定是明确传一个：消费方必须知道自己要哪个 type。
     */
    List<CategoryVO> listEnabled(Integer type);
}
