package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电站状态
 *
 * <p>对应 {@code station.status}：{@code 0停用 1启用 2维护中}。
 *
 * <h2>★ 为什么不用 {@code StatusConstant}</h2>
 *
 * <p>它只有 ENABLE=1 / DISABLE=0 两个值，而站点是<b>三态</b> ——
 * 多出来的「维护中」不是「禁用」的同义词：停用是运营决策（这个站不做了），
 * 维护中是临时状态（修好了就恢复）。把维护中塞进 DISABLE，
 * 前端就没办法区分「永久关闭」和「今天在检修」。
 *
 * <p>顺带记一笔：{@code StatusConstant} 的类注释里把「站点」列为二值开关，
 * 那句话说错了 —— <b>以 DDL 为准</b>，站点是三值。套餐
 * （{@code charge_package.status} 1上架 0下架）才是真正的二值。
 */
public enum StationStatus {

    /** 停用 —— 运营上不再对外，用户端不可见 */
    DISABLED(0, "停用"),

    /** 启用 —— 正常对外营业 */
    ENABLED(1, "启用"),

    /** 维护中 —— 临时状态，用户端应显示为不可用但在修 */
    MAINTENANCE(2, "维护中");

    private final int code;

    private final String label;

    StationStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static StationStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("站点状态为空");
        }
        for (StationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的站点状态：" + code);
    }
}
