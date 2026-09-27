package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 管理员调账请求
 *
 * <p> 车主 id 不在这个 DTO 里，它是路径变量
 * （{@code POST /api/admin/wallet/{memberId}/adjust}）。
 * 理由和 {@code MemberPackageVoidDTO} 把金额从请求里拿掉是同一条：
 * 「给谁调」这件事不该是一个请求体里可以随手改的字段 ——
 * 放在路径上，它和被调的那一行在 URL 里是同一个东西，
 * 抄错 URL 会 404（那个车主不存在），而不是安静地把钱打到别人账上。
 *
 * 为什么 {@code amount} 允许负数，而 {@link OrderRefundDTO} 不允许
 *
 * <p>「退款」和「调账」是两件事，判据是有没有一个能指的单据：
 * <ul>
 * <li>退款指向一笔订单或一份权益，方向永远是「把钱还给用户」，
 * 所以负数一定意味着调用方算错了；</li>
 * <li>调账什么都不指向 —— 它是运营按一句事实把账改平的动作，
 * 方向由那个事实决定。一次误扣要加钱，一次漏收要扣钱，
 * 它们是同一个动作的两个方向。</li>
 * </ul>
 *
 * <p> 所以这里的上下限是对称的（{@code ±100000}），
 * 而 {@code OrderRefundDTO} 只有上界。
 *
 * <p> 负数的下限拦的同样是手滑多打几个 0  ——
 * 调账扣钱扣的是用户已经充进来的钱，扣多了要退回去的流程比退款麻烦得多
 * （用户会先看到余额变少，然后投诉，然后你去查是哪一笔）。
 */
@Data
@Schema(description = "管理员调账请求")
public class WalletAdjustDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 调账金额，可正可负，不能为 0
     *
     * <p> 正 = 给车主加钱，负 = 从车主账上扣钱。
     * 两个方向都会被记进 {@code wallet_transaction}，{@code amount} 保留符号
     * （那列的约定就是「正入账负出账」）。
     *
     * <p> 0 由 {@code @DecimalMin/@DecimalMax} 拦不住（0 落在区间内），
     * 所以它由 {@code WalletServiceImpl.adjust} 单独拒绝，
     * 消息是 {@code MessageConstant.WALLET_ADJUST_AMOUNT_ZERO}。
     * 不要在这里加一个自定义校验去补它 —— 那会让「金额非法」这件事
     * 有两个来源，而两处的文案必然会漂移。
     *
     * <p> 上限拦的不是真实业务（一笔调账到不了 10 万），是手滑。
     * 一个把钱往用户账上送的接口，多打两个 0 是它最可能的失败方式。</p>
     */
    @NotNull(message = "调账金额不能为空")
    @DecimalMin(value = "-100000.00", message = "调账金额超出下限，请确认是否填错")
    @DecimalMax(value = "100000.00", message = "调账金额超出上限，请确认是否填错")
    @Schema(description = "调账金额，正数加钱、负数扣钱，不能为 0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    /**
     * 调账原因，必填
     *
     * <p> 这是全项目唯一一笔「背后没有任何业务对象」的资金变动。
     * 没有订单、没有权益、没有套餐 —— {@code wallet_transaction} 上
     * 只有 {@code type = 7} 和这一行 {@code remark}。
     * 半年后审计的人能拿到的全部信息就是它。
     *
     * <p> 和 {@code MemberPackageVoidDTO.remark} 同样是 200 字上限，
     * 理由也一样（{@code varchar(255)} 的截断是静默的）。
     */
    @NotBlank(message = "调账原因不能为空")
    @Size(max = 200, message = "调账原因不能超过 200 个字")
    @Schema(description = "调账原因，必填，会写进资金流水",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String remark;
}
