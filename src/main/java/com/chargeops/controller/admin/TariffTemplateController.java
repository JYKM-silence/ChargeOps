package com.chargeops.controller.admin;

import com.chargeops.dto.TariffTemplateDTO;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.dto.TariffTemplateUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.TariffTemplateService;
import com.chargeops.vo.TariffTemplateVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 计费模板管理。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护；
 * 公共约定见 {@link StationController} 的类注释。
 *
 * <p>模板和规则一次写完，没有「加一条规则」接口：{@code TariffValidator} 检查的是
 * 「8 条规则合起来无缺口、无重叠地覆盖 24 小时」，逐条加会让模板长期停在中间状态，而这个状态会漏到读路径上。
 *
 * <p>规则一旦被站点绑定就不可修改：{@code charging_order} 冻结的 {@code tariff_template_id}
 * 只在规则不可变时才保值。要调价就新建一个模板版本，用 {@code station_tariff.effective_from} 绑生效期。
 *
 * <p>列表不含规则（{@code rules} 恒为 {@code null}，不是空列表），详情含规则；
 * {@code GET /list-enabled} 给下拉框用，永远不要给它加分页参数。
 */
@RestController
@RequestMapping("/api/admin/tariff-template")
@Tag(name = "管理端-计费模板")
@Slf4j
public class TariffTemplateController {

    private final TariffTemplateService tariffTemplateService;

    public TariffTemplateController(TariffTemplateService tariffTemplateService) {
        this.tariffTemplateService = tariffTemplateService;
    }

    /** {@code rules} 上 {@code @NotEmpty} 和 {@code @Valid} 缺一不可：只有 {@code @Valid} 的话，传空数组就能建出没有规则的模板 */
    @PostMapping
    @Operation(summary = "新增计费模板")
    public Result<Long> create(@Valid @RequestBody TariffTemplateDTO tariffTemplateDTO) {
        return Result.success(tariffTemplateService.create(tariffTemplateDTO));
    }

    /** 不传 {@code rules} 和传空数组都表示「只改头信息」；传非空则走那道不可变守卫 */
    @PutMapping("/{id}")
    @Operation(summary = "修改计费模板")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody TariffTemplateUpdateDTO tariffTemplateUpdateDTO) {
        tariffTemplateService.update(id, tariffTemplateUpdateDTO);
        return Result.success();
    }

    /** 被任何 {@code station_tariff} 行引用时拒绝；规则不级联删，留下的孤儿行至少可查、可人工清理 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除计费模板")
    public Result<Void> delete(@PathVariable Long id) {
        tariffTemplateService.delete(id);
        return Result.success();
    }

    /** 含规则，按 {@code start_time} 排序（零填充的 {@code HH:mm:ss}，字典序等于时间序） */
    @GetMapping("/{id}")
    @Operation(summary = "查询计费模板详情（含计费规则）")
    public Result<TariffTemplateVO> getById(@PathVariable Long id) {
        return Result.success(tariffTemplateService.getById(id));
    }

    /** 不含规则，和 {@code getById} 的区别是有意的 */
    @GetMapping("/page")
    @Operation(summary = "分页查询计费模板")
    public Result<PageResult> page(@Valid TariffTemplatePageQueryDTO query) {
        return Result.success(tariffTemplateService.page(query));
    }

    /** 下拉框用，不分页；只能选到前 10 个模板的下拉框会比没有下拉框更糟，因为它看起来是好的 */
    @GetMapping("/list-enabled")
    @Operation(summary = "查询全部启用中的计费模板（下拉框用）")
    public Result<List<TariffTemplateVO>> listEnabled() {
        return Result.success(tariffTemplateService.listAllEnabled());
    }
}
