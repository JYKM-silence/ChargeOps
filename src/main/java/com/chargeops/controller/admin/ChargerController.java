package com.chargeops.controller.admin;

import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargerService;
import com.chargeops.vo.ChargerVO;
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
 * 管理端 - 充电桩。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护；
 * 公共约定见 {@link StationController} 的类注释。
 *
 * <p>{@code gun_count} 是冗余列，唯一职责是等于 {@code COUNT(connector)}：
 * 创建时强制 0（新建的桩一把枪都没有），也不允许手工改。
 *
 * <p>列表补 {@code stationName} 的那次批量查询必须排在分页查询之后 ——
 * 插在 {@code PageHelper.startPage} 和分页查询之间，会让补名字的 SQL 也带上 {@code LIMIT 10}。
 */
@RestController
@RequestMapping("/api/admin/charger")
@Tag(name = "管理端-充电桩")
@Slf4j
public class ChargerController {

    private final ChargerService chargerService;

    public ChargerController(ChargerService chargerService) {
        this.chargerService = chargerService;
    }

    /** {@code stationId} 的存在性只靠 Service 校验：DDL 里没有外键，直接写 SQL 绕得过去 */
    @PostMapping
    @Operation(summary = "新增充电桩")
    public Result<Long> create(@Valid @RequestBody ChargerDTO chargerDTO) {
        return Result.success(chargerService.create(chargerDTO));
    }

    /** {@code stationId} / {@code gunCount} 不在 UpdateDTO：挪桩会让它下面所有枪的冗余列 {@code station_id} 失真 */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电桩")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ChargerUpdateDTO chargerUpdateDTO) {
        chargerService.update(id, chargerUpdateDTO);
        return Result.success();
    }

    /** 删除充电桩：守卫式硬删，桩下还有充电枪时抛 {@code DataConflictException}；常规手段是 {@code status = 4 维护中} */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电桩")
    public Result<Void> delete(@PathVariable Long id) {
        chargerService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电桩详情")
    public Result<ChargerVO> getById(@PathVariable Long id) {
        return Result.success(chargerService.getById(id));
    }

    /** 最常用的过滤条件是 {@code stationId}：先选站点、再看这个站的桩 */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电桩")
    public Result<PageResult> page(@Valid ChargerPageQueryDTO query) {
        return Result.success(chargerService.page(query));
    }
}
