package com.chargeops.controller.admin;

import com.chargeops.dto.CategoryDTO;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.dto.CategoryUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.CategoryService;
import com.chargeops.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 分类管理。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id；五个接口的形状见 {@link StationController} 的类注释。
 *
 * <p>{@code GET /list} 给下拉框用，固定只返回启用的分类、不分页；要看停用的走 {@code /page}。
 *
 * <p>删除是守卫式硬删：{@code uk_category_type_name} 不看 {@code status}，
 * 名字建错一次就永久占用（车主那边是相反的决定，不提供硬删）。
 */
@RestController
@RequestMapping("/api/admin/category")
@Tag(name = "管理端-分类")
@Slf4j
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** {@code type} 必填：它是 {@code CategoryDTO} 上唯一没有默认值的字段 */
    @PostMapping
    @Operation(summary = "新增分类")
    public Result<Long> create(@Valid @RequestBody CategoryDTO categoryDTO) {
        return Result.success(categoryService.create(categoryDTO));
    }

    /** 改不了 {@code type}：{@code CategoryUpdateDTO} 里根本没有这个字段，是编译期事实 */
    @PutMapping("/{id}")
    @Operation(summary = "修改分类")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody CategoryUpdateDTO categoryUpdateDTO) {
        categoryService.update(id, categoryUpdateDTO);
        return Result.success();
    }

    /** 删除分类（守卫式硬删：分类下还有套餐时拒绝） */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除分类")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }

    /** 传非法 type 不报错、只返回空列表：只读路径的降级，不是这里漏了校验 */
    @GetMapping("/list")
    @Operation(summary = "查询已启用分类（下拉框用）")
    public Result<List<CategoryVO>> list(@RequestParam(required = false) Integer type) {
        return Result.success(categoryService.listEnabled(type));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询分类详情")
    public Result<CategoryVO> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    /** {@code @Valid} 是承重的：{@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @GetMapping("/page")
    @Operation(summary = "分页查询分类")
    public Result<PageResult> page(@Valid CategoryPageQueryDTO query) {
        return Result.success(categoryService.page(query));
    }
}
