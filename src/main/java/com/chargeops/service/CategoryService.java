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
 * <p>{@link #page}（管理端动态条件分页）和 {@link #listEnabled}（下拉框，固定
 * {@code type + status=1}）是两条 SQL，不是一个接口 —— 用 {@link #page} 当下拉框的
 * 数据源会绕开 {@code idx_category_type_sort}，并且把停用的分类发给用户。
 *
 * <p>{@link #delete} 是本域唯一有守卫的方法：分类下面还挂着套餐时不能删。
 * 卫守在 Service 层（{@code CategoryMapper.deleteById} 是裸删除），因为它要读另一张表。
 *
 * <p>{@link #update} 改不了 {@code type}：不是「记得排除」，而是
 * {@code CategoryUpdateDTO} 里没有这个字段。
 */
public interface CategoryService {

    /**
     * 新增分类
     *
     * @throws com.chargeops.exception.ParamErrorException   {@code type} 不是合法值
     * @throws org.springframework.dao.DuplicateKeyException 同一 type 下分类名已存在
     */
    Long create(CategoryDTO categoryDTO);

    /**
     * 修改分类（名称 / 排序 / 状态）
     *
     * <p> 禁用分类不会让已挂在上面的套餐失效 —— 「停用」的语义是
     * 「不再出现在新建套餐的下拉框里」，不是「存量数据作废」。
     *
     * @throws org.springframework.dao.DuplicateKeyException 改名后撞上同 type 下的另一个分类
     */
    void update(Long id, CategoryUpdateDTO categoryUpdateDTO);

    /**
     * 删除分类（硬删）
     *
     * <p> 硬删只用于「名字建错了、下面一个套餐都没有」的场景，但必须支持，
     * 因为 {@code uk_category_type_name} 不看 {@code status}。
     *
     * @throws com.chargeops.exception.DataConflictException 该分类下还有充电套餐
     */
    void delete(Long id);

    /** 按 id 查详情。查不到抛 {@code NotFoundException} */
    CategoryVO getById(Long id);

    /** 管理端分页查询。{@code type} / {@code status} 都是精确匹配，不传就是「全部」 */
    PageResult page(CategoryPageQueryDTO query);

    /**
     * 查某个类型下已启用的全部分类，按 {@code sort} 升序
     *
     * <p> 不分页：分类是配置表，十行量级。{@code type} 可以传 {@code null}，
     * 但消费方必须明确知道自己要哪个 type。
     */
    List<CategoryVO> listEnabled(Integer type);
}
