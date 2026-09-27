package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 退款请求（管理端）
 *
 * <h2>★ 为什么必须传金额，而不是「全额退回」</h2>
 *
 * <p>全额退款看起来是更安全的默认值（少一个填错的字段），但真实退款不总是全额的：
 * 用户投诉了服务费、平台扣了手续费、部分权益已经用掉。如果接口只支持全额，
 * 那些情况就只能在数据库里手工改，<b>而手工改的地方不会有
 * {@code charging_order_event}</b>。★ 用「必填的金额」换「所有退款都有留痕」，
 * 这个交换是划算的。
 *
 * <p>★ {@code amount} 可以为负吗？<b>不可以。</b>传负数等于从用户账上把钱划走 ——
 * 那属于「管理员调账」（{@code WalletTransactionType.ADMIN_ADJUST}），
 * 走的是另一条完全不同的路径，不在退款这里。
 */
@Data
@Schema(description = "退款请求")
public class OrderRefundDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ 上限 <b>10 万元</b>拦的不是真实业务 —— 一笔充电订单不可能到那个量级
     * （订单应付的上限是电池容量 × 电价，现实里几百块封顶）。
     * 它拦的是<b>手滑多打了几个 0</b>：退款是一个把钱往外送的操作，
     * 而送错了要追回来很难。
     */
    @NotNull(message = "退款金额不能为空")
    @DecimalMin(value = "0.01", message = "退款金额必须大于 0")
    @DecimalMax(value = "100000.00", message = "退款金额超出上限，请确认是否填错")
    @Schema(description = "退款金额，必须为正", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;
}
