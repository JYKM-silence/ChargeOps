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
 * 管理端 - 分类管理
 *
 * <p>路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。接口形状（{@code POST / PUT /{id} / DELETE /{id} /
 * GET /{id} / GET /page}）、{@code @Valid} 为什么是承重的、{@code GET /{id}} 和
 * {@code GET /page} 为什么能共存 —— 正本都在 {@link StationController}，这里不重复。
 *
 * <h2>★ 一、第六个端点 {@code GET /list}</h2>
 *
 * <p>它和 {@code /{id}} 靠字面量段优先规则共存（机制同 {@code /page}，见
 * {@link StationController} 第三节）。特别的是它<b>返回 {@code List} 而不是
 * {@code PageResult}</b>：这不是不一致，<b>下拉框不该分页</b> —— 分类是十行量级的配置表，
 * 套分页只会让前端处理「下拉框还得翻页」这个不存在的问题。
 *
 * <h2>★ 二、{@code /list} 固定吃掉 {@code status}，这是它对的地方</h2>
 *
 * <p>{@code status} 条件<b>硬编码在 SQL 里</b>，不提供「连停用的也要」这个选项 ——
 * 「停用的分类不该出现在选择列表里」是<b>业务规则</b>而非展示偏好；要看停用分类走 {@code /page}。
 * {@code type} 不传返回全部启用分类，正常用法必须明确传（见 {@code CategoryType} 的类注释）。
 *
 * <h2>★ 三、删除是守卫式硬删</h2>
 *
 * <p>分类下还有套餐时抛 {@code DataConflictException}。常规退役手段是 {@code status = 0}，
 * 但硬删必须存在：{@code uk_category_type_name} 不看 {@code status}，一个错别字分类名下架后
 * 仍永久占用那个名字。（{@code MemberAdminController} 对车主是<b>相反</b>的决定 ——
 * 不提供硬删，两边理由都写在各自类注释里。）
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

    /**
     * ★ {@code type} 必填（{@code CategoryDTO} 上唯一没有默认值的字段）：少了它会在 DTO 层
     * 变成 400，而不是走到库里撞 NOT NULL。返回新 id 的理由同 {@link StationController#create}。
     */
    @PostMapping
    @Operation(summary = "新增分类")
    public Result<Long> create(@Valid @RequestBody CategoryDTO categoryDTO) {
        return Result.success(categoryService.create(categoryDTO));
    }

    /**
     * ★ <b>改不了 {@code type}</b>，而且不是「记得排除」——
     * {@code CategoryUpdateDTO} 里根本没有这个字段，是编译期事实。
     */
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

    /**
     * ★ 这里<b>没有 {@code @Valid}</b>，也不需要：参数是裸的 {@code Integer}，没有 DTO 可校验，
     * 加了没有任何效果，只会让人以为这里验过 type。传非法值（{@code type=7}）不报错、
     * 只返回空列表 —— 只读路径的降级策略，见 {@code CategoryServiceImpl.listEnabled}。
     */
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

    /**
     * ★ {@code @Valid} 是承重的：{@code CategoryPageQueryDTO} 的 {@code @Max(100)}
     * 是唯一挡住 {@code ?pageSize=1000000} 的东西。完整说明见 {@link StationController#page}。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询分类")
    public Result<PageResult> page(@Valid CategoryPageQueryDTO query) {
        return Result.success(categoryService.page(query));
    }
}
