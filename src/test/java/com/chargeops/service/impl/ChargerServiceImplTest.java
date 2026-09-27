package com.chargeops.service.impl;

import com.chargeops.context.BaseContext;
import com.chargeops.constant.MessageConstant;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.service.ChargerService;
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
 * {@code ChargerServiceImpl} 的集成测试 —— <b>重点只有一件事：{@code assertDeletable} 的两道守卫</b>
 *
 * <p>通用写法（为什么是集成测试、为什么<b>不能</b>标 {@code @Transactional}、
 * 为什么每条测试自己造站点）见 {@code WalletServiceImplTest} 的类注释。
 * 和 {@code StationServiceImplTest} 是同一个形状的另一半：那边守站点，这边守桩。
 *
 * <h2>★★ 本类要钉死的四件事</h2>
 *
 * <ol>
 *   <li>★ {@link #chargerWithConnectorsShouldNotBeDeleted()} —— 第一道守卫（枪）仍然在，
 *       而且消息里的数字是<b>真的数出来的</b>。先钉它，是为了让第二道被证明成
 *       「<b>额外</b>的一道」，而不是把第一道<b>替换</b>掉了</li>
 *   <li>★★ {@link #chargerWithInFlightOrdersButNoConnectorShouldNotBeDeleted()} ——
 *       枪已经全部删掉、但订单还在时<b>仍然不能删</b>。这个状态必须手工构造，见下</li>
 *   <li>★★ {@link #chargerReferencedOnlyByTerminalOrdersShouldBeDeleted()} ——
 *       只被 {@code 7 已退款 / 8 已取消} 引用的桩<b>必须能删</b>，
 *       否则任何出过工单的桩都会变成永久不可删</li>
 *   <li>★★ 下面一节：站点那一道和本类这一道查的是<b>同一批订单</b>，
 *       却<b>一道都不能删</b>。这是本类最容易被误删的一道守卫</li>
 * </ol>
 *
 * <h2>★★ 这条用例抓到过一个真实的 bug（已经修好，但过程要留下）</h2>
 *
 * <p>{@link #chargerReferencedByPaidOrderShouldBeDeleted()} 刚写出来的时候是<b>红</b>的，
 * 红的是生产代码：{@code OrderStatus.blocksDeviceDeletion()} 当时的实现是
 * {@code return !isTerminal();}，而 {@code isTerminal()} 只认 {@code 7 / 8} ——
 * 于是 {@code 5 已支付} <b>在</b> {@code blockingCodes()} 里
 * （实测 {@code [1, 2, 3, 4, 5, 6, 9]}），和 {@code OrderStatus.blocksDeviceDeletion}
 * 自己的 javadoc 矛盾（那里明写集合是 {@code {1,2,3,4,6,9}}，
 * 并说 {@code 5 已支付} 特意不在里面）。
 *
 * <p>完整的论证、后果和修法见 {@code StationServiceImplTest} 类注释里的同一节；
 * 修法就是 {@code holdsMoney() || this == ABNORMAL}。
 * 这里只留一句：<b>这条断言不许被改弱</b>，改弱等于承认「已支付的订单可以挡住删除」。
 *
 * <h2>★★ 站点那一道和本类这一道查的是同一批订单，为什么还一道都不能省</h2>
 *
 * <p><b>先说清它们的关系，免得后来的人以为有一处是冗余：</b>一笔订单同时带
 * {@code station_id} 和 {@code charger_id}，所以「站下有没有未完结订单」和
 * 「桩下有没有未完结订单」在正常数据上是<b>同一个问题从两个方向问</b>。
 * 而且删站的路径上，第一道守卫要求先把桩全部删掉 —— 每根桩又都要先过本类这一道，
 * 于是走到删站时，站下的订单守卫<b>实际上已经被查过一遍了</b>。
 *
 * <p>★★ <b>但两道都必须留，因为两个入口各自独立：</b>
 * {@code DELETE /api/admin/charger/{id}} 这条路径<b>完全不经过</b>
 * {@code StationService} —— 管理员在桩列表页上就能直接删一根桩，一次点击就到，
 * 那根桩上的订单守卫只能由 {@code ChargerServiceImpl.assertDeletable} 回答。
 * 反过来说，删站那条路径也不经过本类。
 *
 * <p>所以「删掉一道」的后果是<b>有一条入口没人管</b>：删掉本类这一道，
 * 桩列表页上的一次点击就能把一根桩从历史订单底下抽走；删掉站点那一道，
 * 一次「先删桩、再删站」的误操作会让整个站的历史订单一起变成悬空引用。
 * 两条入口各自需要一个守卫，而不是同一条守卫被写了两遍。
 *
 * <p>★ 并且要诚实地说：站点那一侧的<b>第二道</b>守卫在正常数据上基本是个兜底网
 * （桩删光、订单还指着站，那意味着数据发生了漂移，最可能是本守卫上线之前
 * 就已经被删掉的桩）。所以就别奇怪它在测试里很难构造出「只有它命中」的场景 ——
 * 那正是它工作正常的表现，见 {@code StationServiceImplTest} 里手工造的那个状态。
 *
 * <h2>★ 为什么夹具直接 {@code INSERT} 订单，而不是走真实的建单流程</h2>
 *
 * <p>守卫读的只有 {@code charging_order.charger_id} 和 {@code status} 两列。
 * 走建单流程要凑齐 member、钱包、车辆、计费模板、站点计费绑定五六样东西
 * （见 {@code ChargingOrderServiceImplTest} 的 {@code givenUsableConnector}），
 * 而其中任何一样出问题，报出来的失败都会指向那个依赖，而不是这两道守卫。
 * 同理，订单的 {@code status} 是<b>直接写在 insert 里</b>的，
 * 不是用真实流转推上去的 —— 本类要的是「库里存在这样一行」，
 * 状态机本身由 {@code ChargingOrderMapperTest} 的六条闸门覆盖。
 *
 * <h2>★ 认领哨兵值（和其它测试类互不重叠）</h2>
 *
 * <p>{@code member_id} 从 {@value #TEST_ID_BASE} 起、严格小于 {@value #TEST_ID_LIMIT}；
 * 站点 code 前缀 {@value #STATION_CODE_PREFIX}；订单单号前缀 {@value #ORDER_NO_PREFIX}；
 * 设备号前缀 {@value #DEVICE_NO_PREFIX}（{@code uk_charger_device_no} 是全表唯一，
 * 不加前缀会和别的测试类撞）。
 *
 * <p>★ <b>上界不能省：</b>写法必须是 {@code member_id >= 7e12 AND member_id < 7e12 + 1e6}。
 * 只写 {@code >= 7e12} 的话，收尾时会把 8e12 那一族（另一个测试类）造的行一起删掉 ——
 * 「下界」说明这一行是我的，「上界」说明那一行不是别人的，两个都要写范围才是自己独占的。
 * （反向的风险 —— 别的测试类用无上界的 {@code >=} 收尾、把本类造的行删掉 —— 见
 * {@code StationServiceImplTest} 类注释里同一节，本类实测没踩到过。）
 *
 * <p>★ 计费模板前缀 {@code TT-DEL-B-} 归本类所有：本类不建模板
 * （守卫不看 {@code station_tariff}），所以没有对应的清理语句 ——
 * 将来要往夹具里绑计价方案时用这个前缀，别借用 {@code TT-ORD-}。
 */
@SpringBootTest
class ChargerServiceImplTest {

    @Autowired
    private ChargerService chargerService;

    /**
     * ★ 夹具用它把订单<b>直接</b>插进库（见类注释），
     * 同时也是被 {@code ChargerServiceImpl.assertDeletable} 读 COUNT 的那个 mapper ——
     * 「桩服务要跨到订单表上」这件事本来就该在测试里看得见。
     */
    @Autowired
    private ChargingOrderMapper orderMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private ConnectorMapper connectorMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类造出来的 member_id 下界。2e12/3e12/4e12/5e12/8e12 已被别的测试类占用 */
    private static final long TEST_ID_BASE = 7_000_000_000_000L;

    /** ★ 本类独占的 member_id 上界（不含）—— 见类注释最后一节 */
    private static final long TEST_ID_LIMIT = TEST_ID_BASE + 1_000_000L;

    /** 站点 code 前缀。★ 站点的 id 是自增分配的，只能按 code 认领 */
    private static final String STATION_CODE_PREFIX = "ST-DEL-B-";

    /** 订单单号前缀。{@code charging_order.id} 同样是自增的 */
    private static final String ORDER_NO_PREFIX = "CO-DEL-B-";

    /** 设备号前缀 —— {@code uk_charger_device_no} 不接受重名，见类注释 */
    private static final String DEVICE_NO_PREFIX = "DEV-DEL-B-";

    private static long SEQ = 0;

    @AfterEach
    void cleanUp() {
        // ★ 子表在前、父表在后。顺序不是排版：后面的子查询都要靠父行【还在】
        //   才查得到子行的 id，父行先删掉的话它们会静默地一条都不删
        //   （子查询返回空集 → DELETE 影响 0 行 → 不报错）。
        //
        //   ★ 本类的订单是直接 INSERT 的，event / segment 里其实没有行。
        //   这两条仍然留着：夹具哪天改成走真实建单流程时，它们是不留垃圾的保证。
        jdbcTemplate.update("DELETE FROM charging_order_event WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ? AND member_id < ?)",
                TEST_ID_BASE, TEST_ID_LIMIT);
        jdbcTemplate.update("DELETE FROM charging_segment WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ? AND member_id < ?)",
                TEST_ID_BASE, TEST_ID_LIMIT);
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ? AND member_id < ?",
                TEST_ID_BASE, TEST_ID_LIMIT);

        // 枪挂在桩上，桩挂在站点上 —— 子 → 父 → 祖父
        jdbcTemplate.update("DELETE FROM connector WHERE charger_id IN "
                + "(SELECT id FROM charger WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE ?))", STATION_CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM charger WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE ?)", STATION_CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM station WHERE code LIKE ?", STATION_CODE_PREFIX + "%");

        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 登入一个「操作者」上下文，返回它的 id
     *
     * <p>★ 本类<b>不需要</b>一行真 member：删桩的路径上没有任何代码查账号
     * （{@code member_id} 上也没有外键）。给 id 是为了 {@code @AutoFill} ——
     * 站点、桩、订单的 {@code create_user} 会取当前登录人，为 null 时那条记录就
     * 变成了「不知道谁建的」。同 {@code ChargingOrderMapperTest} 的做法。
     */
    private long login() {
        long operatorId = TEST_ID_BASE + (SEQ++);
        BaseContext.setCurrentId(operatorId);
        return operatorId;
    }

    /** 造一个站点。★ {@code open_time} / {@code close_time} 是 NOT NULL，必须显式给 */
    private Station givenStation() {
        Station station = Station.builder()
                .code(STATION_CODE_PREFIX + (SEQ++))
                .name("删除守卫测试站点B")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        assertThat(station.getId()).as("insert 后 id 应被回填").isNotNull();
        return station;
    }

    /**
     * 造一根桩
     *
     * <p>★ {@code rated_power_kw} 是 NOT NULL 且<b>连默认值都没有</b>，必须给；
     * {@code gun_count} 给 0（刚建好的桩一把枪都没有，见 {@code ChargerServiceImpl.create}）。
     */
    private Charger givenCharger(Long stationId) {
        Charger charger = Charger.builder()
                .stationId(stationId)
                .deviceNo(DEVICE_NO_PREFIX + (SEQ++))
                .name("删除守卫测试桩B")
                .type(1)
                .ratedPowerKw(bd("120.00"))
                .gunCount(0)
                .status(1)
                .build();
        chargerMapper.insert(charger);
        assertThat(charger.getId()).as("insert 后 id 应被回填").isNotNull();
        return charger;
    }

    /**
     * 造一把枪
     *
     * <p>★ {@code ConnectorMapper.insert} <b>没有</b> {@code @AutoFill}，
     * 两个时间必须由调用方显式给 —— 漏了会撞 NOT NULL 报 errno 1048。
     *
     * <p>★ 顺手不刷新 {@code charger.gun_count}：本类的守卫数的是 {@code connector}
     * 表里的<b>真实行数</b>，冗余列的正确性由 {@code ChargerMapperTest} 覆盖。
     * 这里刻意不让夹具去维护它，免得「夹具在替被测代码做事」。
     */
    private Connector givenConnector(Charger charger, String gunNo) {
        LocalDateTime now = LocalDateTime.now();
        Connector connector = Connector.builder()
                .chargerId(charger.getId())
                .stationId(charger.getStationId())
                .gunNo(gunNo)
                .connectorType("GB/T 20234.3")
                .ratedPowerKw(bd("120.00"))
                .status(1)
                .build();
        connector.setCreateTime(now);
        connector.setUpdateTime(now);
        connectorMapper.insert(connector);
        assertThat(connector.getId()).as("insert 后 id 应被回填").isNotNull();
        return connector;
    }

    /**
     * 造一笔已经停在 {@code status} 上的订单，返回订单 id
     *
     * <p>★ {@code tariff_template_id} 给 1、{@code connector_id} 复用枪的 id ——
     * 两列都是 NOT NULL 但都没有外键，而守卫一个字节都不读它们（见类注释：
     * 夹具不引入无关依赖）。
     */
    private Long givenOrder(Long stationId, Long chargerId, OrderStatus status) {
        ChargingOrder order = ChargingOrder.builder()
                .orderNo(ORDER_NO_PREFIX + (SEQ++))
                .memberId(TEST_ID_BASE + (SEQ++))
                .stationId(stationId)
                .chargerId(chargerId)
                .connectorId(chargerId)
                .tariffTemplateId(1L)
                .status(status.getCode())
                .frozenAmount(bd("50.00"))
                .discountAmount(bd("0.00"))
                .refundAmount(bd("0.00"))
                .payMethod(1)
                .version(0)
                .build();
        orderMapper.insert(order);
        assertThat(order.getId()).as("insert 后 id 应被回填").isNotNull();
        return order.getId();
    }

    /** 桩上还挂着几笔订单（不分状态）—— 用来证「订单还在，没被顺手带走」 */
    private long orderCountOfCharger(Long chargerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_order WHERE charger_id = ?", Long.class, chargerId);
    }

    /** 桩下还有几把枪 —— 用来证第一道守卫此刻是放行的 */
    private long connectorCountOfCharger(Long chargerId) {
        return connectorMapper.countByChargerId(chargerId);
    }

    // ==================== 第一道守卫：枪 ====================

    @Test
    @DisplayName("★ 桩下还有充电枪就不能删，而且消息里的数字必须是【真的数出来的】")
    void chargerWithConnectorsShouldNotBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());
        Connector gunA = givenConnector(charger, "A");
        givenConnector(charger, "B");

        assertThatThrownBy(() -> chargerService.delete(charger.getId()))
                .as("★ 第一道守卫。本用例一笔订单都没有，所以这条同时证了"
                        + "它是【额外】的一道，不是被订单守卫替换掉了")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.CHARGER_HAS_CONNECTOR, 2));

        // ★ 被拒绝之后那一行必须还在 —— 「检查通过一半就把行删了」是这里最坏的失败方式。
        //   桩被删而枪留下的话，那些枪会带着一个指向不存在的桩的 charger_id 留在库里：
        //   仍然能被查出来，但再也找不到自己属于哪个桩（没有外键、也没有级联删除）。
        assertThat(chargerMapper.getById(charger.getId())).as("拒绝删除不能顺手把桩删掉").isNotNull();

        // ★★ 再删掉一把枪，消息里的数字必须跟着变成 1。
        //    写死成 2（或者把第一次的 COUNT 缓存起来）的实现能通过上面那一句，
        //    只有真的每次都去数，才能同时通过这两句 —— 一个说错数字的提示，
        //    会让管理员照着一个不存在的枪数去排查。
        jdbcTemplate.update("DELETE FROM connector WHERE id = ?", gunA.getId());

        assertThatThrownBy(() -> chargerService.delete(charger.getId()))
                .as("★★ 消息里的数字必须是【这次】数出来的")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.CHARGER_HAS_CONNECTOR, 1));
    }

    // ==================== ★★ 第二道守卫：订单（只有它命中） ====================

    @Test
    @DisplayName("★★ 枪已经全部删掉、但桩上还有未完结订单时不能删 —— 消息里带着订单数")
    void chargerWithInFlightOrdersButNoConnectorShouldNotBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());
        Connector gunA = givenConnector(charger, "A");

        // 两笔未完结的订单，刻意挑【都不占枪】的两个状态：
        //   · 4 待支付   —— 枪早就放了（holdsConnector() 为 false）
        //   · 6 支付失败  —— 同上，钱还冻着
        // ★★ 用它们而不是 2 充电中，是为了钉住判据：挡删除的是「非终态」，
        //    不是「还占着这把枪」。拿 holdingConnectorCodes() 当判据的实现
        //    能挡住一笔正在充电的订单，却会放这两笔过去 —— 而那两笔的
        //    charger_id 一样会悬空，且不报错（见 OrderStatus.blockingCodes 的 javadoc）。
        givenOrder(station.getId(), charger.getId(), OrderStatus.PENDING_PAY);
        givenOrder(station.getId(), charger.getId(), OrderStatus.PAY_FAILED);

        // ★★ 把唯一的枪删掉，让第一道守卫放行。
        //    这一步用 JdbcTemplate 直接动库，而不是调 ConnectorService ——
        //    本类不该去断言另一个 Service 的守卫（那是 ConnectorServiceImplTest 的事）。
        //
        //    ★ 但要说清：这一步在真实操作里【真的能成功】。枪自己那道守卫
        //    （ConnectorServiceImpl.assertDeletable）用的判据是 holdingConnectorCodes()
        //    （{1,2,3}），4 待支付 / 6 支付失败的订单早就把枪放了 ——
        //    拿它们拦删枪会让一把枪在历史上充过一次电之后就再也删不掉。
        //    于是「枪已经删光、订单还指着桩」不是人为造出来的怪状态，
        //    而是「先删枪、再删桩」这条正常操作路径上的中间态。
        jdbcTemplate.update("DELETE FROM connector WHERE id = ?", gunA.getId());

        assertThat(connectorCountOfCharger(charger.getId()))
                .as("前置条件：第一道守卫此刻必须已经放行，否则本用例证明不了第二道")
                .isZero();

        assertThatThrownBy(() -> chargerService.delete(charger.getId()))
                .as("★★ 枪没了，但订单的 charger_id 还指着这根桩 —— 硬删掉会让一笔"
                        + "append-only 的历史订单永久失去它的设备归属")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.CHARGER_HAS_ORDER, 2));

        assertThat(chargerMapper.getById(charger.getId())).as("拒绝之后桩必须还在").isNotNull();
        assertThat(orderCountOfCharger(charger.getId())).as("订单当然也一笔都不许动").isEqualTo(2);
    }

    // ==================== ★★ 终态订单不挡删除 ====================

    @Test
    @DisplayName("★★ 只被【已退款 / 已取消】的订单引用时，桩必须能删掉")
    void chargerReferencedOnlyByTerminalOrdersShouldBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());

        // 两个终态各一笔。它们代表的是「这根桩出过工单」，不是「这根桩现在还在用」。
        givenOrder(station.getId(), charger.getId(), OrderStatus.REFUNDED);
        givenOrder(station.getId(), charger.getId(), OrderStatus.CANCELLED);

        chargerService.delete(charger.getId());

        // ★★ 这一句是本类价值最高的断言（的一半，另一半见下一条用例）。
        //    7 已退款 / 8 已取消 如果被算进 blockingCodes()，这根桩就永远删不掉了：
        //    订单是 append-only 的，没有「删除订单」这个动作，于是任何出过一次
        //    账的桩都会变成永久不可删 —— 一个设备被拆走、报废、
        //    或者干脆是当初登记错了位置，都没有办法把它从系统里清掉。
        assertThat(chargerMapper.getById(charger.getId()))
                .as("★★ 终态订单不能挡住桩的删除，否则 uk_charger_device_no 会被永久占用")
                .isNull();

        // ★ 反过来说，订单一行都不该少：它们是历史事实，桩没了也要留着。
        //   charger_id 指向一根不存在的桩是【可接受】的 —— 它是历史快照的入口，
        //   不是一个必须能 JOIN 的活引用。真正不可接受的是【删了之后没人知道】
        //   （那正是第二道守卫拦的那一种），而不是【删了之后引用悬空】。
        assertThat(orderCountOfCharger(charger.getId()))
                .as("★ 桩被删不该带走订单 —— 订单是 append-only 的")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 只被【5 已支付】的订单引用时，桩同样必须能删掉 —— 曾经是红的，抓到了那一个 bug")
    void chargerReferencedByPaidOrderShouldBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());
        givenOrder(station.getId(), charger.getId(), OrderStatus.PAID);

        // ★★ 这一句曾经抛 DataConflictException（「该充电桩下还有 1 笔未完结的充电订单，请先处理」），
        //    因为生产代码违背了自己的 javadoc：blocksDeviceDeletion() 当时是
        //    return !isTerminal()，而 isTerminal() 只认 7/8，于是 5 已支付 被算了进去。
        //    修法就在 blocksDeviceDeletion() 那一行。复盘见类注释那一节。
        //
        //    ★ 这句断言【不许】被改弱成 isInstanceOf(DataConflictException) ——
        //      那等于承认「已支付的订单可以挡住删除」，把它变回一个静默的设计错误。
        chargerService.delete(charger.getId());

        assertThat(chargerMapper.getById(charger.getId()))
                .as("★★ 已支付的订单不能挡住桩的删除 —— 它的钱已经结清、枪也早就放了")
                .isNull();
    }
}
