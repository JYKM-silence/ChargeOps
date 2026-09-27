package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车主持有权益（对应 {@code member_package} 表）—— 某个车主真的买下某个 SKU 之后产生的那一行。
 *
 * <p> 快照列（名称、总量、折扣、有效期）是铁律：{@code charge_package} 是可编辑的货架，
 * 已经卖出去的东西条款不能被售后修改，所以「账」记在这里。
 *
 * <p> {@code locked_order_id} 靠「唯一索引不约束 NULL」同时做到两件事：未锁定的权益
 * 可以有任意多份，而一旦写上订单 id，第二笔订单想锁同一份权益在数据库层必然失败（errno 1062）。折扣包恒为 NULL ——
 * 它是修饰符不是消耗品，锁它反而会撞唯一键，豁免是设计不是漏了。
 *
 * <p> {@code expire_time} 用 9999-12-31 哨兵而不是 NULL，因为 {@code WHERE expire_time > NOW()}
 * 遇到 NULL 恒不成立，永久权益会被静默漏掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPackage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 「永久有效」哨兵：{@code 9999-12-31 23:59:59.000}，{@code datetime(3)} 能表示的最大整秒值。不用 {@code LocalDateTime.MAX} —— 它写进 {@code datetime(3)} 会被 MySQL 拒绝。
     */
    public static final LocalDateTime PERMANENT_EXPIRE_TIME =
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 0);

    private Long id;

    private Long memberId;

    private Long packageId;

    /** 恒为 NULL 的预留列：直购模型不建购买单，不是「哪里忘了写」 */
    private Long buyOrderId;

    /** {@code 1电量包 2时长包 3服务费折扣包 4次卡}；决定下面哪一组 {@code total_*} / {@code remaining_*} 有值 */
    private Integer packageType;

    /** 购买时的 SKU 名称快照。SKU 改名不影响已购用户看到的叫法 */
    private String packageName;

    /**
     * 购买时实付金额快照（V3 加的列），退款金额的唯一来源 ——
     * PACKAGE_PURCHASE 流水和 buyOrderId 都是 NULL，两者之间没有 join 键。
     *
     * <p> null 与 0.00 是两个相反的答案：null = V3 之前买的、数字已永久丢失，必须转人工；
     * 0.00 = 赠送套餐，确知无钱可退。所以刻意不写 DEFAULT。
     *
     * <p> 退款不要改用 {@code charge_package.price}：价格可编辑，管理员改价后按新价退旧单，
     * 全程静默。
     */
    private BigDecimal payAmount;

    /** {@code package_type = 1} 时的总电量，{@code decimal(10,2)} */
    private BigDecimal totalKwh;

    /** {@code package_type = 1} 时的剩余电量 —— 结算只动这一列 */
    private BigDecimal remainingKwh;

    /** {@code package_type = 2} 时的总时长（分钟） */
    private Integer totalMinutes;

    /** {@code package_type = 2} 时的剩余时长（分钟） */
    private Integer remainingMinutes;

    /** {@code package_type = 4} 时的总次数 */
    private Integer totalTimes;

    /** {@code package_type = 4} 时的剩余次数 */
    private Integer remainingTimes;

    /** {@code package_type = 3} 时的服务费折扣快照，{@code 0.8000} 表示八折。结算用它算 discount_amount */
    private BigDecimal serviceDiscount;

    /** 生效时间（含）。通常等于购买时间 */
    private LocalDateTime effectiveFrom;

    /** 过期时间（不含）。存绝对时间不存 {@code valid_days} —— 后者是 SKU 属性、可被管理员改 */
    private LocalDateTime expireTime;

    /** 被哪笔订单锁定；{@code NULL} = 未锁定。唯一索引 {@code uk_mp_locked_order}，折扣包恒为 NULL */
    private Long lockedOrderId;

    /** {@code 1生效中 2已用尽 3已过期 4已作废}。它是缓存不是真相：2/3 由剩余量和时间推导 */
    private Integer status;

    private String remark;

    private Integer version;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的 insert 可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
