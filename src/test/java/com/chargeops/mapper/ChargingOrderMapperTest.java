package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.ChargingOrderEvent;
import com.chargeops.entity.ChargingSegment;
import com.chargeops.enumeration.EventSource;
import com.chargeops.enumeration.OrderEventType;
import com.chargeops.enumeration.OrderStatus;
import com.github.pagehelper.PageHelper;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChargingOrderMapper} 的集成测试
 *
 * <p>通用写法见 {@link StaffMapperTest}。本类要钉死的是
 * <b>状态机的六条闸门</b>，每一条失效时都是静默的：
 *
 * <ol>
 *   <li>每个 {@code markXxx} <b>只从它声明的来源状态</b>生效 ——
 *       多接受一个来源就等于多开一条非法路径</li>
 *   <li>★ 尤其重要的是 {@code markCancelled} <b>只能从 1 待启动</b>：
 *       让 2 充电中也走取消，等于给了一条「充完电把订单作废」的路</li>
 *   <li>★ 和 {@code markRefunded} <b>只能从 5 已支付</b>：
 *       从 4 待支付退款 = 退一笔还没扣的钱</li>
 *   <li>{@code markAbnormal} 从<b>任意进行中</b>状态进 9，
 *       但<b>不能</b>覆盖一个终态</li>
 *   <li>{@code bindMemberPackage} 只对「待启动且还没绑过」的订单生效</li>
 *   <li>{@code uk_segment_order_start} 和 {@code uk_event_idempotent}
 *       各自挡住重复插入</li>
 * </ol>
 *
 * <h2>★ 为什么夹具直接 {@code INSERT} 而不是走建单流程</h2>
 *
 * <p>{@code charging_order} 上<b>没有外键</b>（全库统一不加，理由见
 * {@code V1__init_schema.sql} 头部），所以造一笔订单不需要站点、桩、枪、
 * 车辆任何一行。本类测的是 SQL 的条件更新写对没有，
 * 和「订单是怎么建出来的」无关 —— 走真实建单流程会引入
 * 十几个和本类断言无关的依赖，而且失败信息会指向那些依赖。
 *
 * <p>建单流程本身由 {@code ChargingOrderServiceImplTest} 覆盖。
 */
@SpringBootTest
class ChargingOrderMapperTest {

    @Autowired
    private ChargingOrderMapper orderMapper;

    @Autowired
    private ChargingSegmentMapper segmentMapper;

    @Autowired
    private ChargingOrderEventMapper eventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试数据的 member_id 下界，收尾时按范围删 */
    private static final long TEST_ID_BASE = 3_000_000_000_000L;

    private static final AtomicLong SEQ = new AtomicLong();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM charging_order_event WHERE order_id IN "
                        + "(SELECT id FROM charging_order WHERE member_id >= ?)", TEST_ID_BASE);
        jdbcTemplate.update(
                "DELETE FROM charging_segment WHERE order_id IN "
                        + "(SELECT id FROM charging_order WHERE member_id >= ?)", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ?", TEST_ID_BASE);
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private long newMemberId() {
        return TEST_ID_BASE + SEQ.incrementAndGet();
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 造一笔处于 {@code status} 的订单
     *
     * <p>★ 起点固定是 1 待启动，然后<b>用真实的状态流转 SQL 推到目标状态</b>，
     * 而不是直接 {@code UPDATE ... SET status = ?}。
     * 直接写的话，测试自己会绕过被测的闸门 ——
     * 那样「markSettled 只从 3 生效」这类断言就永远测不出来，
     * 因为前置状态是我硬塞进去的，不是我证出来的。
     */
    private ChargingOrder givenOrderAt(long memberId, OrderStatus target) {
        LocalDateTime now = now();
        ChargingOrder order = ChargingOrder.builder()
                .orderNo("CO-T" + SEQ.incrementAndGet())
                .memberId(memberId)
                .stationId(1L).chargerId(1L).connectorId(1L)
                .tariffTemplateId(1L)
                .status(OrderStatus.CREATED.getCode())
                .frozenAmount(bd("50.00"))
                .discountAmount(bd("0.00"))
                .refundAmount(bd("0.00"))
                .payMethod(1)
                .version(0)
                .build();
        orderMapper.insert(order);
        assertNotNull(order.getId(), "insert 后 id 应被回填");

        if (target == OrderStatus.CREATED) {
            return order;
        }
        assertEquals(1, orderMapper.markStarted(order.getId(), now, bd("1000.0000"), 30, now));
        if (target == OrderStatus.CHARGING) {
            return order;
        }
        assertEquals(1, orderMapper.markEnded(order.getId(), now.plusHours(1), 3600,
                bd("1030.0000"), bd("30.0000"), 80, 1, now));
        if (target == OrderStatus.SETTLING) {
            return order;
        }
        assertEquals(1, orderMapper.markSettled(order.getId(), bd("36.00"), bd("6.00"),
                bd("42.00"), bd("0.00"), bd("42.00"), now, now));
        if (target == OrderStatus.PENDING_PAY) {
            return order;
        }
        if (target == OrderStatus.PAY_FAILED) {
            assertEquals(1, orderMapper.markPayFailed(order.getId(), "测试用的失败原因", now));
            return order;
        }
        assertEquals(1, orderMapper.markPaid(order.getId(), now, now));
        return order;
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMapper.getById(orderId).getStatus());
    }

    // ==================== 建单与查询 ====================

    @Test
    @DisplayName("insert：@AutoFill 填上审计时间，status 由调用方给")
    void insertShouldFillAuditFields() {
        ChargingOrder order = givenOrderAt(newMemberId(), OrderStatus.CREATED);

        ChargingOrder loaded = orderMapper.getById(order.getId());
        assertNotNull(loaded.getCreateTime(), "@AutoFill(INSERT) 应填上 createTime");
        assertNotNull(loaded.getUpdateTime());
        assertEquals(OrderStatus.CREATED.getCode(), loaded.getStatus());
    }

    @Test
    @DisplayName("getByIdAndMember：别人的订单一律返回 null，不区分「不存在」和「不是你的」")
    void getByIdAndMemberShouldNotLeak() {
        long owner = newMemberId();
        long stranger = newMemberId();
        ChargingOrder order = givenOrderAt(owner, OrderStatus.CREATED);

        assertNotNull(orderMapper.getByIdAndMember(order.getId(), owner));
        assertNull(orderMapper.getByIdAndMember(order.getId(), stranger),
                "★ 区分「不存在」和「不是你的」会让接口变成订单号探测器");
    }

    @Test
    @DisplayName("listHoldingByConnector：只返回占着枪的状态，终态不在其中")
    void listHoldingByConnectorShouldExcludeTerminalStates() {
        long memberId = newMemberId();
        long connectorId = SEQ.incrementAndGet();

        ChargingOrder charging = givenOrderAt(memberId, OrderStatus.CHARGING);
        jdbcTemplate.update("UPDATE charging_order SET connector_id = ? WHERE id = ?",
                connectorId, charging.getId());

        List<ChargingOrder> holding = orderMapper.listHoldingByConnector(
                connectorId, OrderStatus.holdingConnectorCodes());
        assertEquals(1, holding.size());
        assertEquals(charging.getId(), holding.get(0).getId());

        // 取消之后就不再占枪了
        jdbcTemplate.update("UPDATE charging_order SET status = 8 WHERE id = ?", charging.getId());
        assertTrue(orderMapper.listHoldingByConnector(
                connectorId, OrderStatus.holdingConnectorCodes()).isEmpty());
    }

    @Test
    @DisplayName("listHoldingByVehicle：按车主 + 车辆收窄，不会串到别人的车")
    void listHoldingByVehicleShouldScopeToMember() {
        long mine = newMemberId();
        long others = newMemberId();
        long vehicleId = SEQ.incrementAndGet();

        ChargingOrder myOrder = givenOrderAt(mine, OrderStatus.CHARGING);
        ChargingOrder otherOrder = givenOrderAt(others, OrderStatus.CHARGING);
        jdbcTemplate.update("UPDATE charging_order SET vehicle_id = ? WHERE id IN (?, ?)",
                vehicleId, myOrder.getId(), otherOrder.getId());

        List<ChargingOrder> holding = orderMapper.listHoldingByVehicle(
                mine, vehicleId, OrderStatus.holdingConnectorCodes());
        assertEquals(1, holding.size(),
                "★ 车辆 id 全局唯一，但查询的语义是「**你的**车在不在充」");
        assertEquals(myOrder.getId(), holding.get(0).getId());
    }

    // ==================== ★★ 六条闸门 ====================

    @Test
    @DisplayName("★★ markCancelled 只能从 1 待启动 —— 充电中取消是「偷电」")
    void markCancelledShouldOnlyAcceptCreated() {
        long memberId = newMemberId();
        LocalDateTime now = now();

        ChargingOrder created = givenOrderAt(memberId, OrderStatus.CREATED);
        assertEquals(1, orderMapper.markCancelled(created.getId(), now));
        assertEquals(OrderStatus.CANCELLED, statusOf(created.getId()));

        // 充电中的订单不能取消
        ChargingOrder charging = givenOrderAt(memberId, OrderStatus.CHARGING);
        assertEquals(0, orderMapper.markCancelled(charging.getId(), now),
                "★★ 让充电中也走取消，等于给了一条「充完电把订单作废」的路径");
        assertEquals(OrderStatus.CHARGING, statusOf(charging.getId()), "状态必须原封不动");

        // 重复取消也是 0 行 —— 这就是取消的幂等
        assertEquals(0, orderMapper.markCancelled(created.getId(), now));
    }

    @Test
    @DisplayName("★★ markRefunded 只能从 5 已支付 —— 待支付退款 = 退一笔还没扣的钱")
    void markRefundedShouldOnlyAcceptPaid() {
        long memberId = newMemberId();
        LocalDateTime now = now();

        ChargingOrder pendingPay = givenOrderAt(memberId, OrderStatus.PENDING_PAY);
        assertEquals(0, orderMapper.markRefunded(pendingPay.getId(), bd("42.00"), now, now),
                "★★ 4 待支付的时候钱还没扣，要退的是冻结 —— 那走取消/挂账，不是退款");
        assertEquals(OrderStatus.PENDING_PAY, statusOf(pendingPay.getId()));

        ChargingOrder paid = givenOrderAt(memberId, OrderStatus.PAID);
        assertEquals(1, orderMapper.markRefunded(paid.getId(), bd("42.00"), now, now));
        assertEquals(OrderStatus.REFUNDED, statusOf(paid.getId()));

        assertEquals(0, orderMapper.markRefunded(paid.getId(), bd("42.00"), now, now),
                "重复退款必须被挡在第二个缺口 —— 钱包侧的唯一索引是最后一道，不是第一道");
    }

    @Test
    @DisplayName("markStarted / markEnded / markSettled / markPaid：各自只接受声明的前置状态")
    void eachMarkShouldOnlyAcceptItsOwnFromStatus() {
        long memberId = newMemberId();
        LocalDateTime now = now();

        // 待启动的订单不能直接结束
        ChargingOrder created = givenOrderAt(memberId, OrderStatus.CREATED);
        assertEquals(0, orderMapper.markEnded(created.getId(), now, 60,
                bd("1010.0000"), bd("10.0000"), 40, 1, now));
        assertEquals(0, orderMapper.markSettled(created.getId(), bd("1.00"), bd("1.00"),
                bd("2.00"), bd("0.00"), bd("2.00"), now, now));
        assertEquals(0, orderMapper.markPaid(created.getId(), now, now));
        assertEquals(OrderStatus.CREATED, statusOf(created.getId()));

        // 充电中不能重复启动
        ChargingOrder charging = givenOrderAt(memberId, OrderStatus.CHARGING);
        assertEquals(0, orderMapper.markStarted(charging.getId(), now, bd("9999.0000"), 99, now),
                "★ 重复的启动上报必须被挡住 —— 否则会再冻一次钱");
        assertEquals(OrderStatus.CHARGING, statusOf(charging.getId()));

        // 待结算不能直接支付
        ChargingOrder settling = givenOrderAt(memberId, OrderStatus.SETTLING);
        assertEquals(0, orderMapper.markPaid(settling.getId(), now, now));
        assertEquals(OrderStatus.SETTLING, statusOf(settling.getId()));

        // 已支付不能重复支付
        ChargingOrder paid = givenOrderAt(memberId, OrderStatus.PAID);
        assertEquals(0, orderMapper.markPaid(paid.getId(), now, now),
                "★★ 这一步返回 0 就是 WalletService 那条注释里说的「订单侧自己的幂等判断」");
    }

    @Test
    @DisplayName("★★ markPaid：支付失败的订单充值后能【再付一次】（6 → 5）")
    void markPaidShouldAcceptRetryFromPayFailed() {
        long memberId = newMemberId();
        LocalDateTime now = now();

        ChargingOrder failed = givenOrderAt(memberId, OrderStatus.PAY_FAILED);
        assertEquals(1, orderMapper.markPaid(failed.getId(), now, now),
                "★★ 用户充值回来点第二次支付时订单正停在 6 上。"
                        + "只认 4 的话那个按钮永远返回「状态不对」，而钱一直冻着");
        assertEquals(OrderStatus.PAID, statusOf(failed.getId()));
    }

    @Test
    @DisplayName("markSettled：金额三兄弟和 settle_time 一次性写定")
    void markSettledShouldWriteAllAmounts() {
        long memberId = newMemberId();
        ChargingOrder settling = givenOrderAt(memberId, OrderStatus.SETTLING);
        LocalDateTime settleTime = now();

        assertEquals(1, orderMapper.markSettled(settling.getId(),
                bd("36.00"), bd("6.00"), bd("42.00"), bd("2.00"), bd("40.00"),
                settleTime, settleTime));

        ChargingOrder after = orderMapper.getById(settling.getId());
        assertEquals(0, bd("42.00").compareTo(after.getTotalAmount()));
        assertEquals(0, bd("40.00").compareTo(after.getPayAmount()));
        assertNotNull(after.getSettleTime());
        // ★ 恒等式：应付 = 电费 + 服务费；实付 = 应付 − 优惠
        assertEquals(0, after.getTotalAmount().compareTo(after.getEnergyFee().add(after.getServiceFee())));
        assertEquals(0, after.getPayAmount()
                .compareTo(after.getTotalAmount().subtract(after.getDiscountAmount())));
    }

    @Test
    @DisplayName("★★ markAbnormal：钱还冻着的状态都能进 9，但覆盖不了一个终态")
    void markAbnormalShouldNotOverrideTerminalStates() {
        long memberId = newMemberId();
        LocalDateTime now = now();
        List<Integer> holding = OrderStatus.holdingMoneyCodes();

        for (OrderStatus from : List.of(OrderStatus.CREATED, OrderStatus.CHARGING,
                OrderStatus.SETTLING, OrderStatus.PENDING_PAY, OrderStatus.PAY_FAILED)) {
            ChargingOrder order = givenOrderAt(memberId, from);
            assertEquals(1, orderMapper.markAbnormal(order.getId(), holding, "测试原因", now),
                    "从「" + from.getLabel() + "」应该能进异常挂账");
            assertEquals(OrderStatus.ABNORMAL, statusOf(order.getId()));
            assertEquals("测试原因", orderMapper.getById(order.getId()).getRemark(),
                    "★ 没有原因的挂账单只能靠人翻日志");
        }

        // ★★ 4 和 6 恰恰是挂账最常用的两个入口（余额不够、重试仍失败），
        //    用 holdingConnectorCodes()（只有 1/2/3）会把它们挡在外面，
        //    而且影响 0 行之后被当作「状态已经变了」静默跳过
        assertTrue(holding.contains(OrderStatus.PENDING_PAY.getCode()),
                "★★ 待支付必须在挂账来源里 —— 钱冻着、账结不掉，正是要挂的那类");
        assertTrue(holding.contains(OrderStatus.PAY_FAILED.getCode()),
                "★★ 支付失败同理");
        assertTrue(!holding.contains(OrderStatus.PAID.getCode()),
                "★ 已支付的钱已经结清，要退就退款，不该挂账");

        // 终态不该被挂账覆盖
        ChargingOrder cancelled = givenOrderAt(memberId, OrderStatus.CREATED);
        orderMapper.markCancelled(cancelled.getId(), now);
        assertEquals(0, orderMapper.markAbnormal(cancelled.getId(), holding, "迟到的挂账", now),
                "★ 一笔已经取消的订单不该被一个延迟到达的挂账请求改掉");
        assertEquals(OrderStatus.CANCELLED, statusOf(cancelled.getId()));
    }

    @Test
    @DisplayName("bindMemberPackage：只对「待启动且还没绑过」的订单生效，重复调用不改写")
    void bindMemberPackageShouldBeIdempotent() {
        long memberId = newMemberId();
        LocalDateTime now = now();

        ChargingOrder created = givenOrderAt(memberId, OrderStatus.CREATED);
        assertEquals(1, orderMapper.bindMemberPackage(created.getId(), 777L, now));
        assertEquals(777L, orderMapper.getById(created.getId()).getMemberPackageId());

        assertEquals(0, orderMapper.bindMemberPackage(created.getId(), 888L, now),
                "★ 第二次补写必须失败 —— 否则一个延迟请求会换掉已经估过价的权益");
        assertEquals(777L, orderMapper.getById(created.getId()).getMemberPackageId());

        // 已经启动的订单也不能再换权益
        ChargingOrder charging = givenOrderAt(memberId, OrderStatus.CHARGING);
        assertEquals(0, orderMapper.bindMemberPackage(charging.getId(), 999L, now),
                "★ 少了 status = 1 的话，钱已经按旧权益估过价了却换了权益");
        assertNull(orderMapper.getById(charging.getId()).getMemberPackageId());
    }

    // ==================== 分段与事件 ====================

    private ChargingSegment segment(long orderId, int seq, LocalDateTime start) {
        return ChargingSegment.builder()
                .orderId(orderId).seq(seq).periodType(1)
                .startTime(start).endTime(start.plusHours(1)).durationSeconds(3600)
                .energyKwh(bd("10.0000")).energyPrice(bd("1.2000")).servicePrice(bd("0.2000"))
                .energyFee(bd("12.00")).serviceFee(bd("2.00")).amount(bd("14.00"))
                .createTime(now())
                .build();
    }

    @Test
    @DisplayName("★★ uk_segment_order_start：同订单同起点的分段插第二条抛 DuplicateKeyException")
    void duplicateSegmentShouldBeRejected() {
        ChargingOrder order = givenOrderAt(newMemberId(), OrderStatus.SETTLING);
        LocalDateTime start = now().withNano(0);

        segmentMapper.batchInsert(List.of(segment(order.getId(), 1, start)));

        assertThrows(DuplicateKeyException.class,
                () -> segmentMapper.batchInsert(List.of(segment(order.getId(), 2, start))),
                "★★ 这条唯一索引就是结算幂等的落点：算法重跑撞唯一键，"
                        + "而不是把金额翻倍");
    }

    @Test
    @DisplayName("batchInsert：一次写多段，按 seq 升序读回")
    void batchInsertShouldWriteAllSegmentsInOrder() {
        ChargingOrder order = givenOrderAt(newMemberId(), OrderStatus.SETTLING);
        LocalDateTime start = now().withNano(0);

        segmentMapper.batchInsert(List.of(
                segment(order.getId(), 1, start),
                segment(order.getId(), 2, start.plusHours(1)),
                segment(order.getId(), 3, start.plusHours(2))));

        List<ChargingSegment> rows = segmentMapper.listByOrderId(order.getId());
        assertEquals(3, rows.size());
        assertEquals(List.of(1, 2, 3), rows.stream().map(ChargingSegment::getSeq).toList());
        // ★ 恒等式：分段金额 = 电费 + 服务费
        for (ChargingSegment row : rows) {
            assertEquals(0, row.getAmount().compareTo(row.getEnergyFee().add(row.getServiceFee())));
        }
    }

    private ChargingOrderEvent event(Long orderId, String idempotentKey) {
        return ChargingOrderEvent.builder()
                .orderId(orderId).fromStatus(1).toStatus(2)
                .eventType(OrderEventType.START.getCode())
                .source(EventSource.DEVICE.getCode())
                .idempotentKey(idempotentKey)
                .createTime(now())
                .build();
    }

    @Test
    @DisplayName("★★ uk_event_idempotent：设备重复上报撞唯一键；NULL 的事件不受约束")
    void deviceEventShouldBeDeduplicated() {
        ChargingOrder order = givenOrderAt(newMemberId(), OrderStatus.CHARGING);
        String key = "DEV-TEST-" + SEQ.incrementAndGet() + "-1";

        eventMapper.insert(event(order.getId(), key));
        assertThrows(DuplicateKeyException.class,
                () -> eventMapper.insert(event(order.getId(), key)),
                "★ 设备重传同一个事件必须被数据库挡住");

        // ★ 非设备来源的事件 idempotent_key 为 NULL，不受约束
        for (int i = 0; i < 3; i++) {
            eventMapper.insert(event(order.getId(), null));
        }
        assertEquals(4, eventMapper.listByOrderId(order.getId()).size(),
                "★ 唯一索引不约束 NULL —— 这是它在本项目第四次被利用");
    }

    @Test
    @DisplayName("listByOrderId：事件按时间**升序**，同毫秒用 id 保证全序")
    void listEventsShouldBeAscending() {
        ChargingOrder order = givenOrderAt(newMemberId(), OrderStatus.CHARGING);
        LocalDateTime same = now();

        for (int i = 0; i < 3; i++) {
            ChargingOrderEvent e = event(order.getId(), null);
            e.setCreateTime(same);
            eventMapper.insert(e);
        }

        List<ChargingOrderEvent> rows = eventMapper.listByOrderId(order.getId());
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).getId() < rows.get(1).getId(),
                "★ 排查时要读的是「先发生什么」，倒序读一遍因果要自己在脑子里翻过来");
        assertTrue(rows.get(1).getId() < rows.get(2).getId());
    }
}
