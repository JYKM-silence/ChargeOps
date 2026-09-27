package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.dto.CategoryDTO;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.dto.CategoryUpdateDTO;
import com.chargeops.entity.Category;
import com.chargeops.enumeration.CategoryType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.CategoryMapper;
import com.chargeops.mapper.ChargePackageMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.CategoryService;
import com.chargeops.vo.CategoryVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import java.util.List;

/**
 * 分类服务实现
 *
 * <p> 本类没有任何 {@code BaseContext} 的引用：分类是全局配置，不属于任何车主，
 * 管理端的鉴权由拦截器在 {@code /api/admin/**} 上完成。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * 这个依赖只服务 {@link #assertDeletable} 里的删除守卫。
     *
     * <p> 依赖对方的 Mapper 而不是对方的 Service：后者已经反向依赖
     * {@code CategoryMapper}，两个 Service 互相注入是 Spring 允许但很难读的结构。
     */
    private final ChargePackageMapper chargePackageMapper;

    @Override
    @Transactional
    public Long create(CategoryDTO categoryDTO) {
        // CategoryDTO 上的 @Min(1) @Max(2) 是复制的约束：枚举里加第三个值时
        // 它不会跟着变。CategoryType.of() 读的就是枚举本身，是唯一真本。
        CategoryType type = CategoryType.of(categoryDTO.getType());

        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO, category);

        // sort / status 必须在这里兜底：字段初始值只挡「JSON 里没有这个键」，
        // {"status": null} 是显式 null，Jackson 会照常调 setter 覆盖掉初始值，
        // 而 insert 是全列插入，两个 NOT NULL 列拿到 null 就是 errno 1048。
        if (category.getSort() == null) {
            category.setSort(CategoryDTO.DEFAULT_SORT);
        }
        if (category.getStatus() == null) {
            category.setStatus(CategoryDTO.DEFAULT_STATUS);
        }

        categoryMapper.insert(category);

        log.info("新增分类成功：id={}, name={}, type={}({})",
                category.getId(), category.getName(), type.getCode(), type.getLabel());
        return category.getId();
    }

    @Override
    @Transactional
    public void update(Long id, CategoryUpdateDTO categoryUpdateDTO) {
        Category category = requireCategory(id);

        // 不用 BeanUtils.copyProperties —— 那是全量覆盖，会把 DTO 里的 null
        // 也写进去，于是「不传」变成「清空」。完整论证见
        // ChargePackageServiceImpl.mergeNonNull 那段。
        mergeNonNull(categoryUpdateDTO, category);

        categoryMapper.update(category);

        log.info("修改分类：id={}, name={}", id, category.getName());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Category category = requireCategory(id);
        assertDeletable(id, category.getName());
        categoryMapper.deleteById(id);
        log.info("删除分类：id={}, name={}", id, category.getName());
    }

    @Override
    public CategoryVO getById(Long id) {
        return toVo(requireCategory(id));
    }

    @Override
    public PageResult page(CategoryPageQueryDTO query) {
        // 本方法没有富化步骤，顺序天然是对的 —— 危险之处也正在于此：将来往这里
        // 加第一行「顺便查一下……」的代码时，没有任何东西会提醒你那是不允许的。
        // PageHelper 的调用顺序见 StationServiceImpl.page 里那段长注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Category> rows = categoryMapper.page(query);

        // 强转成 Page 只为了拿 total。PageHelper 返回的 List 实际就是 Page
        long total = ((Page<Category>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    @Override
    public List<CategoryVO> listEnabled(Integer type) {
        // 这个方法不做 type 校验，传个 7 进来也只是返回空列表：只读路径降级，
        // 而空列表本身就是前端要的答案。对比 create 对同一个字段是严格的，
        // 因为它会把非法值写进数据库。
        List<Category> rows = categoryMapper.listEnabled(type);
        return rows.stream().map(this::toVo).toList();
    }

    // ---------- 私有 ----------

    private Category requireCategory(Long id) {
        Category category = categoryMapper.getById(id);
        if (category == null) {
            throw new NotFoundException(MessageConstant.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /**
     * 删除守卫：分类下还有套餐就不许删
     *
     * <p>检查顺序是「先把分类读出来（不存在就 404），再查套餐数」：反过来写的话，
     * 删一个不存在的 id 会白跑一次 {@code countByCategoryId}。
     *
     * <p> 它在并发下不是原子的：「先 count 再 delete」之间，另一个请求可以把一个
     * 套餐挂到这个分类上。结论是接受它 —— 分类是十行量级的配置表、由管理员手工
     * 操作，而正确修法（把检查写进 DELETE 的 WHERE 里）的代价配不上这个收益。
     */
    private void assertDeletable(Long id, String name) {
        int packageCount = chargePackageMapper.countByCategoryId(id);
        if (packageCount > 0) {
            log.warn("分类下有套餐，拒绝删除：id={}, name={}, packageCount={}", id, name, packageCount);
            throw new DataConflictException(
                    String.format(MessageConstant.CATEGORY_HAS_PACKAGE, packageCount));
        }
    }

    /** 刻意不用 {@code BeanUtils.copyProperties}，理由见 {@code ChargePackageServiceImpl.mergeNonNull}（那个注释是正本） */
    private void mergeNonNull(CategoryUpdateDTO dto, Category target) {
        if (dto.getName() != null) {
            target.setName(dto.getName());
        }
        if (dto.getSort() != null) {
            target.setSort(dto.getSort());
        }
        if (dto.getStatus() != null) {
            target.setStatus(dto.getStatus());
        }
    }

    private CategoryVO toVo(Category category) {
        CategoryVO vo = new CategoryVO();
        BeanUtils.copyProperties(category, vo);
        vo.setTypeLabel(labelOfType(category.getType()));
        vo.setStatusLabel(labelOfStatus(category.getStatus()));
        return vo;
    }

    /**
     * 派生字段的降级策略见 {@code StationServiceImpl.labelOf} ——
     * 只读路径上的派生字段失败返回 null，不抛。
     */
    private String labelOfType(Integer type) {
        if (type == null) {
            return null;
        }
        try {
            return CategoryType.of(type).getLabel();
        } catch (ParamErrorException e) {
            log.warn("分类类型无法识别，type={}", type);
            return null;
        }
    }

    /**
     * 分类状态是真的二值（{@code 1启用 0停用}），所以直接映射，不需要枚举。
     *
     * <p> 常量写在左边：{@code status == StatusConstant.ENABLE} 比较的是
     * Integer 引用，只因 Integer 缓存了 -128~127 才恰好正确。
     */
    private String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (StatusConstant.ENABLE.equals(status)) {
            return "启用";
        }
        if (StatusConstant.DISABLE.equals(status)) {
            return "停用";
        }
        log.warn("分类状态无法识别，status={}", status);
        return null;
    }
}
