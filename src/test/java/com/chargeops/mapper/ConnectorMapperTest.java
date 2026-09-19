package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.ConnectorPageQueryDTO;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ConnectorMapper} 的集成测试 —— <b>P1a 里价值最高的一组断言</b>
 *
 * <p>通用写法见 {@link StaffMapperTest}。这个类里每一个测试都对应一条
 * 「写错了不会报错、只会在几个月后以数据问题的形式浮出来」的机制。
 *
 * <h2>本类的四条承重断言</h2>
 *
 * <ol>
 *   <li>{@link #insertMustNotUseAutoFill()} —— ★★ 用反射断言
 *       {@code Connector} <b>没有</b> {@code setCreateUser}。
 *       这是「为什么这个 Mapper 不能标 {@code @AutoFill}」的<b>可执行文档</b>：
 *       谁哪天给实体加了字段，它会立刻失败</li>
 *   <li>{@link #versionShouldProvideOptimisticLocking()} —— ★★ 乐观锁真的会挡住
 *       过期的写入。它是这套并发方案的唯一探针</li>
 *   <li>{@link #currentOrderIdShouldBeMutuallyExclusive()} —— ★★
 *       {@code uk_connector_current_order} 的枪口互斥。
 *       <b>P1a 从不写这一列</b>，但整个「一把枪同时只能服务一笔订单」
 *       的保证全压在它上面</li>
 *   <li>{@link #currentOrderIdShouldBeNullForNewConnectors()} —— ★★
 *       P1a 创建出来的枪<b>必然</b>是空闲的（结构上不可能被占用）</li>
 * </ol>
 */
@SpringBootTest
class ConnectorMapperTest {

    @Autowired
    private ConnectorMapper connectorMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdChargerIds = new ArrayList<>();
    private final List<Long> createdStationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdChargerIds) {
            jdbcTemplate.update("DELETE FROM connector WHERE charger_id = ?", id);
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
                .code("ST-CON-" + System.nanoTime())
                .name("枪测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        createdStationIds.add(station.getId());
        return station;
    }

    /**
     * 建一根桩，连同它所在的站点
     *
     * <p>★ 注意每次调用都会建一个<b>新的站点</b>。想让两根桩共处一站，
     * 用 {@link #insertChargerIn(Station)} —— 这个区分在一次测试失败之后
     * 才被补上：当时我以为两次 {@code insertCharger()} 会得到同一站点的
     * 两根桩，于是断言「按 stationId 查应该有 3 把枪」，
     * 实际只查到 2 把。<b>是我的假设错了，不是代码错了。</b>
     */
    private Charger insertCharger() {
        return insertChargerIn(insertStation());
    }

    private Charger insertChargerIn(Station station) {
        Charger charger = Charger.builder()
                .stationId(station.getId())
                .deviceNo("DEV-CON-" + System.nanoTime())
                .name("枪测试桩")
                .type(1)
                .ratedPowerKw(new BigDecimal("120.00"))
                // ★ gun_count 是 NOT NULL，必须显式给值（DDL 的 DEFAULT 救不了 null）
                .gunCount(0)
                .status(1)
                .build();
        chargerMapper.insert(charger);
        createdChargerIds.add(charger.getId());
        return charger;
    }

    /**
     * 建一把枪。
     *
     * <p>★ 注意这里<b>手动 set 了两个时间</b> —— 因为 {@code ConnectorMapper.insert}
     * 没有 {@code @AutoFill}，也没有别的东西会填它们。
     * 这不是测试的权宜之计，而是 {@code ConnectorServiceImpl} 要做的同一件事：
     * 这两个时间必须由<b>应用侧</b>给出，好让全库的时间只有一个来源。
     */
    private Connector insertConnector(Charger charger, String gunNo) {
        Connector connector = Connector.builder()
                .chargerId(charger.getId())
                .stationId(charger.getStationId())
                .gunNo(gunNo)
                .connectorType("GB/T 20234.3")
                .ratedPowerKw(new BigDecimal("120.00"))
                .status(1)
                .build();
        connector.setCreateTime(LocalDateTime.now());
        connector.setUpdateTime(LocalDateTime.now());
        connectorMapper.insert(connector);
        assertNotNull(connector.getId(), "insert 后 id 应被回填");
        return connector;
    }

    // ==================== ★★ 一、无 @AutoFill ====================

    @Test
    @DisplayName("★★ Connector 实体【没有】createUser/updateUser —— 这就是它不能标 @AutoFill 的原因")
    void insertMustNotUseAutoFill() throws NoSuchMethodException {
        // ★★ 这条断言是整个类存在的首要理由。
        //
        //   AutoFillInterceptor 是【反射】去找 setCreateUser(Long) 的，
        //   找不到直接抛 IllegalStateException —— 不是静默跳过。
        //   而 connector 表只有 create_time / update_time 两列，
        //   所以一旦有人「顺手」给写方法加上 @AutoFill，
        //   所有枪的增删改会【当场全部炸掉】。
        //
        //   这条断言把「为什么不能标」从注释里的一句话，
        //   变成了一条会失败的测试：
        //   谁哪天给 Connector 加上了 createUser 字段（或者反过来，
        //   以为它应该有而加上去），它会立刻告诉你不该这么做。
        try {
            Method m = Connector.class.getMethod("setCreateUser", Long.class);
            fail("Connector 不应该有 setCreateUser —— 出现了说明有人给实体加了 create_user 字段。"
                    + "如果 connector 表真的要加这一列，那要先改 DDL，"
                    + "并同步更新 ConnectorMapper 的类注释和这条测试。实际拿到：" + m);
        } catch (NoSuchMethodException expected) {
            // 正是期望的结果
        }
        assertThrows(NoSuchMethodException.class,
                () -> Connector.class.getMethod("setUpdateUser", Long.class));

        // ★ 对照组：Station 四列齐全，它【有】这两个 setter。
        //   没有这个对照，上面那两条断言可能只是因为方法名拼错了而通过。
        assertNotNull(Station.class.getMethod("setCreateUser", Long.class),
                "Station 应该有 setCreateUser（它的 Mapper 标了 @AutoFill）");
        assertNotNull(Station.class.getMethod("setUpdateUser", Long.class));
    }

    @Test
    @DisplayName("★ 插入的两个时间来自 Java 侧，不是数据库默认值")
    void insertShouldUseJavaSideTimestamps() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();

        LocalDateTime before = LocalDateTime.now();
        Connector connector = insertConnector(charger, "A");
        LocalDateTime after = LocalDateTime.now();

        // ★ 这条断言和「不为 null」的区别：DDL 里这两个列都有
        //   DEFAULT CURRENT_TIMESTAMP(3)，所以即使 Service 完全忘了 set，
        //   库里也会有值 —— 只是那来自【数据库服务器】的时钟。
        //   而全库其它表的时间来自【应用服务器】。两台机器的时钟总会差几毫秒，
        //   于是「这笔订单创建时那把枪是什么状态」这类跨表时间比较
        //   会出现看似不可能的次序。
        assertTrue(connector.getCreateTime().isAfter(before.minusSeconds(1))
                        && connector.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 不在本次调用的时间区间内 —— 很可能是数据库默认值填的，"
                        + "说明调用方忘了 set：" + connector.getCreateTime());

        // ★ 读回来必然差最多 1 毫秒 —— 而且**不是**舍入方向固定的一毫秒。
        //   datetime(3) 只有毫秒精度，而 LocalDateTime.now() 是纳秒：
        //   落库时超出部分被【四舍五入】（不是截断）。
        //   我第一次写这条断言时按「截断」算了期望值（nano / 1_000_000 * 1_000_000），
        //   单独跑时恰好没触发进位，在整个测试套件里跑就失败了 ——
        //   报的是 expected .014 but was .015。
        //
        //   ★ 结论和 StaffMapperTest 里那段一样：**别拿内存值去比库值**，
        //     要比就两边都从库里取。这里要断言的其实只有一件事 ——
        //     写进去和读回来是同一个瞬间（误差不超过 datetime(3) 的一格）。
        //     这个精度差是设计如此：毫秒对时间戳绰绰有余，
        //     为迁就测试改成 datetime(6) 是舍本逐末。
        Connector reloaded = connectorMapper.getById(connector.getId());
        long diffMs = Math.abs(java.time.Duration.between(
                connector.getCreateTime(), reloaded.getCreateTime()).toMillis());
        assertTrue(diffMs <= 1,
                "读回来的 create_time 应该和写进去的是同一个瞬间（datetime(3) 精度），"
                        + "实际差了 " + diffMs + " 毫秒：写 " + connector.getCreateTime()
                        + "，读 " + reloaded.getCreateTime());
    }

    @Test
    @DisplayName("★★ P1a 创建出来的枪必然是空闲的：current_order_id / total_charge_count / version 全走 DDL 默认值")
    void currentOrderIdShouldBeNullForNewConnectors() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();

        Connector connector = Connector.builder()
                .chargerId(charger.getId())
                .stationId(charger.getStationId())
                .gunNo("A")
                .ratedPowerKw(new BigDecimal("120.00"))
                .status(1)
                // ★★ 恶意地显式传这三个字段 —— 它们【不在】insert 的 SQL 里，
                //    所以传了也没用。这条测试证明的是这个「没用」。
                .currentOrderId(9999L)
                .totalChargeCount(999)
                .version(999)
                .build();
        connector.setCreateTime(LocalDateTime.now());
        connector.setUpdateTime(LocalDateTime.now());
        connectorMapper.insert(connector);

        Connector reloaded = connectorMapper.getById(connector.getId());

        // ★★ 这是结构性的保证，不是「Service 里记得别设」：
        //    current_order_id 归 P1c 的订单状态机所有。如果管理端的创建接口
        //    能写这一列，就等于给「枪-订单互斥」这条唯一索引开了一个
        //    可以绕过的注入口 —— 而且是一个永远不会被注意到的注入口，
        //    因为正常流程里没人会传它。
        assertNull(reloaded.getCurrentOrderId(),
                "新建的枪必然是空闲的。current_order_id 被写进去了，"
                        + "说明它出现在 ConnectorMapper.xml 的 insert 列表里 —— 必须删掉");
        assertEquals(0, reloaded.getTotalChargeCount());
        assertEquals(0, reloaded.getVersion());
        assertEquals(1, reloaded.getStatus());
    }

    // ==================== ★★ 二、乐观锁 ====================

    @Test
    @DisplayName("★★ 乐观锁：用过期 version 更新必须影响 0 行")
    void versionShouldProvideOptimisticLocking() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector connector = insertConnector(charger, "A");

        Connector v0 = connectorMapper.getById(connector.getId());
        assertEquals(0, v0.getVersion(), "新建的枪 version 应该是 0");

        // ---- 1) 拿 v0 改一次，应该成功 ----
        // ★ 没有 @AutoFill，所以 update_time 必须由调用方显式给出。
        //   XML 里写的是 `update_time = #{updateTime}`（无条件的），
        //   所以忘了 set 不会「静默保留旧值」，而是直接撞 NOT NULL 约束 ——
        //   见 updateWithoutUpdateTimeShouldFailLoudly。编译器拦不住这件事，数据库能。
        //   ★ 这张表还【没有 update_user】列，所以「谁改的」完全没有记录，
        //   只有两个时间戳能追。这是 DDL 层面接受的代价，不是遗漏。
        v0.setUpdateTime(LocalDateTime.now());
        v0.setGunNo("A1");
        assertEquals(1, connectorMapper.update(v0), "用正确的 version 更新应该影响 1 行");

        Connector v1 = connectorMapper.getById(connector.getId());
        assertEquals("A1", v1.getGunNo(), "改动生效了");
        assertEquals(1, v1.getVersion(), "★ version 应该在 SQL 里自增到 1");

        // ---- 2) ★★ 再拿【过期的 v0】改一次，必须影响 0 行 ----
        Connector stale = Connector.builder()
                .id(connector.getId())
                .gunNo("B1")
                .version(0)          // ← 过期了，库里现在是 1
                .updateTime(LocalDateTime.now())
                .build();

        assertEquals(0, connectorMapper.update(stale),
                "★★ 用过期 version 更新应该影响 0 行。"
                        + "返回 1 说明 WHERE 里的 AND version = #{version} 丢了 —— "
                        + "那样两个并发请求后到的会静默覆盖先到的，没有任何报错");

        // ---- 3) 数据没被改掉 ----
        Connector stillV1 = connectorMapper.getById(connector.getId());
        assertEquals("A1", stillV1.getGunNo(),
                "过期写入被挡住了，gun_no 应该还是 A1，不能是 B1");
        assertEquals(1, stillV1.getVersion(), "被挡住的更新不应该改变 version");

        // ---- 4) 服务端自己读 version 再写 = 装饰性的乐观锁 ----
        //   这条不是断言，是把「为什么 version 必须由客户端回传」写下来：
        //   Service 里如果写成 getById() → 拿到的 version → update()，
        //   那个 version 永远是刚读出来的最新值，检查【永远通过】。
        //   它看起来有并发保护，实际只多了一次查询。
        //   所以 ConnectorVO 里必须带 version，前端改完回传。
        Connector freshlyRead = connectorMapper.getById(connector.getId());
        freshlyRead.setGunNo("A2");
        assertEquals(1, connectorMapper.update(freshlyRead),
                "刚读出来的 version 当然能改成功 —— 这正是「服务端自己读」"
                        + "这种写法的问题所在：它永远成功，所以什么都没保证");
    }

    @Test
    @DisplayName("★ 忘了 set updateTime 会当场报错，而不是静默保留旧值（没有 @AutoFill 的代价与兜底）")
    void updateWithoutUpdateTimeShouldFailLoudly() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector connector = insertConnector(charger, "A");

        Connector missingTime = Connector.builder()
                .id(connector.getId())
                .gunNo("A9")
                .version(0)
                // ★ 故意不 setUpdateTime
                .build();

        // ★ 「没有 @AutoFill」这件事最容易被低估的后果在这里：
        //   没有人会替你填 update_time，而 XML 里那句
        //   `update_time = #{updateTime}` 是无条件的 ——
        //   所以忘了 set 的结果是往一个 NOT NULL 列里写 NULL。
        //
        //   ★ 这个失败方向是**对的**，值得说清楚为什么：
        //     另一种写法是 <if test="updateTime != null">，那样忘了 set 会
        //     静默保留旧值 —— 更新成功了（gun_no 真的改了），
        //     但 update_time 停在几天前。「这条记录最后一次改动是什么时候」
        //     从此是个谎言，而且没有任何迹象提示你。
        //     响亮地失败 > 静默地留下错误的数据。
        assertThrows(DataIntegrityViolationException.class,
                () -> connectorMapper.update(missingTime),
                "update_time 是 NOT NULL 且无条件的，忘了 set 应该报错");

        // 数据也确实没被改
        assertEquals("A", connectorMapper.getById(connector.getId()).getGunNo());
    }

    // ==================== ★★ 三、枪口互斥 ====================

    @Test
    @DisplayName("★★ uk_connector_current_order：两把枪不能同时被同一笔订单占用")
    void currentOrderIdShouldBeMutuallyExclusive() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gunA = insertConnector(charger, "A");
        Connector gunB = insertConnector(charger, "B");

        // ★ 只能用原生 SQL 写这一列 —— ConnectorMapper 的 insert / update
        //   都刻意不碰它（见上一条测试）。这恰恰说明了这个索引的重要性：
        //   P1a 的代码根本写不了它，所以要验证它只能绕过 Mapper。
        //   P1c 的订单状态机会用自己的一条条件更新来写这一列 ——
        //   那条 SQL 和这里的管理端更新是两件事，不该共用一个方法。
        jdbcTemplate.update("UPDATE connector SET current_order_id = ? WHERE id = ?",
                1001L, gunA.getId());
        assertEquals(1001L, connectorMapper.getById(gunA.getId()).getCurrentOrderId());

        // ★★ B 枪也设成 1001 → 必须失败。
        //    这是「一把枪同时只能服务一笔订单」和它的对偶
        //    「一笔订单同时只能占用一把枪」的唯一保证。
        //    没有这个索引，同一笔订单的两次启动请求会各自占用一把枪，
        //    然后两把枪都在往同一笔订单里充电量。
        assertThrows(DuplicateKeyException.class,
                () -> jdbcTemplate.update("UPDATE connector SET current_order_id = ? WHERE id = ?",
                        1001L, gunB.getId()),
                "两把枪不能同时被订单 1001 占用");

        // ---- 换一个订单 id 就可以了 ----
        jdbcTemplate.update("UPDATE connector SET current_order_id = ? WHERE id = ?",
                1002L, gunB.getId());
        assertEquals(1002L, connectorMapper.getById(gunB.getId()).getCurrentOrderId());

        // ---- ★★ 但多把枪的 current_order_id 可以同时是 NULL ----
        //   MySQL 的唯一索引【不约束 NULL】（NULL != NULL），
        //   所以空闲的枪想有多少有多少。这不是 bug，是这张表能工作的前提：
        //   如果唯一索引把 NULL 也当成一个值，整个站点只允许有一把空闲的枪。
        Charger other = insertCharger();
        Connector gunC = insertConnector(other, "A");
        Connector gunD = insertConnector(other, "B");
        assertNull(connectorMapper.getById(gunC.getId()).getCurrentOrderId());
        assertNull(connectorMapper.getById(gunD.getId()).getCurrentOrderId());
    }

    // ==================== 同桩同枪口号 ====================

    @Test
    @DisplayName("uk(charger_id, gun_no)：同桩同枪口号失败，异桩同枪口号成功")
    void gunNoShouldBeUniqueWithinChargerOnly() {
        BaseContext.setCurrentId(1L);
        Charger charger1 = insertCharger();
        Charger charger2 = insertCharger();

        insertConnector(charger1, "A");

        // 同一根桩上的第二个「A」枪 → 失败
        assertThrows(DuplicateKeyException.class,
                () -> insertConnector(charger1, "A"),
                "同一根桩上不能有两个 A 枪口");

        // ★ 但【另一根桩】上的 A 枪口是合法的 —— 唯一键是
        //   (charger_id, gun_no) 而不是 gun_no。
        //   少了这个断言，一个写成 UNIQUE(gun_no) 的实现也能通过上面那条测试，
        //   而它会让整个车棚里只有一根桩能有 A 枪口。
        assertNotNull(insertConnector(charger2, "A").getId(),
                "不同桩上的同名枪口号应该允许");

        // 同一根桩上换个枪口号也可以
        assertNotNull(insertConnector(charger1, "B").getId());
    }

    // ==================== 必填字段 ====================

    @Test
    @DisplayName("rated_power_kw 是 NOT NULL：漏传直接报错（DTO 层必须拦住）")
    void missingRatedPowerShouldFailLoudly() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();

        assertThrows(DataIntegrityViolationException.class, () -> {
            Connector bad = Connector.builder()
                    .chargerId(charger.getId())
                    .stationId(charger.getStationId())
                    .gunNo("A")
                    .status(1)
                    .build();
            bad.setCreateTime(LocalDateTime.now());
            bad.setUpdateTime(LocalDateTime.now());
            connectorMapper.insert(bad);
        });
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：chargerId / stationId / status 三个维度")
    void pageShouldFilterCorrectly() {
        BaseContext.setCurrentId(1L);
        // ★ 两根桩必须在【同一个站点】下，下面第 2 条断言才有意义 ——
        //   它验的正是「枪冗余一份 station_id」这个决定的回报：
        //   「这个站有哪些枪」不需要 join charger。
        Station station = insertStation();
        Charger charger1 = insertChargerIn(station);
        Charger charger2 = insertChargerIn(station);

        insertConnector(charger1, "A");
        insertConnector(charger1, "B");
        Connector fault = insertConnector(charger2, "A");

        // ---- 1) 按 chargerId ----
        ConnectorPageQueryDTO q1 = new ConnectorPageQueryDTO();
        q1.setChargerId(charger1.getId());
        PageHelper.startPage(1, 10);
        List<Connector> rows1 = connectorMapper.page(q1);
        assertEquals(2, rows1.size());
        assertEquals(2, ((Page<Connector>) rows1).getTotal());

        // ---- 2) 按 stationId（★ 这个冗余列存在的意义：免去 join charger） ----
        ConnectorPageQueryDTO q2 = new ConnectorPageQueryDTO();
        q2.setStationId(station.getId());
        PageHelper.startPage(1, 10);
        assertEquals(3, connectorMapper.page(q2).size(),
                "同一站点下两根桩的三把枪都该被查到 —— 不需要 join charger");

        // ---- 3) 按 status ----
        Connector toFault = connectorMapper.getById(fault.getId());
        toFault.setStatus(4);
        toFault.setUpdateTime(LocalDateTime.now());
        connectorMapper.update(toFault);

        ConnectorPageQueryDTO q3 = new ConnectorPageQueryDTO();
        q3.setChargerId(charger2.getId());
        q3.setStatus(4);
        PageHelper.startPage(1, 10);
        assertEquals(1, connectorMapper.page(q3).size());

        ConnectorPageQueryDTO q4 = new ConnectorPageQueryDTO();
        q4.setChargerId(charger2.getId());
        q4.setStatus(1);
        PageHelper.startPage(1, 10);
        assertEquals(0, connectorMapper.page(q4).size(), "那把枪已经改成故障状态了");

        // ---- 4) gun_no 是包含匹配（枪口号就一个字符，前缀没有意义） ----
        ConnectorPageQueryDTO q5 = new ConnectorPageQueryDTO();
        q5.setStationId(charger1.getStationId());
        q5.setGunNo("A");
        PageHelper.startPage(1, 10);
        assertEquals(2, connectorMapper.page(q5).size(), "两根桩上各有一把 A 枪");
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("deleteById 是真删；删完那个枪口号可以被重新使用")
    void deleteShouldFreeTheGunNo() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector connector = insertConnector(charger, "A");

        // 同桩同枪口号此时插不进去
        assertThrows(DuplicateKeyException.class, () -> insertConnector(charger, "A"));

        assertEquals(1, connectorMapper.deleteById(connector.getId()));

        // ★ 删掉之后枪口号就空出来了 —— 硬件换枪是运维的常规操作
        //   （枪线坏了，换一把新的插回同一个枪口）
        assertNotNull(insertConnector(charger, "A").getId(),
                "删掉的枪口号应该可以被重新使用");

        assertEquals(0, connectorMapper.deleteById(connector.getId()), "重复删除返回 0 行");
    }
}
