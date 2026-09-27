package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 作废一份套餐权益的请求（管理端）
 *
 * <h2>★★ 只有 {@code remark} 一个字段，金额<b>不在</b>请求里 ★★</h2>
 *
 * <p>这和 {@link OrderRefundDTO} 的处理<b>刻意相反</b>，值得说清楚为什么，
 * 否则下一个人会觉得这两处不一致是漏改的。
 *
 * <p>{@code OrderRefundDTO} 要求调用方填金额，理由是「真实退款不总是全额的」
 * （投诉了服务费、扣了手续费、部分权益已用掉）。而<b>套餐退款的口径被定死了：
 * 只退完全没用过的，全额退</b> —— 也就是说金额<b>不是一个决定，是一个计算结果</b>：
 * 它等于 {@code member_package.pay_amount}。
 *
 * <p>★★ 把它做成请求参数会制造一个**真实的风险**：运营在页面上能看到那个数字，
 * 于是他能改它。而一个可以被手输的退款金额，在系统里留下的痕迹和
 * 一笔正确的退款**完全一样** —— 都是「作废成功、退了一笔钱」，
 * 唯一能发现差异的地方是事后逐笔核对 {@code pay_amount} 和流水金额。
 * 一个不由人输入的字段不存在被输错的可能。</p>
 *
 * <p>★ 所以金额由 Service 从 {@code MemberPackage#getPayAmount()} 上读，
 * 全程不经过 HTTP。要退一笔不一样的金额（客服补偿、部分退），
 * 那是 <b>{@code ADMIN_ADJUST}</b> 该干的事 —— 它本来就为「一笔人为决定的钱」
 * 准备着，而且会在 {@code wallet_transaction} 上留下一个不同的 type，
 * 对账时一眼能分开。
 */
@Data
@Schema(description = "作废套餐权益请求")
public class MemberPackageVoidDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 作废原因，<b>必填</b>
     *
     * <p>★ 它会同时被写进两个地方，两个地方都是给人看的：
     * <ul>
     *   <li>{@code member_package.remark} —— 客服打开这份权益时看到的「为什么它是灰的」</li>
     *   <li>{@code wallet_transaction.remark} —— 对账的人看到的「这笔钱为什么回来了」</li>
     * </ul>
     *
     * <p>★★ 而这条资金流水<b>没有 {@code order_id}</b>（套餐直购没有购买单），
     * 所以它<b>没有任何别的列</b>能解释这笔钱。填「1」或者留空，
     * 等于半年后让人去猜 —— 那时那份权益的上下文早就散了。
     *
     * <p>★ 上限 200：DDL 里 {@code remark} 是 {@code varchar(255)}，
     * 留 55 个字符给 {@code MemberPackageServiceImpl} 拼上去的前缀
     * （「作废套餐权益退款：<b>套餐名</b>（<b>原因</b>）」）。
     * 不设上限的话，一段 500 字的原因会在 INSERT 时被 MySQL 截断 ——
     * 而 MySQL 默认是<b>截断加一个 warning</b>，不是报错，所以那段文字会静静地丢一半。
     */
    @NotBlank(message = "作废原因不能为空")
    @Size(max = 200, message = "作废原因不能超过 200 个字")
    @Schema(description = "作废原因，必填，会写进权益备注和资金流水",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String remark;
}
