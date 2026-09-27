package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车主持有权益（对应 {@code member_package} 表）
 *
 * <p>它是 {@link ChargePackage}（SKU）的<b>持有实例</b>：
 * 「花 100 元买 150 度电」是一个 SKU，某个车主真的买下之后，
 * 产生的是这一行。
 *
 * <h2>★ 一、为什么 SKU 之外必须还有这张表</h2>
 *
 * <p>「还剩多少分钟」「还剩几次」「这个车主此刻有没有生效中的折扣」都是
 * <b>持有属性</b>，而 SKU 只能回答「花多少钱能买什么」。电量包也不例外 ——
 * 150 度要跨多笔订单核销，「还剩 120 度」必须有个地方存。
 *
 * <h2>★ 二、这些列为什么要快照（而不是每次去查 SKU）</h2>
 *
 * <p>和 {@code charging_order} 冻结 {@code tariff_template_id} 是同一条铁律：
 * <b>已经卖出去的东西，条款不能被售后修改。</b>
 * {@code charge_package} 是可编辑的，如果核销时去查 SKU 拿「总电量」，
 * 管理员把 150 改成 100 的那一刻，所有已经付了 150 度钱的用户会<b>静默地少掉 50 度</b>。
 * 所以：{@code charge_package} 负责「货架」，本表负责「账」。
 *
 * <p>★ <b>只快照计费要用到的和用户会看到的</b>：名称、总量、折扣、有效期。
 * {@code description} / {@code image} 这类纯展示字段不快照 ——
 * 用户想看最新的商品图，应该去看 SKU。
 *
 * <h2>★ 三、{@code lockedOrderId} 是第四处「唯一索引不约束 NULL」</h2>
 *
 * <p>前三处是 {@code connector.current_order_id}、
 * {@code charging_order_event.idempotent_key}、{@code wallet_transaction.(order_id, type)}。
 * 这里同样：任意多份未锁定的权益可以并存，但一旦写成
 * {@code locked_order_id = 1001}，第二笔订单想锁同一份权益，
 * 在<b>数据库层</b>必然失败（{@code errno 1062}）。
 * 它把「同一份权益被两笔订单同时消费」从「靠代码写对」变成「数据库不允许错」。
 *
 * <p>★ 反方向也一并约束了：一笔订单<b>最多锁定一个</b>消耗型权益实例 ——
 * 一次充电只消耗一种主权益，这正是 {@code charging_order} 上那个单一的可空外键
 * {@code member_package_id} 所表达的意思。
 *
 * <p><b>★ 折扣包（{@code packageType = 3}）永远不写这个字段，恒为 NULL。</b>
 * 它不是消耗品而是「有效期内自动适用」的修饰符，结算时打折但不扣量。
 * 给它加锁没有意义，反而会因为「一笔订单同时用了电量包和折扣包」撞上唯一键 ——
 * 豁免它是设计，不是漏了。
 *
 * <h2>★ 四、{@code lockedOrderId} 同时充当幂等标记</h2>
 *
 * <p>设备会重复上报「充电结束」，结算因此可能被触发多次。防重复扣减靠的是
 * 把「判断」和「修改」放进同一条 UPDATE：
 *
 * <pre>
 *   UPDATE member_package
 *      SET remaining_kwh = remaining_kwh - #{kwh}, locked_order_id = NULL, ...
 *    WHERE id = #{id} AND locked_order_id = #{orderId}
 * </pre>
 *
 * <p>第一次执行后 {@code locked_order_id} 变成 NULL，第二次必然匹配不上、影响行数为 0。
 * ★ <b>不要</b>写成「先 SELECT 判断再 UPDATE」—— 那是 TOCTOU，
 * 并发下两个线程都能通过检查然后各扣一次。和 {@code wallet} 那段是同一条规则，
 * 见 {@link com.chargeops.mapper.MemberPackageMapper}。
 *
 * <h2>★ 五、{@code expireTime} 用哨兵而不是 NULL</h2>
 *
 * <p>永久有效的权益写成 {@code 9999-12-31 23:59:59.000}，因为「有效期到那天」的查询
 * 写成 {@code WHERE expire_time > NOW()}，而 <b>NULL 参与任何比较都不成立</b> ——
 * 永久有效的权益会被这个查询<b>漏掉</b>，表现是「明明买了永久套餐，结算时说没有」。
 * 这和 {@code station_id = 0} 而不是 NULL 是同一个陷阱。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPackage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 「永久有效」的哨兵值：{@code 9999-12-31 23:59:59.000}
     *
     * <p>这是 {@code datetime(3)} 能表示的最大整秒值。★ 不用
     * {@code LocalDateTime.MAX}：它是 {@code +999999999-12-31T...}，
     * 写进 {@code datetime(3)} 会被 MySQL 拒绝，表现为结算时报一个
     * 看起来毫不相干的日期错误。
     */
    public static final LocalDateTime PERMANENT_EXPIRE_TIME =
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 0);

    private Long id;

    /** 持有人，指向 {@code member} 表 */
    private Long memberId;

    /** SKU，指向 {@code charge_package} 表 */
    private Long packageId;

    /**
     * 购买这笔权益的支付单
     *
     * <p>★★ <b>恒为 {@code NULL}，而且不是「暂时」。</b>★★
     *
     * <p>这个字段原来配着一句「P1c-3 落地订单之前恒为 NULL」—— 那句话读起来像是
     * 「等订单表建好就会填」，而它<b>从来没有被填过，也不打算被填</b>。
     * 现在的购买模型是<b>直购</b>：一个事务里「扣钱 + 插这一行」，
     * <b>不建购买单</b>。理由是充电链路需要状态机，是因为「设备上报」这个外部事件
     * 把流程切成了多段、每段之间都可能失败重试；而套餐购买<b>没有外部事件</b>，
     * 扣钱和得到权益是同一个事务里的两件事，不存在需要 CAS 保护的中间态。
     *
     * <p>★ <b>所以这一列现在的含义是「如果将来改成下单-支付两段式，这里放购买单 id」</b>，
     * 它是一处<b>预留</b>。看到它恒为 NULL 时不要去找「哪里忘了写」——
     * 没有地方忘，是这个模型不产生这个值。
     *
     * <p>★ 直接后果（见 {@code WalletTransactionType.PACKAGE_PURCHASE}）：
     * 购买流水没有 join 键能回到这一行，也没有数据库级的幂等保证。
     */
    private Long buyOrderId;

    /**
     * ★ 购买时冻结的 SKU 类型：{@code 1电量包 2时长包 3服务费折扣包 4次卡}
     *
     * <p>决定下面哪一组 {@code total_* / remaining_*} 有值。快照的理由见类注释第二节 ——
     * 类型被改的话，「该扣哪一列」这个判断本身就会错。
     */
    private Integer packageType;

    /** 购买时的 SKU 名称快照。SKU 改名不影响已购用户看到的叫法 */
    private String packageName;

    /**
     * 购买时<b>实际付掉</b>的金额快照（{@code V3} 加的列）
     *
     * <p>★ 它是<b>退款金额的唯一来源</b>，也是这一列存在的全部理由。别的地方都没有这个数：
     * 那条 {@code PACKAGE_PURCHASE} 钱包流水的 {@code order_id} 是 NULL（直购没有购买单），
     * {@link #buyOrderId} 同样恒为 NULL，两者之间<b>没有 join 键</b>。
     *
     * <p>★★ <b>它和 {@code charge_package.price} 是两回事，永远不要拿后者替代它。</b>★★
     * SKU 的价格是可编辑的：管理员把 100 改成 80 之后，用 SKU 价格退款等于
     * 「按今天的售价退去年的钱」，而且<b>全程静默</b> —— 车主只会收到一笔比应得少的钱。
     * 这和 {@code charging_order} 冻结电价、冻结套餐名是同一条铁律：
     * <b>已经卖出去的东西，条款不能被售后修改。</b>
     *
     * <p>★★ <b>{@code null} 和 {@code 0} 是两个不同的答案，别把它们合并：</b>
     * <ul>
     *   <li>{@code null} —— <b>不知道</b>。这一行建于 {@code V3} 之前，
     *       当时的表里没有这一列，真正的数字已经<b>永久丢失</b>（没有 join 键能找回来）。
     *       退款路径必须<b>拒绝</b>并转人工，见
     *       {@code MessageConstant.MEMBER_PACKAGE_PAY_AMOUNT_MISSING}。</li>
     *   <li>{@code 0.00} —— <b>确知是 0</b>。赠送套餐（{@code price = 0} 是合法商品价格，
     *       见 {@code ChargePackageDTO}）。它没有钱可退，但那是<b>一个已知的事实</b>，
     *       不是一次数据缺失。</li>
     * </ul>
     * 所以这一列<b>没有 DEFAULT</b>：写成 {@code DEFAULT 0.00} 会让上面两种行
     * 变成同一个值，而它们该触发的动作完全相反。见 {@code V3} 迁移的注释。
     */
    private BigDecimal payAmount;

    /** {@code package_type = 1} 时的总电量，{@code decimal(10,2)} */
    private BigDecimal totalKwh;

    /** {@code package_type = 1} 时的剩余电量 —— <b>结算只动这一列</b> */
    private BigDecimal remainingKwh;

    /** {@code package_type = 2} 时的总时长（分钟） */
    private Integer totalMinutes;

    /** {@code package_type = 2} 时的剩余时长（分钟） */
    private Integer remainingMinutes;

    /** {@code package_type = 4} 时的总次数 */
    private Integer totalTimes;

    /** {@code package_type = 4} 时的剩余次数 */
    private Integer remainingTimes;

    /**
     * {@code package_type = 3} 时的服务费折扣快照，{@code decimal(5,4)}，{@code 0.8000} 表示八折
     *
     * <p>它和 {@code charging_order.discount_amount} 是配套的：
     * 结算时用这个值算出订单的优惠金额。
     */
    private BigDecimal serviceDiscount;

    /** 生效时间（含）。通常等于购买时间 */
    private LocalDateTime effectiveFrom;

    /**
     * 过期时间（<b>不含</b>）
     *
     * <p>★ <b>存的是绝对时间，不是 {@code valid_days}</b>：
     * {@code charge_package.valid_days} 是 SKU 属性、可以被管理员改，
     * 而本列是持有属性（「你这一份到 2026-12-17 为止」），
     * <b>不该被任何人的后续操作改变</b>。永久有效用 {@link #PERMANENT_EXPIRE_TIME}。
     */
    private LocalDateTime expireTime;

    /**
     * 被哪笔订单锁定；{@code NULL} = 未锁定
     *
     * <p>★ 有唯一索引 {@code uk_mp_locked_order}，<b>折扣包恒为 NULL</b> —— 见类注释第三节。
     */
    private Long lockedOrderId;

    /**
     * {@code 1生效中 2已用尽 3已过期 4已作废}
     *
     * <p>★ <b>它是缓存，不是真相</b>：2 和 3 是从剩余量、时间推导出来的。
     * 判断「现在能不能用」必须同时看 {@code expireTime} 和剩余量，
     * 完整说明见 {@link com.chargeops.enumeration.MemberPackageStatus}。
     */
    private Integer status;

    private String remark;

    /** 乐观锁 */
    private Integer version;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的 insert 可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
