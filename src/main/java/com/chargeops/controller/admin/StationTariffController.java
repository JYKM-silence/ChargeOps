package com.chargeops.controller.admin;

import com.chargeops.dto.StationTariffDTO;
import com.chargeops.dto.StationTariffUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.StationTariffService;
import com.chargeops.vo.StationTariffVO;
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
 * 管理端 - 站点计费绑定。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护；
 * 公共约定见 {@link StationController} 的类注释。
 *
 * <p>这里管的是「站点 ↔ 模板」的关系表，所以没有 {@code page}：绑定永远按站点查，
 * {@code GET /by-station/{stationId}} 就是那个站点的调价历史。
 *
 * <p>生效区间是半开的 {@code [effectiveFrom, effectiveTo)}，{@code effectiveTo} 必须严格晚于
 * {@code effectiveFrom} —— 两者相等是一个永远不会被命中的空区间，在列表页上却和正常绑定一样。
 *
 * <p>没有「同一站点的绑定首尾相接」这种校验，空窗期的代价由 {@code resolveTemplateIdAt} 抛异常承担。
 */
@RestController
@RequestMapping("/api/admin/station-tariff")
@Tag(name = "管理端-站点计费绑定")
@Slf4j
public class StationTariffController {

    private final StationTariffService stationTariffService;

    public StationTariffController(StationTariffService stationTariffService) {
        this.stationTariffService = stationTariffService;
    }

    /**
     * 三样都校验：站点存在、模板存在、模板是启用状态（停用模板不能新建绑定，已有绑定不受影响）。
     * 同一站点同一 {@code effectiveFrom} 重复绑定会撞 {@code uk_station_tariff_window}。
     */
    @PostMapping
    @Operation(summary = "为站点绑定计费模板")
    public Result<Long> bind(@Valid @RequestBody StationTariffDTO stationTariffDTO) {
        return Result.success(stationTariffService.bind(stationTariffDTO));
    }

    /** 只能改 {@code effectiveTo} / {@code priority} / {@code status}：另外三个合起来是这条绑定的身份 */
    @PutMapping("/{id}")
    @Operation(summary = "修改站点计费绑定")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody StationTariffUpdateDTO stationTariffUpdateDTO) {
        stationTariffService.update(id, stationTariffUpdateDTO);
        return Result.success();
    }

    /** 常规退役手段是 {@code status = 0} 停用而不是删除：删掉的那段时期会变成空白，事后追不回依据 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除站点计费绑定")
    public Result<Void> delete(@PathVariable Long id) {
        stationTariffService.delete(id);
        return Result.success();
    }

    /** 含模板名与编码：列表里只有 {@code templateId} 是没有信息量的，没人记得模板 7 是哪个价 */
    @GetMapping("/{id}")
    @Operation(summary = "查询站点计费绑定详情")
    public Result<StationTariffVO> getById(@PathVariable Long id) {
        return Result.success(stationTariffService.getById(id));
    }

    /** 不分页，见类注释；也因此没有 PageHelper 陷阱，补 {@code templateName} 的批量查询可以跟在后面 */
    @GetMapping("/by-station/{stationId}")
    @Operation(summary = "查询某站点的全部计费绑定（按生效时刻倒序）")
    public Result<List<StationTariffVO>> listByStationId(@PathVariable Long stationId) {
        return Result.success(stationTariffService.listByStationId(stationId));
    }
}
