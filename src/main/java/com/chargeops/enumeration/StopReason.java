package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 停止原因，对应 {@code charging_order.stop_reason}
 *
 * <p> 可空，且 NULL 有信息量：只有真的开始过充电的订单才有停止原因，用
 * {@link #USER} 去填「待启动就被取消」的订单会污染「用户主动停的比例」这个统计。
 * {@link #USER} 和 {@link #FULL} 要分开，充满自停不该按用户行为处理。
 */
public enum StopReason {

    /** 用户在 App / 小程序上点了停止 */
    USER(1, "用户主动"),

    /** 电池充满，设备自动停止 */
    FULL(2, "充满自停"),

    /** 余额不足，系统停止。实际金额可能大于已冻结的预估额，差额走可用余额，不够就进 9 异常挂账 */
    BALANCE(3, "余额不足"),

    /** 设备故障（急停、通信中断、模块报错） */
    DEVICE_FAULT(4, "设备故障"),

    /** 超时（长时间无遥测上报，由定时任务判定） */
    TIMEOUT(5, "超时"),

    /** 运维人工停止。 走这条路径必须留 {@code operator_id} */
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
