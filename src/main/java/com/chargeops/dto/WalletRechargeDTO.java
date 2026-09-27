package com.chargeops.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 充值请求
 *
 * <p> 只有 {@code amount} 一个字段，没有 memberId ——
 * 车主从 token 取，客户端无法给别人充值。
 *
 * <p> 上限 {@code 50000}：本项目没有接真实支付，这个接口直接给余额加钱 ——
 * 不设上限的话，一个脚本能在几秒内造出一个天文数字的余额，而这种事在演示环境里
 * 一定会有人试。接真实支付时这个上限由支付的单笔限额决定，那时应该改成从配置读。
 *
 * <p> 下限 {@code 0.01} 而不是 {@code 0}：充 0 元会写一条金额为 0 的流水，
 * 它对账时是个纯粹的噪音（本来就没有任何业务含义）。
 */
@Data
public class WalletRechargeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "充值金额不能为空")
    @DecimalMin(value = "0.01", message = "充值金额必须大于 0")
    @DecimalMax(value = "50000.00", message = "单笔充值不能超过 50000 元")
    private BigDecimal amount;
}
