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
 * <h2>★★ 一、为什么 SKU 之外必须还有这张表 ★★</h2>
 *
 * <p>四种 {@code package_type} 里有三种根本没法用 SKU 表达 ——
 * 「还剩多少分钟」「还剩几次」「这个车主此刻有没有生效中的折扣」
 * 都是<b>持有属性</b>，不是 SKU 属性。SKU 只能回答「花多少钱能买什么」，
 * 回答不了「你已经买了什么、还剩多少」。
 *
 * <p>电量包也不例外：150 度要跨多笔订单核销，
 * 「还剩 120 度」必须有个地方存。
 *
 * <h2>★★ 二、这些列为什么要快照（而不是每次去查 SKU）★★</h2>
 *
 * <p>和 {@code charging_order} 冻结 {@code tariff_template_id} 是<b>同一条铁律</b>，
 * 只是换了个对象：<b>已经卖出去的东西，条款不能被售后修改。</b>
 *
 * <p>{@code charge_package} 是可编辑的（管理端有 update 接口）。
 * 如果核销时去查 SKU 拿「总电量」，那么管理员把 150 改成 100 的那一刻，
 * 所有已经付了 150 度钱的用户会<b>静默地少掉 50 度</b> ——
 * 每一步都不报错，只有某天对账时发现余额对不上，
 * 而那时已经无从追溯是哪次改动引入的。
 *
 * <p>所以：{@code charge_package} 负责「货架」，本表负责「账」。
 *
 * <p>★ <b>只快照计费要用到的和用户会看到的</b>：名称、总量、折扣、有效期。
 * {@code description} / {@code image} 这类纯展示字段不快照 ——
 * 用户想看最新的商品图，应该去看 SKU。
 *
 * <h2>★★ 三、{@code lockedOrderId} 是第四处「唯一索引不约束 NULL」★★</h2>
 *
 * <p>前三处分别在 {@code connector.current_order_id}、
 * {@code charging_order_event.idempotent_key}、
 * {@code wallet_transaction.(order_id, type)}。这里是第四次：
 *
 * <ul>
 *   <li>任意多份未锁定的权益（{@code locked_order_id IS NULL}）并存，互不冲突</li>
 *   <li>一旦写成 {@code locked_order_id = 1001}，第二笔订单想锁同一份权益，
 *       在<b>数据库层</b>必然失败（{@code errno 1062}）</li>
 * </ul>
 *
 * <p>它把「同一份权益被两笔订单同时消费」从「靠代码写对」变成「数据库不允许错」。
 *
 * <p>★ 反方向也一并约束了：一笔订单<b>最多锁定一个</b>消耗型权益实例。
 * 这符合业务语义 —— 一次充电只消耗一种主权益（要么电量包、要么时长包、
 * 要么次卡、要么余额直充），这也正是 {@code charging_order}
 * 上那个单一的可空外键 {@code member_package_id} 所表达的意思。
 *
 * <p><b>★★ 折扣包（{@code packageType = 3}）永远不写这个字段，恒为 NULL ★★</b>
 * <br>它不是消耗品，是「有效期内自动适用」的修饰符 —— 结算时问
 * 「这个车主此刻有没有生效的折扣包」，有就打折，不扣任何量。
 * 给它加锁没有意义，反而会因为「一笔订单同时用了电量包和折扣包」
 * 而撞上唯一键。<b>所以豁免它，不是漏了。</b>
 *
 * <h2>★★ 四、幂等：{@code lockedOrderId} 同时充当幂等标记 ★★</h2>
 *
 * <p>设备会重复上报「充电结束」，结算因此可能被触发多次。
 * 防重复扣减靠的是把「判断」和「修改」放进同一条 UPDATE：
 *
 * <pre>
 *   UPDATE member_package
 *      SET remaining_kwh = remaining_kwh - #{kwh}, locked_order_id = NULL, ...
 *    WHERE id = #{id} AND locked_order_id = #{orderId}
 * </pre>
 *
 * <p>第一次执行后 {@code locked_order_id} 变成 NULL，第二次必然匹配不上，
 * 影响行数为 0。见 {@link com.chargeops.mapper.MemberPackageMapper}。
 *
 * <p>★ <b>不要</b>写成「先 SELECT 判断 locked_order_id 是不是这笔订单，再 UPDATE」——
 * 那是 TOCTOU（检查与使用之间存在时间窗口），并发下两个线程都能通过检查，
 * 然后各扣一次。这条和 {@code wallet} 那段
 * 「严禁先 select 判断再 update」是同一条规则。
 *
 * <h2>★ 五、{@code expireTime} 用哨兵而不是 NULL</h2>
 *
 * <p>永久有效的权益写成 {@code 9999-12-31 23:59:59.000}，不用 {@code NULL}。
 * 理由是「有效期到那天」的查询写成 {@code WHERE expire_time > NOW()}，
 * 而 <b>NULL 参与任何比较都是 NULL（不成立）</b>——
 * 永久有效的权益会被这个查询<b>漏掉</b>，
 * 表现是「明明买了永久套餐，结算时说没有」。
 *
 * <p>这和 {@code station_id = 0} 而不是 NULL 是同一个陷阱。
 * 常量见 {@link #PERMANENT_EXPIRE_TIME}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberPackage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 「永久有效」的哨兵值
     *
     * <p>{@code 9999-12-31 23:59:59.000} 是 MySQL {@code datetime} 类型能表示的最大值
     * （上限是 {@code 9999-12-31 23:59:59.999999}，这里取毫秒精度的最大整值）。
     *
     * <p>★ 为什么不直接用 {@code LocalDateTime.MAX}：它是
     * {@code +999999999-12-31T23:59:59.999999999}，写进 {@code datetime(3)}
     * 会被 MySQL 拒绝（超范围），表现为结算时报一个看起来毫不相干的日期错误。
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
     * <p>P1c-3 落地订单之前恒为 {@code NULL}：
     * 权益的购买流水也要走 {@code wallet_transaction}，
     * 而那张表依赖订单流程先存在。
     */
    private Long buyOrderId;

    /**
     * ★ 购买时冻结的 SKU 类型：
     * {@code 1电量包 2时长包 3服务费折扣包 4次卡}
     *
     * <p>决定下面哪一组 {@code total_* / remaining_*} 有值。
     * 快照的理由见类注释第二节 —— 类型被改的话，
     * 「该扣哪一列」这个判断本身就会错。
     */
    private Integer packageType;

    /** 购买时的 SKU 名称快照。SKU 改名不影响已购用户看到的叫法 */
    private String packageName;

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
     * {@code package_type = 3} 时的服务费折扣快照，{@code decimal(5,4)}
     *
     * <p>{@code 0.8000} 表示八折。它和 {@code charging_order.discount_amount}
     * 是配套的：结算时用这个值算出订单的优惠金额。
     */
    private BigDecimal serviceDiscount;

    /** 生效时间（含）。通常等于购买时间 */
    private LocalDateTime effectiveFrom;

    /**
     * 过期时间（<b>不含</b>）
     *
     * <p>★ <b>存的是绝对时间，不是 {@code valid_days}</b>。
     * {@code charge_package.valid_days} 是 SKU 属性（「这种套餐有 90 天有效期」），
     * 可以被管理员改；而本列是持有属性（「你这一份到 2026-12-17 为止」），
     * <b>不该被任何人的后续操作改变</b>。
     *
     * <p>永久有效用 {@link #PERMANENT_EXPIRE_TIME}，见类注释第五节。
     */
    private LocalDateTime expireTime;

    /**
     * 被哪笔订单锁定；{@code NULL} = 未锁定
     *
     * <p>★ 有唯一索引 {@code uk_mp_locked_order}，见类注释第三节。
     * <b>折扣包恒为 NULL</b>。
     */
    private Long lockedOrderId;

    /**
     * {@code 1生效中 2已用尽 3已过期 4已作废}
     *
     * <p>★ <b>它是缓存，不是真相</b>：2 和 3 是从剩余量、时间推导出来的。
     * 判断「现在能不能用」必须同时看 {@code expireTime} 和剩余量。
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
