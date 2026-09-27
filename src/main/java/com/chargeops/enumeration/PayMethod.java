package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 支付方式，对应 {@code charging_order.pay_method}
 *
 * <p>★ 本项目只实现了 {@link #BALANCE}：另外两个值留在枚举里，但<b>没有任何代码
 * 能产生它们</b>（没有支付网关、没有回调接口、没有签名校验）。写在这里是为了让
 * 「不支持的支付方式」是一次<b>可指名的失败</b>：建单时看到 {@code WECHAT}
 * 会明确拒绝，而不是把它当成余额扣了钱 ——
 * 一个「微信支付」按钮点了之后从余额扣钱，是比没有这个按钮严重得多的错误。
 */
public enum PayMethod {

    /** 余额支付。★ 结算和扣款在同一个事务里，4 待支付是瞬间穿过的 */
    BALANCE(1, "余额"),

    /** ★ 未实现：没有支付网关。选了会被明确拒绝，不会被当成余额 */
    WECHAT(2, "微信"),

    /** ★ 未实现。同上 */
    ALIPAY(3, "支付宝");

    private final int code;

    private final String label;

    PayMethod(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 本项目当前真正能走通的支付方式 */
    public static boolean isSupported(PayMethod method) {
        return method == BALANCE;
    }

    public static PayMethod of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("支付方式为空");
        }
        for (PayMethod method : values()) {
            if (method.code == code) {
                return method;
            }
        }
        throw new ParamErrorException("未知的支付方式：" + code);
    }
}
