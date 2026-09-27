package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 充电枪状态
 *
 * <p>对应 {@code connector.status}：{@code 0离线 1空闲 2已占用 3充电中 4故障 5维护}。
 *
 * <h2>★ 管理员只能改其中的四个</h2>
 *
 * <p>{@link #OCCUPIED 2已占用} 和 {@link #CHARGING 3充电中} <b>归订单状态机所有，
 * 管理端接口必须拒绝手工设置</b>。原因不是「权限」，而是这两个值
 * <b>不是独立的字段，是订单的存在性在枪上的投影</b>：
 * 手工设成 3 会凭空造出一把「充电中」但没有订单的枪 —— 用户扫它启动充电时，
 * 条件更新 {@code WHERE status=1 AND current_order_id IS NULL} 不匹配，
 * 得到「该枪正在使用中」，而实际上没人在用，运维看着界面也不知道怎么恢复；
 * 手工设成 2 则会直接和 {@code uk_connector_current_order} 的语义打架。
 *
 * <p>允许管理员设置的只有 {@link #OFFLINE 0}、{@link #IDLE 1}、
 * {@link #FAULT 4}、{@link #MAINTENANCE 5}。
 *
 * <h2>为什么「离线」和「故障」要分开</h2>
 *
 * <p>离线是<b>通信问题</b>（心跳断了，枪本身可能是好的），
 * 故障是<b>设备问题</b>（枪自己上报了错误码）。
 * 合并成一个状态的话，运维去现场排查时无法区分
 * 「要不要带万用表」和「要不要带网线」。
 */
public enum ConnectorStatus {

    /** 离线 —— 心跳超时，通信中断。管理员可设置 */
    OFFLINE(0, "离线"),

    /** 空闲 —— 可被扫码启动。管理员可设置 */
    IDLE(1, "空闲"),

    /** ★ 已占用 —— 有订单占着但还没开始充电。归订单状态机所有，管理员不可设置 */
    OCCUPIED(2, "已占用"),

    /** ★ 充电中 —— 正在输电。归订单状态机所有，管理员不可设置 */
    CHARGING(3, "充电中"),

    /** 故障 —— 设备上报的错误。管理员可设置 */
    FAULT(4, "故障"),

    /** 维护 —— 运维主动置位，不参与派单。管理员可设置 */
    MAINTENANCE(5, "维护");

    private final int code;

    private final String label;

    ConnectorStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 这个状态是否允许由管理端接口手工设置
     *
     * <p>把判断收进枚举，是为了让「哪些值归订单状态机所有」只有一处定义 ——
     * 散在 Service 里的 {@code if (status == 2 || status == 3)} 迟早会和别处不一致，
     * 而这类不一致的表现是「某条路径能绕过限制把枪设成充电中」，不会报错。
     */
    public boolean isAdminSettable() {
        return this != OCCUPIED && this != CHARGING;
    }

    public static ConnectorStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("充电枪状态为空");
        }
        for (ConnectorStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的充电枪状态：" + code);
    }
}
