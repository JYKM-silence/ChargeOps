package com.chargeops.controller.admin;

import com.chargeops.dto.WalletAdjustDTO;
import com.chargeops.result.Result;
import com.chargeops.service.WalletService;
import com.chargeops.vo.WalletVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 车主钱包。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id，所以「给谁调账」从路径的 {@code memberId} 来、不从上下文取。
 * 只有调账一个接口：充值和流水查询都是「车主看/改自己的账」，归车主端。
 *
 * <p>没有「管理端查任意车主流水」是有意留的缺口：权限模型落地之前，那种接口会把「没有行级权限」变成可利用的洞。
 */
@RestController
@RequestMapping("/api/admin/wallet")
@Tag(name = "管理端-车主钱包")
@Slf4j
@RequiredArgsConstructor
public class WalletAdminController {

    private final WalletService walletService;

    /**
     * 金额正数加钱、负数扣钱、0 被拒；扣钱时余额不足会失败，不会把余额扣成负数。
     * 它是全项目唯一一笔背后没有业务对象的资金变动，所以原因必填 —— 审计能拿到的全部信息就是那行 remark。
     */
    @PostMapping("/{memberId}/adjust")
    @Operation(summary = "管理员调账",
            description = "金额正数加钱、负数扣钱、不能为 0；原因必填；返回调账后的钱包")
    public Result<WalletVO> adjust(@PathVariable Long memberId,
                                   @Valid @RequestBody WalletAdjustDTO dto) {
        return Result.success(
                walletService.adjust(memberId, dto.getAmount(), dto.getRemark()));
    }
}
