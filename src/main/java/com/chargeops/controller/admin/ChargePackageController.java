package com.chargeops.controller.admin;

import com.chargeops.dto.ChargePackageDTO;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.dto.ChargePackageUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargePackageService;
import com.chargeops.vo.ChargePackageVO;
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

/**
 * 管理端 - 充电套餐。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护；
 * 公共约定见 {@link StationController} 的类注释。
 *
 * <p>{@code stationId = 0} 表示全平台而不是 {@code null}：MySQL 的唯一索引不约束 NULL，
 * 用 null 表示全平台会让 {@code uk_pkg_name_station} 在这一档上静默失效。
 *
 * <p>{@code packageType} 决定哪个字段必填、另外三个必须为 null，这条跨字段校验在 Service 里 ——
 * 残留的专属字段是静默的：时长包里残留的 {@code energyKwh = 150} 会被扣减逻辑读走，凭空送出 150 度电。
 */
@RestController
@RequestMapping("/api/admin/package")
@Tag(name = "管理端-充电套餐")
@Slf4j
public class ChargePackageController {

    private final ChargePackageService chargePackageService;

    public ChargePackageController(ChargePackageService chargePackageService) {
        this.chargePackageService = chargePackageService;
    }

    /** {@code price} 允许为 0（0 元体验包），但 {@code serviceDiscount} 必须大于 0 且不超过 1 */
    @PostMapping
    @Operation(summary = "新增充电套餐")
    public Result<Long> create(@Valid @RequestBody ChargePackageDTO chargePackageDTO) {
        return Result.success(chargePackageService.create(chargePackageDTO));
    }

    /** 校验的是「库里那行 + 本次改动」合并后的结果，不是请求体 */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电套餐")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ChargePackageUpdateDTO chargePackageUpdateDTO) {
        chargePackageService.update(id, chargePackageUpdateDTO);
        return Result.success();
    }

    /** 硬删必须存在：{@code uk_pkg_name_station} 不看 {@code status}，名字建错一次就永久占用 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电套餐")
    public Result<Void> delete(@PathVariable Long id) {
        chargePackageService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电套餐详情")
    public Result<ChargePackageVO> getById(@PathVariable Long id) {
        return Result.success(chargePackageService.getById(id));
    }

    /** 查「全平台套餐」就是 {@code ?stationId=0}，不是「不过滤」 */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电套餐")
    public Result<PageResult> page(@Valid ChargePackagePageQueryDTO query) {
        return Result.success(chargePackageService.page(query));
    }
}
