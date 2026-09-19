package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Station;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * {@link ChargerMapper} 的集成测试
 *
 * <p>通用写法见 {@link StaffMapperTest}。
 *
 * <h2>★★ 本类最重要的断言是 {@code refreshGunCount}</h2>
 *
 * <p>{@code charger.gun_count} 是 DDL 明写的<b>冗余列</b>（注释：
 * 「枪数，冗余自 connector 便于列表展示」）。冗余列的性质是：
 * <b>它不会自己出错，也永远不会自己发现自己错了。</b>
 *
 * <p>漂移的样子：页面上显示「A区-01（2 枪）」，点进去只有 1 把枪。
 * 它不报错、不影响充电、不影响计费，只会让所有人对列表页的数字失去信任 ——
 * 一直到有人手工数了一遍枪才会被发现。
 *
 * <p>所以这条测试不是「顺便验一下」，它是 {@code gun_count} 这个字段
 * <b>唯一的存在证明</b>。而它偏偏住在 {@link ConnectorMapper} 上
 * （SQL 更新的是 charger 表，但它属于枪的生命周期）——
 * 放进这个类是因为它断言的<b>结果</b>是 charger 的列。
 */
@SpringBootTest
class ChargerMapperTest {

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private StationMapper stationMapper;

    /** ★ refreshGunCount 住在 ConnectorMapper 上，但它写的是 charger 表 */
    @Autowired
    private ConnectorMapper connectorMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdChargerIds = new ArrayList<>();
    private final List<Long> createdStationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 先删枪（引用者），再删桩，最后删站点 —— 没有外键约束，
        // 但这个顺序让「如果哪天加了外键，这里不会突然开始失败」
        if (!createdChargerIds.isEmpty()) {
            for (Long id : createdChargerIds) {
                jdbcTemplate.update("DELETE FROM connector WHERE charger_id = ?", id);
            }
        }
        for (Long id : createdChargerIds) {
            jdbcTemplate.update("DELETE FROM charger WHERE id = ?", id);
        }
        for (Long id : createdStationIds) {
            jdbcTemplate.update("DELETE FROM station WHERE id = ?", id);
        }
        createdChargerIds.clear();
        createdStationIds.clear();
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private Station insertStation() {
        Station station = Station.builder()
                .code("ST-CHG-" + System.nanoTime())
                .name("桩测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        createdStationIds.add(station.getId());
        return station;
    }

    private Charger insertCharger(Long stationId, String deviceNo) {
        Charger charger = Charger.builder()
                .stationId(stationId)
                .deviceNo(deviceNo)
                .name("A区-01")
                .type(1)
                .ratedPowerKw(new BigDecimal("7.35"))
                // ★ gun_count 是 NOT NULL，全列插入下【必须显式给值】——
                //   DDL 的 DEFAULT 1 救不了 null（实测 errno 1048）。
                //   给 0 而不是 1，理由见 ChargerMapper.xml 的 insert 注释：
                //   刚建好的桩一把枪都没有，0 才是真的。
                .gunCount(0)
                .status(1)
                .build();
        chargerMapper.insert(charger);
        assertNotNull(charger.getId(), "insert 后 id 应被回填");
        createdChargerIds.add(charger.getId());
        return charger;
    }

    private Connector insertConnector(Long chargerId, Long stationId, String gunNo) {
        Connector connector = Connector.builder()
                .chargerId(chargerId)
                .stationId(stationId)
                .gunNo(gunNo)
                .connectorType("GB/T 20234.3")
                .ratedPowerKw(new BigDecimal("120.00"))
                .status(1)
                .build();
        // ★ 没有 @AutoFill，所以这两个时间必须由调用方显式设置 ——
        //   这正是 ConnectorMapperTest 里重点验证的那件事，这里先照做
        connector.setCreateTime(LocalDateTime.now());
        connector.setUpdateTime(LocalDateTime.now());
        connectorMapper.insert(connector);
        assertNotNull(connector.getId());
        return connector;
    }

    // ==================== INSERT ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填审计四列，decimal(6,2) 单价值原样往返")
    void insertShouldFillAuditColumnsAndRoundTripDecimal() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        LocalDateTime before = LocalDateTime.now();

        Charger charger = insertCharger(station.getId(), "DEV-CHG-" + System.nanoTime());
        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, charger.getCreateUser());
        assertEquals(1L, charger.getUpdateUser());
        assertTrue(charger.getCreateTime().isAfter(before.minusSeconds(1))
                        && charger.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 应该是 Java 侧的时钟，不是数据库默认值");
        assertEquals(charger.getCreateTime(), charger.getUpdateTime());

        // ★ decimal(6,2) 的往返：7.35 是一个二进制浮点数表示不精确的值
        //   （double 的 7.35 是 7.3499999999999996），
        //   所以它专门用来抓「有人把 decimal 改成了 double」
        Charger reloaded = chargerMapper.getById(charger.getId());
        assertEquals(0, new BigDecimal("7.35").compareTo(reloaded.getRatedPowerKw()),
                "decimal(6,2) 的 7.35 往返后数值应该精确相等");

        // ★ gun_count 刻意【不】继承 DDL 的默认值 1 ——
        //   刚建好的桩一把枪都没有，真实值是 0。
        //   默认值 1 会让列表页显示一把不存在的枪。
        //   见 ChargerMapper.xml 的 insert 注释。
        assertEquals(0, reloaded.getGunCount(),
                "新建的桩应该是 0 把枪。得到 1 说明有人改成依赖 DDL 默认值了");
        assertEquals(1, reloaded.getType());
        assertEquals("A区-01", reloaded.getName());
        assertNull(reloaded.getFirmwareVersion(), "没填的可空字段应该是 null 而不是空串");
    }

    @Test
    @DisplayName("★★ rated_power_kw 是 NOT NULL 且【无默认值】，漏传直接报错")
    void missingRatedPowerShouldFailLoudly() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();

        // ★ 和 station.open_time 那个坑是同一类问题，但这个更严重：
        //   open_time 至少有 DEFAULT，这里**连默认值都没有**，
        //   所以 DTO 层的 @NotNull 是唯一的防线。
        //   少了它，用户看到的是一个只提到列名的 500 错误。
        assertThrows(DataIntegrityViolationException.class,
                () -> chargerMapper.insert(Charger.builder()
                        .stationId(station.getId())
                        .deviceNo("DEV-NOPWR-" + System.nanoTime())
                        .type(1)
                        .status(1)
                        .build()),
                "不传 rated_power_kw 应该撞 NOT NULL 约束");
    }

    @Test
    @DisplayName("重复 device_no 抛 DuplicateKeyException")
    void duplicateDeviceNoShouldThrow() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        String deviceNo = "DEV-DUP-" + System.nanoTime();
        insertCharger(station.getId(), deviceNo);

        assertThrows(DuplicateKeyException.class,
                () -> insertCharger(station.getId(), deviceNo));
    }

    // ==================== ★★ refreshGunCount ====================

    @Test
    @DisplayName("★★ refreshGunCount：gun_count 必须等于真实的 COUNT(*)，且是【重算】不是自增")
    void refreshGunCountShouldMatchActualCount() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Charger charger = insertCharger(station.getId(), "DEV-COUNT-" + System.nanoTime());

        // ---- 1) 加 3 把枪，刷一次 ----
        insertConnector(charger.getId(), station.getId(), "A");
        Connector gunB = insertConnector(charger.getId(), station.getId(), "B");
        insertConnector(charger.getId(), station.getId(), "C");

        assertEquals(1, connectorMapper.refreshGunCount(charger.getId()));

        // ★ 断言的是「charger 里的值 == connector 里数出来的值」，
        //   而不是「== 3」。写法上的区别很重要：前者是一个恒等式，
        //   就算 countByChargerId 和 refreshGunCount 的 SQL 哪天都被改错了
        //   （比如都漏了 WHERE），只要漏得一样，这条断言依然成立 ——
        //   所以下面还要拿一个**常量**去比一次，两个断言缺一不可。
        assertEquals(connectorMapper.countByChargerId(charger.getId()),
                chargerMapper.getById(charger.getId()).getGunCount(),
                "gun_count 和 COUNT(*) 应该永远相等");
        assertEquals(3, chargerMapper.getById(charger.getId()).getGunCount(),
                "3 把枪，gun_count 就应该是 3");

        // ---- 2) 删掉 1 把，再刷一次 —— 这一步才证明是「重算」而不是「自增」 ----
        jdbcTemplate.update("DELETE FROM connector WHERE id = ?", gunB.getId());

        connectorMapper.refreshGunCount(charger.getId());

        // ★★ 如果实现是 gun_count = gun_count + 1 / - 1 这种增量式，
        //    并发下会丢更新，而**单线程下看起来完全正确**。
        //    这一步删完再刷得到 2 而不是 4，才说明它是重新数了一遍。
        assertEquals(2, chargerMapper.getById(charger.getId()).getGunCount(),
                "删掉一把枪后 gun_count 应该是 2。得到 4 说明是自增实现；"
                        + "得到 3 说明根本没有重算");

        // ---- 3) 全删光，gun_count 归 0 ----
        jdbcTemplate.update("DELETE FROM connector WHERE charger_id = ?", charger.getId());
        connectorMapper.refreshGunCount(charger.getId());
        assertEquals(0, chargerMapper.getById(charger.getId()).getGunCount(),
                "一把枪都没有时 gun_count 应该是 0");
    }

    // ==================== countByStationId ====================

    @Test
    @DisplayName("countByStationId：站点删除守卫读的就是它")
    void countByStationIdShouldCountOnlyThatStation() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Station other = insertStation();

        assertEquals(0, chargerMapper.countByStationId(station.getId()),
                "新建的站点应该是 0 个桩");

        insertCharger(station.getId(), "DEV-S1-" + System.nanoTime());
        insertCharger(station.getId(), "DEV-S2-" + System.nanoTime());
        insertCharger(other.getId(), "DEV-S3-" + System.nanoTime());

        assertEquals(2, chargerMapper.countByStationId(station.getId()));
        assertEquals(1, chargerMapper.countByStationId(other.getId()),
                "★ 这个断言是这条测试的意义：如果 SQL 漏了 WHERE station_id，它会变成 3");
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("UPDATE：改 update_* 不碰 create_*，且【改不了】gun_count")
    void updateShouldNotTouchCreateColumnsNorGunCount() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Charger charger = insertCharger(station.getId(), "DEV-UPD-" + System.nanoTime());

        Charger afterInsert = chargerMapper.getById(charger.getId());
        LocalDateTime originalCreateTime = afterInsert.getCreateTime();
        Long originalCreateUser = afterInsert.getCreateUser();

        // 造一个真实状态：加两把枪并刷新，gun_count 变成 2
        insertConnector(charger.getId(), station.getId(), "A");
        insertConnector(charger.getId(), station.getId(), "B");
        connectorMapper.refreshGunCount(charger.getId());
        assertEquals(2, chargerMapper.getById(charger.getId()).getGunCount());

        BaseContext.setCurrentId(2L);
        chargerMapper.update(Charger.builder()
                .id(charger.getId())
                .name("改过的桩位名")
                .status(4)
                // ★ 故意传一个错的 gun_count 进去
                .gunCount(99)
                .build());

        Charger reloaded = chargerMapper.getById(charger.getId());
        assertEquals("改过的桩位名", reloaded.getName());
        assertEquals(4, reloaded.getStatus());
        assertEquals(2L, reloaded.getUpdateUser(), "update_user 应该是本次的操作者 id=2");
        assertEquals(originalCreateTime, reloaded.getCreateTime(),
                "create_time 被 UPDATE 改掉了");
        assertEquals(originalCreateUser, reloaded.getCreateUser(),
                "create_user 被 UPDATE 改掉了");

        // ★★ gun_count 仍然是 2，不是 99。
        //    这一条守的是「冗余列只有一条写入路径」这个设计：
        //    gun_count 只由 ConnectorMapper.refreshGunCount 维护，
        //    Mapper 的 <set> 里刻意没有它。
        //    如果哪天有人「顺手」把它加进 <set>，这条断言会失败 ——
        //    而那个改动的后果是：管理员改一次桩位名的同时
        //    能把枪数改成任意值，且不会有人发现（列表页显示的数字
        //    和实际枪数不一致，下次刷新又被覆盖回去）。
        assertEquals(2, reloaded.getGunCount(),
                "gun_count 被 update 改成了 99。它是冗余列，"
                        + "只应该由 ConnectorMapper.refreshGunCount 重算，不该出现在 <set> 里");
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：stationId 过滤、deviceNo 前缀匹配、组合条件与总数")
    void pageShouldFilterCorrectly() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Station other = insertStation();

        String tag = "D" + Long.toString(System.nanoTime() % 100_000_000L, 36);
        insertCharger(station.getId(), "DEV-" + tag + "-A");
        insertCharger(station.getId(), "DEV-" + tag + "-B");
        insertCharger(other.getId(), "DEV-" + tag + "-C");

        // ---- 1) stationId ----
        ChargerPageQueryDTO q1 = new ChargerPageQueryDTO();
        q1.setStationId(station.getId());
        PageHelper.startPage(1, 10);
        List<Charger> rows1 = chargerMapper.page(q1);
        assertEquals(2, rows1.size());
        assertEquals(2, ((Page<Charger>) rows1).getTotal());

        // ---- 2) ★ deviceNo 是【前缀】匹配，不是包含匹配 ----
        ChargerPageQueryDTO q2 = new ChargerPageQueryDTO();
        q2.setDeviceNo("DEV-" + tag);
        PageHelper.startPage(1, 10);
        assertEquals(3, chargerMapper.page(q2).size(), "前缀匹配应该命中全部 3 条");

        // ★ 反过来：把中间一段当查询条件应该【查不到】。
        //   这一条才真正区分了前缀匹配和包含匹配 ——
        //   只测上面那种「用完整前缀去查」的写法，两种实现都能通过。
        ChargerPageQueryDTO q3 = new ChargerPageQueryDTO();
        q3.setDeviceNo(tag);
        PageHelper.startPage(1, 10);
        assertEquals(0, chargerMapper.page(q3).size(),
                "deviceNo 应该是【前缀】匹配。这里返回了数据说明实现变成了 LIKE '%x%'，"
                        + "那会让 uk_charger_device_no 索引失效 —— 而设备号正是运维报障时的搜索入口");

        // ---- 3) 组合条件：stationId + status ----
        ChargerPageQueryDTO q4 = new ChargerPageQueryDTO();
        q4.setStationId(station.getId());
        q4.setStatus(1);
        PageHelper.startPage(1, 10);
        assertEquals(2, chargerMapper.page(q4).size());

        ChargerPageQueryDTO q5 = new ChargerPageQueryDTO();
        q5.setStationId(station.getId());
        q5.setStatus(3);
        PageHelper.startPage(1, 10);
        assertEquals(0, chargerMapper.page(q5).size(), "station 下没有故障状态的桩");

        // ---- 4) 跨站点列表是主要场景：不传 stationId 时应该查到多个站点的桩 ----
        ChargerPageQueryDTO q6 = new ChargerPageQueryDTO();
        q6.setDeviceNo("DEV-" + tag);
        q6.setType(1);
        PageHelper.startPage(1, 10);
        assertEquals(3, chargerMapper.page(q6).size());
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("deleteById 是真删；桩下的枪不会被级联删除")
    void deleteByIdShouldNotCascadeToConnectors() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Charger charger = insertCharger(station.getId(), "DEV-DEL-" + System.nanoTime());
        insertConnector(charger.getId(), station.getId(), "A");

        assertEquals(1, chargerMapper.deleteById(charger.getId()));
        assertNull(chargerMapper.getById(charger.getId()));

        // ★ 没有外键、也没有级联 —— 枪还在，只是指向一根不存在的桩。
        //   这正是「删除守卫必须存在的理由」：Service 层要先拦住
        //   「桩下还有枪」的删除请求，否则库里就会出现这种孤儿数据。
        //   这条断言把「数据库不会帮你兜底」这件事固定下来。
        assertEquals(1, connectorMapper.countByChargerId(charger.getId()),
                "枪不会被级联删除 —— 所以删除守卫必须在 Service 层，不能指望数据库");
    }
}
