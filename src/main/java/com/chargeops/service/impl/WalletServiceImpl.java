package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.WalletRechargeDTO;
import com.chargeops.dto.WalletTransactionPageQueryDTO;
import com.chargeops.entity.Wallet;
import com.chargeops.entity.WalletTransaction;
import com.chargeops.enumeration.WalletTransactionType;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.WalletMapper;
import com.chargeops.mapper.WalletTransactionMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.MemberService;
import com.chargeops.service.WalletService;
import com.chargeops.utils.BizNoGenerator;
import com.chargeops.vo.WalletTransactionVO;
import com.chargeops.vo.WalletVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 钱包服务实现
 *
 * <p>设计说明见 {@link WalletService} 的类注释。本类里所有的「读钱包」都发生在一条
 * UPDATE 之后，这不是随手写的顺序，理由见 {@link #recordTxn}。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletServiceImpl implements WalletService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final WalletMapper walletMapper;

    private final WalletTransactionMapper walletTransactionMapper;

    private final MemberService memberService;

    private final BizNoGenerator bizNoGenerator;

    // ---------- 车主端 ----------

    @Override
    @Transactional(readOnly = true)
    public WalletVO getMine() {
        Long memberId = requireMemberId();
        // 只读接口也查 —— 让「钱包域一律要求账号可用」成为一条没有例外的规则
        memberService.assertActive(memberId);
        return WalletVO.from(walletMapper.getByMemberId(memberId));
    }

    @Override
    @Transactional
    public WalletVO recharge(WalletRechargeDTO dto) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);

        LocalDateTime now = LocalDateTime.now();
        ensureWallet(memberId, now);

        walletMapper.recharge(memberId, dto.getAmount(), now);
        Wallet after = recordTxn(memberId, null, WalletTransactionType.RECHARGE,
                dto.getAmount(), dto.getAmount(), ZERO, "充值", now);

        log.info("充值成功：memberId={}, amount={}, balanceAfter={}",
                memberId, dto.getAmount(), after.getBalance());
        return WalletVO.from(after);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult pageTransactions(WalletTransactionPageQueryDTO query) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);

        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<WalletTransaction> rows = walletTransactionMapper.listByMember(memberId);
        long total = ((Page<WalletTransaction>) rows).getTotal();

        return new PageResult(total, rows.stream().map(WalletTransactionVO::from).toList());
    }

    // ---------- 订单流程调用 ----------

    @Override
    @Transactional
    public void freeze(long memberId, long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            // 不冻 0 元，也不写流水：套餐全额抵扣这类场景本来就没钱可冻，
            // 而一条金额为 0 的流水只是给对账添噪音。
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        ensureWallet(memberId, now);

        if (walletMapper.freeze(memberId, amount, now) == 0) {
            // 影响行数 0 = 余额不足。这是业务结果，不是系统异常
            throw new BalanceNotEnoughException(MessageConstant.BALANCE_NOT_ENOUGH);
        }
        recordTxn(memberId, orderId, WalletTransactionType.FREEZE, amount.negate(),
                amount.negate(), amount, "下单冻结", now);
    }

    @Override
    @Transactional
    public void unfreeze(long memberId, long orderId, BigDecimal amount) {
        // 和 freeze 对 0 元一样宽容：没冻过就没有可解的，
        // 写一条 0 元流水只是噪音
        if (amount == null || amount.signum() <= 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        ensureWallet(memberId, now);

        if (walletMapper.releaseFrozen(memberId, amount, now) == 0) {
            // 和 settle 里那条一样，这里是硬冲突不是业务结果：订单记着冻结了 X，
            // 钱包里却拿不出 X。「解冻得比冻的还多」必须失败，否则余额会凭空变大。
            throw new DataConflictException(
                    "解冻失败：钱包冻结额不足，订单与钱包的记录对不上。orderId=" + orderId
                            + ", amount=" + amount);
        }
        recordTxn(memberId, orderId, WalletTransactionType.UNFREEZE, amount,
                amount, amount.negate(), "订单取消解冻", now);
    }

    @Override
    @Transactional
    public void settle(long memberId, long orderId,
                       BigDecimal frozenAmount, BigDecimal actualAmount) {
        // 这里刻意不调 ensureWallet：钱包不存在时冻结额必然对不上，
        // 应该以 DataConflictException 暴露出来，而不是顺手开一张空卡把它盖住。
        LocalDateTime now = LocalDateTime.now();
        BigDecimal frozen = nullToZero(frozenAmount);
        BigDecimal actual = nullToZero(actualAmount);

        // ---- ① 释放本单全部冻结 ----
        if (frozen.signum() > 0) {
            if (walletMapper.releaseFrozen(memberId, frozen, now) == 0) {
                throw new DataConflictException(
                        "结算失败：钱包冻结额不足，订单与钱包的记录对不上。orderId=" + orderId
                                + ", frozenAmount=" + frozen);
            }
            recordTxn(memberId, orderId, WalletTransactionType.UNFREEZE, frozen,
                    frozen, frozen.negate(), "结算解冻", now);
        }

        // ---- ② 扣掉实际金额 ----
        // 必须排在解冻之后：先扣的话这笔钱还在冻结里，可用余额可能不够，
        // 一笔本该成功的结算会被拒。
        if (actual.signum() > 0) {
            if (walletMapper.deduct(memberId, actual, now) == 0) {
                // 抛出去让整个事务回滚 —— ① 的解冻一起撤销，钱包回到「钱还在冻结里」的
                // 状态。钱没丢，但这笔订单结算不了，该进 status=9 异常挂账。
                throw new BalanceNotEnoughException(
                        "结算失败：可用余额不足以支付 " + actual + "，orderId=" + orderId);
            }
            recordTxn(memberId, orderId, WalletTransactionType.DEDUCT, actual.negate(),
                    actual.negate(), ZERO, "充电结算", now);
        }
    }

    @Override
    @Transactional
    public void refund(long memberId, long orderId, BigDecimal amount) {
        // 和 freeze 对 0 元的宽容不同，这里拒绝得响亮：退款是这个流程的终点，
        // 金额为 0 或负数一定意味着调用方算错了，而负数等于从用户账上偷钱 ——
        // 那种事属于「管理员调账」，不走这条路。
        if (amount == null || amount.signum() <= 0) {
            throw new ParamErrorException("退款金额必须大于 0，实际：" + amount);
        }

        LocalDateTime now = LocalDateTime.now();
        ensureWallet(memberId, now);

        walletMapper.credit(memberId, amount, now);
        recordTxn(memberId, orderId, WalletTransactionType.REFUND, amount,
                amount, ZERO, "订单退款", now);
    }

    @Override
    @Transactional
    public void deduct(long memberId, BigDecimal amount, String remark) {
        // 和 refund 一样拒绝得响亮：负数等于给车主充值，而这条路径没有页面能触发，
        // 出错时不会有用户反馈，只会在对账时表现为「余额莫名其妙变多了」。
        if (amount == null || amount.signum() <= 0) {
            throw new ParamErrorException("扣款金额必须大于 0，实际：" + amount);
        }

        LocalDateTime now = LocalDateTime.now();

        // 刻意不调 ensureWallet：没开过卡的车主扣款必然失败，而「余额不足」正是该给出的
        // 答案；开一张空卡只会随事务一起回滚。
        if (walletMapper.deduct(memberId, amount, now) == 0) {
            throw new BalanceNotEnoughException(MessageConstant.BALANCE_NOT_ENOUGH);
        }

        // orderId 传 null：套餐购买没有购买单，这意味着 uk_txn_order_type 对这条流水不生效
        // （唯一索引不约束 NULL）—— 这是「直购」模型的代价，换来的是不用为一笔即时交易
        // 养一张订单表和一个状态机。remark 因此成了这笔钱唯一的可读线索，调用方必须写清
        // 买了什么。
        recordTxn(memberId, null, WalletTransactionType.PACKAGE_PURCHASE,
                amount.negate(), amount.negate(), ZERO, remark, now);
    }

    @Override
    @Transactional
    public void refundPackage(long memberId, BigDecimal amount, String remark) {
        // 和 refund 同样是「钱往回走」，所以同样拒绝得响亮。0 也拒：赠送套餐
        // （pay_amount = 0.00）在调用方就被 if (signum() > 0) 拦下了，走到这里还是 0
        // 说明调用方那个判断被删了 —— 而这是唯一能拦住它的地方，删了不会有任何其它症状。
        if (amount == null || amount.signum() <= 0) {
            throw new ParamErrorException("套餐退款金额必须大于 0，实际：" + amount);
        }

        LocalDateTime now = LocalDateTime.now();
        // 钱往回走，必须先有一个能落账的账户 —— 同 unfreeze / refund。
        ensureWallet(memberId, now);

        walletMapper.credit(memberId, amount, now);

        // orderId 传 null：这条钱对应的不是订单，是 member_package 里的一行。
        // 这意味着 uk_txn_order_type 对它不生效 —— 幂等由调用方的
        // MemberPackageMapper.voidPackage 条件更新兜着。
        recordTxn(memberId, null, WalletTransactionType.PACKAGE_REFUND,
                amount, amount, ZERO, remark, now);

        // 用 info 而不是 debug：这是钱，而且它背后必然有一次人工操作，
        // 日志里必须留下「谁在什么时候把多少钱退给了谁」。
        log.info("套餐退款入账：memberId={}, amount={}, remark={}", memberId, amount, remark);
    }

    @Override
    @Transactional
    public WalletVO adjust(long memberId, BigDecimal amount, String remark) {
        // 0 单独一句，和「金额为 null」分开：它们的下一步不同 ——
        // 一个把输入框留空了，一个压根没填这一个字段。而且 0 元的调账会写下
        // 一条什么都不改变的流水。
        if (amount == null) {
            throw new ParamErrorException("调账金额不能为空");
        }
        if (amount.signum() == 0) {
            throw new ParamErrorException(MessageConstant.WALLET_ADJUST_AMOUNT_ZERO);
        }
        // remark 必须查，而且查的是去掉空白之后是否为空 —— 一个空格组成的 remark
        // 在 `!= null && !isEmpty()` 下是「合法」的，而它在流水里和一个空的没有区别。
        if (remark == null || remark.isBlank()) {
            throw new ParamErrorException(MessageConstant.WALLET_ADJUST_REMARK_REQUIRED);
        }

        LocalDateTime now = LocalDateTime.now();
        // 加钱要落账、扣钱也要落账（扣完余额必须还在表里），所以两边都 ensureWallet。
        // 这和 deduct 刻意不建行是两回事：调账是人在纠一笔账，没有行就该开一张，
        // 而不是让运营收到一句「余额不足」。
        ensureWallet(memberId, now);

        if (amount.signum() > 0) {
            walletMapper.credit(memberId, amount, now);
        } else {
            // deduct 的 WHERE balance >= ? 就是闸门。影响 0 行 = 余额不足，
            // 这是一个业务结果，不是异常。绝不改成「先查余额再决定」：那是 TOCTOU，
            // 并发下两笔调账会同时通过检查然后各扣一次。
            if (walletMapper.deduct(memberId, amount.negate(), now) == 0) {
                throw new BalanceNotEnoughException(MessageConstant.WALLET_ADJUST_BALANCE_NOT_ENOUGH);
            }
        }

        // amount 原样带符号记进流水 —— WalletTransaction.amount 的约定是「正入账负出账」，
        // 而这里恰好就是那个语义，不需要转换。balanceDelta 传同一个值：credit / deduct
        // 的净效果就是它。recordTxn 返回的就是「那条 UPDATE 之后」的钱包，直接当结果用 ——
        // 不要再 getByMemberId 查一次，那多一次往返，而且给了后面的人一个
        // 「这里可以改成先读后写」的坏示范。
        Wallet after = recordTxn(memberId, null, WalletTransactionType.ADMIN_ADJUST,
                amount, amount, ZERO, remark, now);

        log.info("管理员调账：memberId={}, amount={}, remark={}, 调账后余额={}",
                memberId, amount, remark, after.getBalance());

        return WalletVO.from(after);
    }

    // ---------- 私有 ----------

    private Long requireMemberId() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException();
        }
        return memberId;
    }

    /** 开卡。已存在则什么都不做 —— 靠 {@code uk_wallet_member} 去重，不是先查再插 */
    private void ensureWallet(Long memberId, LocalDateTime now) {
        walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId)
                .balance(ZERO)
                .frozenAmount(ZERO)
                .totalRecharge(ZERO)
                .version(0)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    /**
     * 记一笔流水，返回写完之后钱包的样子
     *
     * <p> 必须在 UPDATE 之后调用，绝不能先读后写。调用方刚执行完的那条 UPDATE 已经持有
     * 该行的排他锁（直到本事务提交），所以这里的 SELECT 读到的一定是本次修改后的值，
     * 别的线程插不进来。反过来「先 SELECT 读 before → UPDATE → 拼 after」的话，
     * 两笔并发的流水会读到同一个 before —— 账就断了，而这种事在对账之前不会有人发现。
     *
     * <p> {@code balanceDelta} / {@code frozenDelta} 是调用方那条 UPDATE 造成的增减，
     * 用来反推 before。它们必须和 UPDATE 的 SET 逐项对应 —— 这层耦合是刻意的：
     * 两者写在一起，改了一处能看见另一处。
     *
     * <p> 撞 {@code uk_txn_order_type} 会抛 {@code DuplicateKeyException} 并让整个事务
     * 回滚，这是有意的：能走到这一步说明调用方的幂等判断漏了，那一刻应该整笔失败并告警，
     * 而不是吞掉异常当作成功。
     */
    private Wallet recordTxn(Long memberId, Long orderId, WalletTransactionType type,
                             BigDecimal amount, BigDecimal balanceDelta, BigDecimal frozenDelta,
                             String remark, LocalDateTime now) {
        Wallet after = walletMapper.getByMemberId(memberId);

        walletTransactionMapper.insert(WalletTransaction.builder()
                .transactionNo(bizNoGenerator.transactionNo())
                .memberId(memberId)
                .orderId(orderId)
                .type(type.getCode())
                .amount(amount)
                .balanceBefore(after.getBalance().subtract(balanceDelta))
                .balanceAfter(after.getBalance())
                .frozenBefore(after.getFrozenAmount().subtract(frozenDelta))
                .frozenAfter(after.getFrozenAmount())
                .remark(remark)
                .createTime(now)
                .build());

        return after;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
