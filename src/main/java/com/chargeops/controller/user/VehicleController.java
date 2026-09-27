package com.chargeops.controller.user;

import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.VehicleService;
import com.chargeops.vo.VehicleVO;
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
 * 车主端 - 我的车辆。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护，
 * BaseContext 里是车主 id。
 *
 * <p>五个端点带 {@code {id}}（和 {@code MemberController} 相反），安全性来自两层：
 * Service 的方法签名里没有 {@code memberId}，而且每条 SQL 的 {@code WHERE} 都带 {@code member_id}。
 *
 * <p>{@code GET /{id}} 取别人的车辆 id 时返回「车辆不存在」而不是「无权访问」：
 * 后者会免费告诉调用方「这个 id 存在，只是不属于你」。
 */
@RestController
@RequestMapping("/api/member/vehicle")
@Tag(name = "车主端-我的车辆")
@Slf4j
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    /** 顺序 {@code is_default DESC, id DESC}，默认车在第一条 —— 前端取第一条，不要去找 {@code isDefault == 1} 的那条 */
    @GetMapping
    @Operation(summary = "查询我的全部车辆", description = "不分页；默认车在第一条")
    public Result<List<VehicleVO>> listMine() {
        return Result.success(vehicleService.listMine());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询我的某一辆车")
    public Result<VehicleVO> getMine(@PathVariable Long id) {
        return Result.success(vehicleService.getMine(id));
    }

    /**
     * 第一辆车会被强制置为默认车，不管请求里传了什么。
     * 车牌入库会 trim + 转大写：不是为了唯一性（排序规则本来就大小写不敏感），而是挡住「尾随空格绕过唯一索引」。
     */
    @PostMapping
    @Operation(summary = "新增车辆")
    public Result<Long> create(@Valid @RequestBody VehicleDTO vehicleDTO) {
        return Result.success(vehicleService.create(vehicleDTO));
    }

    /** 改不了 {@code isDefault}（走 {@code PUT /{id}/default}）和归属；车牌可以改，历史订单存的是当时的快照 */
    @PutMapping("/{id}")
    @Operation(summary = "修改车辆")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody VehicleUpdateDTO vehicleUpdateDTO) {
        vehicleService.update(id, vehicleUpdateDTO);
        return Result.success();
    }

    /**
     * 硬删，没有守卫：{@code charging_order.plate_no} 那张快照列存在的理由就是允许车辆被删，
     * 而 {@code vehicle} 没有状态列。
     *
     * <p>删掉的如果是默认车，剩下的第一辆会自动成为默认车。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除车辆", description = "若删的是默认车，剩余第一辆会自动成为默认车")
    public Result<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return Result.success();
    }

    /** 幂等；用 {@code PUT} 是因为它不创建资源，只是把「默认车」这个位置指派给这一辆 */
    @PutMapping("/{id}/default")
    @Operation(summary = "设为默认车")
    public Result<Void> setDefault(@PathVariable Long id) {
        vehicleService.setDefault(id);
        return Result.success();
    }
}
