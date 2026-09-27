package com.chargeops.controller.admin;

import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.StationService;
import com.chargeops.vo.StationVO;
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
 * 管理端 - 充电站管理。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id。本类的注释是全项目接口形状的正本：
 * POST 建 / PUT {id} 改 / DELETE {id} 删 / GET {id} 详情 / GET page 分页。
 *
 * <p>每个 {@code @RequestBody} 都必须带 {@code @Valid}：少了它 DTO 上的约束全部静默失效
 * （注解还在、编译通过、Swagger 照常显示，只是不执行），本该 400 的请求会撞在 {@code NOT NULL}
 * 上变成 500，或者把一个负数原样写进库。
 *
 * <p>{@code GET /{id}} 和 {@code GET /page} 能共存，靠的是 PathPatternParser 优先匹配字面量段；
 * 这个前提在「{@code page} 是字面量段」时才成立，加新路径前先确认一遍。
 *
 * <p>已知缺口：{@code staff} 表有 {@code role} / {@code station_id}，但 {@code BaseContext}
 * 只带 {@code currentId}，一个站点运维可以改任意站点。
 */
@RestController
@RequestMapping("/api/admin/station")
@Tag(name = "管理端-充电站")
@Slf4j
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /** 返回新 id 而不是 Void：{@code useGeneratedKeys} 已经回填了，顺手返回能省一次往返 */
    @PostMapping
    @Operation(summary = "新增充电站")
    public Result<Long> create(@Valid @RequestBody StationDTO stationDTO) {
        return Result.success(stationService.create(stationDTO));
    }

    /** id 走路径不走请求体：客户端无法靠改 payload 改「改的是哪一行」 */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电站")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody StationUpdateDTO stationUpdateDTO) {
        stationService.update(id, stationUpdateDTO);
        return Result.success();
    }

    /** 守卫式硬删：站点下还有充电桩时拒绝。{@code uk_station_code} 不看 {@code status}，编码建错一次就永久占用 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电站")
    public Result<Void> delete(@PathVariable Long id) {
        stationService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电站详情")
    public Result<StationVO> getById(@PathVariable Long id) {
        return Result.success(stationService.getById(id));
    }

    /** {@code @Valid} 在这里是承重的：{@code pageSize} 上的 {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电站")
    public Result<PageResult> page(@Valid StationPageQueryDTO query) {
        return Result.success(stationService.page(query));
    }
}
