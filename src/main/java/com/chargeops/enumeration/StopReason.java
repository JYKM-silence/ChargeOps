package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 停止原因，对应 {@code charging_order.stop_reason}
 *
 * <p>★ 它<b>可空</b>，而且 {@code NULL} 是有信息量的：只有「充电真的开始过」的订单
 * 才会有停止原因，待启动就被取消的订单没有它 —— 用 {@code 1 用户主动} 去填
 * 会制造一个假的信号，让「用户主动停的比例」这个统计把取消的订单也算进去。
 *
 * <p>★ 区分 {@link #USER} 和 {@link #FULL} 不是为了好看：「充满自停」的订单
 * 不该被计费规则按「用户行为」处理（比如将来的「未充满补偿」），
 * 而且运营要看的是「多少订单是因为用户嫌慢拔枪走的」—— 那才是服务质量问题。
 */
public enum StopReason {

    /** 用户在 App / 小程序上点了停止 */
    USER(1, "用户主动"),

    /** 电池充满，设备自动停止 */
    FULL(2, "充满自停"),

    /**
     * 余额不足，系统停止。★ 它和结算的关系最紧：实际金额仍可能大于已冻结的
     * 预估额，结算时要走「差额从可用余额出」那条路；可用余额也不够就进 9 异常挂账。
     */
    BALANCE(3, "余额不足"),

    /** 设备故障（急停、通信中断、模块报错） */
    DEVICE_FAULT(4, "设备故障"),

    /** 超时（长时间无遥测上报，由定时任务判定） */
    TIMEOUT(5, "超时"),

    /** 运维人工停止。★ 走这条路径必须留 {@code operator_id} */
    OPERATOR(6, "运维干预");

    private final int code;

    private final String label;

    StopReason(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static StopReason of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("停止原因为空");
        }
        for (StopReason reason : values()) {
            if (reason.code == code) {
                return reason;
            }
        }
        throw new ParamErrorException("未知的停止原因：" + code);
    }
}
