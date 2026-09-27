package com.chargeops.controller.user;

import com.chargeops.result.Result;
import com.chargeops.service.MemberPackageService;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车主端 - 充电套餐（权益）。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护，
 * BaseContext 里是车主 id，三个端点都不带 {@code memberId}。
 *
 * <p>货架（{@code ChargePackageVO}）和「我的套餐」（{@code MemberPackageVO}）是两个端点而不是
 * 一个带开关的端点：两者字段、来源表、语义都不同，合成一个会让返回体有两种互不相容的形状。
 */
@RestController
@RequestMapping("/api/member/package")
@Tag(name = "车主端-充电套餐")
@Slf4j
@RequiredArgsConstructor
public class MemberPackageController {

    private final MemberPackageService memberPackageService;

    /** {@code stationId} 必传：不传时抛参数错误，而不是静默返回全平台套餐 */
    @GetMapping("/available")
    @Operation(summary = "我在这个站点能买哪些套餐",
            description = "含全平台套餐（station_id=0）和本站专属套餐。stationId 必传")
    public Result<List<ChargePackageVO>> listAvailable(
            @Parameter(description = "站点 id", example = "1")
            @RequestParam Long stationId) {
        return Result.success(memberPackageService.listAvailable(stationId));
    }

    /** 不幂等：连点两次会真的买两份（同款买两份是合法的），前端要在请求期间禁用按钮 */
    @PostMapping("/{packageId}/purchase")
    @Operation(summary = "购买套餐", description = "从余额直接扣款，一个事务完成；不做站点范围校验")
    public Result<MemberPackageVO> purchase(
            @Parameter(description = "套餐 id", example = "1")
            @PathVariable Long packageId) {
        return Result.success(memberPackageService.purchase(packageId));
    }

    /** 不过滤状态：已用尽 / 已过期 / 已作废的也返回，前端渲染成灰态 */
    @GetMapping("/mine")
    @Operation(summary = "我的套餐", description = "不过滤状态，已用尽/已过期/已作废同样返回")
    public Result<List<MemberPackageVO>> listMine() {
        return Result.success(memberPackageService.listMine());
    }
}
