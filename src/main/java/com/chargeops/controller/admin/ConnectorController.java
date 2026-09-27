package com.chargeops.controller.admin;

import com.chargeops.dto.ConnectorDTO;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.dto.ConnectorUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ConnectorService;
import com.chargeops.vo.ConnectorVO;
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
 * 管理端 - 充电枪。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护；
 * 公共约定见 {@link StationController} 的类注释。
 *
 * <p>路径是扁平的一层，不嵌套在充电桩下：后台要的是跨桩、跨站点的枪列表，
 * 而「枪必须属于某个桩」由 {@code ConnectorDTO.chargerId} 上的 {@code @NotNull} 保证。
 *
 * <p>{@code version} 是乐观锁，客户端必须把详情/列表拿到的值原样回传 ——
 * 让服务端自己先读一次再写，那个检查永远会通过，只是看起来有锁。
 *
 * <p>管理员只能设 0 离线 / 1 空闲 / 4 故障 / 5 维护：2 已占用和 3 充电中由订单状态机写入，
 * 手工设会绕过 {@code uk_connector_current_order}。白名单在 Service 里判断，值域连续、注解表达不了。
 */
@RestController
@RequestMapping("/api/admin/connector")
@Tag(name = "管理端-充电枪")
@Slf4j
public class ConnectorController {

    private final ConnectorService connectorService;

    public ConnectorController(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    /** {@code stationId} 由服务端从 {@code chargerId} 派生；{@code refreshGunCount} 必须和插入同事务 */
    @PostMapping
    @Operation(summary = "新增充电枪")
    public Result<Long> create(@Valid @RequestBody ConnectorDTO connectorDTO) {
        return Result.success(connectorService.create(connectorDTO));
    }

    /** 必须回传 {@code version}；{@code chargerId} 不在 UpdateDTO：挪枪会让原桩和新桩的 {@code gun_count} 同时失真 */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电枪")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ConnectorUpdateDTO connectorUpdateDTO) {
        connectorService.update(id, connectorUpdateDTO);
        return Result.success();
    }

    /** {@code current_order_id} 非空、或状态是 2/3，命中任一即拒绝 —— 只查一个，另一类坏行会被放行 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电枪")
    public Result<Void> delete(@PathVariable Long id) {
        connectorService.delete(id);
        return Result.success();
    }

    /** 返回里带 {@code version}，编辑页要把它原样回传 */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电枪详情")
    public Result<ConnectorVO> getById(@PathVariable Long id) {
        return Result.success(connectorService.getById(id));
    }

    /** 按 {@code chargerId} 查出的条数应恰好等于该桩的 {@code gun_count}，对不上就是漏调过一次 {@code refreshGunCount} */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电枪")
    public Result<PageResult> page(@Valid ConnectorPageQueryDTO query) {
        return Result.success(connectorService.page(query));
    }
}
