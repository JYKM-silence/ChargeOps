package com.chargeops.service;

import com.chargeops.dto.WalletRechargeDTO;
import com.chargeops.dto.WalletTransactionPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.WalletVO;

import java.math.BigDecimal;

/**
 * 钱包服务
 *
 * <p>分<b>三</b>组方法，判据是<b>谁在调</b>：
 * <ol>
 *   <li><b>车主端自己用的</b>：{@link #getMine} / {@link #recharge} /
 *       {@link #pageTransactions} / {@link #deduct}（套餐购买由车主发起）</li>
 *   <li><b>订单状态机用的</b>：{@link #freeze} / {@link #unfreeze} /
 *       {@link #settle} / {@link #refund}。车主端接口不暴露它们</li>
 *   <li><b>管理端用的</b>：{@link #refundPackage} / {@link #adjust}。
 *       ★ <b>它们收 {@code memberId} 参数，因为调用方是运营而不是那个车主</b> ——
 *       见下面那一段「★★ 第三组不能读 BaseContext ★★」</li>
 * </ol>
 *
 * <h2>★★ 资金安全的两道防线，分属两层 ★★</h2>
 *
 * <p>第一道是这里的条件更新：判断写进 {@code WHERE}，影响行数为 0
 * 就是"余额不足"这个业务结果，而不是异常。
 *
 * <p>第二道是 {@code wallet_transaction} 上的
 * {@code uk_txn_order_type (order_id, type)} —— 它在<b>数据库层</b>
 * 保证"同一订单的同一种流水只有一条"。
 *
 * <p>★★ <b>但第二道防线有一个前提：{@code order_id} 非空。</b>★★
 * 唯一索引不约束 NULL，所以 {@code order_id = NULL} 的流水<b>完全不受它保护</b>。
 * 目前有两类：{@link #recharge}（充值本来就可以有任意多笔，这是对的）和
 * {@link #deduct}（套餐购买，见下）。<b>说「所有资金操作都有唯一索引兜底」是错的</b> ——
 * 加新的流水类型之前先确认它落在哪一边，别把这条索引当成通用的幂等保证。
 *
 * <p>★★ <b>调用 {@link #settle} / {@link #refund} 之前，调用方必须先有自己的
 * 幂等判断</b>（订单侧用 {@code charging_order.settle_time} 那个条件更新）。
 * 流水撞唯一键会抛 {@code DuplicateKeyException}，而它一旦抛出，
 * <b>整个事务已被标记为回滚</b> —— 调用方即使 catch 住，提交时仍会失败。
 *
 * <p>所以正确的用法是：<b>订单侧先 CAS，CAS 成功了才调钱包。</b>
 * 钱包的唯一索引是最后一道防线，正常情况下不该被触发；真被触发时说明
 * 订单侧的判断漏了，那一刻<b>应该</b>整笔失败并告警，而不是把异常吞掉
 * 当作成功。
 *
 * <h2>★ 账号状态</h2>
 *
 * <p>所有涉及资金的入口都调 {@code MemberService.assertActive} 重新查一次
 * {@code member.status} —— token 有效不等于账号可用（禁用不撤销 token，
 * 这是已记录的缺口）。只读的 {@link #getMine} 也查，为的是让
 * "钱包域一律要求账号可用"成为一条没有例外的规则，而不是一张清单。
 *
 * <p>★★ <b>第三组（{@link #refundPackage} / {@link #adjust}）是那条规则的
 * 唯一例外，而且必须是。</b>★★ 理由有两层，任一层都足够：
 * <ol>
 *   <li><b>「账号被禁用」本身就是退钱和调账的常见原因</b>（客服补偿、
 *       账号注销前的清算、误扣的纠正）。要求账号可用，等于让运营在
 *       最需要动这笔钱的那一刻动不了 —— 而那笔钱会一直挂在账上。</li>
 *   <li><b>那个校验的前提在这里不成立。</b>{@code assertActive} 查的是
 *       「<b>发起请求的那个账号</b>」的状态，而第三组的请求是<b>运营</b>发的、
 *       动的是<b>另一个车主</b>的账。拿运营的账号状态去判断能不能给车主退钱，
 *       从一开始就是答非所问。</li>
 * </ol>
 *
 * <h2>★★ 第三组不能读 {@code BaseContext} ★★</h2>
 *
 * <p>这是上面第 2 条的落地形式，也是这个接口里最容易写错的一行。
 * 第一组的方法内部调 {@code requireMemberId()}（从 {@code BaseContext} 取当前
 * 登录人），那是对的 —— 请求就是那个车主发的。而第三组<b>绝对不能</b>那么写：
 *
 * <pre>
 *   /api/admin/** 上挂的是<b>管理端</b>拦截器，它往 BaseContext 里放的是【员工】的 id；
 *   而 /api/member/** 上挂的是车主端拦截器。
 *   所以一个 admin 请求里 BaseContext.getCurrentId() 返回的是【运营的 staffId】。
 * </pre>
 *
 * <p>★★ <b>症状：运营点了「作废并退款」，钱退进了运营自己的钱包，而不是那个车主的。</b>★★
 * 而且它<b>不会报错</b> —— {@code wallet} 表里确实多了一笔钱，流水也确实写下了，
 * 整个链路看上去完全成功。唯一的异常是「车主说没收到」，而那时运营的账上
 * 已经躺着一笔他自己都解释不清的钱。
 *
 * <p>所以第三组的方法<b>收 {@code memberId} 参数</b>，由 Service 层从
 * <b>业务对象</b>上取（「这份权益是谁的」），而不是从请求上下文里取。
 * 这个形参的存在本身就是那道防线：它让「我该退给谁」成为一个必须显式回答的问题。
 */
public interface WalletService {

    /** 我的钱包。没开过卡返回零钱包，不是 404 */
    WalletVO getMine();

    /**
     * 充值
     *
     * <p>★ <b>本项目没有接真实支付</b>，这里直接给余额加钱。
     * 上线前必须换成"支付回调 → 入账"，见 {@link WalletRechargeDTO} 的上限说明。
     */
    WalletVO recharge(WalletRechargeDTO dto);

    /** 我的流水（分页，按时间倒序） */
    PageResult pageTransactions(WalletTransactionPageQueryDTO query);

    /**
     * 下单冻结：可用余额转入冻结
     *
     * @throws com.chargeops.exception.BalanceNotEnoughException 余额不足。
     *         ★ 这是<b>业务失败不是系统异常</b>，调用方应据此拒绝下单，
     *         而不是当成错误页面
     */
    void freeze(long memberId, long orderId, BigDecimal amount);

    /**
     * 解冻：把本单冻结的钱全部退回可用余额，<b>不扣款</b>
     *
     * <p>★ 取消订单走这里。它和 {@link #settle} 的区别是<b>没有第二步</b>：
     * 结算会解冻之后再扣掉实付，而取消是「这笔生意不做了」，
     * 钱原样还回去。
     *
     * <p>★ 金额为 0 时什么都不做，<b>不写流水</b> —— 和 {@link #freeze} 一样：
     * 全额权益抵扣的订单本来就没冻过钱，给它记一条 0 元流水只是给对账添噪音。
     *
     * <p>★ 和 {@link #settle} 不同，这里<b>会</b>调 {@code ensureWallet}：
     * 「钱包不存在却要解冻」在取消路径上是正常的边界情况（建单事务回滚了
     * 一半之类），而开一张空卡把它盖住不会掩盖任何金额错误 ——
     * 解冻 0 元是恒等式。
     *
     * @throws com.chargeops.exception.DataConflictException 冻结额不足。
     *         说明订单上记的 {@code frozen_amount} 和钱包里的实际冻结对不上，
     *         那是需要人工对账的硬冲突，不能静默跳过
     */
    void unfreeze(long memberId, long orderId, BigDecimal amount);

    /**
     * 结算：释放本单全部冻结，再扣掉实际金额
     *
     * <p>★ 顺序是<b>先解冻、后扣款</b>，不能反过来 ——
     * 先扣的话钱还在冻结里，可用余额可能不够，
     * 一笔本该成功的结算会被拒。
     *
     * <p>★ 两步写的两条流水是 {@code 3解冻} 和 {@code 4扣款}，
     * 各自带订单号，靠 {@code uk(order_id, type)} 各自幂等。
     *
     * <p>★ 实际金额<b>可以大于</b>冻结金额（预估少了）。
     * 差额从可用余额出；可用余额也不够时抛
     * {@link com.chargeops.exception.BalanceNotEnoughException}，
     * 整个事务回滚 —— 钱包回到冻结状态，钱没丢，
     * 但这笔订单结算不了，应进 {@code status=9} 异常挂账由人工处理。
     *
     * @param frozenAmount 启动时冻结的金额，可为 0（套餐全额抵扣时）
     * @param actualAmount 实际应付金额，可为 0
     */
    void settle(long memberId, long orderId, BigDecimal frozenAmount, BigDecimal actualAmount);

    /**
     * 退款：把已扣的钱还回可用余额
     *
     * <p>★ 只加 {@code balance}，不动 {@code total_recharge} ——
     * 后者记的是"充过多少"，是个历史事实。
     *
     * <p>★ 金额必须为正。传负数等于从用户账上偷钱，那是
     * 「管理员调账」该干的事，不该走退款这条路。
     */
    void refund(long memberId, long orderId, BigDecimal amount);

    /**
     * 直接扣可用余额，<b>不经冻结</b> —— 套餐购买走这里
     *
     * <p>★ 它和 {@link #settle} 里那一步扣款的<b>区别只有一个</b>：
     * {@code settle} 是「先解冻、再扣款」两步，而这里没有可解的冻 ——
     * 套餐购买是用户主动、即时的一笔支出，不存在「先冻着等业务确认」的中间态，
     * 也就没有那个中间态要用状态机去保护。
     *
     * <p>★★ <b>调用方欠一次 {@code MemberService.assertActive}</b>。
     * 本方法<b>刻意不查</b>账号状态，和 {@link #freeze} / {@link #settle} 保持一致：
     * 那几个由订单状态机调用，而订单可能是设备上报触发的 ——
     * 那一刻没有「当前登录人」可查，也不该因为账号此刻被禁用就让一笔
     * 已经发生的充电结不了账。<b>查状态这件事属于「有用户上下文的入口」</b>，
     * 也就是 Service 的方法入口，不是钱包这一层。
     *
     * <p>★ <b>必须传正数。</b>底层是 {@code SET balance = balance - #{amount}}，
     * 传负数<b>等于给车主充值</b>，而且这条路径没有任何页面能触发 ——
     * 出错时不会有用户来反馈，只会在对账时表现为「余额莫名其妙变多了」。
     * {@link #refund} 已有同样的守卫，这里不能少。
     *
     * <p>★★ <b>不调 {@code ensureWallet}，这是有意的。</b>没开过钱包的车主
     * 扣款必然影响 0 行，而「余额不足」正是这一步该给出的答案 ——
     * 一个从没充过钱的人确实买不起。开一张空卡再去失败，只是让事务多做一次
     * 无用的 INSERT（而且它随事务一起回滚，等于什么都没做）。
     * 这和 {@link #settle} 不调 {@code ensureWallet} 是同一条理由的反面：
     * 只有<b>钱往回走</b>的路径（{@link #unfreeze} / {@link #refund}）才需要预先建行。
     *
     * <p>★★ <b>{@code orderId} 传 {@code null}，于是流水上的
     * {@code uk_txn_order_type} 对这个类型<b>不生效</b> ——
     * 套餐购买没有购买单，这是「直购」模型（一个事务，不建订单表）的固有代价。
     * 完整说明和它换来的东西见 {@link WalletTransactionType#PACKAGE_PURCHASE}。
     *
     * @param remark 必须写清买的是什么。★ <b>它是这笔扣款唯一的可读线索</b> ——
     *               {@code order_id} 是空的，没有别的列能告诉你这笔钱花在哪了
     * @throws com.chargeops.exception.BalanceNotEnoughException 余额不足
     * @throws com.chargeops.exception.ParamErrorException        {@code amount <= 0}
     */
    void deduct(long memberId, BigDecimal amount, String remark);

    // ========================================================================
    // 第三组：管理端
    // ========================================================================

    /**
     * 套餐退款：作废一份权益时，把它当初付掉的钱退回可用余额
     *
     * <p>★★ <b>调用方必须先完成权益侧的条件更新，而且必须检查影响行数。</b>★★
     * 这个方法<b>没有</b>任何幂等判断：{@code order_id} 是 NULL，
     * {@code uk_txn_order_type} 对它不生效，重复调用会真的退两笔钱。
     * 唯一挡住重复退款的是 {@code MemberPackageMapper.voidPackage} 的
     * {@code WHERE status = 1} —— <b>它的影响行数就是「该不该退」的答案</b>。
     * 次序必须是「先 CAS、CAS 成功才调这里」，和 {@link #refund} 的约定一致。
     *
     * <p>★ <b>金额必须大于 0，而且必须来自 {@code member_package.pay_amount}</b>。
     * 传 0 是没有意义的（赠送套餐在<b>调用方</b>就该被拦下并给出
     * 「没有可退金额」这句话），传 SKU 的现价则是一个错误 ——
     * 见 {@code MemberPackage#payAmount}。
     *
     * <p>★ <b>不动 {@code total_recharge}</b> —— 走的是 {@code walletMapper.credit}，
     * 不是 {@code recharge}。理由同 {@link #refund}：那个数字记的是「充过多少」。
     *
     * <p>★ 会调 {@code ensureWallet}：钱是<b>往回走</b>的，必须有一个能落账的账户。
     * 这和 {@link #unfreeze} / {@link #refund} 同一条，和 {@link #deduct} 相反。
     *
     * <p>★★ <b>不查 {@code assertActive}，这是有意的。</b>★★
     * 「账号被禁用」恰恰是作废一份权益的<b>常见原因之一</b>（客服补偿、账号注销清算）。
     * 在这里要求账号可用，会让运营在最需要退钱的那一刻退不了。
     * 完整说明见下面「第三组不能读 BaseContext」那一段。
     *
     * @param remark 必须写清「作废了哪一份权益」。★ 它是这笔钱唯一的可读线索 ——
     *               {@code order_id} 是空的，没有别的列能告诉你钱为什么回来了
     * @throws com.chargeops.exception.ParamErrorException 金额为 null 或 {@code <= 0}
     */
    void refundPackage(long memberId, BigDecimal amount, String remark);

    /**
     * 管理员调账：往车主的可用余额里加钱或扣钱
     *
     * <p>★ <b>{@code amount} 可正可负</b>，这是它和 {@link #recharge} /
     * {@link #deduct} 的关键区别 —— 那两个各只能走一个方向，
     * 而「调账」这件事本身就是「把账调平」，方向由运营按事实决定。
     * <b>0 被拒绝</b>：它什么都不改变，却会留下一条流水。
     *
     * <p>★ <b>扣钱时余额不足会抛异常，不会把余额扣成负数。</b>
     * 底层是 {@code walletMapper.deduct}，它的 {@code WHERE balance >= ?}
     * 就是那道闸门。★ <b>这里刻意不做「扣到负数再由人工处理」</b> ——
     * 一个负余额的钱包会让后面每一次 {@code freeze} 都失败，
     * 而失败的原因（「余额不足」）在账面上看起来完全正常。
     *
     * <p>★★ <b>它会写下一条 {@code ADMIN_ADJUST} 流水，{@code remark} 必填。</b>★★
     * 这是全项目<b>唯一</b>一笔「背后没有一个业务单据」的资金变动：
     * 没有订单、没有权益、没有任何东西能反查。半年后审计的人只能看到
     * {@code type=7} 加那一行 {@code remark}。
     * 所以 {@link com.chargeops.enumeration.WalletTransactionType#ADMIN_ADJUST}
     * 的 javadoc 和 {@code MessageConstant.WALLET_ADJUST_REMARK_REQUIRED}
     * 都写着同一句话：<b>必须留原因</b>。
     *
     * <p>★★ <b>不查 {@code assertActive}，理由同 {@link #refundPackage}。</b>★★
     * 禁用账号的同时把他账上多扣的钱还回去，是一件必须能做的事。
     *
     * <p>★ <b>返回调账之后这个钱包的样子</b>（余额、冻结、累计充值）。
     * 这是一个动真钱的接口，调用方需要能确认「现在是多少」——
     * 让它再发一次查询去拿，会留下一个「写入成功、读出旧值」的窗口
     * （读到的是别人并发改之前的状态）。这里返回的是<b>本事务内、
     * 那条 UPDATE 之后</b>读到的值，见 {@code WalletServiceImpl.recordTxn}。
     *
     * @param remark 必填，说明为什么调这笔钱。★ 空串和空白串都会被拒
     * @throws com.chargeops.exception.ParamErrorException        金额为 0/为 null、
     *         或者 {@code remark} 为空
     * @throws com.chargeops.exception.BalanceNotEnoughException  扣钱时可用余额不足
     */
    WalletVO adjust(long memberId, BigDecimal amount, String remark);
}
