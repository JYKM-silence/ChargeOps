package com.chargeops.job;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.dto.OrderCreateDTO;
import com.chargeops.dto.OrderEndDTO;
import com.chargeops.dto.OrderPageQueryDTO;
import com.chargeops.dto.OrderStartDTO;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.ChargingOrderEvent;
import com.chargeops.entity.Wallet;
import com.chargeops.enumeration.EventSource;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.mapper.ChargingOrderEventMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.WalletMapper;
import com.chargeops.properties.SchedulingProperties;
import com.chargeops.result.PageResult;
import com.chargeops.service.ChargingOrderService;
import com.chargeops.vo.AdminOrderDetailVO;
import com.chargeops.vo.ChargingOrderDetailVO;
import com.chargeops.vo.ChargingOrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ChargingOrderSweepJob} 的集成测试 —— 支付失败兜底扫描的九条边界
 *
 * <h2>★★ 一、被扫的任务 Bean 是<b>手工 new 出来的</b>，不是 {@code @Autowired} 进来的 ★★</h2>
 *
 * <p>{@code src/test/resources/application.properties} 把
 * {@code chargeops.scheduling.enabled} 设成了 {@code false}，于是
 * {@code @ConditionalOnProperty} 把这个 Bean 挡在容器外。那一条配置是为了关掉
 * <b>调度器</b>：{@code @EnableScheduling} 在测试上下文里是真开着的，让任务自己按
 * {@code fixedDelay} 跑起来，会在 {@code ChargingOrderServiceImplTest} 断言到一半时
 * 把它的 {@code status=4} 订单扫成 6 —— 那是一套「平时全绿、偶尔红一项、重跑又好了」
 * 的测试，也就是最难查的那种失败。
 *
 * <p>★★ <b>但「调度器不跑」不等于「这个类测不了」。</b>★★
 * {@code @ConditionalOnProperty} 管的是<b>装配</b>，它回答的是「这个环境要不要让
 * 框架自己把任务调起来」，而不是「这个类能不能被实例化」。所以本类的做法是
 * <b>把任务显式地造出来</b>：{@link #setUp()} 里
 * {@code new ChargingOrderSweepJob(orderMapper, orderService, schedulingProperties)}，
 * 三个依赖仍然从容器注入（{@code orderService} 是 Spring 的代理，它上面的
 * {@code @Transactional} 照常生效 —— 这一点是第二节和第九节的断言能成立的前提）。
 *
 * <p>★ <b>被测的仍然是生产代码里那个对象。</b>唯一的差别是「谁决定它什么时候被调一次」：
 * {@code @Scheduled} 的间隔是运维参数，「等 5 分钟看它会不会自己跑」既慢又不稳定。
 *
 * <p>★ 代价是构造参数写死在这里。<b>这个类的构造依赖一旦变化（多一个 Mapper、
 * 少一个参数），本类会编译不过</b> —— 那是正确的、响亮的失败，
 * 比一个静默失配的 {@code @Autowired} 好得多。
 *
 * <h2>★★ 二、本类最值钱的一条断言：账龄是 {@code settle_time}，不是 {@code create_time} ★★</h2>
 *
 * <p>见 {@link #agingShouldUseSettleTimeNotCreateTime()}。
 * 一笔充了 8 小时、1 分钟前才结束的订单，它的 {@code create_time} 是 8 小时前；
 * 按 {@code create_time} 判账龄的实现会<b>立刻</b>把它打成「支付失败」，
 * 而用户此刻可能正在付款页面上输密码。这个错误<b>不会报错</b>，
 * 只会让每一个充电时间超过 15 分钟的用户都看到一次莫名其妙的失败记录。
 *
 * <h2>★★ 三、第二条：扫描绝不重试 {@code pay()} ★★</h2>
 *
 * <p>见 {@link #sweepMustNotRetryPayEvenWithEnoughBalance()}。
 * {@code pay()} 刻意不查 {@code assertActive}，所以自动重试等于一次未经同意的扣款。
 *
 * <h2>★ 四、夹具直接 {@code INSERT}，不走建单流程</h2>
 *
 * <p>{@code charging_order} 上<b>没有外键</b>（全库统一不加），所以造一笔
 * {@code status=4} 的订单不需要站点、桩、枪、车辆、计费模板任何一行 ——
 * 本类断言的是扫描任务的<b>取件条件</b>和它<b>写下去的东西</b>，
 * 和「订单是怎么建出来的」无关。真实建单流程由
 * {@code ChargingOrderServiceImplTest} 覆盖，状态机闸门由
 * {@code ChargingOrderMapperTest} 覆盖。
 *
 * <p>★ 因此本类也<b>不造 member 行</b>：被测的两条路径
 * （{@code pay} / {@code markPayFailed}）都不调 {@code assertActive} ——
 * 那是「{@code pay} 是被禁用车主解冻资金的唯一出口」这条设计在测试里的可见后果，
 * 见 {@code ChargingOrderServiceImpl.pay} 的 javadoc。
 *
 * <h2>★ 五、账龄是「把自己的行拨老」，不是「把全局阈值调小」</h2>
 *
 * <p>{@link ChargingOrderSweepJob#sweepStalePendingPay()} 直接调一次就是扫全表。
 * 本类<b>只把自己的行拨到 3 倍阈值之前</b>（{@link #staleMinutes()}），
 * 不去动 {@code chargeops.scheduled.pay-timeout-minutes} —— 改小全局阈值
 * 会让<b>别的测试类</b>刚建出来的 {@code status=4} 订单落进扫描范围，
 * 那种失败会以一种和本类毫无关系的样子出现。
 */
@SpringBootTest
class ChargingOrderSweepJobTest {

    @Autowired
    private ChargingOrderMapper orderMapper;

    @Autowired
    private ChargingOrderEventMapper eventMapper;

    @Autowired
    private ChargingOrderService orderService;

    @Autowired
    private WalletMapper walletMapper;

    /**
     * ★ 从容器注入的<b>那一份</b>配置：账龄阈值和单轮上限都以它为准。
     * 测试**不**改它 —— 见类注释第五节。
     */
    @Autowired
    private SchedulingProperties schedulingProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ★ 手工构造的被测对象，理由见类注释第一节 */
    private ChargingOrderSweepJob sweepJob;

    /** 测试数据的 member_id 下界，收尾时按范围删 */
    private static final long TEST_ID_BASE = 8_000_000_000_000L;

    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach
    void setUp() {
        // ★ 见类注释第一节：这里不是「把任务关掉」，是「把它显式地造出来」。
        //   orderService 是容器里的那个代理，markPayFailed 的独立事务照常生效。
        sweepJob = new ChargingOrderSweepJob(orderMapper, orderService, schedulingProperties);
    }

    @AfterEach
    void cleanUp() {
        // ★ 先删事件再删订单：子查询要靠订单行还在才查得到 id
        jdbcTemplate.update("DELETE FROM charging_order_event WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ?)", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet_transaction WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet WHERE member_id >= ?", TEST_ID_BASE);
        // ★ 本类不造 member / station / connector / tariff_template，理由见类注释第四节，
        //   所以这里也没有它们要认领的前缀（ST-SWP- / TT-SWP- 在本类里没有用武之地）。

        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private static long newMemberId() {
        return TEST_ID_BASE + SEQ.incrementAndGet();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual, String what) {
        assertThat(actual).as("%s（期望 %s，实际 %s）", what, expected, actual).isNotNull();
        assertThat(actual.compareTo(bd(expected)))
                .as("%s（期望 %s，实际 %s）", what, expected, actual).isZero();
    }

    /**
     * 本类造出来的账龄：配置阈值的 3 倍
     *
     * <p>★ 「3 倍」这个系数只是为了离边界足够远，<b>不是</b>业务含义。
     * 阈值本身（默认 15 分钟）来自配置，本类不去改它 —— 见类注释第五节。
     */
    private long staleMinutes() {
        return schedulingProperties.getPayTimeoutMinutes() * 3L;
    }

    /**
     * 直接 {@code INSERT} 一笔指定状态的订单（{@code settle_time} 留 NULL）
     *
     * <p>★ {@code insert} 的列清单里<b>没有</b> {@code settle_time}，所以刚插进去的行
     * 账龄是「未知」—— 第 4 条测试要的就是这个初始状态，
     * 其余测试自己用 {@link #ageSettleTime} 拨到想要的位置。
     */
    private ChargingOrder givenOrder(long memberId, OrderStatus status) {
        ChargingOrder order = ChargingOrder.builder()
                .orderNo("CO-SWP-" + SEQ.incrementAndGet())
                .memberId(memberId)
                .stationId(1L).chargerId(1L).connectorId(1L)
                .tariffTemplateId(1L)
                .status(status.getCode())
                // 20.00 冻着 —— 第 7 条测试要靠它证明「扫描一分钱都没解冻」
                .frozenAmount(bd("20.00"))
                .discountAmount(bd("0.00"))
                .refundAmount(bd("0.00"))
                .payMethod(1)
                .version(0)
                .build();
        orderMapper.insert(order);
        assertThat(order.getId()).as("insert 后 id 应被回填").isNotNull();

        // ★★ insert 的列清单里【没有】pay_amount / total_amount —— 它们是 markSettled
        //    写的两列（见 ChargingOrderMapper.xml），而本类的夹具不走结算流程。
        //    不补这一行的话它是一个 NULL，而 pay() 会把 NULL 当成 0
        //    （WalletServiceImpl.settle 的 nullToZero）—— 于是「被扫成 6 的订单
        //    还能不能付」那条测试会变成一条「一分钱都没扣」的假绿。
        //    这里显式补上，让订单上真的有一笔待付的金额。
        //    ★ 也正因为它不在 insert 的列清单里，上面 builder 上写 payAmount 是没用的 ——
        //      金额只有这一个写入口。
        jdbcTemplate.update("UPDATE charging_order SET pay_amount = ? WHERE id = ?",
                bd("15.00"), order.getId());
        return order;
    }

    /**
     * 把 {@code settle_time} 拨到 {@code minutes} 分钟以前 —— 账龄就是这么造出来的
     *
     * <p>★ {@code minutes = 0} 表示「刚刚结算完」，即一个还在付款页面上的用户。
     */
    private void ageSettleTime(Long orderId, long minutes) {
        jdbcTemplate.update("UPDATE charging_order SET settle_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(minutes), orderId);
    }

    /** 给车主开一张卡。★ 冻结额与测试订单上的 {@code frozen_amount} 对齐 */
    private void givenWallet(long memberId, String balance, String frozen) {
        LocalDateTime now = LocalDateTime.now();
        walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(bd(balance)).frozenAmount(bd(frozen))
                .totalRecharge(bd(balance))
                .version(0).createTime(now).updateTime(now).build());
    }

    private Wallet wallet(long memberId) {
        return walletMapper.getByMemberId(memberId);
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMapper.getById(orderId).getStatus());
    }

    private List<ChargingOrderEvent> eventsOf(Long orderId) {
        return eventMapper.listByOrderId(orderId);
    }

    /** 本单在钱包侧写了几条流水。★ 扫描「不动钱」这件事最硬的证据 */
    private long walletTxnCount(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE order_id = ?", Long.class, orderId);
    }

    private LocalDateTime settleTimeOf(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT settle_time FROM charging_order WHERE id = ?", LocalDateTime.class, orderId);
    }

    // ==================== 一、该扫的扫、不该扫的不扫 ====================

    @Test
    @DisplayName("★★ 卡在「待支付」太久的订单被扫成「支付失败」，并留下一条说清原因的流水")
    void oldPendingPayShouldBeSweptToPayFailed() {
        long memberId = newMemberId();
        ChargingOrder order = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(order.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        ChargingOrder after = orderMapper.getById(order.getId());
        assertThat(OrderStatus.of(after.getStatus()))
                .as("超过支付时限仍停在 4 的订单应被推到 6")
                .isEqualTo(OrderStatus.PAY_FAILED);

        // ★★ 「6」不是一个「换了个数字」的状态，它必须是【可行动的】。★★
        //   订单详情页读的是 remark，用户从这里知道「我还能做什么」；
        //   把文案写成「支付超时」的实现在这里会失败 —— 用户看到它只能去问客服，
        //   而正确的下一步（充值后重新支付）本来就在这条记录里。
        assertThat(after.getRemark())
                .as("★★ 失败原因必须告诉用户下一步能做什么，不能只是一个状态码")
                .isNotEmpty()
                .contains("重新支付")
                .contains("充值");
        // ★ 阈值来自配置、会变，而这句话被永久写进每一笔被扫到的订单 ——
        //   把可变的分钟数固化进业务数据，半年后没人能解释「为什么这批写的是 15 分钟」
        assertThat(after.getRemark())
                .as("★ 文案里不能带具体分钟数（见 ChargingOrderSweepJob.STALE_REASON 的注释）")
                .doesNotContain(String.valueOf(schedulingProperties.getPayTimeoutMinutes()));

        // ★ 事件表是「什么时候因为什么卡住了」的唯一留痕，它给的是扫描这件事的可观测性
        List<ChargingOrderEvent> events = eventsOf(order.getId());
        assertThat(events).as("一趟扫描只写一条事件").hasSize(1);
        ChargingOrderEvent event = events.get(0);
        assertThat(event.getFromStatus()).isEqualTo(OrderStatus.PENDING_PAY.getCode());
        assertThat(event.getToStatus()).isEqualTo(OrderStatus.PAY_FAILED.getCode());
        assertThat(event.getSource())
                .as("★ 来源必须是 3 定时任务 —— 排查「谁改的」要有唯一答案")
                .isEqualTo(EventSource.SCHEDULED.getCode());
        assertThat(event.getDetail()).as("原因要同时留在事件里").isEqualTo(after.getRemark());

        // ★ 扫描只改状态和原因，金额和支付时间一概不碰
        assertAmount("15.00", after.getPayAmount(), "实付金额不该被扫描改掉");
        assertThat(after.getPayTime()).as("★ 它没有支付过，pay_time 必须还是空").isNull();
    }

    @Test
    @DisplayName("★ 刚进入「待支付」的订单不扫 —— 用户可能正在付款页面上输密码")
    void freshPendingPayShouldNotBeSwept() {
        long memberId = newMemberId();
        ChargingOrder fresh = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(fresh.getId(), 0);

        // ★ 控制组：同一轮里必须有一笔真的被扫走的订单。
        //   没有它的话，这条测试在「扫描任务压根没查库 / 查询条件写错成空集」时
        //   也会绿 —— 那就成了一条什么也没证明的测试。
        ChargingOrder control = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(control.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        assertThat(statusOf(fresh.getId()))
                .as("刚结算完的订单还在支付窗口内，必须原封不动")
                .isEqualTo(OrderStatus.PENDING_PAY);
        assertThat(statusOf(control.getId())).as("控制组：这一轮扫描确实跑起来了").isEqualTo(OrderStatus.PAY_FAILED);
    }

    @Test
    @DisplayName("★★★ 账龄看 settle_time：充了 8 小时、1 分钟前才结束的订单不能被判成失败")
    void agingShouldUseSettleTimeNotCreateTime() {
        long memberId = newMemberId();
        // 一笔真实存在过的订单：车从早上充到现在，刚刚结束、刚刚结算完
        ChargingOrder longCharge = givenOrder(memberId, OrderStatus.PENDING_PAY);
        jdbcTemplate.update("UPDATE charging_order SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(8), longCharge.getId());
        ageSettleTime(longCharge.getId(), 1);

        // ★ 前置条件：这确实是一笔「建单很早、进入待支付很晚」的订单。
        //   不先把它证出来的话，下面那条断言可能是因为建单时间压根没被拨老而白绿。
        LocalDateTime createTime = orderMapper.getById(longCharge.getId()).getCreateTime();
        assertThat(createTime).as("前置条件：建单时间已经很旧").isBefore(LocalDateTime.now().minusHours(7));
        assertThat(settleTimeOf(longCharge.getId()))
                .as("前置条件：进入待支付才 1 分钟").isAfter(LocalDateTime.now().minusMinutes(5));

        ChargingOrder control = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(control.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        // ★★★ 这是本类最值钱的一条断言。★★★
        //   把取件条件里的 settle_time 换成 create_time（一个看起来完全无害的重构：
        //   「都是时间列」），这一笔会被【立刻】判成支付失败 —— 用户刚把枪插回去，
        //   打开 App 准备付款，看到的是「支付失败」。而这个 bug 不报错、不告警，
        //   表现是「充电时间长的用户总是被莫名判失败」。
        //   settle_time 才是「他有几分钟可以慢慢付」的起点，它是 markSettled 写的。
        assertThat(statusOf(longCharge.getId()))
                .as("★★★ 按 create_time 判账龄的实现会在这里把它扫成 6 —— 用户正在付款页面上输密码")
                .isEqualTo(OrderStatus.PENDING_PAY);
        assertThat(eventsOf(longCharge.getId())).as("连一条事件都不该有").isEmpty();
        assertThat(statusOf(control.getId())).as("控制组：这一轮扫描确实跑起来了").isEqualTo(OrderStatus.PAY_FAILED);
    }

    @Test
    @DisplayName("★ settle_time 为空的待支付订单扫不到 —— 失败方向是「不处理」，不是「误判」")
    void nullSettleTimeShouldNotBeSwept() {
        long memberId = newMemberId();
        ChargingOrder unknown = givenOrder(memberId, OrderStatus.PENDING_PAY);

        // ★ 前置条件：insert 的列清单里没有 settle_time，所以它天然是 NULL。
        //   这一行手工改库也能造出来，是「账龄不明」的那个形状。
        assertThat(settleTimeOf(unknown.getId())).as("前置条件：这笔订单的账龄未知").isNull();

        ChargingOrder control = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(control.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        // ★ MySQL 里 NULL < ? 求值为 NULL，即 false —— 这类行扫不到。
        //   这是刻意的：把条件写成 (settle_time IS NULL OR settle_time < ?) 会「兜住」它，
        //   但那等于把一笔账龄不明的订单当场打成失败。两个方向都是静默的，
        //   选「不处理」是因为它可恢复：人工查一下 settle_time 该是多少，
        //   而一笔被误判的失败记录是抹不掉的（事件表只追加）。
        assertThat(statusOf(unknown.getId()))
                .as("★ 账龄不明时宁可不动它 —— 误判比不处理严重")
                .isEqualTo(OrderStatus.PENDING_PAY);
        assertThat(statusOf(control.getId())).as("控制组：这一轮扫描确实跑起来了").isEqualTo(OrderStatus.PAY_FAILED);
    }

    @Test
    @DisplayName("★★ 只碰 status=4：其它八个状态的订单即使账龄很老也一律不动")
    void onlyPendingPayShouldBeTouched() {
        long memberId = newMemberId();

        // ★ 故意把这些状态的 settle_time 也拨老。1/2/3 正常情况下根本没有 settle_time，
        //   7/8/9 是终态或挂起态 —— 「有一列时间很旧」不能成为扫描它们的理由。
        Map<Long, OrderStatus> untouched = new LinkedHashMap<>();
        for (OrderStatus status : List.of(OrderStatus.CREATED, OrderStatus.CHARGING,
                OrderStatus.SETTLING, OrderStatus.PAID, OrderStatus.PAY_FAILED,
                OrderStatus.REFUNDED, OrderStatus.CANCELLED, OrderStatus.ABNORMAL)) {
            ChargingOrder other = givenOrder(memberId, status);
            ageSettleTime(other.getId(), staleMinutes());
            untouched.put(other.getId(), status);
        }

        // ★ 6 是本次扫描的【目标】状态，所以它格外重要：把取件条件放宽成
        //   status IN (4, 6)（一个看起来无害的「顺手也捞一下已经失败的」）会
        //   覆盖掉订单上原来那条失败原因，并再写一条事件 —— 用户看到的
        //   「为什么失败」被换成了一句更笼统的话。
        Long alreadyFailed = untouched.entrySet().stream()
                .filter(e -> e.getValue() == OrderStatus.PAY_FAILED)
                .findFirst().orElseThrow().getKey();
        jdbcTemplate.update("UPDATE charging_order SET remark = ? WHERE id = ?",
                "用户主动支付失败的原始原因", alreadyFailed);

        ChargingOrder control = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(control.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        // ★ 这个方法只有一件事要做：把 4 推到 6。取件条件放宽一格，
        //   就会静默地改动一批它从来没打算碰的订单 —— 状态机里每一个状态
        //   都有自己的出口，而那个出口不该是这个定时任务。
        for (Map.Entry<Long, OrderStatus> entry : untouched.entrySet()) {
            assertThat(statusOf(entry.getKey()))
                    .as("状态「%s」的订单不该被兜底扫描改动", entry.getValue().getLabel())
                    .isEqualTo(entry.getValue());
        }
        assertThat(orderMapper.getById(alreadyFailed).getRemark())
                .as("★★ 已经失败的订单上那条原因不能被后来的扫描覆盖")
                .isEqualTo("用户主动支付失败的原始原因");
        assertThat(statusOf(control.getId())).as("控制组：这一轮扫描确实跑起来了").isEqualTo(OrderStatus.PAY_FAILED);
    }

    @Test
    @DisplayName("★★ 单轮上限是真的上限：积压 5 笔、上限 3，只推走最老的 3 笔")
    void sweepShouldRespectTheBatchLimit() {
        int limit = 3;
        // ★ 自己造一份配置，只把「单轮最多几笔」压小 —— 账龄阈值仍然取容器里那一份，
        //   这样本类其余测试的语义不受影响，也不会有别的测试类被卷进来。
        SchedulingProperties capped = new SchedulingProperties();
        capped.setPayTimeoutMinutes(schedulingProperties.getPayTimeoutMinutes());
        capped.setPayTimeoutBatchSize(limit);
        ChargingOrderSweepJob cappedJob =
                new ChargingOrderSweepJob(orderMapper, orderService, capped);

        long memberId = newMemberId();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < limit + 2; i++) {
            ChargingOrder order = givenOrder(memberId, OrderStatus.PENDING_PAY);
            // 拨老的同时让第 0 笔最老 —— 取件顺序是 ORDER BY settle_time, id
            ageSettleTime(order.getId(), staleMinutes() + (limit + 2 - i) * 10L);
            ids.add(order.getId());
        }

        cappedJob.sweepStalePendingPay();

        long moved = ids.stream().filter(id -> statusOf(id) == OrderStatus.PAY_FAILED).count();
        // ★★ 上限是<b>必需的参数，不是优化</b>：一轮把几千笔待支付订单全捞进内存、
        //    逐笔开事务去写，会在数据库上形成一个长尾，而且每一笔都持有
        //    charging_order 上的行锁直到方法结束 —— 而真实的用户正在下单、在支付。
        //    去掉这个上限之后没有任何东西会报错，第一个把它跑在积压数据上的人
        //    才会看到后果（OOM，或者整张订单表的写入被拖住）。
        assertThat(moved).as("★★ 一轮最多只处理 payTimeoutBatchSize 笔，剩下的下一轮再来")
                .isEqualTo(limit);
        // ★ 取件顺序是确定的（settle_time, id）：没有确定性的话，
        //   「某几笔被反复取到、另几笔永远轮不上」是会真实发生的
        assertThat(ids.subList(0, limit)).as("★ 推走的是最老的 N 笔")
                .allMatch(id -> statusOf(id) == OrderStatus.PAY_FAILED);
        assertThat(ids.subList(limit, ids.size())).as("剩下的留在 4 上，等着下一轮")
                .allMatch(id -> statusOf(id) == OrderStatus.PENDING_PAY);
    }

    // ==================== 二、扫描不动钱 ====================

    @Test
    @DisplayName("★★ 扫描绝不重试支付：余额充足也不扣款，订单停在 6 而不是 5")
    void sweepMustNotRetryPayEvenWithEnoughBalance() {
        long memberId = newMemberId();
        // ★ 前提：这位车主账户里有钱，而且是【付得起】的钱 —— 这正是「重试一下就好了」
        //   这个念头成立的条件。断言的是「即使能付，也不去付」。
        givenWallet(memberId, "1000.00", "20.00");
        ChargingOrder order = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(order.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();

        // ★★★ pay() 刻意不查 assertActive（车主是否被禁用），那个决定是对的：
        //   它是被禁用车主【解冻资金的唯一出口】—— 一个被禁用的账号如果连
        //   「把已经冻住的钱付掉、结束这笔订单」都做不到，那笔钱就永久冻死了。
        //   ★ 正因为那个校验不在，自动重试 pay() 就等于【一次未经同意的扣款】：
        //   车主这笔充值可能是为了别的事（明天要充另一辆车），扫描把钱拿走了。
        //   本仓库其余每一处资金变动都由用户动作或设备事件触发，定时任务不在此列。
        assertThat(statusOf(order.getId()))
                .as("★★ 必须停在 6 等用户自己决定 —— 绝不能是 5")
                .isEqualTo(OrderStatus.PAY_FAILED);

        Wallet w = wallet(memberId);
        assertAmount("1000.00", w.getBalance(), "★★ 余额一分未动");
        assertAmount("20.00", w.getFrozenAmount(), "★ 钱仍然冻着 —— 既没扣掉也没退回");
        assertThat(walletTxnCount(order.getId()))
                .as("★★ 一笔钱包流水都不能产生（重试支付会写解冻 + 扣款两条）")
                .isZero();
        assertThat(orderMapper.getById(order.getId()).getPayTime())
                .as("★ 没有支付过，pay_time 必须是空").isNull();

        // ★ 扫描做的事是「把订单推到一个诚实的、等人工或等用户的停留态」：
        //   4 待支付 → 6 支付失败，外加一条说得清为什么的记录。
    }

    @Test
    @DisplayName("★★ 被扫成 6 的订单，用户充值后照常能付（6 → 5）—— 这就是 4 → 6 安全的全部理由")
    void sweptOrderShouldStillBePayableByTheUser() {
        long memberId = newMemberId();
        givenWallet(memberId, "100.00", "20.00");
        ChargingOrder order = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(order.getId(), staleMinutes());

        sweepJob.sweepStalePendingPay();
        assertThat(statusOf(order.getId()))
                .as("前置条件：它已经被扫成 6 了").isEqualTo(OrderStatus.PAY_FAILED);

        // ★ 用户在订单详情页点「重新支付」走的就是这一步
        orderService.pay(order.getId());

        // ★ markPaid 的谓词是 status IN (4, 6) —— 6 本来就在支持范围内
        //   （那是「余额不足失败后充值回来再付一次」那条路）。
        //   ★★ 所以 4 → 6 是一次【不丢任何东西】的流转：
        //      · 用户的重试按钮照常工作（这一条测试就是它的守门人）；
        //      · 6 比 4 还多一条 charging_order_event，说的是「它在什么时候
        //        因为什么卡住了」，可查、可告警；
        //      · 钱一直冻着，既没扣掉也没退回 —— markPayFailed 不动钱包。
        //   这就是「与其停在 4 上没人管，不如推到 6 上留个痕迹」的全部理由。
        assertThat(statusOf(order.getId())).isEqualTo(OrderStatus.PAID);
        Wallet w = wallet(memberId);
        assertAmount("105.00", w.getBalance(), "100 + 解冻 20 − 实付 15");
        assertAmount("0.00", w.getFrozenAmount(), "★ 冻结清零，钱有了出路");
    }

    // ==================== 三、失败隔离 ====================

    @Test
    @DisplayName("★ 一笔处理失败不能带走一轮：它后面的订单照常被扫走")
    void oneBadOrderShouldNotTakeDownTheWholeSweep() {
        long memberId = newMemberId();
        ChargingOrder bad = givenOrder(memberId, OrderStatus.PENDING_PAY);
        // ★ 让它在取件顺序里排第一（ORDER BY settle_time, id）——
        //   失败发生在最前面，才能证明循环【继续】了，而不是「恰好还没轮到它」
        ageSettleTime(bad.getId(), staleMinutes() + 10);
        ChargingOrder good = givenOrder(memberId, OrderStatus.PENDING_PAY);
        ageSettleTime(good.getId(), staleMinutes());

        ChargingOrderSweepJob jobWithFailingService = new ChargingOrderSweepJob(
                orderMapper, new FailOnOrderService(orderService, bad.getId()), schedulingProperties);

        // ★ 循环里的 try/catch 是刻意的，而且它和 ChargingOrderServiceImpl 类注释里
        //   那条「绝不能 catch BalanceNotEnoughException」不矛盾：那里的问题是
        //   「在【同一个】事务里 catch 住异常继续写库」—— 事务已经被标记 rollback-only，
        //   写进去的东西提交时会丢；这里每一笔 markPayFailed 是一个已经完成、
        //   已经回滚完的独立事务，异常是代理在回滚之后才抛回来的，catch 它不会污染任何东西。
        assertThatCode(jobWithFailingService::sweepStalePendingPay).doesNotThrowAnyException();

        assertThat(statusOf(bad.getId()))
                .as("处理失败的那笔原样停在 4 —— 不静默改成别的状态")
                .isEqualTo(OrderStatus.PENDING_PAY);

        // ★★ 没有那个 try/catch 的话，异常会直接冲出 sweepStalePendingPay，
        //    后面所有订单<b>一笔都不会被处理</b> —— 而它们本来都能处理成功。
        //    一笔坏数据让整个兜底机制停摆，是这类批处理任务最常见的坏法：
        //    它不报错，只是「从某一天起，再也没有订单被扫成 6」。
        assertThat(statusOf(good.getId()))
                .as("★★ 它后面那笔照样被扫走了 —— 单笔失败不影响其余")
                .isEqualTo(OrderStatus.PAY_FAILED);

        // ★★ 本方法【不能】标 @Transactional，两个独立的理由，任何一个都足够：
        //    ① markPayFailed 自己的 javadoc 就写着它「必须在旧事务回滚之后的【新事务】里
        //       调用」—— 它设计成自成一笔事务。套上外层事务之后，它就会被 REQUIRED
        //       吸收进那一笔里，代理那层「独立提交」的保证当场消失；
        //    ② 一轮扫几百笔放在同一个事务里，会在 charging_order 上累积大量行锁直到
        //       方法结束才一起释放 —— 而这期间真实的用户在支付、在扫码建单。
        //       一笔慢扫描拖住整张订单表的写入，是典型的「定时任务把线上搞挂」。
        //    ★ 上面那条「第 37 笔失败不回滚前 36 笔」的断言，就是①的可执行证据。
    }

    // ==================== 桩 ====================

    /**
     * 手写的 {@link ChargingOrderService} 桩：让某一笔订单的 {@code markPayFailed} 抛异常
     *
     * <p>★ 用桩而不是「找一个真的会失败的订单」，因为 {@code markPayFailed} 在正常语义下
     * <b>不抛异常</b>：订单已经不在 4 上时它返回 0 行、记一条 info 就结束了
     * （那条路径由 {@code ChargingOrderServiceImplTest.markPayFailedShouldBeSilentWhenStatusMovedOn}
     * 覆盖）。能让它抛出来的是数据库层面的意外 —— 而循环里那个 try/catch 兜的正是意外。
     * 用桩来制造这个意外，信号是干净的：除了这一笔抛异常，其余行为和真实实现完全一样。
     *
     * <p>★ 手写而不是 Mockito：接口以后新加方法时编译器会逼着这里实现它，
     * 而 {@code mock()} 会静默返回 null —— 一个返回 null 的 {@code pageMine}
     * 在别处看起来像是「查不到数据」这个合法结果。
     */
    private static final class FailOnOrderService implements ChargingOrderService {

        private final ChargingOrderService delegate;

        private final long failOnOrderId;

        private FailOnOrderService(ChargingOrderService delegate, long failOnOrderId) {
            this.delegate = delegate;
            this.failOnOrderId = failOnOrderId;
        }

        @Override
        public void markPayFailed(Long orderId, String reason) {
            if (orderId == failOnOrderId) {
                // 模拟：这一笔在写库时撞上了意外（连接断了 / 死锁被选为牺牲者）
                throw new IllegalStateException("模拟：标记这一笔支付失败时数据库报了错");
            }
            delegate.markPayFailed(orderId, reason);
        }

        // ---- 以下方法扫描任务都不调用。★ 真的被调到了就是「这个任务的职责变了」，
        //      宁可在这里响亮地失败，也不要让一条没人看过的路径静默通过。

        @Override
        public ChargingOrderVO create(OrderCreateDTO dto) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public PageResult pageMine(OrderPageQueryDTO query) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public ChargingOrderDetailVO getMine(Long orderId) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void cancel(Long orderId) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void pay(Long orderId) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void start(Long orderId, OrderStartDTO dto) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void end(Long orderId, OrderEndDTO dto) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void settle(Long orderId) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        @Override
        public void refund(Long orderId, BigDecimal amount) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        /**
         * ★ 补这个是**编译器逼出来的**，正是本桩手写而不 Mockito 的理由
         *   （见类注释）：接口一加方法，这里就编译不过，不会静默漏掉。
         *
         * <p>扫描任务不查管理端详情 —— 真被调到了说明这个任务的职责变了，
         * 应该响亮地失败而不是返回 null。
         */
        @Override
        public AdminOrderDetailVO getForAdmin(Long orderId) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }

        /**
         * ★ 同上，也是**编译器逼出来的**。
         *
         * <p>扫描任务不查管理端列表 —— 唯一的调用方是
         * {@code OrderAdminController} 的订单分页。真被调到了说明这个任务的职责变了，
         * 应该响亮地失败而不是返回 null
         * （返回 null 会被调用方当成「这一页没有订单」这个合法结果）。
         */
        @Override
        public PageResult pageForAdmin(AdminOrderPageQueryDTO query) {
            throw new UnsupportedOperationException("本桩只实现 markPayFailed");
        }
    }
}
