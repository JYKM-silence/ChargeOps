package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

import java.util.List;

/**
 * 充电订单状态，对应 {@code charging_order.status}
 *
 * <p> 三组集合判据不同，不能合并：
 * <pre>
 * holdingConnectorCodes()  {1,2,3}       还占着一把枪
 * holdingMoneyCodes()      {1,2,3,4,6}   还占着车主的钱
 * blockingCodes()          {1,2,3,4,6,9} 妨碍删站点 / 桩
 * </pre>
 *
 * <p> 每次流转都写成带 from 的条件更新（{@code WHERE id = ? AND status = ?}），
 * 影响行数 0 就是别人先动了这一行。不要先 SELECT 再判断再 UPDATE：
 * 两个并发的启动请求会同时读到 1，然后都成功，冻结两次钱。
 *
 * <p> 终态只有 7 已退款 / 8 已取消。{@link #ABNORMAL} 是挂起不是失败：
 * 算不出正确结果时把订单原样停住、留线索等人工，绝不自动重试。
 */
public enum OrderStatus {

    /** 待启动。已冻结预估金额，取消要解冻 */
    CREATED(1, "待启动"),

    /** 充电中。设备上报启动成功后进入 */
    CHARGING(2, "充电中"),

    /** 充电结束待结算。设备上报结束只说明枪停了，账单还没算 */
    SETTLING(3, "充电结束待结算"),

    /** 待支付。余额支付会瞬间穿过，微信/支付宝在这里等回调，而回调可能不来 */
    PENDING_PAY(4, "待支付"),

    /** 已支付。这里之后没有正向流转，想再收钱只能新建订单 */
    PAID(5, "已支付"),

    /** 支付失败。钱还冻着。可重试 → 5，重试仍失败 → 9 */
    PAY_FAILED(6, "支付失败"),

    /** 已退款。终态 */
    REFUNDED(7, "已退款"),

    /** 已取消。终态。取消前必须把冻结的钱释放回去 */
    CANCELLED(8, "已取消"),

    /** 异常挂账。挂起不是失败，不自动重试；进这个状态必须留事件说明原因 */
    ABNORMAL(9, "异常挂账");

    private final int code;

    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 终态判定集中在这里，避免加了新终态之后有地方漏改 */
    public boolean isTerminal() {
        return this == REFUNDED || this == CANCELLED;
    }

    /** 是否占着一把枪。设备重复上报「结束」时用它判断这把枪上还有没有订单 */
    public boolean holdsConnector() {
        return this == CREATED || this == CHARGING || this == SETTLING;
    }

    /** 是否占着车主的钱（冻结中，或已扣但还能退）。6 支付失败也在里面：那笔钱既没扣掉也没退回 */
    public boolean holdsMoney() {
        return this == CREATED || this == CHARGING || this == SETTLING
                || this == PENDING_PAY || this == PAY_FAILED;
    }

    /** {@code holdsConnector()} 的取值集，给 SQL 的 {@code IN} 用 */
    public static List<Integer> holdingConnectorCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::holdsConnector)
                .map(OrderStatus::getCode)
                .toList();
    }

    /**
     * {@code holdsMoney()} 的取值集，也是转入异常挂账的合法来源
     *
     * <p> 不能拿 {@link #holdingConnectorCodes()} 当挂账来源，那个集合漏掉
     * 4 待支付和 6 支付失败，这两类订单会挂不上去（影响 0 行被当成状态已变而跳过），
     * 于是一笔钱冻着、账结不掉，却没有任何东西会再去碰它。
     */
    public static List<Integer> holdingMoneyCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::holdsMoney)
                .map(OrderStatus::getCode)
                .toList();
    }

    /**
     * 这个状态的订单是否妨碍删除它所属的站点 / 桩。
     *
     * <p> 判据是「钱还冻着」并上「挂起等人工」。不要写成 !isTerminal()：
     * 那个口径会把 5 已支付算进来，让完成过一次充电的站点永久不可删。
     */
    public boolean blocksDeviceDeletion() {
        return holdsMoney() || this == ABNORMAL;
    }

    /** {@code blocksDeviceDeletion()} 的取值集，给 SQL 的 {@code IN} 用 */
    public static List<Integer> blockingCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::blocksDeviceDeletion)
                .map(OrderStatus::getCode)
                .toList();
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("订单状态为空");
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的订单状态：" + code);
    }
}
