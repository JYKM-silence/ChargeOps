package com.chargeops.service;

import com.chargeops.dto.WalletRechargeDTO;
import com.chargeops.dto.WalletTransactionPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.WalletVO;

import java.math.BigDecimal;

/**
 * 钱包服务。方法按调用方分三组：车主端自己用的、订单状态机用的、管理端用的。
 *
 * <p>余额是两列：{@code balance}（可用）和 {@code frozen_amount}（被在途订单占住）。
 * 可动用 = balance + 本单冻结额。
 *
 * <p>资金安全的两道防线：本层的条件更新（判断写进 {@code WHERE}，影响行数为 0
 * 就是"余额不足"这个业务结果，而不是异常），以及 {@code wallet_transaction} 上的
 * {@code uk_txn_order_type (order_id, type)} —— 后者是防重复扣款的最后一道防线。
 * 但它一旦触发，{@code DuplicateKeyException} 抛出时整个事务已被标记回滚，
 * 调用方 catch 住提交时照样失败；而且唯一索引不约束 NULL，{@code order_id}
 * 为 NULL 的流水（充值、套餐购买）完全不受它保护。所以调用 {@link #settle} /
 * {@link #refund} 之前，调用方必须先在订单侧做自己的幂等判断。
 *
 * <p>第三组（{@link #refundPackage} / {@link #adjust}）收 {@code memberId} 参数。
 * 不能从 {@code BaseContext} 取：{@code /api/admin/**} 上挂的是管理端拦截器，
 * 它往 BaseContext 里放的是员工的 id，车主端拦截器放的才是车主 id。
 * 症状：运营点了「作废并退款」，钱退进了运营自己的钱包，而不是那个车主的，
 * 而且不会报错。第三组也不查账号状态 —— 「账号被禁用」本身就是退钱和调账的常见原因。
 */
public interface WalletService {

    /** 我的钱包。没开过卡返回零钱包，不是 404 */
    WalletVO getMine();

    /** 充值。本项目没有接真实支付，这里直接给余额加钱 */
    WalletVO recharge(WalletRechargeDTO dto);

    /** 我的流水（分页，按时间倒序） */
    PageResult pageTransactions(WalletTransactionPageQueryDTO query);

    /**
     * 下单冻结：可用余额转入冻结
     *
     * @throws com.chargeops.exception.BalanceNotEnoughException 余额不足。
     * 这是业务失败不是系统异常，调用方应据此拒绝下单，而不是当成错误页面
     */
    void freeze(long memberId, long orderId, BigDecimal amount);

    /**
     * 解冻：把本单冻结的钱全部退回可用余额，不扣款。取消订单走这里
     *
     * <p> 金额为 0 时什么都不做，不写流水 —— 全额权益抵扣的订单本来就没冻过钱。
     *
     * @throws com.chargeops.exception.DataConflictException 冻结额不足。
     * 订单上记的 {@code frozen_amount} 和钱包里的实际冻结对不上，
     * 那是需要人工对账的硬冲突，不能静默跳过
     */
    void unfreeze(long memberId, long orderId, BigDecimal amount);

    /**
     * 结算：释放本单全部冻结，再扣掉实际金额
     *
     * <p> 顺序是先解冻、后扣款，不能反过来 —— 先扣的话钱还在冻结里，可用余额可能不够，
     * 一笔本该成功的结算会被拒。
     *
     * <p> 实际金额可以大于冻结金额（预估少了）。差额从可用余额出；可用余额也不够时抛
     * {@link com.chargeops.exception.BalanceNotEnoughException}，整个事务回滚 ——
     * 钱没丢，但这笔订单结算不了，应进 {@code status=9} 异常挂账由人工处理。
     *
     * @param frozenAmount 启动时冻结的金额，可为 0（套餐全额抵扣时）
     * @param actualAmount 实际应付金额，可为 0
     */
    void settle(long memberId, long orderId, BigDecimal frozenAmount, BigDecimal actualAmount);

    /**
     * 退款：把已扣的钱还回可用余额
     *
     * <p> 只加 {@code balance}，不动 {@code total_recharge} —— 后者记的是"充过多少"，
     * 是个历史事实。金额必须为正。
     */
    void refund(long memberId, long orderId, BigDecimal amount);

    /**
     * 直接扣可用余额，不经冻结 —— 套餐购买走这里
     *
     * <p> 必须传正数。底层是 {@code SET balance = balance - #{amount}}，
     * 传负数等于给车主充值，而且这条路径没有任何页面能触发 ——
     * 出错时不会有用户来反馈，只会在对账时表现为「余额莫名其妙变多了」。
     *
     * <p> {@code orderId} 传 {@code null}，于是流水上的 {@code uk_txn_order_type}
     * 对这个类型不生效。本方法刻意不查账号状态，调用方欠一次
     * {@code MemberService.assertActive}。
     *
     * @param remark 必须写清买的是什么。 它是这笔扣款唯一的可读线索 ——
     * {@code order_id} 是空的，没有别的列能告诉你这笔钱花在哪了
     * @throws com.chargeops.exception.BalanceNotEnoughException 余额不足
     * @throws com.chargeops.exception.ParamErrorException        {@code amount <= 0}
     */
    void deduct(long memberId, BigDecimal amount, String remark);

    // ---------- 第三组：管理端 ----------

    /**
     * 套餐退款：作废一份权益时，把它当初付掉的钱退回可用余额
     *
     * <p> 这个方法没有任何幂等判断：{@code order_id} 是 NULL，
     * {@code uk_txn_order_type} 对它不生效，重复调用会真的退两笔钱。
     * 唯一挡住重复退款的是 {@code MemberPackageMapper.voidPackage} 的
     * {@code WHERE status = 1} —— 次序必须是「先 CAS、CAS 成功才调这里」。
     *
     * <p> 金额必须大于 0，而且必须来自 {@code member_package.pay_amount}，
     * 不是 SKU 的现价（见 {@code MemberPackage#payAmount}）。
     *
     * @param remark 必须写清「作废了哪一份权益」。 它是这笔钱唯一的可读线索
     * @throws com.chargeops.exception.ParamErrorException 金额为 null 或 {@code <= 0}
     */
    void refundPackage(long memberId, BigDecimal amount, String remark);

    /**
     * 管理员调账：往车主的可用余额里加钱或扣钱
     *
     * <p> {@code amount} 可正可负，这是它和 {@link #recharge} / {@link #deduct} 的关键区别。
     * 0 被拒绝：它什么都不改变，却会留下一条流水。
     *
     * <p> 扣钱时余额不足会抛异常，不会把余额扣成负数（底层是
     * {@code walletMapper.deduct} 的 {@code WHERE balance >= ?}）。一个负余额的钱包
     * 会让后面每一次 {@code freeze} 都失败，而失败的原因在账面上看起来完全正常。
     *
     * <p> 返回调账之后这个钱包的样子。这是一个动真钱的接口，调用方需要能确认
     * 「现在是多少」—— 让它再发一次查询去拿，会留下一个「写入成功、读出旧值」的窗口。
     *
     * @param remark 必填，说明为什么调这笔钱。 空串和空白串都会被拒
     * @throws com.chargeops.exception.ParamErrorException        金额为 0/为 null、
     * 或者 {@code remark} 为空
     * @throws com.chargeops.exception.BalanceNotEnoughException  扣钱时可用余额不足
     */
    WalletVO adjust(long memberId, BigDecimal amount, String remark);
}
