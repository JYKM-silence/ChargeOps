package com.chargeops.vo;

import com.chargeops.entity.MemberPackage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 作废一份套餐权益的结果
 *
 * 为什么要有这个返回值，而不是 {@code Result<Void>}
 *
 * <p>这是一个真实资金操作的接口。调用方（运营）点完之后必须能确认
 * 「退了多少钱、退给谁了」—— {@code Result<Void>} 只回答「成功了」，
 * 而运营需要的是一个能对着念的数字。
 *
 * <p> {@code refundAmount} 可能就是 {@code 0.00}，那不是错误：
 * 赠送套餐（{@code pay_amount = 0}）作废时什么都不用退。
 * 所以调用方不要用「refundAmount 是否大于 0」判断这次操作成功了没有 ——
 * 判据是 HTTP 200 加 {@code code = 1}。
 *
 * <p> 为什么不把 {@code MemberPackageVO} 整个返回去：
 * 那个视图是给车主端「我的套餐」用的，里面 7 个类型专属列在这一刻
 * 全部没有意义（权益已经作废了，还剩多少度一点都不重要）。
 * 返回它等于让调用方以为「剩余量还有 30 度」，而那份权益已经不能用了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPackageVoidVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被作废的那一行 {@code member_package.id} */
    private Long memberPackageId;

    /**
     * 钱退给了谁。它是从权益行上读出来的，不是从请求里拿的 ——
     * 两者在正常情况下相同，但只有前者能保证「退的那个人」和
     * 「拥有这份权益的那个人」是同一个。见 {@code WalletService} 里
     * 「第三组不能读 BaseContext」那一段。
     */
    private Long memberId;

    /** SKU 名称快照。 让日志和运营的确认弹窗里出现的是「150 度电包」，而不是一个 id */
    private String packageName;

    /** 退回去的金额 = {@code member_package.pay_amount}，可能是 0.00 */
    private BigDecimal refundAmount;

    public static MemberPackageVoidVO from(MemberPackage mp, BigDecimal refundAmount) {
        return MemberPackageVoidVO.builder()
                .memberPackageId(mp.getId())
                .memberId(mp.getMemberId())
                .packageName(mp.getPackageName())
                .refundAmount(refundAmount)
                .build();
    }
}
