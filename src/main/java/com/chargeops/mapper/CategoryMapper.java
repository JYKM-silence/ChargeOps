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
 * 分类数据访问。不写「名字是不是被占了」的预检，交给 {@code uk_category_type_name}，
 * 由 {@code GlobalExceptionHandler} 翻译成「已存在」。
 */
@Mapper
public interface CategoryMapper {

    Category getById(@Param("id") Long id);

    /**
     * 查某类型下已启用的分类，按 {@code sort} 升序。
     * {@code type} 可空 = 全部启用。走 {@code idx_category_type_sort}，末尾补 {@code id} 保证全序。
     */
    List<Category> listEnabled(@Param("type") Integer type);

    /** 全列插入：{@code sort} / {@code status} 是 NOT NULL，漏一个就是 errno 1048 */
    @AutoFill(OperationType.INSERT)
    void insert(Category category);

    /** 不碰 {@code type}：改它等于把一个分类连同挂着的套餐搬到另一个语义域，而套餐那边不会有任何反应 */
    @AutoFill(OperationType.UPDATE)
    void update(Category category);

    /** 硬删，不做守卫。调用方必须先查 {@link ChargePackageMapper#countByCategoryId} */
    @Delete("DELETE FROM category WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<Category> page(CategoryPageQueryDTO query);
}
