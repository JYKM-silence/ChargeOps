package com.chargeops.service.impl;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Station;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.MemberStationService;
import com.chargeops.vo.MemberConnectorVO;
import com.chargeops.vo.MemberStationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MemberStationServiceImpl} 的集成测试 —— 车主端「找站 → 选枪」两个接口
 *
 * <p>通用写法（为什么是集成测试、为什么<b>不能</b>标 {@code @Transactional}、
 * 为什么每条用例自己造站点）见 {@code WalletServiceImplTest} 的类注释，三条理由完全一样。
 * ★ 本类尤其吃「必须是真的库」这一条：<b>下面四条里有两条要钉的边界只存在于 SQL 里</b> ——
 * {@code IN ()} 是语法错误（不是空结果）、{@code GROUP BY} 不给没有匹配行的组。
 * 把 mapper 换成桩类之后，这两条就变成了「我在断言我自己写的桩」，
 * 而真实失败恰恰发生在桩和 MyBatis 不一致的那一侧。
 *
 * <h2>★★ 本类要钉死的四件事</h2>
 *
 * <ol>
 *   <li>★ {@link #emptyResultShouldNotBlowUpOnTheEmptyIdList()} ——
 *       查不到站点时返回<b>空页</b>。它真正钉的是
 *       {@code ConnectorMapper.countByStationIds} 的「{@code ids} 不能是空集合」：
 *       少了那道短路，MyBatis 展开出来的 {@code IN ()} 是<b>SQL 语法错误</b>，
 *       整页 500。<b>这条用例红的方式是抛异常，不是断言失败</b>；</li>
 *   <li>★★ {@link #maintenanceAndDisabledStationsShouldNotAppear()} ——
 *       停用（0）和维护中（2）的站点对车主<b>不存在</b>。
 *       三个站点只有 {@code status} 不同，命中的必须只有那一个；</li>
 *   <li>★★ {@link #connectorsShouldMirrorStatusAndKeepTheExpectedOrder()} ——
 *       {@code available} 就是 {@code status == 1}（快照，不是另一套判据），
 *       且顺序<b>原样透传</b>（{@code gun_no} 是字符串，排它得到 {@code 1, 10, 2}）；</li>
 *   <li>★★ {@link #stationsWithoutIdleConnectorsShouldReportZeroNotNull()} ——
 *       两个「0」：站下一把枪都没有时整行<b>不在</b>聚合结果里（取不到必须当 0，
 *       拆箱即 NPE），而枪全不是空闲时 {@code SUM(status = 1)} 返回的是
 *       <b>NULL 而不是 0</b>（靠 SQL 里的 {@code COALESCE} 落回 0）。</li>
 * </ol>
 *
 * <h2>★ 为什么这里给站点起一个<b>独有的名字</b>，而不是像别处那样按 code 认领</h2>
 *
 * <p>{@code MemberStationPageQueryDTO} 里<b>没有 {@code code}</b>（车主不按编码找站），
 * 只有 {@code name} 和 {@code city}，而 {@code name} 是<b>模糊</b>匹配。
 * 所以本类用 {@value #STATION_NAME_PREFIX} 开头、再拼一个自增序号来保证两件事：
 * ①「查得到自己造的站」；②「查不到别人的」。★ 断言 {@code total} 时才不会
 * 因为库里别的站点恰好命中而飘 —— 那种失败看起来像是分页坏了，实际是夹具撞名。
 *
 * <h2>★ 认领哨兵值（和其它测试类互不重叠）</h2>
 *
 * <p>站点 code 前缀 {@value #STATION_CODE_PREFIX}；设备号前缀 {@value #DEVICE_NO_PREFIX}
 * （{@code uk_charger_device_no} 是全表唯一，不加前缀会和别的测试类撞）；
 * {@code create_user} 用 {@value #TEST_ID_BASE} 起的 id（2e12~9e12 都已被别的测试类占用）。
 *
 * <p>★ 本类<b>不造订单</b>，所以清理全部落在站点 code 前缀上（枪挂在桩上、桩挂在站上），
 * 不需要按 {@code member_id} 区间写「上界」—— 那是给「按 member 区间删订单」
 * 准备的写法（见 {@code StationServiceImplTest} 类注释最后一节）。
 * 那个 id 在这里只有 {@code create_user} 一个用途，将来要往夹具里加订单时
 * 从它往后排，别去借 2e12~9e12。
 */
@SpringBootTest
class MemberStationServiceImplTest {

    @Autowired
    private MemberStationService memberStationService;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private ConnectorMapper connectorMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类造出来的 {@code create_user} 下界 —— 见类注释最后一节 */
    private static final long TEST_ID_BASE = 10_000_000_000_000L;

    /** 站点 code 前缀。{@code station.id} 是自增的，只能按 code 认领 */
    private static final String STATION_CODE_PREFIX = "ST-MBR-";

    /** 设备号前缀 —— {@code uk_charger_device_no} 不接受重名 */
    private static final String DEVICE_NO_PREFIX = "DEV-MBR-";

    /** 站点名前缀。★ 查询条件只有 name / city，见类注释 */
    private static final String STATION_NAME_PREFIX = "车主端站点-";

    private static long SEQ = 0;

    @AfterEach
    void cleanUp() {
        // ★ 子表在前、父表在后。带子查询的那两条靠父行【还在】才查得到子行的 id ——
        //   父行先删掉的话，子查询返回空集、DELETE 影响 0 行，而且是【静默】的。
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
     * 登入一个「操作者」上下文
     *
     * <p>★ 本类<b>不需要</b>一行真 member：两个接口都不查账号、也不认身份
     * （见 {@code MemberStationService} 类注释第二节）。给 id 只是为了
     * {@code @AutoFill} —— 站点的 {@code create_user} 取当前登录人。
     */
    private long login() {
        long operatorId = TEST_ID_BASE + (SEQ++);
        BaseContext.setCurrentId(operatorId);
        return operatorId;
    }

    /**
     * 造一个站点
     *
     * <p>★ {@code open_time} / {@code close_time} 是 {@code NOT NULL} 且这条 INSERT
     * 是全列插入，不传会撞 errno 1048 —— 见 {@code StationMapper.insert} 的注释。
     */
    private Station givenStation(int status, String name) {
        Station station = Station.builder()
                .code(STATION_CODE_PREFIX + (SEQ++))
                .name(name)
                .city("深圳市")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(status)
                .build();
        stationMapper.insert(station);
        assertThat(station.getId()).as("insert 后 id 应被回填").isNotNull();
        return station;
    }

    /** 造一根桩。★ {@code rated_power_kw} 是 {@code NOT NULL} 且连默认值都没有 */
    private Charger givenCharger(Station station) {
        Charger charger = Charger.builder()
                .stationId(station.getId())
                .deviceNo(DEVICE_NO_PREFIX + (SEQ++))
                .name("车主端测试桩")
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
     * <p>★ {@code ConnectorMapper.insert} <b>没有</b> {@code @AutoFill}
     * （{@code connector} 表没有 create_user / update_user 两列），
     * 两个时间必须显式给，漏了撞 errno 1048。
     *
     * <p>★ {@code gunNo} 由调用方给，本类用的是 {@code "1"} / {@code "2"} / {@code "10"} ——
     * 那正是「按字符串排会得到 {@code 1, 10, 2}」的那一组，见第三条用例。
     */
    private Connector givenConnector(Charger charger, String gunNo, int status) {
        LocalDateTime now = LocalDateTime.now();
        Connector connector = Connector.builder()
                .chargerId(charger.getId())
                .stationId(charger.getStationId())
                .gunNo(gunNo)
                .connectorType("GB/T 20234.3")
                .ratedPowerKw(bd("120.00"))
                .status(status)
                .build();
        connector.setCreateTime(now);
        connector.setUpdateTime(now);
        connectorMapper.insert(connector);
        assertThat(connector.getId()).as("insert 后 id 应被回填").isNotNull();
        return connector;
    }

    /** 造一个只带 {@code name} 的查询条件。★ 分页参数用 DTO 的字段初始值（1 / 10） */
    private MemberStationPageQueryDTO queryByName(String name) {
        MemberStationPageQueryDTO query = new MemberStationPageQueryDTO();
        query.setName(name);
        return query;
    }

    /** 一次唯一的站点名 —— 见类注释「为什么起独有的名字」 */
    private String uniqueName(String tag) {
        return STATION_NAME_PREFIX + tag + "-" + (SEQ++);
    }

    /** {@code PageResult.records} 是 {@code List<?>}（对外的形状只承诺到这儿），这里收窄一次 */
    @SuppressWarnings("unchecked")
    private static List<MemberStationVO> recordsOf(PageResult result) {
        return (List<MemberStationVO>) result.getRecords();
    }

    // ==================== ★ 一、空结果 ====================

    @Test
    @DisplayName("★ 一条站点都查不到时返回空页 —— 钉的是 countByStationIds 不接受空 id 集合")
    void emptyResultShouldNotBlowUpOnTheEmptyIdList() {
        PageResult result = memberStationService.page(queryByName(uniqueName("查不到")));

        // ★★ 这一条用例【红的方式是抛异常，不是断言失败】：
        //    没有 toStationVos 开头那句 isEmpty() 短路的话，
        //    countByStationIds 会收到一个空 List，MyBatis 把它展开成 `IN ()` ——
        //    那是 SQL 语法错误（BadSqlGrammarException），不是「返回空列表」。
        //    而「一页站点恰好一条都没有」在业务上很常见（搜了个不存在的站名、
        //    翻到最后一页之后），是日常路径。
        assertThat(result.getTotal()).as("没有匹配的站点时 total 是 0").isZero();
        assertThat(result.getRecords()).as("★ 空页，而不是 500").isEmpty();
    }

    // ==================== ★★ 二、停用 / 维护中的站点不可见 ====================

    @Test
    @DisplayName("★★ 停用（0）和维护中（2）的站点不出现在车主端 —— 只有启用的那个命中")
    void maintenanceAndDisabledStationsShouldNotAppear() {
        login();
        String name = uniqueName("状态");
        Station enabled = givenStation(1, name);
        Station disabled = givenStation(0, name);
        Station maintenance = givenStation(2, name);

        PageResult result = memberStationService.page(queryByName(name));

        // ★ 三个站点只有 status 不同，名字一模一样、也都在同一页里 ——
        //   所以「少了一个」只可能是状态过滤做的，不可能是分页或名字匹配。
        assertThat(recordsOf(result))
                .as("★ 只看得见 1 启用；0 停用和 2 维护中对车主就是不展示")
                .extracting(MemberStationVO::getId)
                .containsExactly(enabled.getId())
                .doesNotContain(disabled.getId(), maintenance.getId());
        assertThat(result.getTotal())
                .as("★ total 也必须跟着过滤 —— 它由 PageHelper 的 COUNT 得出，"
                        + "如果 status 条件只写在内存里过滤，total 会大于 records 的长度，"
                        + "前端翻页时会在最后一页看到一片空白")
                .isEqualTo(1);
    }

    // ==================== ★★ 三、available 与顺序 ====================

    @Test
    @DisplayName("★★ available 就是 status == 1；返回顺序原样透传（gun_no 是字符串，排它得到 1,10,2）")
    void connectorsShouldMirrorStatusAndKeepTheExpectedOrder() {
        login();
        Station station = givenStation(1, uniqueName("选枪"));
        Charger charger = givenCharger(station);

        // ★ 这三把枪的 gun_no 刻意选成「按字符串排会和按 id 排不一致」的那一组：
        //   字符串序是 "1" < "10" < "2"，而插入序是 1 → 2 → 10。
        //   于是「谁顺手加了个 sorted(by gunNo)」会当场把下面第一句断言打红。
        Connector idle = givenConnector(charger, "1", 1);
        Connector occupied = givenConnector(charger, "2", 2);
        Connector faulty = givenConnector(charger, "10", 4);

        List<MemberConnectorVO> vos = memberStationService.listByStation(station.getId());

        // ★ 期望值直接取自 mapper：本用例要证的是「没有重排」，
        //   而不是「恰好按 id 升序」—— 后者是 listByStation 的实现细节，
        //   由 ConnectorMapperTest 覆盖。★ 而且必须带 0 个元素的那一支也要过：
        //   一个空列表和「枪全被删光」看起来一样。
        List<Long> rawIds = connectorMapper.listByStation(station.getId())
                .stream().map(Connector::getId).toList();
        assertThat(vos)
                .as("★ 顺序必须和 listByStation 一致（这里是插入序 1 → 2 → 10）："
                        + "重排是【前端】的职责（先可用、再按桩分组），不是后端的")
                .extracting(MemberConnectorVO::getId)
                .containsExactlyElementsOf(rawIds);

        // ★★ available 只是 status == 1 的方便写法，不是第二套判据。
        //    逐条钉死，而不是只钉总数 —— 后者在「顺序变了」时仍然可能通过。
        assertThat(vos.get(0).getId()).as("第 1 条是「1 空闲」那把").isEqualTo(idle.getId());
        assertThat(vos.get(0).getStatus()).isEqualTo(1);
        assertThat(vos.get(0).getStatusLabel()).isEqualTo("空闲");
        assertThat(vos.get(0).getAvailable()).as("★ 空闲 → true（快照，不保证下单成功）").isTrue();

        assertThat(vos.get(1).getId()).as("第 2 条是「2 已占用」那把").isEqualTo(occupied.getId());
        assertThat(vos.get(1).getStatus()).isEqualTo(2);
        assertThat(vos.get(1).getStatusLabel()).isEqualTo("已占用");
        assertThat(vos.get(1).getAvailable()).as("★ 已占用不是「可用」").isFalse();

        assertThat(vos.get(2).getId()).as("第 3 条是「4 故障」那把").isEqualTo(faulty.getId());
        assertThat(vos.get(2).getStatus()).isEqualTo(4);
        assertThat(vos.get(2).getStatusLabel()).isEqualTo("故障");
        assertThat(vos.get(2).getAvailable()).as("★ 故障不是「可用」").isFalse();

        // ★ 契约：对【每一条】返回的行，available 都等于 status == 1。
        //   写成一个循环而不是三句 get()，是为了让它不依赖本用例造了哪几种状态。
        assertThat(vos).allSatisfy(vo -> assertThat(vo.getAvailable())
                .as("available 必须等价于 status == 1，status=%s", vo.getStatus())
                .isEqualTo(vo.getStatus() == 1));

        // ★ 不过滤状态：故障那把枪必须在（把不能用的枪筛掉会让维护中的站显示成空列表）
        assertThat(vos).hasSize(3);

        // ★ 所属桩的信息是批量补上的 —— 同一个站下多根桩的枪混在一张列表里，
        //   只给 chargerId 的话，用户站在桩前面不知道哪把是自己选的。
        assertThat(vos).allSatisfy(vo -> {
            assertThat(vo.getChargerId()).isEqualTo(charger.getId());
            assertThat(vo.getChargerDeviceNo()).isEqualTo(charger.getDeviceNo());
            assertThat(vo.getChargerName()).isEqualTo(charger.getName());
            assertThat(vo.getChargerTypeLabel()).as("★ 车主端给的是文案，不是数字码").isEqualTo("直流快充");
            assertThat(vo.getStationId()).isEqualTo(station.getId());
        });
    }

    // ==================== ★★ 四、两个「0」 ====================

    @Test
    @DisplayName("★★ 站下没有枪时两个枪数都是 0（不是 null）；枪全不空闲时 idle 也必须是 0")
    void stationsWithoutIdleConnectorsShouldReportZeroNotNull() {
        login();
        String name = uniqueName("计数");
        Station noConnector = givenStation(1, name);
        Station allFaulty = givenStation(1, name);
        Charger charger = givenCharger(allFaulty);
        givenConnector(charger, "1", 4);
        givenConnector(charger, "2", 4);

        PageResult result = memberStationService.page(queryByName(name));
        assertThat(result.getTotal()).as("两个站都在这一页里").isEqualTo(2);
        Map<Long, MemberStationVO> byId = recordsOf(result).stream()
                .collect(Collectors.toMap(MemberStationVO::getId, Function.identity()));

        // ★★ 第一半：站下一把枪都没有时，countByStationIds 的结果里【根本没有这一行】
        //    （GROUP BY 只给有匹配行的组）。所以这里要的是 `map.get(id)` 拿不到时
        //    的默认值 —— 少了它，这一句就是 NPE，而「刚建好、还没装枪的站」
        //    在列表页上是常见状态，不是边界。
        MemberStationVO empty = byId.get(noConnector.getId());
        assertThat(empty).as("这个站必须在页里").isNotNull();
        assertThat(empty.getConnectorCount())
                .as("★★ 一把枪都没有 → 0，不是 null。拆箱取值会在这一句上 NPE")
                .isZero();
        assertThat(empty.getIdleConnectorCount()).isZero();

        // ★★ 第二半（另一条 SQL 边界，方向和上面相反）：这个站有枪，
        //    所以聚合结果的【那一行是存在的】，但 SUM(status = 1) 在没有任何行满足时
        //    返回 NULL 而不是 0 —— 靠 ConnectorMapper.xml 里的 COALESCE 落回 0。
        //    少了 COALESCE，这里拿到的是 null，界面上显示成空白，
        //    而「空白」和「0」会混成同一个展示，它们却是不同的意思。
        MemberStationVO faulty = byId.get(allFaulty.getId());
        assertThat(faulty).as("这个站也必须在页里").isNotNull();
        assertThat(faulty.getConnectorCount())
                .as("★ connectorCount 含故障的枪 —— 它是「有多少把枪」，不是「有多少把能用」")
                .isEqualTo(2);
        assertThat(faulty.getIdleConnectorCount())
                .as("★★ 2 把枪全是故障 → 0（不是 null）：这是 COALESCE 那一半")
                .isZero();
    }
}
