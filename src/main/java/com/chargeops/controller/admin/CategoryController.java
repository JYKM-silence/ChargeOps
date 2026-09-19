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
 * <b>本类不需要写任何鉴权代码</b>。
 *
 * <p>★ 五个接口的形状（{@code POST / PUT /{id} / DELETE /{id} / GET /{id} /
 * GET /page}）、{@code @Valid} 为什么是承重的、{@code GET /{id}} 和
 * {@code GET /page} 为什么能共存 —— 这三件事的正本都在 {@link StationController}，
 * 这里不重复。下面只写本域特有的部分。
 *
 * <h2>★ 一、第六个端点 {@code GET /list}：字面量段优先规则的第二个用例</h2>
 *
 * <p>{@code /api/admin/category/list} 和 {@code /{id}} 也是靠
 * {@code PathPatternParser} 优先匹配字面量段才共存的 ——
 * 和 {@code /page} 是同一个机制，见 {@link StationController} 第三节。
 * 但这里多了一个值得注意的地方：<b>{@code /list} 返回的是
 * {@code List} 而不是 {@code PageResult}</b>，同一个 Controller 里有两种
 * 分页形态，看接口清单时容易以为漏了一个。
 *
 * <p>★ 这不是不一致。<b>下拉框不该分页</b> —— 分类是十行量级的配置表，
 * 给它套分页只会让前端必须处理「下拉框还得翻页」这个根本不存在的问题。
 * 两个端点的分工与各自服务的 SQL 见 {@code CategoryPageQueryDTO} 的类注释。
 *
 * <h2>★★ 二、{@code /list} 会<b>吃掉</b> {@code type} 参数，这是它对的地方</h2>
 *
 * <p>{@code GET /api/admin/category/list?type=1} 固定返回
 * 「{@code type=1} 且 {@code status=1}」的分类。{@code status} 那个条件是
 * <b>硬编码在 SQL 里的</b>，接口不提供「连停用的也要」这个选项 ——
 * 因为「停用的分类不该出现在选择列表里」是<b>业务规则</b>，不是展示偏好。
 * 需要看到停用分类的场景是管理端列表，那是 {@code /page}。
 *
 * <p>★ {@code type} 不传就返回全部启用分类。正常用法一定是明确传的 ——
 * 消费方必须知道自己要哪个 type，见 {@code CategoryType} 的类注释。
 *
 * <h2>★ 三、删除是守卫式硬删</h2>
 *
 * <p>分类下还有套餐时抛 {@code DataConflictException}，消息形如
 * 「该分类下还有 3 个充电套餐，请先删除或调整分类」。
 * 常规的「退役」手段是 {@code status = 0 停用}，但硬删必须存在 ——
 * {@code uk_category_type_name} 不看 {@code status}，
 * 一个错别字分类名下架之后仍然永久占用那个名字。
 *
 * <p>★ 这和 {@code station} 是同一个理由，而
 * {@code MemberAdminController} 对车主是<b>相反</b>的决定（不提供硬删）——
 * 两边的理由都写在各自的类注释里，比对统一规则更有价值。
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
     * 新增分类
     *
     * <p>★ 返回 {@code Result<Long>}（新 id）而不是 {@code Result<Void>} ——
     * 理由同 {@link StationController#create}：{@code useGeneratedKeys}
     * 已经把 id 回填了，顺手返回能省掉「新建后跳详情」的一次往返。
     *
     * <p>★ {@code type} 是必填的（{@code CategoryDTO} 上唯一没有默认值的字段），
     * 少了它会在 DTO 层变成 400 而不是走到库里撞 NOT NULL。见那个类的第二节。
     */
    @PostMapping
    @Operation(summary = "新增分类")
    public Result<Long> create(@Valid @RequestBody CategoryDTO categoryDTO) {
        return Result.success(categoryService.create(categoryDTO));
    }

    /**
     * 修改分类（名称 / 排序 / 状态）
     *
     * <p>★ <b>改不了 {@code type}</b>，而且不是「记得排除」——
     * {@code CategoryUpdateDTO} 里根本没有这个字段，是编译期事实。
     * 理由见 {@code CategoryType} 的类注释第二节。
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
     * 查某个类型下已启用的分类（给下拉框用，<b>不分页</b>）
     *
     * <p>★ {@code @RequestParam(required = false)} —— 不传返回全部启用分类。
     *
     * <p>★ 这个方法上<b>没有 {@code @Valid}</b>，而且不需要：
     * 参数是一个裸的 {@code Integer}，没有 DTO 可校验。
     * 传了非法值（比如 {@code type=7}）不会报错，只是返回空列表 ——
     * 这是只读路径的降级策略，见 {@code CategoryServiceImpl.listEnabled}。
     *
     * <p>★ 写在这里免得有人「顺手补上」一个 {@code @Valid}：
     * 它加了也没有任何效果，只会让人以为这里验过 type。
     */
    @GetMapping("/list")
    @Operation(summary = "查询已启用分类（下拉框用）")
    public Result<List<CategoryVO>> list(@RequestParam(required = false) Integer type) {
        return Result.success(categoryService.listEnabled(type));
    }

    /** 按 id 查详情 */
    @GetMapping("/{id}")
    @Operation(summary = "查询分类详情")
    public Result<CategoryVO> getById(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ 这里的 {@code @Valid} 是承重的：{@code CategoryPageQueryDTO}
     * 的 {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西。
     * 完整说明见 {@link StationController#page}。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询分类")
    public Result<PageResult> page(@Valid CategoryPageQueryDTO query) {
        return Result.success(categoryService.page(query));
    }
}
