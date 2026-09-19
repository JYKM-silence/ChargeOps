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
 * <p>形状沿用 {@link StationService}，本域特有的部分只有下面三点。
 *
 * <h2>★ 一、{@link #page} 和 {@link #listEnabled} 是两个接口，不是一个</h2>
 *
 * <p>它们服务两个不同的场景，用的也是两条不同的 SQL
 * （管理端动态条件分页 / 下拉框固定 {@code type + status=1}）。
 * 完整对照表见 {@code CategoryPageQueryDTO} 的类注释 ——
 * 那里解释了「为什么不干脆用一个接口」。
 *
 * <p>★ 这里只补一句要紧的：{@link #listEnabled} 是
 * {@code idx_category_type_sort} 这条索引<b>唯一真正的消费方</b>。
 * 用 {@link #page} 当下拉框的数据源，不只是多写几行前端代码的事 ——
 * 它还绕开了这条索引，并且会<b>把停用的分类发给用户</b>。
 *
 * <h2>★★ 二、{@link #delete} 是本域唯一有守卫的方法</h2>
 *
 * <p>分类下面还挂着充电套餐时不能删 —— 见
 * {@code CategoryMapper.deleteById} 的注释：那个方法是裸删除，
 * 守卫在 Service 层，因为它要读另一张表。
 *
 * <p>★ 这是 {@code station → charger} 那条守卫的同形版本，
 * 也是同一个理由：级联删除看起来方便，但删掉一个分类会带走
 * 「下面那些套餐属于什么」这个信息，而套餐可能正在售出。
 *
 * <h2>★ 三、{@link #update} 改不了 {@code type}</h2>
 *
 * <p>不是「记得排除」，而是 {@code CategoryUpdateDTO} 里没有这个字段。
 * 完整论证见 {@code CategoryType} 的类注释第二节：
 * type 是业务事实（这个分类是什么），status 是运营开关（它还出不出现）。
 * 前者改了会让挂在下面的套餐跑到别的语义域，后者改了什么影响都没有。
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
     * <p>★ <b>不能改 {@code type}</b> —— 见接口注释第三节。
     *
     * <p>★ <b>禁用分类不会让已挂在上面的套餐失效。</b>
     * 「停用」的语义是「不再出现在新建套餐的下拉框里」，
     * 不是「存量数据作废」。完整论证见 {@code ChargePackageServiceImpl.requireCategory}。
     *
     * @throws com.chargeops.exception.NotFoundException      分类不存在
     * @throws org.springframework.dao.DuplicateKeyException  改名后撞上同 type 下的另一个分类
     */
    void update(Long id, CategoryUpdateDTO categoryUpdateDTO);

    /**
     * 删除分类（硬删）
     *
     * <p>★ 常规的「退役」手段是 {@code status = 0 停用}，硬删只用于
     * 「名字建错了、下面一个套餐都没有」的场景 —— 但那个场景必须支持，
     * 因为 {@code uk_category_type_name} 不看 {@code status}。
     * 理由和 {@code station} 完全一致，而 {@code member} 是<b>相反</b>的
     * （手机号是真实世界的标识），两边的理由都写在代码里。
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
     * <p>{@code type} / {@code status} 都是精确匹配且可传可不传 ——
     * 不传就是「全部」。见 {@code CategoryPageQueryDTO}。
     */
    PageResult page(CategoryPageQueryDTO query);

    /**
     * 查某个类型下<b>已启用</b>的全部分类，按 {@code sort} 升序
     *
     * <p>给下拉框用：新建套餐时选「充电套餐分类」（{@code type=1}），
     * 将来的增值服务页选 {@code type=2}。
     *
     * <p>★ <b>不分页。</b>分类是配置表，十行量级 ——
     * 给它套分页只会让前端必须处理「下拉框还得翻页」这个不存在的问题。
     *
     * <p>★ {@code type} 可以传 {@code null}（返回全部启用分类），
     * 但正常用法一定是明确传一个 —— 见 {@code CategoryType} 的类注释：
     * 消费方必须知道自己要哪个 type。
     */
    List<CategoryVO> listEnabled(Integer type);
}
