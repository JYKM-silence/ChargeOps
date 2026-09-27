package com.chargeops.controller.user;

import com.chargeops.dto.WalletRechargeDTO;
import com.chargeops.dto.WalletTransactionPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.WalletService;
import com.chargeops.vo.WalletVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车主端 - 我的钱包。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护，
 * BaseContext 里是车主 id，三个端点都没有 {@code memberId} 参数。
 *
 * <p>只有车主自助的三个操作；冻结 / 结算 / 退款虽然也在 {@link WalletService} 上，
 * 但那是订单流程调的，不暴露成 HTTP —— 让客户端直接触发扣款等于把资金安全交给客户端。
 *
 * <p>充值接口是演示用的，直接给余额加钱。
 */
@RestController
@RequestMapping("/api/member/wallet")
@Tag(name = "车主端-我的钱包")
@Slf4j
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** 未开卡返回零钱包，不是 404 —— 前端不必把「没开卡」当成错误分支 */
    @GetMapping
    @Operation(summary = "查询我的钱包", description = "余额 / 冻结 / 累计充值；未开卡返回 0")
    public Result<WalletVO> getMine() {
        return Result.success(walletService.getMine());
    }

    @PostMapping("/recharge")
    @Operation(summary = "充值", description = "演示接口：直接入账，未接真实支付")
    public Result<WalletVO> recharge(@Valid @RequestBody WalletRechargeDTO dto) {
        return Result.success(walletService.recharge(dto));
    }

    @GetMapping("/transactions")
    @Operation(summary = "查询我的钱包流水", description = "分页，按时间倒序")
    public Result<PageResult> pageTransactions(@Valid WalletTransactionPageQueryDTO query) {
        return Result.success(walletService.pageTransactions(query));
    }
}
