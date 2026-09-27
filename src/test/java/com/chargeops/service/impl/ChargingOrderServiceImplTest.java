package com.chargeops.service.impl;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.OrderCreateDTO;
import com.chargeops.dto.OrderEndDTO;
import com.chargeops.dto.OrderStartDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.Connector;
import com.chargeops.entity.MemberPackage;
import com.chargeops.entity.Station;
import com.chargeops.entity.StationTariff;
import com.chargeops.entity.TariffRule;
import com.chargeops.entity.TariffTemplate;
import com.chargeops.entity.Vehicle;
import com.chargeops.entity.Wallet;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.exception.ConnectorOccupiedException;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.mapper.StationTariffMapper;
import com.chargeops.mapper.TariffRuleMapper;
import com.chargeops.mapper.TariffTemplateMapper;
import com.chargeops.mapper.VehicleMapper;
import com.chargeops.mapper.WalletMapper;
import com.chargeops.service.ChargingOrderService;
import com.chargeops.service.StationTariffService;
import com.chargeops.vo.ChargingOrderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ChargingOrderServiceImpl} 的集成测试 —— <b>P1c-3 的核心断言</b>
 *
 * <p>通用写法（为什么是集成测试、为什么不能标 {@code @Transactional}、
 * 为什么每条测试自己造 member 和站点）见
 * {@code WalletServiceImplTest} 的类注释，三条理由完全一样。
 *
 * <h2>本类要钉死的六件事</h2>
 *
 * <ol>
 *   <li>★★ {@link #settleShouldUseTheFrozenTemplateNotTheCurrentOne()} ——
 *       <b>整个 {@code station_tariff} 表存在的理由</b>。下单后调价，
 *       在途订单必须按<b>下单那一刻冻住的价</b>结算</li>
 *   <li>★★ {@link #happyPathShouldMoveMoneyExactlyOnce()} —— 一条订单
 *       从建单走到支付，钱包三列在每一步的数字</li>
 *   <li>★★ {@link #payWithInsufficientBalanceShouldKeepMoneyFrozen()} ——
 *       余额不足时钱<b>冻着</b>而不是消失，且可重试</li>
 *   <li>★ {@link #cancelShouldReleaseFreezeAndPackageLock()} ——
 *       取消要同时放掉钱和权益，少一样都是没人清理的中间态</li>
 *   <li>★ {@link #metreProblemsShouldStillRecordThatChargingHappened()} ——
 *       读数坏了也要把「车被充过」这个事实落库</li>
 *   <li>★ 每一步的重复调用都撞 {@code status} 条件更新，而不是靠唯一索引兜底</li>
 * </ol>
 *
 * <h2>★★ 为什么计费模板用「一条 00:00~24:00 的规则」★★</h2>
 *
 * <p>{@code start()} 的起始时刻取的是服务端 {@code now()}（设备上报的
 * 开始时刻本项目还没有字段承载）。窗口是测试运行的那一刻到
 * {@code endTime}，所以<b>窗口落在哪个电价档是不确定的</b>。
 *
 * <p>用单条覆盖 24 小时的规则，金额就与运行时刻无关：
 * {@code 电费 = 电量 × 单价}，可以写死期望值。
 * 分时段切分的正确性由 {@code BillingEngineTest} 穷举，不在这里重复 ——
 * 这里要证的是订单流程有没有把它算出来的钱<b>正确地写进库、正确地动钱包</b>。
 */
@SpringBootTest
class ChargingOrderServiceImplTest {

    @Autowired
    private ChargingOrderService orderService;

    @Autowired
    private ChargingOrderMapper orderMapper;

    @Autowired
    private MemberPackageMapper memberPackageMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private ConnectorMapper connectorMapper;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private TariffTemplateMapper tariffTemplateMapper;

    @Autowired
    private TariffRuleMapper tariffRuleMapper;

    @Autowired
    private StationTariffMapper stationTariffMapper;

    @Autowired
    private StationTariffService stationTariffService;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试数据的 member_id 下界，收尾时按范围删 */
    private static final long TEST_ID_BASE = 4_000_000_000_000L;

    private static long SEQ = 0;

    /** 计费单价。★ 一条规则覆盖 24 小时 —— 见类注释最后一节 */
    private static final String ENERGY_PRICE = "1.2000";
    private static final String SERVICE_PRICE = "0.3000";

    @AfterEach
    void cleanUp() {
        // 车主相关的表都挂在 member_id 上，而 member.id 是测试自己指定的 —— 按范围删
        jdbcTemplate.update("DELETE FROM charging_order_event WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ?)", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM charging_segment WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ?)", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM member_package WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet_transaction WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM vehicle WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM member WHERE id >= ?", TEST_ID_BASE);

        // ★ 站点 / 桩 / 枪 / 模板的 id 全部由自增分配 —— 建的时候<b>指定不了</b>
        //   （insert 用 useGeneratedKeys，而且列清单里没有 id，
        //   传进去的 id 会被生成值覆盖）。所以这几张表按 code 前缀认领，
        //   而且必须<b>先删引用方再删被引用方</b>：子查询要靠父行还在才查得到 id。
        jdbcTemplate.update("DELETE FROM station_tariff WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE 'ST-ORD-%')");
        jdbcTemplate.update("DELETE FROM connector WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE 'ST-ORD-%')");
        jdbcTemplate.update("DELETE FROM charger WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE 'ST-ORD-%')");
        jdbcTemplate.update("DELETE FROM tariff_rule WHERE template_id IN "
                + "(SELECT id FROM tariff_template WHERE code LIKE 'TT-ORD-%')");
        jdbcTemplate.update("DELETE FROM tariff_template WHERE code LIKE 'TT-ORD-%'");
        jdbcTemplate.update("DELETE FROM station WHERE code LIKE 'ST-ORD-%'");

        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual, String what) {
        assertThat(actual).as("%s（期望 %s，实际 %s）", what, expected, actual).isNotNull();
        assertThat(actual.compareTo(bd(expected)))
                .as("%s（期望 %s，实际 %s）", what, expected, actual).isZero();
    }

    /**
     * 造一个车主并登入上下文
     *
     * <p>★ {@code assertActive} 要求 {@code member.status = 1}，
     * 所以必须是一行真 member —— {@code member_id} 上没有外键，
     * 但「账号可用」这个判断是真的会去查的。
     */
    private long loginAsNewMember() {
        long memberId = TEST_ID_BASE + (SEQ++);
        jdbcTemplate.update(
                "INSERT INTO member (id, phone, nickname, status) VALUES (?, ?, ?, 1)",
                memberId, "138" + memberId, "订单测试车主");
        BaseContext.setCurrentId(memberId);
        return memberId;
    }

    /** 给钱包塞初始余额。★ {@code frozen_amount} 从 0 出发，冻了多少由断言自己看 */
    private void givenBalance(long memberId, String balance) {
        LocalDateTime now = LocalDateTime.now();
        walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(bd(balance))
                .frozenAmount(BigDecimal.ZERO).totalRecharge(bd(balance))
                .version(0).createTime(now).updateTime(now).build());
    }

    private Wallet wallet(long memberId) {
        return walletMapper.getByMemberId(memberId);
    }

    /** 造一个车主的车 */
    private Vehicle givenVehicle(long memberId, String capacityKwh) {
        Vehicle vehicle = Vehicle.builder()
                .memberId(memberId)
                .plateNo("京A" + (SEQ++))
                .batteryCapacityKwh(capacityKwh == null ? null : bd(capacityKwh))
                .isDefault(0)
                .build();
        vehicleMapper.insert(vehicle);
        return vehicle;
    }

    /**
     * 造一份计费模板，含一条覆盖 24 小时的规则
     *
     * <p>★ {@code code} 用 {@code TT-ORD-} 前缀 —— 收尾时靠它认领，
     * 因为 id 是自增分配的，测试指定不了。
     */
    private Long givenTemplate(String energyPrice, String servicePrice) {
        LocalDateTime now = LocalDateTime.now();
        long tag = SEQ++;

        TariffTemplate template = TariffTemplate.builder()
                .code("TT-ORD-" + tag).name("订单测试模板" + tag)
                .region("测试市").status(1).build();
        tariffTemplateMapper.insert(template);

        tariffRuleMapper.batchInsert(java.util.List.of(TariffRule.builder()
                .templateId(template.getId()).seq(1).periodType(3)
                .startTime("00:00:00").endTime("24:00:00")
                .energyPrice(bd(energyPrice)).servicePrice(bd(servicePrice))
                .createTime(now).updateTime(now)
                .build()));
        return template.getId();
    }

    /** 把模板绑到站点上，{@code effectiveFrom} 取一个已经过去的时刻 */
    private void bindTariff(long stationId, Long templateId) {
        LocalDateTime now = LocalDateTime.now();
        stationTariffMapper.insert(StationTariff.builder()
                .stationId(stationId).templateId(templateId)
                .effectiveFrom(now.minusMinutes(1)).effectiveTo(null)
                .priority(0).status(1)
                .createTime(now).updateTime(now)
                .build());
    }

    /**
     * 并发建单的一个线程体 —— 只为 {@link #concurrentCreateShouldProduceExactlyOneOrder} 服务
     *
     * <p>★ 抽成方法而不是内联 lambda：五个出参容器（计数、赢家、失败原因）在
     * lambda 里会让那段测试完全读不出来。它们都是并发安全的收集器。
     */
    private void attemptCreate(long memberId, OrderCreateDTO dto,
                               java.util.concurrent.CountDownLatch start,
                               java.util.concurrent.atomic.AtomicInteger ok,
                               java.util.concurrent.atomic.AtomicInteger rejected,
                               java.util.List<Long> winners,
                               java.util.List<String> failures) {
        // ★ 必须是本线程自己设 —— BaseContext 是 ThreadLocal，
        //   主线程设的在子线程里读不到，表现是 UserNotLoginException
        BaseContext.setCurrentId(memberId);
        try {
            start.await();
            ChargingOrderVO vo = orderService.create(dto);
            winners.add(vo.getId());
            ok.incrementAndGet();
        } catch (ConnectorOccupiedException | DataConflictException e) {
            // ★ 三种「正确拒绝」都落在这一支里，而它们由不同的关卡抛出：
            //     · 对方还没提交 → ② assertConnectorFree 看到进行中的订单；
            //     · 对方已提交   → ① assertConnectorUsable 看到枪不空闲；
            //     · 正好在窗口中间 → ③ occupy 影响 0 行。
            //   ★ 到底哪一道拦住的取决于线程调度，**不能**断言具体异常 ——
            //     只认其中一种的断言会变成偶尔红一次的那种测试。
            //     这里断的是【结果】（恰好一笔成功），不是【谁拦的】。
            rejected.incrementAndGet();
        } catch (Exception e) {
            failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            BaseContext.removeCurrentId();
        }
    }

    /** 一个可用站点（站 + 桩 + 枪 + 计费模板），返回那把枪 */
    private Connector givenUsableConnector() {
        long tag = SEQ++;
        LocalDateTime now = LocalDateTime.now();

        Station station = Station.builder()
                .code("ST-ORD-" + tag).name("订单测试站点" + tag)
                .openTime("00:00:00").closeTime("24:00:00").status(1).build();
        stationMapper.insert(station);

        Charger charger = Charger.builder()
                .stationId(station.getId()).deviceNo("DEV-ORD-" + tag).name("订单测试桩")
                .type(1).ratedPowerKw(bd("120.00")).gunCount(0).status(1).build();
        chargerMapper.insert(charger);

        // ★ ConnectorMapper.insert 没有 @AutoFill，两个时间必须手工给
        Connector connector = Connector.builder()
                .chargerId(charger.getId()).stationId(station.getId())
                .gunNo("ORD-" + tag).connectorType("GB/T 20234.3")
                .ratedPowerKw(bd("120.00")).status(1).build();
        connector.setCreateTime(now);
        connector.setUpdateTime(now);
        connectorMapper.insert(connector);

        bindTariff(station.getId(), givenTemplate(ENERGY_PRICE, SERVICE_PRICE));
        return connector;
    }

    private OrderCreateDTO createDto(Connector connector, Long vehicleId) {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setStationId(connector.getStationId());
        dto.setChargerId(connector.getChargerId());
        dto.setConnectorId(connector.getId());
        dto.setVehicleId(vehicleId);
        return dto;
    }

    /** 建一笔订单（走真实的 create），返回订单 id */
    private Long givenOrder(Connector connector, Long vehicleId) {
        ChargingOrderVO vo = orderService.create(createDto(connector, vehicleId));
        return vo.getId();
    }

    private OrderStartDTO startDto(String startMeterKwh) {
        OrderStartDTO dto = new OrderStartDTO();
        dto.setStartMeterKwh(startMeterKwh == null ? null : bd(startMeterKwh));
        dto.setStartSoc(30);
        return dto;
    }

    /**
     * 结束上报
     *
     * <p>★★ {@code endTime} 必须显式给、而且离 {@code start()} 那一刻
     * <b>至少一秒</b>。★★
     *
     * <p>{@code start()} 的起始时刻取服务端 {@code now()}，所以只调
     * {@code end()` 的话窗口就是「几毫秒」—— 而 {@code BillingRequest}
     * 会把起止时间截到<b>整秒</b>，于是窗口塌成 {@code [T, T)}，
     * 计费引擎拒收（「结束时间必须晚于开始时间」）。
     *
     * <p>给一个小时既让窗口健康，又因为模板只有一条覆盖 24 小时的规则，
     * 金额仍然与运行时刻无关。见类注释最后一节。
     */
    private OrderEndDTO endDto(String endMeterKwh, Integer stopReason) {
        OrderEndDTO dto = new OrderEndDTO();
        dto.setEndMeterKwh(endMeterKwh == null ? null : bd(endMeterKwh));
        dto.setEndSoc(80);
        dto.setStopReason(stopReason);
        dto.setEndTime(LocalDateTime.now().plusHours(1));
        return dto;
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMapper.getById(orderId).getStatus());
    }

    private long segmentCount(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_segment WHERE order_id = ?", Long.class, orderId);
    }

    private long eventCount(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_order_event WHERE order_id = ?", Long.class, orderId);
    }

    // ==================== 建单 ====================

    @Test
    @DisplayName("建单：冻住计费模板 id、冻住预估金额、订单停在「待启动」")
    void createShouldFreezeTemplateAndMoney() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Vehicle vehicle = givenVehicle(memberId, "50.00");
        Connector connector = givenUsableConnector();

        ChargingOrderVO vo = orderService.create(createDto(connector, vehicle.getId()));

        ChargingOrder order = orderMapper.getById(vo.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED.getCode());
        assertThat(order.getTariffTemplateId()).as("★ 计费模板必须被冻在订单上").isNotNull();
        // ★ 车牌是快照，不是每次 JOIN vehicle 取的实时值
        assertThat(order.getPlateNo()).isEqualTo(vehicle.getPlateNo());

        // 预估 = 容量 50 × 最贵单价 1.50。★ 用最贵算，实付必然小于等于它
        assertAmount("75.00", order.getFrozenAmount(), "预估冻结额");

        Wallet w = wallet(memberId);
        assertAmount("425.00", w.getBalance(), "可用余额（500 − 75）");
        assertAmount("75.00", w.getFrozenAmount(), "冻结（钱没被扣掉，只是被占住）");
        assertAmount("500.00", w.getTotalRecharge(), "★ 冻结不动「累计充值」这个历史事实");

        assertThat(eventCount(vo.getId())).as("建单要留一条事件").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 预估冻结额压上限：容量填成 500 度也只会冻 500 元")
    void createShouldCapTheFreezeAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100000.00");
        // 容量是用户自己填的字段 —— 填成 99999 会把余额一次冻光
        Vehicle vehicle = givenVehicle(memberId, "99999.00");

        ChargingOrderVO vo = orderService.create(createDto(givenUsableConnector(), vehicle.getId()));

        // 99999 × 1.50 远超上限 500.00
        assertAmount("500.00", orderMapper.getById(vo.getId()).getFrozenAmount(),
                "预估冻结额（应被 MAX_FREEZE_AMOUNT 压住）");
    }

    @Test
    @DisplayName("★ 余额不够就建不出单：订单和冻结一起回滚，不会留下半拉子状态")
    void createShouldRollBackEverythingWhenBalanceIsNotEnough() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "10.00");
        Vehicle vehicle = givenVehicle(memberId, "50.00");
        Connector connector = givenUsableConnector();

        assertThatThrownBy(() -> orderService.create(createDto(connector, vehicle.getId())))
                .isInstanceOf(BalanceNotEnoughException.class);

        // ★ 整个事务回滚：订单没建出来，钱也没被冻住
        Long orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_order WHERE member_id = ?", Long.class, memberId);
        assertThat(orders).as("★ 不能留下一笔「建了单但没冻钱」的订单").isZero();
        assertAmount("10.00", wallet(memberId).getBalance(), "余额原封不动");
        assertAmount("0.00", wallet(memberId).getFrozenAmount(), "冻结也应回滚");
    }

    @Test
    @DisplayName("建单：明确拒绝微信/支付宝，不静默降级成余额扣款")
    void createShouldRejectUnsupportedPayMethod() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        OrderCreateDTO dto = createDto(connector, null);
        dto.setPayMethod(2);

        // ★ 「微信支付」按钮点了之后从余额扣钱，比没有这个按钮严重得多
        assertThatThrownBy(() -> orderService.create(dto))
                .isInstanceOf(ParamErrorException.class);
    }

    @Test
    @DisplayName("★ 同一把枪同时只能有一笔进行中的订单")
    void createShouldRejectAnOccupiedConnector() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        givenOrder(connector, null);

        assertThatThrownBy(() -> orderService.create(createDto(connector, null)))
                .isInstanceOf(DataConflictException.class);
    }

    @Test
    @DisplayName("★ 同一辆车同时只能有一笔进行中的订单 —— 否则会拿到两笔满额订单")
    void createShouldRejectABusyVehicle() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Vehicle vehicle = givenVehicle(memberId, "50.00");

        givenOrder(givenUsableConnector(), vehicle.getId());

        // ★ 换一把枪，同一辆车。拦不住的话设备只充了一次电，用户拿到两笔订单
        assertThatThrownBy(() -> orderService.create(
                createDto(givenUsableConnector(), vehicle.getId())))
                .isInstanceOf(DataConflictException.class);
    }

    @Test
    @DisplayName("★★ 下单之后调价，在途订单仍按【冻住的那个模板】结算")
    void settleShouldUseTheFrozenTemplateNotTheCurrentOne() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        // ① 用当前模板（电费 1.20 / 服务费 0.30）建单
        Long orderId = givenOrder(connector, null);
        Long frozenTemplate = orderMapper.getById(orderId).getTariffTemplateId();

        // ② 站点调价：换一个「贵十倍」的模板，立刻生效
        Long expensive = givenTemplate("99.0000", "99.0000");
        bindTariff(connector.getStationId(), expensive);

        // ★ 先证明确实换掉了 —— 否则这条测试可能因为「新模板压根没生效」而白绿
        assertThat(stationTariffService.resolveTemplateIdAt(
                connector.getStationId(), LocalDateTime.now()))
                .as("前置条件：此刻站点解析出来的应该是新模板")
                .isEqualTo(expensive);
        assertThat(frozenTemplate).as("前置条件：冻的确实是旧模板").isNotEqualTo(expensive);

        // ③ 充 10 度电走完流程
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        // ★★ 必须是旧价：10 × 1.20 = 12.00 电费，10 × 0.30 = 3.00 服务费。
        //   按新价会是 990.00 —— 用户看到的金额和启动时预估的不一样，而且没法解释。
        //   这一条就是 station_tariff 整张表存在的全部意义。
        ChargingOrder after = orderMapper.getById(orderId);
        assertAmount("12.00", after.getEnergyFee(), "电费（应按冻住的模板）");
        assertAmount("3.00", after.getServiceFee(), "服务费（应按冻住的模板）");
        assertAmount("15.00", after.getTotalAmount(), "应付（应按冻住的模板）");
    }

    // ==================== 全链路 ====================

    @Test
    @DisplayName("★★ 全链路：建单 → 启动 → 结束 → 结算 → 支付，每一步的钱都对得上")
    void happyPathShouldMoveMoneyExactlyOnce() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Vehicle vehicle = givenVehicle(memberId, "50.00");
        Connector connector = givenUsableConnector();

        // ① 建单：冻 75.00（容量 50 × 最贵单价 1.50）
        Long orderId = givenOrder(connector, vehicle.getId());
        assertAmount("425.00", wallet(memberId).getBalance(), "建单后可用余额");
        assertAmount("75.00", wallet(memberId).getFrozenAmount(), "建单后冻结");

        // ② 启动：★ 不动钱。钱在建单时已经冻好了
        orderService.start(orderId, startDto("1000.0000"));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CHARGING);
        assertAmount("425.00", wallet(memberId).getBalance(), "★ 启动不该动余额");
        assertAmount("75.00", wallet(memberId).getFrozenAmount(), "★ 启动不该动冻结");

        // ③ 结束：★ 仍然不动钱，只写「充电结束了」这个事实
        orderService.end(orderId, endDto("1010.0000", 1));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.SETTLING);
        ChargingOrder settled = orderMapper.getById(orderId);
        assertAmount("10.0000", settled.getTotalEnergyKwh(),
                "★ 电量 = 电表差 1010 − 1000，不是结束读数 1010");
        assertAmount("75.00", wallet(memberId).getFrozenAmount(), "★ 结束也不动钱");

        // ④ 结算：算出金额、写分段，★ 还是不动钱
        orderService.settle(orderId);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING_PAY);
        assertAmount("75.00", wallet(memberId).getFrozenAmount(),
                "★★ 结算只算账不动钱 —— 动钱的是支付那一步");
        assertThat(segmentCount(orderId)).as("分段明细已落库").isEqualTo(1);

        ChargingOrder pending = orderMapper.getById(orderId);
        assertAmount("12.00", pending.getEnergyFee(), "电费 10 度 × 1.20");
        assertAmount("3.00", pending.getServiceFee(), "服务费 10 度 × 0.30");
        assertAmount("15.00", pending.getTotalAmount(), "应付");
        assertAmount("15.00", pending.getPayAmount(), "实付（无权益抵扣）");

        // ⑤ 支付：先解冻 75，再扣 15。余额回到 500 − 15 = 485
        orderService.pay(orderId);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);

        Wallet w = wallet(memberId);
        assertAmount("485.00", w.getBalance(), "支付后余额（500 − 15）");
        assertAmount("0.00", w.getFrozenAmount(), "★ 冻结必须清零，不能留一笔挂着");
        assertAmount("500.00", w.getTotalRecharge(), "累计充值不变");

        // ★ 流水：建单冻结 1 条 + 支付时解冻 1 条 + 扣款 1 条 = 3 条
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE order_id = ?", Long.class, orderId))
                .as("★★ 一笔订单在钱包侧的流水条数是恒定的 —— 多一条就是重复扣款")
                .isEqualTo(3);

        // ★ 事件链：create → start → end → settle → pay
        assertThat(eventCount(orderId)).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 取消：退回冻结 + 释放权益锁，不消耗任何权益")
    void cancelShouldReleaseFreezeAndPackageLock() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        MemberPackage pack = givenEnergyPackage(memberId, "150.00");
        // ★ 带上车（容量 50）—— 不带车时冻结额按默认容量 60 算，是 90.00 而不是 75.00
        Vehicle vehicle = givenVehicle(memberId, "50.00");

        Long orderId = givenOrder(connector, vehicle.getId());
        assertThat(orderMapper.getById(orderId).getMemberPackageId())
                .as("前置条件：订单应已锁住权益").isEqualTo(pack.getId());
        assertAmount("425.00", wallet(memberId).getBalance(), "前置条件：钱已冻住");

        orderService.cancel(orderId);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertAmount("500.00", wallet(memberId).getBalance(), "★ 冻结全额退回可用余额");
        assertAmount("0.00", wallet(memberId).getFrozenAmount(), "冻结清零");

        MemberPackage after = memberPackageMapper.getById(pack.getId());
        assertThat(after.getLockedOrderId()).as("★ 权益锁必须释放").isNull();
        assertAmount("150.00", after.getRemainingKwh(), "★ 取消不消耗权益，余量一点不动");
        assertThat(after.getStatus()).as("权益仍然是生效中").isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 充电中的订单不能被取消 —— 那是「结束」，不是「作废」")
    void cancelShouldBeRejectedWhileCharging() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        Vehicle vehicle = givenVehicle(memberId, "50.00");

        Long orderId = givenOrder(connector, vehicle.getId());
        orderService.start(orderId, startDto("1000.0000"));

        assertThatThrownBy(() -> orderService.cancel(orderId))
                .isInstanceOf(DataConflictException.class);
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CHARGING);
        assertAmount("75.00", wallet(memberId).getFrozenAmount(), "钱不能被偷偷退回去");
    }

    // ==================== 幂等 / 冲突 ====================

    @Test
    @DisplayName("★★ 重复结算撞订单状态的条件更新，不会插出第二遍分段")
    void settleTwiceShouldConflict() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        assertThatThrownBy(() -> orderService.settle(orderId))
                .isInstanceOf(DataConflictException.class);

        assertThat(segmentCount(orderId)).as("★★ 分段数必须还是 1 —— 重复结算不能翻倍计费")
                .isEqualTo(1);
        assertAmount("15.00", orderMapper.getById(orderId).getTotalAmount(), "金额不能翻倍");
    }

    @Test
    @DisplayName("★★ 重复支付撞订单状态的条件更新 —— 钱包的唯一索引不该被触发")
    void payTwiceShouldConflict() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);
        orderService.pay(orderId);

        // ★ 这一步必须在订单侧就被挡住。要是让它走到钱包，
        //   撞的是 uk_txn_order_type，那个异常抛出时事务已经被标记回滚了
        assertThatThrownBy(() -> orderService.pay(orderId))
                .isInstanceOf(DataConflictException.class);

        assertAmount("485.00", wallet(memberId).getBalance(), "★★ 只扣一次");
    }

    @Test
    @DisplayName("★★ 余额不足：订单停在「待支付」、钱【冻着】、可重试")
    void payWithInsufficientBalanceShouldKeepMoneyFrozen() {
        long memberId = loginAsNewMember();
        // ★ 造出「扣款失败」要满足 balance < 实付金额。
        //   光是「余额 = 冻结额」不够 —— walletService.settle 是
        //   【先解冻、后扣款】，那 7.50 解冻之后就回到可用余额里，
        //   足够付掉 15.00 里的一部分……不够的部分才由余额补。
        //   所以余额必须小于实付本身：这里 10.00 < 15.00。
        givenBalance(memberId, "10.00");
        Connector connector = givenUsableConnector();
        // 小电池 → 预估冻结只有 5 × 1.50 = 7.50，冻得起
        Vehicle vehicle = givenVehicle(memberId, "5.00");

        Long orderId = givenOrder(connector, vehicle.getId());
        assertAmount("7.50", orderMapper.getById(orderId).getFrozenAmount(), "预估冻结额");
        assertAmount("2.50", wallet(memberId).getBalance(), "冻完还剩 2.50");

        // ★ 实际用了 10 度 = 15.00 元，比预估的 7.50 多 —— 预估是按容量算的，
        //   这正是 WalletService.settle 允许「实付 > 冻结」的那种情况
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);
        assertAmount("15.00", orderMapper.getById(orderId).getPayAmount(), "实付");

        assertThatThrownBy(() -> orderService.pay(orderId))
                .isInstanceOf(BalanceNotEnoughException.class);

        // ★★ 整个事务回滚：订单退回「待支付」，钱退回冻结。
        //   既没扣掉也没丢 —— 4 待支付 是一个安全的停留状态，可以重试。
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING_PAY);
        Wallet w = wallet(memberId);
        assertAmount("7.50", w.getFrozenAmount(), "★★ 钱还在冻结里，没丢");
        assertAmount("2.50", w.getBalance(), "可用余额没被动过");

        // ★ 充值后重试：这次能过。4 待支付 是安全的停留状态，钱一直在冻结里
        jdbcTemplate.update("UPDATE wallet SET balance = 100.00 WHERE member_id = ?", memberId);
        orderService.pay(orderId);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
        // 100 里解冻回 7.50 → 107.50，扣 15.00
        assertAmount("92.50", wallet(memberId).getBalance(), "100 + 7.50 − 15.00");
        assertAmount("0.00", wallet(memberId).getFrozenAmount(), "冻结清零");
    }

    @Test
    @DisplayName("★ 支付失败记录写在新事务里：pay 回滚之后 markPayFailed 仍然写得进去")
    void markPayFailedShouldWriteInItsOwnTransaction() {
        long memberId = loginAsNewMember();
        // 余额 10.00 < 实付 15.00 —— 见上一条测试对「什么情况下 pay 会失败」的说明
        givenBalance(memberId, "10.00");
        Connector connector = givenUsableConnector();
        Vehicle vehicle = givenVehicle(memberId, "5.00");

        Long orderId = givenOrder(connector, vehicle.getId());
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        try {
            orderService.pay(orderId);
        } catch (BalanceNotEnoughException e) {
            // ★ Controller 里的那个 catch 就是这个形状
            orderService.markPayFailed(orderId, e.getMessage());
        }

        ChargingOrder after = orderMapper.getById(orderId);
        assertThat(OrderStatus.of(after.getStatus())).isEqualTo(OrderStatus.PAY_FAILED);
        assertThat(after.getRemark()).as("原因必须留痕").contains("余额");
        assertAmount("7.50", wallet(memberId).getFrozenAmount(), "★ 失败不吞钱，冻结原封不动");
    }

    @Test
    @DisplayName("★ markPayFailed 在订单已经不是「待支付」时不报错 —— 它记的是「这次尝试失败了」")
    void markPayFailedShouldBeSilentWhenStatusMovedOn() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);
        orderService.pay(orderId);

        // ★ 影响行数为 0 是正常的（别的线程已经把它推到 5 了），所以不抛异常
        orderService.markPayFailed(orderId, "迟到的失败回执");

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID);
    }

    // ==================== 电表读数 ====================

    @Test
    @DisplayName("★★ 起始读数缺失：「充电结束了」照常落库，但订单转异常挂账")
    void missingStartReadingShouldStillRecordThatChargingHappened() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        // ★ 设备没上报起始读数 —— 留 NULL，不要填 0
        orderService.start(orderId, startDto(null));
        orderService.end(orderId, endDto("1010.0000", 1));

        ChargingOrder after = orderMapper.getById(orderId);
        assertThat(OrderStatus.of(after.getStatus())).isEqualTo(OrderStatus.ABNORMAL);
        assertThat(after.getRemark()).as("★ 没有原因的挂账单只能靠人翻日志")
                .contains("缺少电表读数");
        // ★ 两个事实都在：车确实被充过（end_time 有值），电量算不出来（为 NULL）
        assertThat(after.getEndTime()).as("★「充电结束」这个物理事实不能被丢掉").isNotNull();
        assertThat(after.getTotalEnergyKwh()).as("★ 算不出来就留 NULL，不能填 0").isNull();
    }

    @Test
    @DisplayName("★★ 结束读数倒退：同样保事实 + 转挂账，且电量不被算成负数")
    void regressedReadingShouldNotProduceNegativeEnergy() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        // 电表读数倒退 —— 换表 / 设备时钟错乱 / 上报串了订单
        orderService.end(orderId, endDto("900.0000", 4));

        ChargingOrder after = orderMapper.getById(orderId);
        assertThat(OrderStatus.of(after.getStatus())).isEqualTo(OrderStatus.ABNORMAL);
        assertThat(after.getRemark()).contains("小于起始读数");
        assertThat(after.getTotalEnergyKwh())
                .as("★★ 电量绝不能是负数 —— 那会算出一笔倒找钱的订单").isNull();
    }

    @Test
    @DisplayName("★ 异常挂账的订单不能再被结算")
    void abnormalOrderShouldNotBeSettleable() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("900.0000", 4));

        assertThatThrownBy(() -> orderService.settle(orderId))
                .isInstanceOf(DataConflictException.class);
        assertThat(segmentCount(orderId)).isZero();
    }

    // ==================== 退款 ====================

    @Test
    @DisplayName("★ 退款：只有「已支付」能退，钱回到可用余额")
    void refundShouldOnlyWorkFromPaid() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        // ★ 「待支付」的时候钱还没扣 —— 那时候要退的是冻结，走取消 / 挂账
        assertThatThrownBy(() -> orderService.refund(orderId, bd("15.00")))
                .isInstanceOf(DataConflictException.class);

        orderService.pay(orderId);
        assertAmount("485.00", wallet(memberId).getBalance(), "支付后");

        orderService.refund(orderId, bd("15.00"));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.REFUNDED);
        assertAmount("500.00", wallet(memberId).getBalance(), "★ 全额退回，回到起点");

        // ★ 重复退款被订单状态挡住，不会退第二次
        assertThatThrownBy(() -> orderService.refund(orderId, bd("15.00")))
                .isInstanceOf(DataConflictException.class);
        assertAmount("500.00", wallet(memberId).getBalance(), "★★ 只退一次");
    }

    // ==================== 权益抵扣 ====================

    /** 造一份电量包并直接插入，不走购买流程 */
    private MemberPackage givenEnergyPackage(long memberId, String kwh) {
        LocalDateTime now = LocalDateTime.now();
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(1).packageName("150度电包")
                .totalKwh(bd(kwh)).remainingKwh(bd(kwh))
                .effectiveFrom(now.minusDays(1)).expireTime(now.plusDays(89))
                .status(1).version(0)
                .createTime(now).updateTime(now)
                .build();
        memberPackageMapper.insert(mp);
        return mp;
    }

    /** 造一份服务费折扣包 */
    private MemberPackage givenDiscountPackage(long memberId, String factor) {
        LocalDateTime now = LocalDateTime.now();
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId).packageId(3L).packageType(3).packageName("服务费八折卡")
                .serviceDiscount(bd(factor))
                .effectiveFrom(now.minusDays(1)).expireTime(now.plusDays(89))
                .status(1).version(0)
                .createTime(now).updateTime(now)
                .build();
        memberPackageMapper.insert(mp);
        return mp;
    }

    /** 造一份时长包 */
    private MemberPackage givenDurationPackage(long memberId, int minutes) {
        LocalDateTime now = LocalDateTime.now();
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId).packageId(2L).packageType(2).packageName("时长包")
                .totalMinutes(minutes).remainingMinutes(minutes)
                .effectiveFrom(now.minusDays(1)).expireTime(now.plusDays(89))
                .status(1).version(0)
                .createTime(now).updateTime(now)
                .build();
        memberPackageMapper.insert(mp);
        return mp;
    }

    @Test
    @DisplayName("★★ 电量包足量：抵扣全部电费，服务费照收（产品假设）")
    void energyPackageShouldCoverTheEnergyFeeOnly() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        MemberPackage pack = givenEnergyPackage(memberId, "150.00");
        Vehicle vehicle = givenVehicle(memberId, "50.00");

        Long orderId = givenOrder(connector, vehicle.getId());
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        // ★ 建单时冻的是「无权益」的 75.00（权益要到结算才知道够不够）
        assertAmount("75.00", orderMapper.getById(orderId).getFrozenAmount(), "冻结额");

        orderService.settle(orderId);

        ChargingOrder after = orderMapper.getById(orderId);
        assertAmount("15.00", after.getTotalAmount(), "应付不变");
        // ★ 剩余 150 > 用量 10，覆盖率封顶 1 → 抵扣 = 电费 12.00
        assertAmount("12.00", after.getDiscountAmount(), "★ 抵扣的是电费");
        assertAmount("3.00", after.getPayAmount(), "★ 服务费照收（权益不覆盖服务费）");

        // 权益按实际用量扣减，锁被释放
        MemberPackage packAfter = memberPackageMapper.getById(pack.getId());
        assertAmount("140.00", packAfter.getRemainingKwh(), "★ 扣的是实际用量 10 度");
        assertThat(packAfter.getLockedOrderId()).as("结算要释放锁").isNull();
        assertThat(packAfter.getStatus()).isEqualTo(1);

        // ★ 实付 3.00 < 冻结 75.00，差额退回余额
        orderService.pay(orderId);
        assertAmount("497.00", wallet(memberId).getBalance(), "500 − 3");
        assertAmount("0.00", wallet(memberId).getFrozenAmount(), "冻结清零");
    }

    @Test
    @DisplayName("★ 电量包不够：按覆盖率抵扣，不能超过电费（否则实付变负数）")
    void partialCoverageShouldNeverExceedTheEnergyFee() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        // 只剩 4 度，实际要用 10 度 → 覆盖率 0.4
        MemberPackage pack = givenEnergyPackage(memberId, "4.00");

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        ChargingOrder after = orderMapper.getById(orderId);
        // ★ 覆盖率 = min(实际, 剩余) / 实际 = 4/10 = 0.4
        //   抵扣 = round2(电费 12.00 × 0.4) = 4.80
        assertAmount("4.80", after.getDiscountAmount(), "★ 按覆盖率抵扣，用多少抵多少");
        assertAmount("10.20", after.getPayAmount(), "15.00 − 4.80");

        MemberPackage packAfter = memberPackageMapper.getById(pack.getId());
        assertAmount("0.00", packAfter.getRemainingKwh(), "余量刚好用尽");
        assertThat(packAfter.getStatus()).as("★ 用尽由 SQL 的 CASE 自动置 2").isEqualTo(2);
    }

    @Test
    @DisplayName("★ 折扣包：只打服务费，不动电费")
    void discountPackageShouldOnlyDiscountTheServiceFee() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        givenDiscountPackage(memberId, "0.8000");

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("1000.0000"));
        orderService.end(orderId, endDto("1010.0000", 1));
        orderService.settle(orderId);

        ChargingOrder after = orderMapper.getById(orderId);
        // 抵扣 = round2(服务费 3.00 × (1 − 0.8)) = 0.60
        assertAmount("0.60", after.getDiscountAmount(), "★ 八折 = 让掉两成服务费");
        assertAmount("14.40", after.getPayAmount(), "15.00 − 0.60");
    }

    @Test
    @DisplayName("★★ 手上只有时长包（没有电量包）时，权益不能被静默跳过")
    void durationPackageShouldBePickedWhenNoEnergyPackageExists() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        // ★ 只给时长包。曾经 tryLockPackage 里有个无条件的 break，
        //   会因为「电量包这一类是空的」而直接跳出循环 ——
        //   用户的时长包永远锁不上，而且什么都不报
        MemberPackage pack = givenDurationPackage(memberId, 600);

        Long orderId = givenOrder(connector, null);

        assertThat(orderMapper.getById(orderId).getMemberPackageId())
                .as("★★ 时长包没被锁上 —— 检查 tryLockPackage 是不是按类型一路试下去")
                .isEqualTo(pack.getId());
    }

    // ========================================================================
    // 枪口占用（connector.current_order_id）
    // ========================================================================

    @Test
    @DisplayName("★★ 建单会真的把枪占上：status=2 且 current_order_id = 订单 id")
    void createShouldOccupyTheConnector() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        Long orderId = givenOrder(connector, null);

        // ★★ 这一条断言在 P1c 收尾之前是【不成立】的：
        //   `current_order_id` 全项目没有任何写入，`uk_connector_current_order`
        //   这个唯一索引因此从来没有被触发过，「一把枪同时只能服务一笔订单」
        //   完全靠一次 SELECT（assertConnectorFree）撑着 —— 而没有约束。
        //   三处注释（RedisConstant / ConnectorOccupiedException / ConnectorStatus）
        //   描述的那个 `UPDATE ... WHERE status=1 AND current_order_id IS NULL`
        //   当时并不存在，它们是关于一份不存在的代码的说明。
        Connector reloaded = connectorMapper.getById(connector.getId());
        assertThat(reloaded.getCurrentOrderId())
                .as("★ 枪没被占上 —— current_order_id 是「这把枪归谁」的唯一记录，"
                        + "它为空意味着唯一索引 uk_connector_current_order 保护不了任何东西")
                .isEqualTo(orderId);
        assertThat(reloaded.getStatus())
                .as("★ status 要一起变成 2 已占用 —— 只写 current_order_id 的话，"
                        + "管理端和无障碍检查看到的都是一把「空闲」的枪")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★★ 两道预检都放行、只有 occupy 能拦的那一态：枪自己的 current_order_id 说了算")
    void createShouldBeDecidedByTheConditionalUpdateNotTheSelect() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        // ★★ 先说明这一态为什么必须是【这一态】。
        //
        //   建单路径上有三道关卡，只有第三道是裁决者：
        //     ① assertConnectorUsable —— 查枪自己的 status，要求 = 1 空闲
        //     ② assertConnectorFree   —— 查【订单表】有没有进行中的订单指着这把枪
        //     ③ occupy                —— 条件更新：WHERE status = 1 AND current_order_id IS NULL
        //
        //   要让 ③ 成为唯一能拦住的那一道，就得让 ①②都过：
        //     · status 必须是 1（否则 ① 就抛了，而且抛的是「当前：已占用」这种
        //       带状态的文案 —— 这一条我最初写错过，见下）；
        //     · 订单表里不能有进行中的订单（否则 ② 抛）；
        //     · 但 current_order_id 必须非空。
        //
        //   ★ 这个组合不是硬凑的，它是「订单表说枪闲着、枪自己说有人」的漂移态，
        //     两个真实来源：
        //       · 有人直接改过库，或者按老版本（不写 current_order_id）跑的
        //         订单把状态推到了终态，留下了这一列没人清理；
        //       · 更要紧的是：**这正是并发建单时另一个请求刚做完第 ⑦ 步的样子。**
        //         两个请求同时查到「枪空闲、没有订单」，然后一个 occupy 成功、
        //         另一个必须失败 —— 而 ② 给不出这个答案，它俩查到的一样。
        //
        //   ★ 我第一版把状态写成 2 已占用，结果 ① 就抛了 DataConflictException。
        //     那是**代码比我设想的更早拦住**，不是 bug —— 测试前提错了，
        //     所以改的是测试，不是放宽断言。
        jdbcTemplate.update("UPDATE connector SET current_order_id = ? WHERE id = ?",
                999999L, connector.getId());

        assertThatThrownBy(() -> orderService.create(createDto(connector, null)))
                .as("★ ①②都放行了，③ 必须影响 0 行并抛 ConnectorOccupiedException。"
                        + "如果这里抛的是别的异常、或者干脆建单成功了，"
                        + "说明 make-or-break 的那条条件更新被挪走了或被弱化了 —— "
                        + "而那意味着两个并发建单会各自成功，两笔单往同一把枪里充电量")
                .isInstanceOf(ConnectorOccupiedException.class);
    }

    @Test
    @DisplayName("★★★ 两个并发建单只有一笔能成 —— 这是「一把枪同时只能有一笔订单」的真实验收")
    void concurrentCreateShouldProduceExactlyOneOrder() throws Exception {
        long memberA = TEST_ID_BASE + (SEQ++);
        long memberB = TEST_ID_BASE + (SEQ++);
        for (long id : new long[]{memberA, memberB}) {
            jdbcTemplate.update(
                    "INSERT INTO member (id, phone, nickname, status) VALUES (?, ?, ?, 1)",
                    id, "138" + id, "并发测试车主");
            givenBalance(id, "500.00");
        }
        Connector connector = givenUsableConnector();
        OrderCreateDTO dto = createDto(connector, null);

        // ★★★ 本类唯一的并发测试，而且它测的正好是那道**只能靠并发才能验证**的关卡。
        //   前面所有测试都是单线程的，而 ① ② 两道预检在单线程下永远够用 ——
        //   也就是说它们「看起来」就是全部的保护。把 ③ 删掉，单线程测试【一条都不会失败】。
        //   这一条是 ③ 存在的唯一证据。
        //
        //   ★ 两个线程各自设自己的 BaseContext：它是 ThreadLocal，
        //     不在子线程里设的话 requireMemberId() 会抛未登录。
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Long> winners = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // ★ 用两个具名 Runnable 而不是一段循环：线程名会在日志里出现，
        //   而「哪一笔成了」在排查时是要对得上日志的
        Thread t1 = new Thread(() -> attemptCreate(memberA, dto, start, ok, rejected, winners, failures),
                "create-A");
        Thread t2 = new Thread(() -> attemptCreate(memberB, dto, start, ok, rejected, winners, failures),
                "create-B");
        t1.start();
        t2.start();
        start.countDown();
        t1.join(30_000);
        t2.join(30_000);

        assertThat(failures).as("★ 两个线程都不该抛出别的异常").isEmpty();
        assertThat(ok.get())
                .as("★★ 必须恰好有一笔成功。两笔成功 = ③ 没了，同一把枪会被两笔单同时占用；"
                        + "零笔成功 = 抢到的那一方也被误拒了")
                .isEqualTo(1);
        assertThat(rejected.get()).as("另一笔必须被明确拒绝，而不是静默失败").isEqualTo(1);

        // ★ 数据侧再确认一遍：那把枪归赢家，而且只有一个订单在途
        Long winner = winners.get(0);
        assertThat(connectorMapper.getById(connector.getId()).getCurrentOrderId())
                .as("★ 枪要归赢家").isEqualTo(winner);
        Integer live = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_order WHERE connector_id = ? AND status IN (1,2,3,4,6)",
                Integer.class, connector.getId());
        assertThat(live)
                .as("★★ 这把枪上只能有一笔未完结的订单。★ 如果 ③ 被删掉，"
                        + "这里会是 2 —— 两笔单都会去扣用户的钱，而设备只会充一次电")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 终态要放枪：取消 / 结算 / 挂账之后，那把枪都回到空闲")
    void terminalStatesShouldReleaseTheConnector() {
        // ---- ① 取消（1 → 8） ----
        long m1 = loginAsNewMember();
        givenBalance(m1, "500.00");
        Connector c1 = givenUsableConnector();
        Long o1 = givenOrder(c1, null);
        assertThat(connectorMapper.getById(c1.getId()).getCurrentOrderId()).isEqualTo(o1);

        orderService.cancel(o1);
        assertThat(connectorMapper.getById(c1.getId()).getCurrentOrderId())
                .as("★ 取消后枪必须放掉 —— 不放的话这把枪永远停在「2 已占用」，"
                        + "而且没有任何东西会去清理它（没有「占用超时回收」这种任务）")
                .isNull();
        assertThat(connectorMapper.getById(c1.getId()).getStatus())
                .as("★ status 要一起回到 1 空闲。只把 current_order_id 置空的话，"
                        + "枪看起来「有主但不知道是谁」—— 用户点不动，运营看不出原因")
                .isEqualTo(1);

        // ---- ② 结算（3 → 4） ----
        long m2 = loginAsNewMember();
        givenBalance(m2, "500.00");
        Connector c2 = givenUsableConnector();
        Long o2 = givenOrder(c2, null);
        orderService.start(o2, startDto("100.00"));
        orderService.end(o2, endDto("110.00", 1));
        orderService.settle(o2);

        assertThat(connectorMapper.getById(c2.getId()).getCurrentOrderId())
                .as("★ 结算时也要放枪。★ 注意 release 是【刻意】不挂在 "
                        + "releaseOrderResources 上的 —— 那个方法还会解冻，"
                        + "而结算时绝不能解冻（钱要在 4 待支付里等着被扣）")
                .isNull();
        assertThat(connectorMapper.getById(c2.getId()).getStatus()).isEqualTo(1);

        // ---- ③ 异常挂账（3 → 9） ----
        long m3 = loginAsNewMember();
        givenBalance(m3, "500.00");
        Connector c3 = givenUsableConnector();
        Long o3 = givenOrder(c3, null);
        orderService.start(o3, startDto("100.00"));
        // ★ 结束读数比开始读数小 → 电表倒退 → 转 9 异常挂账
        orderService.end(o3, endDto("90.00", 1));

        assertThat(orderMapper.getById(o3).getStatus())
                .as("前提：这笔单确实走了异常挂账那条路").isEqualTo(9);
        assertThat(connectorMapper.getById(c3.getId()).getCurrentOrderId())
                .as("★★ 挂账的订单【钱还冻着】，但【枪必须先放掉】—— "
                        + "这两件事在挂账单上分开了，而它们很容易被一起处理："
                        + "把放枪和退钱绑在一起写，挂账时枪就永远占着了")
                .isNull();
    }

    @Test
    @DisplayName("★★ release 是幂等的：重复释放不报错，也不会把别人占着的枪放掉")
    void releaseShouldBeIdempotent() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        Long orderId = givenOrder(connector, null);

        LocalDateTime now = LocalDateTime.now();

        // ★ release 的谓词只有 `current_order_id = #{orderId}`，**不带 status** ——
        //   这正是它能从 {1,2,3,4,6} 五个不同来源状态被无脑调用的原因：
        //   对已经放开过的枪影响 0 行，没有副作用，也不需要先判断「我还占着它吗」。
        assertThat(connectorMapper.release(orderId, now))
                .as("第一次释放应该影响 1 行").isEqualTo(1);
        assertThat(connectorMapper.release(orderId, now))
                .as("★ 第二次释放必须影响 0 行，而且不报错 —— 幂等是这个设计的承重墙")
                .isZero();

        // ★★ 反向：一个**别的**订单 id 绝不能放开这把枪。
        //   少了这条，一个「SET current_order_id = NULL」的漏写 WHERE 也能通过上面两条。
        Connector occupied = connectorMapper.getById(connector.getId());
        assertThat(occupied.getCurrentOrderId()).isNull();

        jdbcTemplate.update("UPDATE connector SET status = 2, current_order_id = ? WHERE id = ?",
                999999L, connector.getId());
        assertThat(connectorMapper.release(orderId, now))
                .as("★★ 订单 id 对不上时必须影响 0 行。写成无条件释放的话，"
                        + "任何一笔订单走到终态都会把【别人正在充电的那把枪】放成空闲，"
                        + "然后第二个人扫码就能建出第二笔单 —— 两笔单往同一把枪里充电量")
                .isZero();
        assertThat(connectorMapper.getById(connector.getId()).getCurrentOrderId())
                .as("★ 那把枪仍然归 999999 号订单").isEqualTo(999999L);
    }

    // ========================================================================
    // total_charge_count
    // ========================================================================

    @Test
    @DisplayName("★★ 结算成功让枪的累计充电次数 +1")
    void settleShouldIncrementChargeCount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();

        assertThat(connectorMapper.getById(connector.getId()).getTotalChargeCount())
                .as("前提：新建的枪是 0 次").isZero();

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("100.00"));
        orderService.end(orderId, endDto("110.00", 1));

        assertThat(connectorMapper.getById(connector.getId()).getTotalChargeCount())
                .as("★ 结算【之前】不该已经加过 —— 加在 start/end 上的话，"
                        + "一笔充到一半异常挂账的订单也会被算成一次成功充电")
                .isZero();

        orderService.settle(orderId);

        assertThat(connectorMapper.getById(connector.getId()).getTotalChargeCount())
                .as("★ 这一列在 P1c 收尾之前【恒为 0】：没有任何代码维护它，"
                        + "而两处注释都声称「P1c 结算时自增」—— 一句关于不存在的代码的话")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 结算是自己一个事务：重复结算被 CAS 挡住，累计次数【不会】变成 2")
    void settleTwiceShouldNotDoubleCountCharge() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("100.00"));
        orderService.end(orderId, endDto("110.00", 1));
        orderService.settle(orderId);

        assertThatThrownBy(() -> orderService.settle(orderId))
                .as("前提：第二次结算确实被 markSettled 的 CAS 挡住了")
                .isInstanceOf(DataConflictException.class);

        assertThat(connectorMapper.getById(connector.getId()).getTotalChargeCount())
                .as("★★ 必须还是 1。★★ 这一条钉的是「自增和 CAS 在同一个事务里」："
                        + "自增是一条普通 mapper UPDATE，提交当且仅当 markSettled 的 CAS 提交。"
                        + "把它改成 REQUIRES_NEW / @Async / 自注入代理调用之后，"
                        + "它会在 CAS 之外独立提交 —— 上一条测试【仍然会通过】，"
                        + "而这一条会失败。这是那三种写法唯一的探针")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 电表异常转挂账的订单不计入累计次数 —— 它数的是「结算成功」而不是「车充过电」")
    void abnormalOrderShouldNotIncrementChargeCount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("100.00"));
        orderService.end(orderId, endDto("90.00", 1));   // ★ 读数倒退 → 9 异常挂账

        assertThat(orderMapper.getById(orderId).getStatus()).isEqualTo(9);

        assertThat(connectorMapper.getById(connector.getId()).getTotalChargeCount())
                .as("★ 语义取舍，不是 bug：`total_charge_count` 数的是【结算成功】的次数。"
                        + "车确实充过电（end() 已经把这个事实落库了），但这笔单从未结算，"
                        + "而状态 9 也没有任何回退路径。取舍写在 Connector.totalChargeCount 上 —— "
                        + "换个语义（比如「枪被插过几次」）需要另一个列，不是改这一条")
                .isZero();
    }

    // ==================== 转挂账时的资源释放 ====================

    @Test
    @DisplayName("★★ 电表异常转挂账必须放掉权益锁 —— 否则那份权益永久不可用、也永久退不了款")
    void abnormalOrderMustReleaseThePackageLock() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        MemberPackage pack = givenEnergyPackage(memberId, "150.00");

        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("100.00"));

        assertThat(memberPackageMapper.getById(pack.getId()).getLockedOrderId())
                .as("前提：create() 确实把这份权益锁给了这笔订单 —— "
                        + "少了这一步，下面那条断言就是在断言一个从未被设置过的 NULL")
                .isEqualTo(orderId);

        orderService.end(orderId, endDto("90.00", 1));   // ★ 读数倒退 → 走 markAbnormal

        assertThat(orderMapper.getById(orderId).getStatus()).isEqualTo(9);

        // ★★ 这一条钉的是一个已经发生过的漏放。★★
        //   markAbnormal 的来源集合是 {1,2,3,4,6}：其中 {4,6} 的权益锁早被 settle
        //   释放过（settle* 的 SET 里有 locked_order_id = NULL），只有 {1,2,3} 还占着 ——
        //   而 end() 的电表异常路径（3 → 9）是一条真实可达的路。
        //   漏放的后果【不报错】，只是那一行 locked_order_id 永久留在那里：
        //     · listUsable 的 locked_order_id IS NULL 从此永久跳过这份权益 ——
        //       车主手里明明有 150 度，下一笔订单却按余额扣钱；
        //     · voidPackage 也会被挡在 locked_order_id IS NULL 外面 ——
        //       一份【一点都没被用过】的权益永远退不了款。
        //   ★ 而且它不可恢复：状态 9 没有任何回退路径，
        //     releaseLock 的调用者全在订单流程里，而这条订单再也走不回去了。
        assertThat(memberPackageMapper.getById(pack.getId()).getLockedOrderId())
                .as("★★ 转挂账和释放枪口是同一件事，权益锁是第三样资源。"
                        + "这条断言在 releasePackageLock 那一行被删掉之后会失败，"
                        + "而在任何别的地方都不会")
                .isNull();

        assertThat(memberPackageMapper.getById(pack.getId()).getStatus())
                .as("★ 只放锁，不碰状态和余量 —— 挂账的订单还没结算，抵扣口径未定")
                .isEqualTo(1);
        assertAmount("150.00", memberPackageMapper.getById(pack.getId()).getRemainingKwh(),
                "★ 余量一个都不能动");
    }

    @Test
    @DisplayName("★★ 放锁是按订单号定位的：转挂账【不会】误伤别的订单锁着的权益")
    void abnormalOrderMustNotReleaseOtherOrdersLocks() {
        long memberA = loginAsNewMember();
        givenBalance(memberA, "500.00");
        MemberPackage packA = givenEnergyPackage(memberA, "150.00");
        Connector connectorA = givenUsableConnector();
        Long orderA = givenOrder(connectorA, null);
        orderService.start(orderA, startDto("100.00"));

        // ★ 换一位车主、换一把枪，开出第二笔订单 —— 于是全表同时存在【两行】非空锁。
        //   这是唯一能让「谓词里漏了 orderId」暴露出来的形状：只有一行锁的时候，
        //   「清空所有锁」和「只清这一笔的锁」的结果一模一样。
        long memberB = loginAsNewMember();
        givenBalance(memberB, "500.00");
        MemberPackage packB = givenEnergyPackage(memberB, "150.00");
        Connector connectorB = givenUsableConnector();
        Long orderB = givenOrder(connectorB, null);
        orderService.start(orderB, startDto("100.00"));

        assertThat(memberPackageMapper.getById(packA.getId()).getLockedOrderId()).isEqualTo(orderA);
        assertThat(memberPackageMapper.getById(packB.getId()).getLockedOrderId()).isEqualTo(orderB);

        // 只让 A 的那笔挂账（BaseContext 此刻已经是 B，得切回来）
        BaseContext.setCurrentId(memberA);
        orderService.end(orderA, endDto("90.00", 1));
        assertThat(orderMapper.getById(orderA).getStatus()).isEqualTo(9);

        assertThat(memberPackageMapper.getById(packA.getId()).getLockedOrderId()).isNull();
        assertThat(memberPackageMapper.getById(packB.getId()).getLockedOrderId())
                .as("★★ B 的锁必须原封不动。★★ 把 releaseLockByOrder 写成不带 "
                        + "WHERE locked_order_id = ? 的「清空所有锁」之后，B 的权益会静默地"
                        + "变成谁都能抢的公共资源 —— 而 B 那笔订单还在充电，"
                        + "结算时它会发现自己锁定的那份权益已经被另一笔订单扣过了")
                .isEqualTo(orderB);
    }

    @Test
    @DisplayName("★ 放锁是自守卫的：对一笔【从未锁过任何权益】的订单转挂账，不影响别的资源")
    void releasePackageLockShouldBeANoOpForOrdersThatHoldNothing() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        Connector connector = givenUsableConnector();
        // ★ 这位车主一份权益都没有 —— create() 的 tryLockPackage 走完 listUsable
        //   拿到空列表，什么都不锁。这是绝大多数订单的真实形状。
        Long orderId = givenOrder(connector, null);
        orderService.start(orderId, startDto("100.00"));

        BigDecimal frozenBefore = orderMapper.getById(orderId).getFrozenAmount();

        orderService.end(orderId, endDto("90.00", 1));

        // ★ 这里没有「一行都不该被改」的直接断言对象，所以断言的是
        //   「走完这条路之后别的资源仍然自洽」—— 它钉的是
        //   releasePackageLock 里那条 UPDATE 的谓词只有 locked_order_id = ? 这件事：
        //   对一份都没锁过的订单影响 0 行，所以枪口释放和钱都不受影响。
        //   ★ 如果哪天有人「顺手」给它加上 SET status = 1，这条测试不会红
        //     （这份订单本来就没有权益行）—— 那个改动会被
        //     abnormalOrderMustReleaseThePackageLock 里那条 status 断言抓住。
        assertThat(orderMapper.getById(orderId).getStatus()).isEqualTo(9);
        assertThat(connectorMapper.getById(connector.getId()).getCurrentOrderId())
                .as("★ 枪口照常释放（那是 releaseConnector 的职责，和权益锁是两回事）").isNull();
        assertAmount(frozenBefore.toPlainString(),
                orderMapper.getById(orderId).getFrozenAmount(),
                "★ 挂账不动钱：冻结还在，等人工核销");
    }
}
