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
 * 车主端 - 我的钱包
 *
 * <p>路径 {@code /api/member/wallet/**}，由 {@code JwtTokenMemberInterceptor} 保护。
 *
 * <p>★ 三个端点都<b>没有 memberId 参数</b> —— 身份只从 token 取：钱包天然是「一个人的一个东西」，
 * 客户端没有表达「操作别人钱包」的能力。
 *
 * <p>★ 这里只有车主自助的三个操作。冻结 / 结算 / 退款虽然也在 {@link WalletService} 上，
 * 但它们是<b>订单流程</b>调的，不暴露成 HTTP —— 让客户端能直接触发扣款，等于把资金安全交给客户端。
 *
 * <p>★ 充值接口是<b>演示用</b>的，直接给余额加钱，没有接真实支付；上限见 {@link WalletRechargeDTO}。
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
