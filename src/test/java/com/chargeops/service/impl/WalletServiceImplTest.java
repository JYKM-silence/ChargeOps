package com.chargeops.service.impl;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.WalletRechargeDTO;
import com.chargeops.entity.Wallet;
import com.chargeops.entity.WalletTransaction;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.WalletMapper;
import com.chargeops.mapper.WalletTransactionMapper;
import com.chargeops.service.WalletService;
import com.chargeops.vo.WalletVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code WalletServiceImpl} 的集成测试
 *
 * <p>★ 为什么是集成测试而不是单元测试：本类的行为几乎全部由
 * <b>SQL 的条件更新 + 事务边界</b>决定。「余额不足时拒绝」这句话的真值
 * 在 {@code WHERE balance >= ?} 里；「结算失败后钱还在冻结里」这句话的真值
 * 在 {@code @Transactional} 的回滚里。把 mapper 换成桩类之后，
 * 这两句话都变成了我在断言我自己写的桩 —— 什么也没证明。
 * （对照 {@code ChargePackageServiceImplTest}，那里的判断是纯逻辑，
 * 所以它不启动 Spring。分界线是「真值在不在数据库里」。）
 *
 * <h2>★★ 本类不能标 {@code @Transactional} ★★</h2>
 *
 * <p>标上之后，测试方法本身开一个事务，Service 的 {@code @Transactional}
 * 会<b>加入</b>它（传播行为 REQUIRED）。此时 Service 抛异常只会把
 * <b>共享的</b>那个事务标记成 rollback-only，而不会立即回滚 ——
 * 于是 {@code settleFailureShouldKeepMoneyFrozen} 里
 * 「回读钱包，冻结还在」会在一个<b>已经被判死刑但还没执行</b>的事务里
 * 读到中间状态。那种测试即使在回滚逻辑被删掉之后也照样绿。
 *
 * <p>所以本类用 {@code JdbcTemplate} 在 {@code @AfterEach} 里手工清理。
 * 代价是每条测试都在真库上留下痕迹再擦掉，收益是
 * <b>回滚是真的发生过的</b>。
 *
 * <h2>★ 每条测试独立造 member，不用种子里的 member 1</h2>
 *
 * <p>{@code assertActive} 要求 {@code member.status = 1}，所以必须造一行真
 * member（{@code member_id} 上没外键，但这里要的是「账号可用」这个判断）。
 * 复用 member 1 的话，测试会改掉种子里那张 500 元的钱包，
 * 跑完不清就污染后续测试，清了又让「种子里有什么」变得不可知。
 */
@SpringBootTest
class WalletServiceImplTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试造出来的 member id，收尾时按范围删 */
    private static final long TEST_ID_BASE = 2_000_000_000_000L;

    private static long SEQ = 0;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_transaction WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM member WHERE id >= ?", TEST_ID_BASE);
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /** 造一个 status=1 的车主并登入上下文，返回它的 id */
    private long loginAsNewMember() {
        long memberId = TEST_ID_BASE + (SEQ++);
        jdbcTemplate.update(
                "INSERT INTO member (id, phone, nickname, status) VALUES (?, ?, ?, 1)",
                memberId, "137" + memberId, "钱包测试车主");
        BaseContext.setCurrentId(memberId);
        return memberId;
    }

    /** 直接给钱包塞一个初始余额（绕过 recharge，让用例从确定的状态出发） */
    private void givenBalance(long memberId, String balance) {
        LocalDateTime now = LocalDateTime.now();
        walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(new BigDecimal(balance))
                .frozenAmount(BigDecimal.ZERO).totalRecharge(new BigDecimal(balance))
                .version(0).createTime(now).updateTime(now).build());
    }

    private Wallet reload(long memberId) {
        return walletMapper.getByMemberId(memberId);
    }

    private List<WalletTransaction> txns(long memberId) {
        return walletTransactionMapper.listByMember(memberId);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 断言两个金额相等
     *
     * <p>★ 用 {@code compareTo} 而不是 {@code isEqualByComparingTo} 的原因是
     * {@code BigDecimal} 的 {@code equals} 会把 scale 算进去 ——
     * {@code 50.00} 和 {@code 50} 相等但 {@code equals} 返回 false，
     * 而它们经过一圈 MySQL {@code decimal(12,2)} 往返之后确实会变成不同 scale。
     * 金额比的是数值，不是小数位数。
     */
    private static void assertAmount(String expected, BigDecimal actual, String... message) {
        assertThat(actual).isNotNull();
        var assertion = assertThat(actual.compareTo(bd(expected)));
        if (message.length > 0) {
            assertion.as(message[0] + "（期望 %s，实际 %s）", expected, actual);
        } else {
            assertion.as("期望 %s，实际 %s", expected, actual);
        }
        assertion.isZero();
    }

    // ==================== 车主端 ====================

    @Test
    @DisplayName("getMine：没开过卡返回零钱包，而不是 null 也不是 404")
    void getMineShouldReturnEmptyWalletWhenAbsent() {
        loginAsNewMember();

        WalletVO vo = walletService.getMine();

        assertAmount("0", vo.getBalance());
        assertAmount("0", vo.getFrozenAmount());
        assertAmount("0", vo.getTotalRecharge());
    }

    @Test
    @DisplayName("recharge：首次充值会顺手开卡，并把余额、累计充值一起记住")
    void rechargeShouldCreateWalletOnFirstUse() {
        long memberId = loginAsNewMember();
        WalletRechargeDTO dto = new WalletRechargeDTO();
        dto.setAmount(bd("100.00"));

        WalletVO vo = walletService.recharge(dto);

        assertAmount("100.00", vo.getBalance());
        assertAmount("100.00", vo.getTotalRecharge());

        // ★ 流水要把 before / after 两个快照都记全，否则对账时要重放全部流水
        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(1);
        WalletTransaction txn = rows.get(0);
        assertThat(txn.getType()).isEqualTo(1);
        assertAmount("0", txn.getBalanceBefore());
        assertAmount("100.00", txn.getBalanceAfter());
        assertAmount("100.00", txn.getAmount());
        assertThat(txn.getOrderId()).isNull();
    }

    @Test
    @DisplayName("recharge：多次充值累加，每笔流水的前值接得上上一笔的后值")
    void rechargeShouldChainSnapshots() {
        long memberId = loginAsNewMember();

        for (String amount : List.of("100.00", "50.00", "30.00")) {
            WalletRechargeDTO dto = new WalletRechargeDTO();
            dto.setAmount(bd(amount));
            walletService.recharge(dto);
        }

        assertAmount("180.00", reload(memberId).getBalance());

        // listByMember 是时间倒序，所以第 i 条的 after 应等于第 i+1 条的 before
        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(3);
        for (int i = 0; i < rows.size() - 1; i++) {
            assertThat(rows.get(i).getBalanceBefore())
                    .as("★ 相邻两笔流水的快照必须接得上，接不上说明有笔操作没记流水")
                    .isEqualByComparingTo(rows.get(i + 1).getBalanceAfter());
        }
    }

    // ==================== 冻结 ====================

    @Test
    @DisplayName("freeze：0 元不冻结也不写流水 —— 套餐全额抵扣时没有钱可冻")
    void freezeShouldSkipZeroAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.freeze(memberId, 9001L, BigDecimal.ZERO);

        assertAmount("100.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).isEmpty();
    }

    @Test
    @DisplayName("freeze：余额不足抛 BalanceNotEnoughException —— 这是业务失败不是系统异常")
    void freezeShouldRejectWhenBalanceNotEnough() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        assertThatThrownBy(() -> walletService.freeze(memberId, 9002L, bd("100.01")))
                .isInstanceOf(BalanceNotEnoughException.class);

        assertAmount("100.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).isEmpty();
    }

    @Test
    @DisplayName("freeze：成功时写一条负数金额的流水，可用减少、冻结等额增加")
    void freezeShouldMoveMoneyAndRecordNegativeAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.freeze(memberId, 9003L, bd("30.00"));

        Wallet after = reload(memberId);
        assertAmount("70.00", after.getBalance());
        assertAmount("30.00", after.getFrozenAmount());

        WalletTransaction txn = txns(memberId).get(0);
        assertThat(txn.getType()).isEqualTo(2);
        assertAmount("-30.00", txn.getAmount());
        assertAmount("30.00", txn.getFrozenAfter());
    }

    // ==================== 结算 ====================

    @Test
    @DisplayName("settle：先解冻再扣款，两条流水各自带订单号")
    void settleShouldReleaseThenDeduct() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");
        walletService.freeze(memberId, 9100L, bd("50.00"));

        walletService.settle(memberId, 9100L, bd("50.00"), bd("42.00"));

        Wallet after = reload(memberId);
        assertAmount("58.00", after.getBalance());
        assertAmount("0", after.getFrozenAmount());

        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(WalletTransaction::getType).toList())
                .containsExactly(4, 3, 2);
    }

    @Test
    @DisplayName("settle：实际金额大于冻结额时，差额从可用余额出")
    void settleShouldChargeTheOverageFromBalance() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");
        walletService.freeze(memberId, 9101L, bd("30.00"));
        // 此刻 balance=70, frozen=30

        walletService.settle(memberId, 9101L, bd("30.00"), bd("50.00"));

        Wallet after = reload(memberId);
        assertAmount("50.00", after.getBalance());
        assertAmount("0", after.getFrozenAmount());
    }

    @Test
    @DisplayName("★★ settle：可用余额也不够时整笔回滚 —— 钱留在冻结里，没有丢")
    void settleFailureShouldKeepMoneyFrozen() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");
        walletService.freeze(memberId, 9102L, bd("50.00"));
        // 此刻 balance=50, frozen=50；结算要 101 元

        assertThatThrownBy(() -> walletService.settle(memberId, 9102L, bd("50.00"), bd("101.00")))
                .isInstanceOf(BalanceNotEnoughException.class);

        Wallet after = reload(memberId);
        assertAmount("50.00", after.getBalance());
        assertAmount("50.00", after.getFrozenAmount());
        // ★ 解冻那条流水也必须一起回滚，否则会留下一条「解冻了但没扣款」的假记录
        assertThat(txns(memberId))
                .as("★ 回滚必须把解冻流水一起撤销")
                .hasSize(1);
        assertThat(txns(memberId).get(0).getType()).isEqualTo(2);
    }

    @Test
    @DisplayName("settle：钱包不存在时抛 DataConflictException，而不是顺手开一张空卡盖住")
    void settleShouldNotCreateWallet() {
        long memberId = loginAsNewMember();

        assertThatThrownBy(() -> walletService.settle(memberId, 9103L, bd("10.00"), bd("10.00")))
                .isInstanceOf(DataConflictException.class);

        assertThat(reload(memberId))
                .as("★ 静默开卡会把「订单和钱包对不上」变成「余额 0 扣款失败」，掩盖真实原因")
                .isNull();
    }

    @Test
    @DisplayName("★★ settle：同一订单重复扣款撞 uk(order_id, type)，整笔失败")
    void duplicateSettleShouldBeRejectedByUniqueIndex() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.settle(memberId, 9104L, BigDecimal.ZERO, bd("10.00"));
        assertAmount("90.00", reload(memberId).getBalance());

        // 第二次：解冻被跳过（冻结额为 0），扣款成功，写流水时撞唯一键
        assertThatThrownBy(() -> walletService.settle(memberId, 9104L, BigDecimal.ZERO, bd("10.00")))
                .isInstanceOf(DuplicateKeyException.class);

        assertAmount("90.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).hasSize(1);
    }

    // ==================== 退款 ====================

    @Test
    @DisplayName("refund：金额必须为正 —— 传负数等于从用户账上偷钱")
    void refundShouldRejectNonPositiveAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        assertThatThrownBy(() -> walletService.refund(memberId, 9200L, BigDecimal.ZERO))
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.refund(memberId, 9200L, bd("-1.00")))
                .isInstanceOf(ParamErrorException.class);

        assertAmount("100.00", reload(memberId).getBalance());
    }

    @Test
    @DisplayName("refund：只加余额，不回退「累计充值」")
    void refundShouldNotReduceTotalRecharge() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.refund(memberId, 9201L, bd("30.00"));

        Wallet after = reload(memberId);
        assertAmount("130.00", after.getBalance());
        assertAmount("100.00", after.getTotalRecharge());
    }

    // ==================== 管理端：套餐退款（type=8）====================

    @Test
    @DisplayName("★★ refundPackage：钱进余额、流水 type=8、order_id 为 NULL")
    void refundPackageShouldCreditAndRecordTypeEight() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.refundPackage(memberId, bd("68.00"), "作废套餐权益退款：150度电包（客户投诉）");

        assertAmount("168.00", reload(memberId).getBalance());

        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(1);
        WalletTransaction txn = rows.get(0);
        assertThat(txn.getType()).isEqualTo(8);
        assertAmount("68.00", txn.getAmount());
        // ★★ order_id 必须是 NULL，而且这一条不是「顺便记一下」★★
        //   套餐直购没有购买单，如果哪天有人为了「可追溯」给它编一个 order_id，
        //   uk_txn_order_type 会开始生效 —— 那时重复退款会被数据库挡住，
        //   看起来是变安全了；但那个 order_id 指向的东西并不存在，
        //   对账的人会顺着它查到一个空的订单。宁可它诚实地是 NULL。
        assertThat(txn.getOrderId()).isNull();
        // ★ remark 是这条流水唯一的解释 —— 它没有订单可挂靠
        assertThat(txn.getRemark()).contains("150度电包");
    }

    @Test
    @DisplayName("★★ refundPackage：金额必须为正 —— 0 元退款是一条什么都不说明的假流水")
    void refundPackageShouldRejectNonPositiveAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        assertThatThrownBy(() -> walletService.refundPackage(memberId, BigDecimal.ZERO, "赠送套餐作废"))
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.refundPackage(memberId, bd("-5.00"), "手滑"))
                .isInstanceOf(ParamErrorException.class);

        assertAmount("100.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).isEmpty();
    }

    @Test
    @DisplayName("★ refundPackage：没开过卡也能退（先开卡再落账），而不是抛「余额不足」")
    void refundPackageShouldOpenTheWalletForANewMember() {
        long memberId = loginAsNewMember();

        walletService.refundPackage(memberId, bd("20.00"), "作废套餐权益退款：次卡（误购）");

        assertAmount("20.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).hasSize(1);
    }

    // ==================== 管理端：调账（type=7）====================

    @Test
    @DisplayName("★★ adjust 正向：余额增加，流水 type=7、amount 保留【正号】、order_id 为 NULL")
    void adjustPositiveShouldCreditAndKeepTheSign() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        WalletVO vo = walletService.adjust(memberId, bd("50.00"), "客服补偿：充电中断");

        assertAmount("150.00", vo.getBalance());
        assertAmount("150.00", reload(memberId).getBalance());

        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(1);
        WalletTransaction txn = rows.get(0);
        assertThat(txn.getType()).isEqualTo(7);
        // ★ 符号必须原样保留：WalletTransaction.amount 的约定是「正入账负出账」，
        //   对账时靠它一眼区分加钱和扣钱。如果这里被改成 abs()，
        //   一笔 500 元的补偿和一笔 500 元的追扣在流水上会完全一样。
        assertAmount("50.00", txn.getAmount());
        assertThat(txn.getOrderId())
                .as("★ 调账背后没有任何业务对象 —— 编一个 order_id 出来只会让对账的人查到一个空订单")
                .isNull();
        assertThat(txn.getRemark()).isEqualTo("客服补偿：充电中断");
    }

    @Test
    @DisplayName("★★ adjust 负向：余额减少，flow type=7、amount 为【负数】，不是把符号丢掉")
    void adjustNegativeShouldDeductAndRecordANegativeAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        walletService.adjust(memberId, bd("-30.00"), "追扣：重复退款冲正");

        assertAmount("70.00", reload(memberId).getBalance());
        WalletTransaction txn = txns(memberId).get(0);
        assertThat(txn.getType()).isEqualTo(7);
        assertAmount("-30.00", txn.getAmount());
        assertAmount("100.00", txn.getBalanceBefore());
        assertAmount("70.00", txn.getBalanceAfter());
    }

    @Test
    @DisplayName("★★ adjust 扣钱扣不动时抛 BalanceNotEnoughException，且【一行流水都不留】")
    void adjustNegativeShouldRollBackWhenBalanceIsNotEnough() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "10.00");

        assertThatThrownBy(() -> walletService.adjust(memberId, bd("-10.01"), "追扣"))
                .isInstanceOf(BalanceNotEnoughException.class);

        assertAmount("10.00", reload(memberId).getBalance());
        assertThat(txns(memberId))
                .as("★ 钱没动，流水也不能有 —— 一条「扣款失败」的流水会让对账多出一笔不存在的支出")
                .isEmpty();
    }

    @Test
    @DisplayName("★ adjust：0 元被拒 —— 它会写下一条什么都不改变的流水，把「调账笔数」变成一个假数字")
    void adjustShouldRejectZeroAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        assertThatThrownBy(() -> walletService.adjust(memberId, BigDecimal.ZERO, "手滑"))
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.adjust(memberId, bd("0.00"), "手滑"))
                .as("★ 0.00 和 0 是同一个值，scale 不构成例外")
                .isInstanceOf(ParamErrorException.class);

        assertAmount("100.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).isEmpty();
    }

    @Test
    @DisplayName("★★ adjust：原因必填，而且【全是空格】也算没填")
    void adjustShouldRequireAMeaningfulRemark() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");

        assertThatThrownBy(() -> walletService.adjust(memberId, bd("10.00"), null))
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.adjust(memberId, bd("10.00"), ""))
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.adjust(memberId, bd("10.00"), "   "))
                .as("★★ 这一条钉的是 isBlank() 而不是 isEmpty()。★★ "
                        + "一个空格的 remark 在 isEmpty() 下是「合法」的，"
                        + "而它在流水里和空的没有任何区别 —— 审计的人看到的仍是一片空白，"
                        + "只会以为是显示问题")
                .isInstanceOf(ParamErrorException.class);
        assertThatThrownBy(() -> walletService.adjust(memberId, bd("10.00"), "  \t "))
                .isInstanceOf(ParamErrorException.class);

        assertAmount("100.00", reload(memberId).getBalance());
        assertThat(txns(memberId)).isEmpty();
    }

    @Test
    @DisplayName("★★ adjust：钱进的是【参数里的】那个 memberId，不是 BaseContext 里的那个")
    void adjustShouldCreditTheGivenMemberNotTheLoggedInOne() {
        long targetMember = loginAsNewMember();
        givenBalance(targetMember, "100.00");

        // ★★ 模拟管理端请求：/api/admin/** 上的拦截器往 BaseContext 里放的是【员工】id。★★
        //   本方法必须完全无视它 —— 一个运营的 staffId 恰好也是一串数字，
        //   它落进 member 表里(或者落不进)都不会报错，
        //   症状是「运营点了调账，钱进了运营自己名下的钱包」。
        //   ★ 这个 id 刻意选成一个**不存在的** member：用种子里的 member 1 反证不了任何事 ——
        //     它是一个真车主，种子给它建了一张 500 元的钱包，
        //     于是「钱进错账了」会被那句 isNull() 误判成「没进错」。
        long staffId = 9_900_000_000_009L;
        BaseContext.setCurrentId(staffId);

        walletService.adjust(targetMember, bd("50.00"), "客服补偿");

        assertAmount("150.00", reload(targetMember).getBalance());
        assertThat(reload(staffId))
                .as("★★ 钱绝不能落到 BaseContext 那个 id 上 —— 那是员工 id，不是车主 id。★★ "
                        + "这一条是实现里「第三组方法收 memberId 参数、不读 BaseContext」"
                        + "那个约定的唯一探针：改成 requireMemberId() 之后，"
                        + "上面的断言会失败在这里，而不是在任何别的地方")
                .isNull();
    }

    @Test
    @DisplayName("★ adjust：车主还没开过卡时【顺手开一张】，而不是回一句「余额不足」")
    void adjustShouldOpenTheWalletRatherThanFail() {
        long memberId = loginAsNewMember();

        WalletVO vo = walletService.adjust(memberId, bd("20.00"), "新客补偿");

        assertAmount("20.00", vo.getBalance());
        assertThat(reload(memberId)).as("★ 这是和 settle 相反的一处取舍，见实现里的注释").isNotNull();
    }
}
