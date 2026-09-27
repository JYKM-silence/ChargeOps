package com.chargeops.controller.admin;

import com.chargeops.dto.MemberPackageVoidDTO;
import com.chargeops.result.Result;
import com.chargeops.service.MemberPackageService;
import com.chargeops.vo.MemberPackageVO;
import com.chargeops.vo.MemberPackageVoidVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 车主持有的套餐权益。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id 而不是车主 id，所以两个接口都显式收 {@code memberId}。
 *
 * <p>查询和作废成对存在：作废要的 {@code member_package.id}，没有别的地方能查到。
 *
 * <p>作废是资金操作：只退完全没用过的，全额退，金额取自权益上的实付快照、不由请求传入。
 */
@RestController
@RequestMapping("/api/admin/member-package")
@Tag(name = "管理端-套餐权益")
@Slf4j
@RequiredArgsConstructor
public class MemberPackageAdminController {

    private final MemberPackageService memberPackageService;

    /**
     * 不过滤状态：已用尽 / 已过期 / 已作废的也要返回，运营来查多半是回答「这份为什么不能用了」。
     * {@code payAmount} 为 {@code null} 表示无法退款（建于本功能之前），前端要显示「未知」而不是 0。
     */
    @GetMapping
    @Operation(summary = "某车主的全部套餐权益",
            description = "不过滤状态；payAmount 为 null 表示无法退款（建于本功能上线之前）")
    public Result<List<MemberPackageVO>> listByMember(@RequestParam Long memberId) {
        return Result.success(memberPackageService.listByMember(memberId));
    }

    /** 只退完全没用过的，全额退；被任何非取消的订单引用过（含待支付 / 支付失败 / 挂账）就整份不退 */
    @PostMapping("/{id}/void")
    @Operation(summary = "作废权益并退款",
            description = "只退完全没用过的，全额退；金额取自权益上的实付快照，不可指定")
    public Result<MemberPackageVoidVO> voidPackage(@PathVariable Long id,
                                                   @Valid @RequestBody MemberPackageVoidDTO dto) {
        return Result.success(memberPackageService.voidPackage(id, dto));
    }
}
