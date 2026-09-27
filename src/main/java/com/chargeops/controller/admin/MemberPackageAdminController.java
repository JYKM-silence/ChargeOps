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
 * 管理端 - 车主持有的套餐权益
 *
 * <p>路径 {@code /api/admin/member-package/**}，由 {@code JwtTokenAdminInterceptor} 保护。
 *
 * <h2>★★ 这个 Controller 只有两个接口，但它们必须成对存在 ★★</h2>
 *
 * <p>查询（{@code GET}）和作废（{@code POST}）是一组，少一个另一个就没法用：
 * <ul>
 *   <li>少了查询：作废要的是 {@code member_package.id}，而在本轮之前
 *       <b>全项目没有任何接口能让人查到一个权益 id</b> ——
 *       车主端只有「我的套餐」（认的是登录人），管理端一条都没有。
 *       一个只能靠猜 id 调用的作废接口不是一个「路径」；</li>
 *   <li>少了作废：查询本身没有意义（车主自己就能看）。</li>
 * </ul>
 *
 * <h2>★★ 为什么这些接口里没有「车主 id 从 token 来」这回事 ★★</h2>
 *
 * <p>{@code /api/admin/**} 上挂的是<b>管理端</b>拦截器，它往 {@code BaseContext}
 * 里放的是<b>员工</b>的 id；放车主 id 的是 {@code /api/member/**} 上的那个拦截器。
 * 所以本类的两个接口都<b>显式地收 {@code memberId}</b>（查询是参数、作废是
 * 「从权益行上读」），一个都不从 {@code BaseContext} 取。
 *
 * <p>★★ <b>写成 {@code requireMemberId()} 的症状：运营点了「作废并退款」，
 * 钱退进了运营自己的钱包，而且全程不报错。</b>★★
 * {@code wallet} 表里确实多了一笔钱、流水也确实写下了，整条链路看上去完全成功 ——
 * 唯一的异常是「车主说没收到」，而那时运营的账上已经躺着一笔他自己都解释不清的钱。
 *
 * <p>★ <b>这也是为什么本类的接口上没有 {@code MemberService.assertActive}</b> ——
 * 「账号被禁用」恰恰是作废并退钱的常见原因。完整论证见
 * {@code MemberPackageService} 类注释第三节。
 *
 * <h2>★ 作废是一个资金操作，不是一次「编辑」</h2>
 *
 * <p>它会把 {@code member_package.pay_amount} 全额退回车主的余额，
 * 并写下一条 {@code PACKAGE_REFUND} 流水。所以那个接口
 * <b>要求填原因</b>（会同时落进权益备注和资金流水），
 * 而且<b>金额不在请求里</b> —— 见 {@link MemberPackageVoidDTO}。
 * 要退一笔和实付不一样的金额（客服补偿、部分退），走
 * {@code POST /api/admin/wallet/{memberId}/adjust}，那是另一条路。
 */
@RestController
@RequestMapping("/api/admin/member-package")
@Tag(name = "管理端-套餐权益")
@Slf4j
@RequiredArgsConstructor
public class MemberPackageAdminController {

    private final MemberPackageService memberPackageService;

    /**
     * 看某个车主名下的全部权益（不过滤状态）
     *
     * <p>★ <b>必须支持「不过滤状态」</b>：已用尽 / 已过期 / 已作废的也要返回。
     * 运营来查的时候，十次里有九次是为了回答「这份权益为什么不能用了」——
     * 滤掉它们等于把答案藏起来（车主说「我的套餐没了」，
     * 而运营在后台查到的是一片空白，两边看的是同一个系统却给出不同的结论）。
     *
     * <p>★ 返回里带 {@code payAmount}：运营在点「作废」之前<b>必须先看到金额</b>。
     * 一个只显示「作废」按钮、不显示金额的界面，等于让人闭着眼睛点一次
     * 真实的资金操作。它可能是 {@code null}（建于 V3 之前），
     * 那时前端要显示「未知」而不是「0」—— 判据是 {@code payAmount == null}。
     */
    @GetMapping
    @Operation(summary = "某车主的全部套餐权益",
            description = "不过滤状态；payAmount 为 null 表示无法退款（建于本功能上线之前）")
    public Result<List<MemberPackageVO>> listByMember(@RequestParam Long memberId) {
        return Result.success(memberPackageService.listByMember(memberId));
    }

    /**
     * 作废一份权益并全额退款
     *
     * <p>★★ <b>口径：只退完全没用过的，全额退。</b>★★
     * 一旦这份权益被任何一笔非取消的订单引用过（包括还没付钱的
     * {@code 4 待支付} / {@code 6 支付失败} 和已经挂账的 {@code 9}），
     * 整份不退 —— 那时余量已经扣了，钱可能还没收到，但电已经用了。
     *
     * <p>★ <b>退款金额 = {@code member_package.pay_amount}</b>，
     * 不由请求传入。赠送套餐（{@code pay_amount = 0}）照常作废，退款 0。
     *
     * <p>★ 返回体里带「退给谁、退了多少」—— 这是一个动真钱的接口，
     * 调用方需要能对着念一遍。见 {@link MemberPackageVoidVO}。
     */
    @PostMapping("/{id}/void")
    @Operation(summary = "作废权益并退款",
            description = "只退完全没用过的，全额退；金额取自权益上的实付快照，不可指定")
    public Result<MemberPackageVoidVO> voidPackage(@PathVariable Long id,
                                                   @Valid @RequestBody MemberPackageVoidDTO dto) {
        return Result.success(memberPackageService.voidPackage(id, dto));
    }
}
