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
 * <p>★ <b>本类没有任何 {@code BaseContext} 的引用</b>，这是对的 ——
 * 分类是全局配置，不属于任何车主，所以这里不存在
 * {@code VehicleServiceImpl} 那种「每个方法第一件事是取当前登录者」的模式。
 * 管理端的鉴权由拦截器在 {@code /api/admin/**} 上完成。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * ★ 这个依赖只服务一件事：{@link #assertDeletable} 里的 delete 守卫。
     *
     * <p>★ 这里选择<b>依赖对方的 Mapper，而不是对方的 Service</b>：让
     * {@code ChargePackageService} 提供一个 {@code countByCategoryId} 会引入
     * {@code CategoryService → ChargePackageService} 的接口依赖，而后者已经
     * <b>反向依赖</b> {@code CategoryMapper} —— 两个 Service 互相注入是 Spring
     * 允许但很难读的结构。依赖 Mapper 则方向单向（Service → Mapper），没有环。
     */
    private final ChargePackageMapper chargePackageMapper;

    @Override
    @Transactional
    public Long create(CategoryDTO categoryDTO) {
        // ★★ 先校验 type 的合法性，再落到实体上。
        //
        //   CategoryDTO 上只有 @Min(1) @Max(2)，它挡得住 0 和 3，但那是一个
        //   【复制的约束】：枚举里将来加第三个值时 @Max(2) 不会跟着变，
        //   表现是「新类型在 DTO 层就被 400 打回」，而改 DTO 的人未必知道
        //   枚举那边加了值。CategoryType.of() 是【唯一真本】—— 它读的就是
        //   枚举本身 —— 所以这里调它，让「合法值有哪些」只有一个定义处。
        CategoryType type = CategoryType.of(categoryDTO.getType());

        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO, category);

        // ★★ sort / status 必须在这里兜底，理由见 CategoryDTO 的类注释第一节：
        //   字段初始值只挡「JSON 里没有这个键」，{"status": null} 是显式 null，
        //   Jackson 会照常调 setter 覆盖掉初始值 —— 而 insert 是全列插入，
        //   两个 NOT NULL 列拿到 null 就是 errno 1048。
        //   用的是 CategoryDTO 上的同一批常量，那正是它们被声明成
        //   public static final 的原因。
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

        // ★ 合并方向：DTO 的非空值覆盖到从库里读出的行上。
        //   不用 BeanUtils.copyProperties —— 那是全量覆盖，会把 DTO 里的 null
        //   也写进去，于是「不传」变成「清空」。完整论证见
        //   ChargePackageServiceImpl.mergeNonNull 那段。
        //
        //   ★ 这里的 update 用的是 <set> + <if>（null 会被跳过），理论上
        //     「直接 new 一个只装着 DTO 字段的对象传下去」也能 work；仍然先读后
        //     合并是为了让【校验和写针对同一个对象】—— 这是本项目统一形状。
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
        // ★ PageHelper 的调用顺序见 StationServiceImpl.page 里那段长注释。
        //   本方法没有富化步骤，所以顺序天然是对的 —— 危险之处也正在于此：
        //   将来往这里加第一行「顺便查一下……」的代码时，
        //   没有任何东西会提醒你那是不允许的。
        //   CategoryVO 的类注释第三节里记了那个具体的坑（「该分类下有 N 个套餐」）。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Category> rows = categoryMapper.page(query);

        // ★ 强转成 Page 只为了拿 total。PageHelper 返回的 List 实际就是 Page
        long total = ((Page<Category>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    @Override
    public List<CategoryVO> listEnabled(Integer type) {
        // ★ 这个方法【不做 type 校验】，哪怕传了个 7 进来也只是返回空列表：
        //   它是只读路径，而项目的约定是「写路径严格，读路径降级」。为一个
        //   查询条件抛异常，会让「暂时没有这一类分类」这个正常状态表现成报错，
        //   而空列表本身就是前端要的答案。
        //   ★ 和 create 对比：那边对同一个字段是【严格】的，因为它会把非法值
        //     写进数据库 —— 同一个值在两个方向上的处理不同，是同一条约定的两个面。
        List<Category> rows = categoryMapper.listEnabled(type);
        return rows.stream().map(this::toVo).toList();
    }

    // ==================== 私有 ====================

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
     * 删一个不存在的 id 会白跑一次 {@code countByCategoryId}，
     * 而且日志里会出现一个指向不存在分类的计数。
     *
     * <p>★ <b>消息里带上具体数量</b>，用的是 {@code MessageConstant.CATEGORY_HAS_PACKAGE}
     * 这个 {@code %d} 模板，拼装方式见那个常量的注释。
     *
     * <p>★★ <b>本方法有一个外键没有的缺口：它在并发下不是原子的。</b>
     * 「先 count 再 delete」之间，另一个请求可以把一个套餐挂到这个分类上。
     * 结论是接受它（分类是十行量级的配置表、由管理员手工操作，而正确修法
     * ——把检查写进 DELETE 的 WHERE 里——的代价配不上这个收益），
     * 完整分析见 {@code ChargePackageMapper.countByCategoryId} 的注释。
     */
    private void assertDeletable(Long id, String name) {
        int packageCount = chargePackageMapper.countByCategoryId(id);
        if (packageCount > 0) {
            log.warn("分类下有套餐，拒绝删除：id={}, name={}, packageCount={}", id, name, packageCount);
            throw new DataConflictException(
                    String.format(MessageConstant.CATEGORY_HAS_PACKAGE, packageCount));
        }
    }

    /**
     * 把 DTO 的非空字段覆盖到实体上
     *
     * <p>★ 刻意不用 {@code BeanUtils.copyProperties} —— 理由见
     * {@code ChargePackageServiceImpl.mergeNonNull}（那个注释是正本）。
     */
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
     * ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} ——
     * 只读路径上的派生字段失败返回 null，不抛。
     *
     * <p>{@code CategoryType.of} 在值非法时会抛 {@code ParamErrorException}，
     * 而 {@code create} 里<b>正是靠这个抛出来拒绝请求</b> ——
     * 同一个方法一个抛一个吞，就是「写路径严格，读路径降级」这条约定本身。
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
     * <p>★ 常量写在左边：{@code status == StatusConstant.ENABLE} 比较的是
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
