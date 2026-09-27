package com.chargeops.service.impl;

import com.chargeops.context.BaseContext;
import com.chargeops.constant.MessageConstant;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.service.StationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code StationServiceImpl} 的集成测试 —— <b>重点只有一件事：{@code assertDeletable} 的两道守卫</b>
 *
 * <p>通用写法（为什么是集成测试、为什么<b>不能</b>标 {@code @Transactional}、
 * 为什么每条测试自己造站点）见 {@code WalletServiceImplTest} 的类注释，三条理由完全一样。
 * 本类尤其吃这一条：守卫的真值是「另一张表里的 COUNT」，把 mapper 换成桩类之后，
 * 这两条测试就变成了「我在断言我自己写的桩」。
 *
 * <h2>★★ 本类要钉死的四件事</h2>
 *
 * <ol>
 *   <li>★ {@link #stationWithChargersShouldNotBeDeleted()} —— 第一道守卫（桩）
 *       仍然在，而且消息里的数字是<b>真的数出来的</b>。
 *       先钉它，是为了让第二道守卫被证明成「<b>额外</b>的一道」，而不是把第一道<b>替换</b>掉了</li>
 *   <li>★★ {@link #stationWithInFlightOrdersButNoChargerShouldNotBeDeleted()} ——
 *       桩已经全部挪走、但站下还有未完结订单时<b>仍然不能删</b>。
 *       这个状态在正常数据上到不了（见下），必须手工构造</li>
 *   <li>★★ {@link #stationReferencedOnlyByTerminalOrdersShouldBeDeleted()} ——
 *       只被 {@code 7 已退款 / 8 已取消} 引用的站点<b>必须能删</b>。
 *       这是本类价值最高的一条断言</li>
 *   <li>★ {@link #blockingCodesShouldExcludePaid()} —— 把上一条背后的那条规定
 *       （{@code 5 已支付}特意不在判据集合里）单独钉成一条契约断言</li>
 * </ol>
 *
 * <h2>★★ 这两条用例抓到过一个真实的 bug（已经修好，但过程要留下）</h2>
 *
 * <p>本类刚写出来的时候，{@link #stationReferencedByPaidOrderShouldBeDeleted()} 和
 * {@link #blockingCodesShouldExcludePaid()} 是<b>红</b>的，红的是生产代码：
 * {@code OrderStatus.blocksDeviceDeletion()} 当时的实现是
 * {@code return !isTerminal();}，而 {@code isTerminal()} 只认
 * {@code 7 已退款 / 8 已取消} —— 于是 {@code 5 已支付} <b>在</b>
 * {@code blockingCodes()} 里（实测 {@code [1, 2, 3, 4, 5, 6, 9]}）。
 *
 * <p>它和<b>三处</b> javadoc 矛盾，每一处都明写 {@code 5 已支付} 不在里面：
 * {@code OrderStatus.blocksDeviceDeletion}（还给出了集合 {@code {1,2,3,4,6,9}}）、
 * {@code MessageConstant.STATION_HAS_ORDER}、
 * {@code ChargingOrderMapper.countByStation}。
 *
 * <p>后果正是那些 javadoc 亲口警告过的那件事：<b>任何完成过一次充电的站点都删不掉了</b> ——
 * 唯一的出路是先把订单退款成 {@code 7 已退款}，而那是动钱的操作，
 * 不是一个清理动作。一个设备被拆走、报废、或者当初就登记错了位置，
 * 都会卡在这里。
 *
 * <p>修法在生产代码里，一行：{@code holdsMoney() || this == ABNORMAL}
 * —— 正好就是它 javadoc 里写的那个集合。现在两条断言都是绿的。
 *
 * <p>★★ <b>但这两条断言【仍然不许】被改弱。</b>把 {@code 5} 从用例里拿掉、
 * 或者改写成「抛了 {@code DataConflictException} 也算过」，都会让同一类不一致
 * 重新变回静默 —— 而它正是本类存在的意义。这个 bug 的成因值得记住：
 * javadoc 里那句「它等于<b>非终态</b>」把结论写成了一个<b>听起来等价的口径</b>，
 * 而那个口径差一个元素。**判据要写成它真正的定义，不要写成一句近义词。**
 *
 * <h2>★ 为什么夹具直接 {@code INSERT} 订单，而不是走真实的建单流程</h2>
 *
 * <p>守卫读的只有 {@code charging_order.station_id} 和 {@code status} 两列，
 * 和「这笔订单是怎么建出来的、钱冻了多少、用哪份权益」<b>一个字的关系都没有</b>。
 * 走 {@code ChargingOrderService.create} 要凑齐 member、钱包、车辆、计费模板、
 * 站点计费绑定五六样东西（见 {@code ChargingOrderServiceImplTest} 的 {@code givenUsableConnector}），
 * 而其中任何一样出问题，报出来的失败都会指向那个依赖，而不是这两道守卫。
 * 建单流程本身由 {@code ChargingOrderServiceImplTest} 覆盖。
 *
 * <p>★ 订单上的 {@code status} 也是<b>直接写在 insert 里</b>的，不是用真实流转推上去的 ——
 * 本类要的是「库里存在这样一行」，不是「状态机能走到那里」（后者由
 * {@code ChargingOrderMapperTest} 的六条闸门覆盖）。用流转推的话，
 * 「造一个已支付的订单」需要先造一次完整的充电与结算，那正是上面说的那些无关依赖。
 *
 * <h2>★★ 为什么「只有第二道守卫命中」的那个状态必须手工构造</h2>
 *
 * <p>{@code assertDeletable} 的 javadoc 亲口承认：第二道守卫在正常数据上是个兜底网。
 * 删站之前第一道守卫会要求先把桩全部删掉，而<b>每一根桩自己</b>都要过一遍
 * {@code ChargerServiceImpl.assertDeletable} 里同样的订单检查 —— 于是在正常路径上
 * 走到这里时，「站下还有未完结订单吗」早就回答过「没有」了。
 *
 * <p>它真正会命中的是<b>数据漂移</b>：桩在本守卫上线之前就被删掉的历史数据、
 * 有人直接改过库、订单和设备的冗余列对不上。本类用
 * {@link #stationWithInFlightOrdersButNoChargerShouldNotBeDeleted()} 手工造出这个状态
 * （订单还在、桩已经没了），也正是「难以构造测试场景」这件事的落点 ——
 * 不能因为难以构造就把这道守卫删掉。
 *
 * <h2>★ 认领哨兵值（和其它测试类互不重叠）</h2>
 *
 * <p>{@code member_id} 从 {@value #TEST_ID_BASE} 起、严格小于 {@value #TEST_ID_LIMIT}；
 * 站点 code 前缀 {@value #STATION_CODE_PREFIX}；订单单号前缀 {@value #ORDER_NO_PREFIX}。
 *
 * <p>★ <b>两个上界都不能省：</b>如果写成 {@code member_id >= 6e12}，收尾时会把
 * {@code ChargerServiceImplTest}（它从 7e12 起）造的行一起删掉 —— 那是另一个兄弟类的哨兵值。
 * <b>下界要写、上界也要写</b>，范围才是自己独占的。
 *
 * <p>★★ <b>但反过来管不住：</b>本类能保证自己不删别人的行，保证不了别人不删自己的。
 * {@code ChargingOrderMapperTest}（从 3e12 起）和 {@code ChargingOrderServiceImplTest}
 * （从 4e12 起）的收尾是<b>无上界</b>的 {@code member_id >= ...}，两个类<b>同时</b>跑的时候，
 * 它们会把本类造的行一起删掉 —— 表现是「夹具刚插进去的订单，几句断言之后就不见了」。
 * 本类实测踩到过一次（{@code orderCountOfStation} 期望 2、实得 0）。
 * 要修得改那两个类的收尾写法，不是本类；但看到那个形状的失败时，先往这里想，
 * 别去怀疑守卫本身。
 *
 * <p>★ 计费模板前缀 {@code TT-DEL-A-} 归本类所有：本类不建模板
 * （守卫不看 {@code station_tariff}），所以没有对应的清理语句 ——
 * 将来要往夹具里绑计价方案时用这个前缀，别借用 {@code TT-ORD-}。
 */
@SpringBootTest
class StationServiceImplTest {

    /**
     * ★ 夹具用它把订单<b>直接</b>插进库（见类注释），
     * 同时也是被 {@code StationServiceImpl.assertDeletable} 读 COUNT 的那个 mapper ——
     * 「站点服务要跨到订单表上」这件事本来就该在测试里看得见。
     */
    @Autowired
    private ChargingOrderMapper orderMapper;

    @Autowired
    private StationService stationService;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类造出来的 member_id 下界。2e12/3e12/4e12/5e12/8e12 已被别的测试类占用 */
    private static final long TEST_ID_BASE = 6_000_000_000_000L;

    /** ★ 本类独占的 member_id 上界（不含）—— 见类注释最后一节 */
    private static final long TEST_ID_LIMIT = TEST_ID_BASE + 1_000_000L;

    /**
     * 站点 code 前缀。★ {@code station.id} 是自增分配的，测试<b>指定不了</b>
     * （insert 用 useGeneratedKeys，列清单里也没有 id），所以只能按 code 认领。
     */
    private static final String STATION_CODE_PREFIX = "ST-DEL-A-";

    /** 订单单号前缀。{@code charging_order.id} 同样是自增的 */
    private static final String ORDER_NO_PREFIX = "CO-DEL-A-";

    private static long SEQ = 0;

    @AfterEach
    void cleanUp() {
        // ★ 子表在前、父表在后。带子查询的那几条尤其要注意顺序：
        //   `charger WHERE station_id IN (SELECT id FROM station ...)` 要靠父行【还在】
        //   才查得到子行的 id —— 父行先删掉的话，子查询返回空集、DELETE 影响 0 行，
        //   而且是【静默】的（不报错，只是垃圾留下了）。
        //
        //   ★ 本类的订单是直接 INSERT 的，所以 event / segment 两张表里其实没有行。
        //   这两条仍然留着：夹具哪天改成走真实建单流程时，它们是「不留垃圾」的保证，
        //   而那一天最容易漏掉的正是它们。
        jdbcTemplate.update("DELETE FROM charging_order_event WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ? AND member_id < ?)",
                TEST_ID_BASE, TEST_ID_LIMIT);
        jdbcTemplate.update("DELETE FROM charging_segment WHERE order_id IN "
                + "(SELECT id FROM charging_order WHERE member_id >= ? AND member_id < ?)",
                TEST_ID_BASE, TEST_ID_LIMIT);
        // 订单挂在站点上（station_id 是裸 bigint，没有外键），但它按 member 区间认领
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ? AND member_id < ?",
                TEST_ID_BASE, TEST_ID_LIMIT);

        // 桩是本类唯一直接建的站点子行（枪由 ChargerServiceImplTest 负责）
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
     * <p>★ 本类<b>不需要</b>一行真 member：没有任何代码在删站的路径上查账号
     * （{@code member_id} 上也没有外键）。给 id 是为了 {@code @AutoFill} ——
     * 站点和订单的 {@code create_user} 会取当前登录人，为 null 时那条记录就
     * 变成了「不知道谁建的」。这和 {@code ChargingOrderMapperTest} 里
     * 不建 member 行、只借一个 id 是同一个做法。
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
                .name("删除守卫测试站点")
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
     * <p>★ {@code gun_count} 给 0：刚建好的桩一把枪都没有（DDL 的默认值 1 是个
     * 「一桩一枪」的近似，见 {@code ChargerServiceImpl.create}）。
     * 本类的站点守卫数的是 {@code charger} 表的行数，不读 {@code gun_count}，
     * 所以这里不用再去刷新那个冗余列。
     */
    private Charger givenCharger(Long stationId) {
        Charger charger = Charger.builder()
                .stationId(stationId)
                .deviceNo("DEV-DEL-A-" + (SEQ++))
                .name("删除守卫测试桩")
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
     * 造一笔已经停在 {@code status} 上的订单，返回订单 id
     *
     * <p>★ {@code tariff_template_id} 给 1 —— 它是 NOT NULL 且没有外键，
     * 而守卫一个字节都不读它（见类注释：夹具不引入无关依赖）。
     * {@code connector_id} 同理，直接复用 {@code chargerId}。
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

    /** 站点下还挂着几笔订单（不分状态）—— 用来证「订单还在，没被顺手带走」 */
    private long orderCountOfStation(Long stationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM charging_order WHERE station_id = ?", Long.class, stationId);
    }

    /** 站点下还有几根桩 —— 用来证第一道守卫此刻是放行的 */
    private long chargerCountOfStation(Long stationId) {
        return chargerMapper.countByStationId(stationId);
    }

    // ==================== 第一道守卫：桩 ====================

    @Test
    @DisplayName("★ 站点下还有充电桩就不能删，而且消息里的数字必须是【真的数出来的】")
    void stationWithChargersShouldNotBeDeleted() {
        login();
        Station station = givenStation();
        Charger first = givenCharger(station.getId());
        givenCharger(station.getId());

        assertThatThrownBy(() -> stationService.delete(station.getId()))
                .as("★ 第一道守卫。本用例一笔订单都没有，所以这条同时证了"
                        + "它是【额外】的一道，不是被订单守卫替换掉了")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.STATION_HAS_CHARGER, 2));

        // ★ 被拒绝之后那一行必须还在 —— 「检查通过一半就把行删了」是这里最坏的失败方式
        assertThat(stationMapper.getById(station.getId())).as("拒绝删除不能顺手把站点删掉").isNotNull();

        // ★★ 再删掉一根桩，消息里的数字必须跟着变成 1。
        //    写死成 2（或者把第一次的 COUNT 缓存起来）的实现能通过上面那一句，
        //    只有真的每次都去数，才能同时通过这两句。
        //    —— 一个说错数字的提示比没有提示更坏：管理员会照着它去找 2 根桩。
        jdbcTemplate.update("DELETE FROM charger WHERE id = ?", first.getId());

        assertThatThrownBy(() -> stationService.delete(station.getId()))
                .as("★★ 消息里的数字必须是【这次】数出来的")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.STATION_HAS_CHARGER, 1));
    }

    // ==================== ★★ 第二道守卫：订单（只有它命中） ====================

    @Test
    @DisplayName("★★ 桩已经全部挪走、但站下还有未完结订单时不能删 —— 消息里带着订单数")
    void stationWithInFlightOrdersButNoChargerShouldNotBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());

        // 两笔未完结的订单：一笔正在充电的，一笔异常挂账的。
        // ★ 挂账（9）必须也在判据里 —— 它是【挂起】不是终结：还有一笔钱冻着、
        //   等人处理，这时把站点删掉会让处理它的人失去现场（见 OrderStatus.blocksDeviceDeletion）。
        //   两笔也是刻意的：数字 2 能同时钉住「9 在里面」和「数字是数出来的」。
        givenOrder(station.getId(), charger.getId(), OrderStatus.CHARGING);
        givenOrder(station.getId(), charger.getId(), OrderStatus.ABNORMAL);

        // ★★ 手工制造数据漂移：把桩删掉，模拟「本守卫上线之前桩就已经被删了」的历史数据。
        //    这是【唯一】能让第二道守卫单独命中的形状 —— 正常路径下删站要求先删桩，
        //    而删桩时同一批订单就已经被 ChargerServiceImpl 的那道守卫拒绝过一次了。
        //    所以这里不能用 service 删桩（那会被订单守卫挡住），只能直接动库。
        jdbcTemplate.update("DELETE FROM charger WHERE id = ?", charger.getId());

        assertThat(chargerCountOfStation(station.getId()))
                .as("前置条件：第一道守卫此刻必须已经放行，否则本用例证明不了第二道")
                .isZero();

        assertThatThrownBy(() -> stationService.delete(station.getId()))
                .as("★★ 桩没了，但订单的 station_id 还指着这个站 —— 硬删掉会让一笔"
                        + "append-only 的历史订单永久失去它的站点归属")
                .isInstanceOf(DataConflictException.class)
                .hasMessage(String.format(MessageConstant.STATION_HAS_ORDER, 2));

        assertThat(stationMapper.getById(station.getId())).as("拒绝之后站点必须还在").isNotNull();
        assertThat(orderCountOfStation(station.getId())).as("订单当然也一笔都不许动").isEqualTo(2);
    }

    // ==================== ★★ 终态订单不挡删除 ====================

    @Test
    @DisplayName("★★ 只被【已退款 / 已取消】的订单引用时，站点必须能删掉")
    void stationReferencedOnlyByTerminalOrdersShouldBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());

        // 两个终态各一笔。它们代表的是「这个站历史上被用过」，不是「这个站现在还在用」。
        givenOrder(station.getId(), charger.getId(), OrderStatus.REFUNDED);
        givenOrder(station.getId(), charger.getId(), OrderStatus.CANCELLED);

        // 先把桩删掉 —— 这是删站的正常前置条件，而且它自己也得先过订单守卫：
        // 上面这两笔都是终态，所以这一步本来就该成功（终态不挡删桩）
        jdbcTemplate.update("DELETE FROM charger WHERE id = ?", charger.getId());

        stationService.delete(station.getId());

        // ★★ 这一句是本类价值最高的断言（的一半，另一半见下一条用例）。
        //    7 已退款 / 8 已取消 如果被算进 blockingCodes()，这个站点就永远删不掉了：
        //    订单是 append-only 的，没有「删除订单」这个动作，
        //    于是「这个站出过一次账」就等价于「这个站永久占用 uk_station_code」——
        //    把一个可恢复的运维操作（先处理订单、再删站点）变成了不可恢复的。
        assertThat(stationMapper.getById(station.getId()))
                .as("★★ 终态订单不能挡住站点的删除，否则 uk_station_code 会被永久占用")
                .isNull();

        // ★ 反过来说，订单一行都不该少：它们是历史事实，站点没了也要留着。
        //   station_id 指向一个不存在的站是【可接受】的 —— 它是历史快照的入口，
        //   不是一个必须能 JOIN 的活引用（见 OrderStatus.blockingCodes 的 javadoc）。
        assertThat(orderCountOfStation(station.getId()))
                .as("★ 站点被删不该带走订单 —— 订单是 append-only 的")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 只被【5 已支付】的订单引用时，站点同样必须能删掉 —— 曾经是红的，抓到了那一个 bug")
    void stationReferencedByPaidOrderShouldBeDeleted() {
        login();
        Station station = givenStation();
        Charger charger = givenCharger(station.getId());
        givenOrder(station.getId(), charger.getId(), OrderStatus.PAID);

        jdbcTemplate.update("DELETE FROM charger WHERE id = ?", charger.getId());

        // ★★ 这一句曾经抛 DataConflictException（「该站点下还有 1 笔未完结的充电订单，请先处理」），
        //    因为生产代码违背了自己的 javadoc：blocksDeviceDeletion() 当时是
        //    return !isTerminal()，而 isTerminal() 只认 7/8，于是 5 已支付 被算了进去。
        //    后果是「任何完成过一次充电的站点都删不掉」—— 而那正是
        //    OrderStatus.blocksDeviceDeletion() / MessageConstant.STATION_HAS_ORDER
        //    / ChargingOrderMapper.countByStation 三处 javadoc 都点名说【不能发生】的事。
        //    修法就在 blocksDeviceDeletion() 那一行（改成 holdsMoney() || this == ABNORMAL）。
        //    完整复盘见类注释那一节。
        //
        //    ★ 下面这句断言【不许】被改弱成 isInstanceOf(DataConflictException)，
        //      那等于承认「已支付的订单可以挡住删除」，把它变回一个静默的设计错误。
        stationService.delete(station.getId());

        assertThat(stationMapper.getById(station.getId()))
                .as("★★ 已支付的订单不能挡住站点删除 —— 它的钱已经结清、枪也早就放了")
                .isNull();
    }

    @Test
    @DisplayName("★ blockingCodes() 里【没有】5 已支付 —— 它与上一条红在同一个实现上，一起修好的")
    void blockingCodesShouldExcludePaid() {
        // ★★ 上一条测试是「行为」，这一条是「规定」。分开写是因为它们的失败信息
        //    指向完全不同的地方：行为变了要去看 assertDeletable 的 SQL，
        //    规定变了要去看 OrderStatus.blocksDeviceDeletion。
        //
        //    ★ 这两条一起抓到过那个 bug：旧实现（return !isTerminal()）实测给出
        //      [1, 2, 3, 4, 5, 6, 9]。现在实现是 holdsMoney() || this == ABNORMAL，
        //      下面每一句都成立。复盘见类注释那一节。
        assertThat(OrderStatus.blockingCodes())
                .as("★★ 5 已支付 特意不在里面。它的钱已经结清、枪也早就放了，"
                        + "拿它挡住删除等于让任何出过单的站点永久不可删")
                .doesNotContain(OrderStatus.PAID.getCode());

        // 两个终态都不挡
        assertThat(OrderStatus.blockingCodes())
                .as("★ 终态（7 已退款 / 8 已取消）同样不挡：它们不会有任何后续流转了")
                .doesNotContain(OrderStatus.REFUNDED.getCode())
                .doesNotContain(OrderStatus.CANCELLED.getCode());

        // 该在的一个都不能少，尤其是 9 异常挂账 —— 它是挂起不是终结
        assertThat(OrderStatus.blockingCodes())
                .as("★★ 判据是「钱还冻着」∪「挂账等人工」。9 异常挂账必须在内："
                        + "它的钱还冻着、账没核销，这时删掉站点会让处理它的人失去现场")
                .containsExactlyInAnyOrder(
                        OrderStatus.CREATED.getCode(),
                        OrderStatus.CHARGING.getCode(),
                        OrderStatus.SETTLING.getCode(),
                        OrderStatus.PENDING_PAY.getCode(),
                        OrderStatus.PAY_FAILED.getCode(),
                        OrderStatus.ABNORMAL.getCode());

        // ★ 和另外两个集合的关系：blockingCodes 比 holdingMoneyCodes 恰好多一个 9；
        //   用 holdingConnectorCodes()（只有 1/2/3）来挡删除的话，
        //   4 待支付 / 6 支付失败 / 9 异常挂账 的订单挡不住删除，而且不报错。
        assertThat(OrderStatus.blockingCodes())
                .as("★★ 用错集合是【静默】失败：这里少一个状态，就有一类订单挡不住删除")
                .containsAll(OrderStatus.holdingMoneyCodes());
    }
}
