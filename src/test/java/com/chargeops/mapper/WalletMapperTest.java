package com.chargeops.mapper;

import com.chargeops.entity.Wallet;
import com.chargeops.entity.WalletTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WalletMapper} / {@link WalletTransactionMapper} 的集成测试
 *
 * <p>通用写法见 {@link StaffMapperTest}。本类要钉死的是资金侧的
 * <b>四条不变量</b>。它们失效时都不报错，只是钱对不上：
 *
 * <ol>
 *   <li><b>总额守恒</b>：freeze / releaseFrozen 只挪钱，balance + frozen 不变</li>
 *   <li><b>三个 {@code WHERE} 判据</b>：freeze / releaseFrozen / deduct
 *       在额度不够时返回 0 行，且<b>一个字段都没动</b></li>
 *   <li><b>{@code total_recharge} 只增不减</b>：credit 不碰它，recharge 加它</li>
 *   <li><b>{@code uk_txn_order_type}</b>：同订单同类型只能有一条流水，
 *       而 {@code order_id = NULL} 的充值不受约束</li>
 * </ol>
 *
 * <h2>★★ 为什么「失败时字段没动」要单独断言 ★★</h2>
 *
 * <p>条件更新写错时最常见的样子不是「错扣了钱」，而是「扣了但没扣成」——
 * 例如误写成先 {@code SET balance = balance - ?} 再在 WHERE 里判断，
 * 或者漏掉 WHERE 里的 {@code AND balance >= ?}。前者在余额不足时会
 * <b>扣成负数</b>，后者会<b>直接扣到 0 以下</b>。两种都返回 1 行，
 * 只看返回值发现不了，必须回读字段。
 *
 * <p>★ 每条测试用自己的 memberId（{@code System.nanoTime()}），避开种子数据。
 * {@code member_id} 上没有外键（全库统一不加 FK），凭空造 id 合法。
 */
@SpringBootTest
class WalletMapperTest {

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的流水 id，收尾时按 id 删 */
    private final List<Long> createdTxnIds = new ArrayList<>();

    /**
     * 流水号计数器
     *
     * <p>★ 用递增计数而不是 {@code System.nanoTime()} 拼流水号：
     * 后者拼出来 34 个字符，超过 {@code transaction_no varchar(32)}，
     * 在严格模式下会直接报 Data too long —— 一个和被测逻辑无关的失败。
     */
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    @AfterEach
    void cleanUp() {
        for (Long id : createdTxnIds) {
            jdbcTemplate.update("DELETE FROM wallet_transaction WHERE id = ?", id);
        }
        createdTxnIds.clear();
        // 钱包按 member_id 删 —— 它没有别的引用
        jdbcTemplate.update("DELETE FROM wallet WHERE member_id >= 1000000000000");
    }

    // ==================== 工具方法 ====================

    /**
     * 每条测试独立的持有人 id，避开种子数据
     *
     * <p>★ 这里不能用 {@code System.nanoTime()} 直接当 memberId 然后收尾删 ——
     * 收尾的 {@code DELETE} 需要一个可判定的范围，naonTime 是 16 位，
     * 而种子里的 member_id 是 1、2 这种小数字。用 10^12 当分界，
     * 「比它大的都是测试造的数据」这条规则简单且不会误伤。
     */
    private long newMemberId() {
        return 1_000_000_000_000L + (System.nanoTime() % 1_000_000_000L);
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 开一张余额为 {@code balance} 的钱包 */
    private Wallet givenWallet(long memberId, String balance) {
        Wallet wallet = Wallet.builder()
                .memberId(memberId)
                .balance(new BigDecimal(balance))
                .frozenAmount(BigDecimal.ZERO)
                .totalRecharge(new BigDecimal(balance))
                .version(0)
                .createTime(now())
                .updateTime(now())
                .build();
        walletMapper.insertIgnore(wallet);
        return wallet;
    }

    /** 回读钱包的当前状态 */
    private Wallet reload(long memberId) {
        return walletMapper.getByMemberId(memberId);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ==================== 开卡 ====================

    @Test
    @DisplayName("insertIgnore：重复开卡静默跳过，只有一行")
    void insertIgnoreShouldBeIdempotent() {
        long memberId = newMemberId();

        int first = walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(BigDecimal.ZERO).frozenAmount(BigDecimal.ZERO)
                .totalRecharge(BigDecimal.ZERO).version(0)
                .createTime(now()).updateTime(now()).build());
        int second = walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(BigDecimal.ZERO).frozenAmount(BigDecimal.ZERO)
                .totalRecharge(BigDecimal.ZERO).version(0)
                .createTime(now()).updateTime(now()).build());

        assertEquals(1, first, "首次开卡应插入 1 行");
        assertEquals(0, second, "重复开卡应静默跳过（0 行），而不是抛异常");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet WHERE member_id = ?", Integer.class, memberId);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("getByMemberId：没开过卡返回 null，不是空对象")
    void getByMemberIdShouldReturnNullWhenAbsent() {
        assertNull(walletMapper.getByMemberId(newMemberId()));
    }

    // ==================== 冻结 / 解冻 / 扣款 ====================

    @Test
    @DisplayName("freeze：总额守恒 —— balance 减少、frozen 等额增加、两者之和不变")
    void freezeShouldMoveMoneyWithoutChangingTotal() {
        long memberId = newMemberId();
        givenWallet(memberId, "100.00");

        assertEquals(1, walletMapper.freeze(memberId, bd("30.00"), now()));

        Wallet after = reload(memberId);
        assertEquals(0, bd("70.00").compareTo(after.getBalance()), "可用余额应为 70");
        assertEquals(0, bd("30.00").compareTo(after.getFrozenAmount()), "冻结应为 30");
        assertEquals(0, bd("100.00").compareTo(after.getBalance().add(after.getFrozenAmount())),
                "★ 冻结不是扣款：可用 + 冻结必须恒等于操作前的可用");
    }

    @Test
    @DisplayName("freeze：余额不足返回 0 行，且 balance / frozen 一个都没动")
    void freezeShouldFailCleanlyWhenBalanceNotEnough() {
        long memberId = newMemberId();
        givenWallet(memberId, "50.00");

        assertEquals(0, walletMapper.freeze(memberId, bd("50.01"), now()),
                "差 1 分也应该拒绝");

        Wallet after = reload(memberId);
        assertEquals(0, bd("50.00").compareTo(after.getBalance()),
                "★ 失败的更新不能留下痕迹 —— 余额必须还是 50");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getFrozenAmount()), "冻结必须还是 0");
    }

    @Test
    @DisplayName("freeze：恰好等于余额时成功（边界是 >= 不是 >）")
    void freezeShouldAllowExactBalance() {
        long memberId = newMemberId();
        givenWallet(memberId, "50.00");

        assertEquals(1, walletMapper.freeze(memberId, bd("50.00"), now()));

        Wallet after = reload(memberId);
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getBalance()));
        assertEquals(0, bd("50.00").compareTo(after.getFrozenAmount()));
    }

    @Test
    @DisplayName("releaseFrozen：冻结不够时返回 0 行 —— 挡住「解冻得比冻的还多」凭空造钱")
    void releaseFrozenShouldNotMintMoney() {
        long memberId = newMemberId();
        givenWallet(memberId, "100.00");
        walletMapper.freeze(memberId, bd("20.00"), now());

        assertEquals(0, walletMapper.releaseFrozen(memberId, bd("20.01"), now()),
                "解冻额超过冻结额必须拒绝");

        Wallet after = reload(memberId);
        assertEquals(0, bd("80.00").compareTo(after.getBalance()), "余额不该变多");
        assertEquals(0, bd("20.00").compareTo(after.getFrozenAmount()));

        // 额度刚好时仍然可以
        assertEquals(1, walletMapper.releaseFrozen(memberId, bd("20.00"), now()));
        Wallet released = reload(memberId);
        assertEquals(0, bd("100.00").compareTo(released.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(released.getFrozenAmount()));
    }

    @Test
    @DisplayName("deduct：余额不足返回 0 行，余额不动")
    void deductShouldFailCleanlyWhenBalanceNotEnough() {
        long memberId = newMemberId();
        givenWallet(memberId, "10.00");

        assertEquals(0, walletMapper.deduct(memberId, bd("10.01"), now()));

        Wallet after = reload(memberId);
        assertEquals(0, bd("10.00").compareTo(after.getBalance()),
                "★ 余额绝不能变成负数 —— 这正是「先查后改」会踩的坑");
    }

    // ==================== 入账 ====================

    @Test
    @DisplayName("recharge 加 total_recharge，credit 不动它 —— 这是两者唯一的区别")
    void creditShouldNotTouchTotalRecharge() {
        long memberId = newMemberId();
        givenWallet(memberId, "100.00");

        walletMapper.recharge(memberId, bd("50.00"), now());
        Wallet afterRecharge = reload(memberId);
        assertEquals(0, bd("150.00").compareTo(afterRecharge.getBalance()));
        assertEquals(0, bd("150.00").compareTo(afterRecharge.getTotalRecharge()));

        walletMapper.credit(memberId, bd("20.00"), now());
        Wallet afterCredit = reload(memberId);
        assertEquals(0, bd("170.00").compareTo(afterCredit.getBalance()), "credit 要加余额");
        assertEquals(0, bd("150.00").compareTo(afterCredit.getTotalRecharge()),
                "★ credit 不能动「累计充值」—— 它记的是充过多少，是个历史事实");
    }

    @Test
    @DisplayName("入账没有上限条件：余额为 0 的钱包也能被充值")
    void rechargeShouldAlwaysSucceed() {
        long memberId = newMemberId();
        givenWallet(memberId, "0.00");

        assertEquals(1, walletMapper.recharge(memberId, bd("0.01"), now()),
                "★ 入账永远允许 —— 这是它和所有出账方向的根本区别");
        assertEquals(0, bd("0.01").compareTo(reload(memberId).getBalance()));
    }

    @Test
    @DisplayName("version 每次成功更新都 +1，失败的更新不动它")
    void versionShouldAdvanceOnlyOnSuccess() {
        long memberId = newMemberId();
        givenWallet(memberId, "100.00");

        walletMapper.freeze(memberId, bd("10.00"), now());
        assertEquals(1, reload(memberId).getVersion());

        walletMapper.freeze(memberId, bd("999.00"), now());
        assertEquals(1, reload(memberId).getVersion(), "失败的更新不该推进版本号");

        walletMapper.releaseFrozen(memberId, bd("10.00"), now());
        assertEquals(2, reload(memberId).getVersion());
    }

    // ==================== 流水 ====================

    /** 造一条流水；{@code orderId} 为 null 表示充值 */
    private WalletTransaction insertTxn(long memberId, Long orderId, int type, String amount) {
        LocalDateTime now = now();
        WalletTransaction txn = WalletTransaction.builder()
                .transactionNo("WT" + SEQ.incrementAndGet())
                .memberId(memberId)
                .orderId(orderId)
                .type(type)
                .amount(bd(amount))
                .balanceBefore(bd("100.00"))
                .balanceAfter(bd("100.00").add(bd(amount)))
                .frozenBefore(BigDecimal.ZERO)
                .frozenAfter(BigDecimal.ZERO)
                .remark("测试")
                .createTime(now)
                .build();
        walletTransactionMapper.insert(txn);
        assertNotNull(txn.getId(), "insert 后 id 应被回填");
        createdTxnIds.add(txn.getId());
        return txn;
    }

    @Test
    @DisplayName("★★ uk(order_id, type)：同订单同类型插第二条抛 DuplicateKeyException")
    void sameOrderSameTypeShouldBeRejected() {
        long memberId = newMemberId();
        long orderId = System.nanoTime();

        insertTxn(memberId, orderId, 4, "-30.00");

        assertThrows(DuplicateKeyException.class,
                () -> insertTxn(memberId, orderId, 4, "-30.00"),
                "★ 这就是资金幂等的落点：扣两次钱在数据库层不可能");
    }

    @Test
    @DisplayName("同订单的不同类型可以各有一条（解冻 + 扣款）")
    void sameOrderDifferentTypeShouldCoexist() {
        long memberId = newMemberId();
        long orderId = System.nanoTime();

        insertTxn(memberId, orderId, 3, "50.00");
        insertTxn(memberId, orderId, 4, "-42.00");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE order_id = ?", Integer.class, orderId);
        assertEquals(2, count);
    }

    @Test
    @DisplayName("★ order_id = NULL 的充值不受唯一索引约束，可以有很多条")
    void nullOrderIdShouldNotBeConstrained() {
        long memberId = newMemberId();

        insertTxn(memberId, null, 1, "10.00");
        insertTxn(memberId, null, 1, "20.00");
        insertTxn(memberId, null, 1, "30.00");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE member_id = ?", Integer.class, memberId);
        assertEquals(3, count, "★ MySQL 唯一索引不约束 NULL —— 充值本来就可以有任意多笔");
    }

    @Test
    @DisplayName("listByMember：按时间倒序，同毫秒的用 id 倒序保证全序")
    void listByMemberShouldBeTotallyOrdered() {
        long memberId = newMemberId();
        // 三条 create_time 完全相同 —— 只能靠 id DESC 定序
        LocalDateTime same = now();
        for (int i = 0; i < 3; i++) {
            WalletTransaction txn = WalletTransaction.builder()
                    .transactionNo("WT" + SEQ.incrementAndGet())
                    .memberId(memberId).orderId(null).type(1).amount(bd("10.00"))
                    .balanceBefore(BigDecimal.ZERO).balanceAfter(bd("10.00"))
                    .frozenBefore(BigDecimal.ZERO).frozenAfter(BigDecimal.ZERO)
                    .createTime(same).build();
            walletTransactionMapper.insert(txn);
            createdTxnIds.add(txn.getId());
        }

        List<WalletTransaction> rows = walletTransactionMapper.listByMember(memberId);
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).getId() > rows.get(1).getId(),
                "★ 时间相同的两笔必须有一致的相对顺序，否则翻页会重复或漏行");
        assertTrue(rows.get(1).getId() > rows.get(2).getId());
    }

    @Test
    @DisplayName("listByMember：只看得到自己的流水")
    void listByMemberShouldNotLeakOtherMembers() {
        long mine = newMemberId();
        long others = newMemberId();

        insertTxn(mine, null, 1, "10.00");
        insertTxn(others, null, 1, "20.00");

        List<WalletTransaction> rows = walletTransactionMapper.listByMember(mine);
        assertEquals(1, rows.size());
        assertEquals(mine, rows.get(0).getMemberId());
    }

    @Test
    @DisplayName("getByOrderAndType：按订单 + 类型精确命中，查不到返回 null")
    void getByOrderAndTypeShouldMatchExactly() {
        long memberId = newMemberId();
        long orderId = System.nanoTime();
        insertTxn(memberId, orderId, 4, "-30.00");

        WalletTransaction found = walletTransactionMapper.getByOrderAndType(orderId, 4);
        assertNotNull(found);
        assertEquals(orderId, found.getOrderId());

        assertNull(walletTransactionMapper.getByOrderAndType(orderId, 3),
                "同订单的另一种类型还不存在");
        assertNull(walletTransactionMapper.getByOrderAndType(System.nanoTime(), 4),
                "另一个订单查不到");
    }
}
