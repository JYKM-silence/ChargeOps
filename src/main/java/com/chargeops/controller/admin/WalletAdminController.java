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
 * 管理端 - 车主钱包
 *
 * <p>路径 {@code /api/admin/wallet/**}，由 {@code JwtTokenAdminInterceptor} 保护。
 *
 * <h2>★ 目前只有「调账」一个接口，这是对的</h2>
 *
 * <p>充值和流水查询都在车主端（{@code /api/member/wallet/**}），
 * 因为那两件事都是「车主看/改自己的账」。管理端唯一需要的是
 * <b>「把一笔账改平」</b> —— 它是运营才有权做的动作。
 *
 * <p>★ <b>没有「管理端查任意车主的流水」这个接口，这是一个有意留下的缺口</b>：
 * 调账之后要核对，目前只能让车主自己截图，或者去数据库查。
 * 加它需要先想清楚<b>数据权限</b>（谁是「可以看全部车主账目」的角色），
 * 而这个项目现在只有一份「是员工 / 不是员工」的二元判断
 * （见 README 的已知缺口表：「没有行级权限」）。
 * 在权限模型落地之前开一个能查别人全部流水的接口，
 * 等于把那个缺口变成一个可被利用的洞。
 *
 * <h2>★★ 车主 id 在路径上，不在请求体里 ★★</h2>
 *
 * <p>{@code POST /api/admin/wallet/{memberId}/adjust} —— 「给谁调」和被调的那一行
 * 在 URL 里是同一个东西。抄错 id 会作用在<b>另一个明确的车主</b>身上，
 * 而不是一个静默的默认值。完整理由见 {@link WalletAdjustDTO}。
 *
 * <p>★ 本类<b>不读 {@code BaseContext}</b>，理由同
 * {@code MemberPackageAdminController} 的类注释 —— 管理端请求里
 * 那个上下文里放的是<b>员工</b>的 id。
 */
@RestController
@RequestMapping("/api/admin/wallet")
@Tag(name = "管理端-车主钱包")
@Slf4j
@RequiredArgsConstructor
public class WalletAdminController {

    private final WalletService walletService;

    /**
     * 管理员调账：加钱或扣钱
     *
     * <p>★ <b>金额可正可负</b>：正数加钱、负数扣钱、0 被拒。
     * 扣钱时余额不足会失败（不会把余额扣成负数）——
     * 一个负余额的钱包会让它后面每一次下单冻结都失败，
     * 而失败的原因（「余额不足」）在账面上看起来完全正常。
     *
     * <p>★★ <b>这是全项目唯一一笔「背后没有任何业务对象」的资金变动</b>：
     * 没有订单、没有权益、没有套餐，{@code wallet_transaction} 上
     * 只有 {@code type = 7} 和那一行 {@code remark}。
     * 所以原因<b>必填</b>，而且验证在 DTO 和 Service 各有一道 ——
     * 半年后审计的人能拿到的全部信息就是那行字。
     *
     * <p>★ 返回调账之后的钱包（余额 / 冻结 / 累计充值）：
     * 运营需要一个能对着念的数字来确认这次操作生效了，
     * 而不是自己再查一次。
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
