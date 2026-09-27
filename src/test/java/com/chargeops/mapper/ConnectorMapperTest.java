package com.chargeops.mapper;

import com.chargeops.constant.MessageConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.ConnectorStatus;
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
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <h2>★ 五、订单状态机对枪的四个条件更新</h2>
 *
 * <p>{@code occupy} / {@code markCharging} / {@code markOccupied} / {@code release}
 * 是 {@code current_order_id} 的<b>唯一</b>写入点。它们出现之前，上面第 3 条那条
 * 唯一索引是一个<b>空转的约束</b> —— 结构上完美，却没有任何代码写过这一列。
 *
 * <p>这一组测试钉的是<b>谓词</b>，因为调用方唯一的依据就是影响行数：
 * 拿到 0 行必须当成冲突（{@code ConnectorOccupiedException}），
 * 所以「什么情况下返回 0」比「成功时写成什么样」更值得守。
 * 其中两条最容易被后来的改动悄悄破坏：
 *
 * <ul>
 *   <li>{@link #occupyShouldRefuseANonIdleGun()} —— {@code status = 1} 和
 *       {@code current_order_id IS NULL} 是<b>两个</b>条件，少一个就有枪能被下单</li>
 *   <li>{@link #releaseShouldBeIdempotentAndSelfGuarding()} —— {@code release} 的
 *       谓词<b>只有</b> {@code current_order_id}，它必须对「重复调用」
 *       「对别人的枪调用」都无副作用</li>
 * </ul>
 *
 * <p>★ <b>本类的每一个测试都用自己独有的订单号</b>（{@link #newOrderId()}）——
 * 这不是洁癖，见那个方法的注释。
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

    /** 本类生成的订单号下界，见 {@link #newOrderId()} */
    private static final long TEST_ORDER_BASE =
            4_000_000_000_000L + System.nanoTime() % 1_000_000_000L;

    private static final AtomicLong ORDER_SEQ = new AtomicLong();

    /**
     * 给下一个测试造一个<b>独一无二</b>的订单号
     *
     * <p>★★ 这不只是为了「不重名」。两个理由，各自都能让一个测试以一个
     * 和被测代码毫无关系的断言失败：
     *
     * <ol>
     *   <li>{@code ConnectorMapper.release} 的 WHERE 里<b>没有 id</b> ——
     *       它凭 {@code current_order_id = orderId} 能改到<b>全库任意一把枪</b>。
     *       写死一个「测试订单号」（比如 1001），一旦另一个测试类恰好也用了它，
     *       这里的一次 release 就会把别的测试的枪放掉</li>
     *   <li>{@code uk_connector_current_order} 是<b>全库</b>唯一索引，
     *       而测试库就是开发库 —— 别的进程（另一个测试类、一个手动启动的应用）
     *       同样可能让某个固定 id 落在某把枪上，于是 {@code occupy} 会抛一个
     *       谁都没预料到的 {@code DuplicateKeyException}</li>
     * </ol>
     *
     * <p>基数几百亿：真实订单 id 永远到不了这里，所以也不会误伤手上这台库里
     * 正在被别人摆弄的数据。
     */
    private long newOrderId() {
        return TEST_ORDER_BASE + ORDER_SEQ.incrementAndGet();
    }

    /**
     * 按主键读回一把枪，顺带断言它还在
     *
     * <p>★ 本类不硬删枪（那是 {@code deleteById} 的事），所以「读不到」只有一个含义：
     * 上一步的某条语句把行删了 —— 那是一个必须当场暴露的情况，不是一个可以
     * 继续往下断言的 {@code null}。
     */
    private Connector reloadConnector(Long connectorId) {
        Connector row = connectorMapper.getById(connectorId);
        assertNotNull(row, "枪 " + connectorId + " 应该还在");
        return row;
    }

    /**
     * 走<b>管理端那条</b> UPDATE 把枪设成某个状态
     *
     * <p>★ 用真 SQL（连 version 一起回传）而不是
     * {@code jdbcTemplate.update("UPDATE connector SET status = ?")}：
     * 后面几条测试要断言的正是「管理员的改动会推 version、会被乐观锁挡住」，
     * 绕过 Mapper 直接改状态的话 version 停在原地，那些断言就变成了在测一个
     * 和管理端真实行为不一样的场景 —— 测试会绿，但绿得没有意义。
     */
    private void adminSetStatus(Long connectorId, ConnectorStatus status) {
        Connector current = reloadConnector(connectorId);
        Connector edit = Connector.builder()
                .id(connectorId)
                .status(status.getCode())
                .version(current.getVersion())
                .updateTime(LocalDateTime.now())
                .build();
        assertEquals(1, connectorMapper.update(edit),
                "管理员改状态应该成功 —— 返回 0 说明 version 对不上，是前置步骤出了问题");
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

    // ==================== ★★ 四、订单状态机的四个条件更新 ====================
    // 判据是「当前占用者是不是我这一单」（current_order_id = orderId），
    // 和管理端那条 WHERE id = ? AND version = ? 是两件事，刻意不共用一条 SQL。
    // 调用方唯一的依据是影响行数：0 行 = 冲突。所以这一节全部在钉「什么时候是 0 行」。

    @Test
    @DisplayName("★★ occupy 只成功一次：换一笔订单再来一次必须影响 0 行，而且不抛异常")
    void occupyShouldSucceedOnceAndOnlyOnce() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long firstOrder = newOrderId();
        Long secondOrder = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        Connector idle = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), idle.getStatus());
        assertNull(idle.getCurrentOrderId());
        assertEquals(0, idle.getVersion());

        // ---- 1) 空闲枪上的第一次抢占 ----
        assertEquals(1, connectorMapper.occupy(gun.getId(), firstOrder, now),
                "空闲枪上的第一次 occupy 必须影响 1 行");

        Connector occupied = reloadConnector(gun.getId());
        // ★ 抢占必须【同时】写 status。只写 current_order_id 的话，这把枪在管理端列表上
        //   还是「空闲」—— 运维会以为它闲着，可以随便改、随便删，也看不出有人在用。
        //   ConnectorStatus 里那句「2/3 是订单存在性在枪上的投影」说的就是这件事：
        //   投影和本体分两处写、写漏一处，界面上就会有两种互相矛盾的真相。
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), occupied.getStatus(),
                "★ 抢占必须同时把 status 改成 2 已占用");
        assertEquals(firstOrder, occupied.getCurrentOrderId());
        assertEquals(1, occupied.getVersion(),
                "★ occupy 要推 version（理由见 casMethodsShouldBumpVersionSoAStaleAdminEditConflicts）");

        // ---- 2) ★★ 第二次必须影响 0 行 ----
        //   ★ 这里特意换了一笔【不同的】订单号。用同一个订单号的话，「第二次失败」
        //     还可以用 uk_connector_current_order 解释掉 —— 那条断言就测不出 WHERE 里的
        //     status = 1 AND current_order_id IS NULL 到底还在不在。
        //     换成新订单号之后，这条 UPDATE 退化成一次「给这把枪换个占用者」的普通更新：
        //     谓词没了它照样影响 1 行，而且返回值和成功路径一模一样，没有任何异常。
        assertEquals(0, connectorMapper.occupy(gun.getId(), secondOrder, now),
                "★★ 已被占用的枪不能被第二次抢占。返回 1 说明谓词被削弱了 —— "
                        + "两个并发建单请求会各自以为抢到了枪，各自冻钱、各自开始充电。"
                        + "调用方只把 0 行翻译成「" + MessageConstant.CONNECTOR_OCCUPIED + "」，"
                        + "拿到 1 就一路往下走，不会有任何察觉");

        // ---- 3) ★ 失败了就一个字都不该改 ----
        Connector afterFailed = reloadConnector(gun.getId());
        assertEquals(firstOrder, afterFailed.getCurrentOrderId(),
                "★ 枪上留下的必须先到的那个订单号。变成 secondOrder 就是「后来者覆盖先到者」——"
                        + "用户的电费记到输家头上，而输家的订单根本没在充电");
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), afterFailed.getStatus());
        assertEquals(1, afterFailed.getVersion(),
                "★ 被拒绝的那次不能推 version —— 否则管理员手上的 version 会莫名其妙过期，"
                        + "一次和枪无关的失败会变成一次假的编辑冲突");
    }

    @Test
    @DisplayName("★★ 「先查后插」的竞态：两个请求读到的是同一把空闲枪，由数据库裁决出唯一的赢家")
    void occupyShouldLoseTheRaceToTheOrderThatGotThereFirst() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderA = newOrderId();
        Long orderB = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        // ---- 两个「并发」请求各自读了一次枪：两次读到的都是「空闲」 ----
        //   ★ 这就是 occupy 要替代掉的那种写法（查一次有没有进行中的订单，然后建单）
        //     为什么必然失效：两次 SELECT 都会通过检查。SELECT 拿到的只是某个瞬间的
        //     快照，它<b>不是裁决</b> —— 把下面两行写出来是为了让这个「两边都通过」
        //     变成可执行的证据，而不是注释里的一句断言。
        Connector seenByA = reloadConnector(gun.getId());
        Connector seenByB = reloadConnector(gun.getId());
        assertEquals(seenByA.getStatus(), seenByB.getStatus(),
                "★ 两个请求看到的枪状态是一样的（都是 1 空闲）");
        assertNull(seenByA.getCurrentOrderId());
        assertNull(seenByB.getCurrentOrderId());
        assertEquals(seenByA.getVersion(), seenByB.getVersion(),
                "★ 连 version 都一样 —— 所以「读到空闲」在这套方案里不构成任何保证");

        // ---- 裁决只有一条：两条 UPDATE 里必然有一条影响 0 行 ----
        //   ★ 这里是顺序调用的，但两次调用之间【没有任何共享的应用层状态】——
        //     谁赢完全由那条 WHERE 决定。顺序调用和被真正并发时数据库看到的东西
        //     是一样的，区别只是「谁先到达」被固定下来了。
        int aRows = connectorMapper.occupy(gun.getId(), orderA, now);
        int bRows = connectorMapper.occupy(gun.getId(), orderB, now);
        assertEquals(1, aRows, "先到的请求应该抢到枪");
        assertEquals(0, bRows,
                "★★ 后到的请求必须影响 0 行。两条都返回 1 就意味着两个订单同时占着"
                        + "同一把枪，而两边的用户都会看到「启动成功」");

        // ---- ★★ 枪上留下的是 A 的订单号，B 的订单号根本没出现过 ----
        Connector afterRace = reloadConnector(gun.getId());
        assertEquals(orderA, afterRace.getCurrentOrderId(),
                "★★ 占用者不能被后来者顶替。把判据写成「枪上没人」而不是「枪上是不是我」，"
                        + "第二条 UPDATE 也会影响 1 行（它只是把 current_order_id 换了个值）——"
                        + "影响行数完全正常，错误只表现为「A 在充电，账记在 B 头上」");
        assertEquals(1, afterRace.getVersion(), "★ 只有赢的那一次推了 version");
        // ★ 顺带说清两道防线各管一段：挡住 B 的是 occupy 的谓词，不是唯一索引 ——
        //   B 的订单号压根没落到枪上，所以 uk_connector_current_order 这次没有被触发。
        //   索引管的是「一笔订单同时占两把枪」（见 oneOrderShouldNotOccupyTwoGuns），
        //   谓词管的是「一把枪同时接两笔订单」。
    }

    @Test
    @DisplayName("★★ occupy 的 WHERE 里 status = 1 是必需的：故障枪 / 维护枪都不能被下单")
    void occupyShouldRefuseANonIdleGun() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector faultGun = insertConnector(charger, "A");
        Connector maintenanceGun = insertConnector(charger, "B");

        adminSetStatus(faultGun.getId(), ConnectorStatus.FAULT);
        adminSetStatus(maintenanceGun.getId(), ConnectorStatus.MAINTENANCE);

        LocalDateTime now = LocalDateTime.now();

        // ★ 故障枪的 current_order_id 同样是 NULL —— 它和空闲枪唯一的区别就是 status。
        //   所以下面那条断言真的在测 status = 1 这个条件，而不是在测「有没有被占用」。
        Connector fault = reloadConnector(faultGun.getId());
        assertNull(fault.getCurrentOrderId(),
                "★ 前置条件：故障枪没有人占着，它被拒绝的理由只能是状态");
        int faultVersion = fault.getVersion();

        assertEquals(0, connectorMapper.occupy(faultGun.getId(), newOrderId(), now),
                "★★ 「4 故障」的枪绝不能被下单：车插上去充不了电，而这笔订单"
                        + "已经把用户的钱冻上了 —— 冻钱在 occupy 之后、同一个事务里，"
                        + "所以这里返回 1 的后果是「钱冻了，电充不上，用户得找客服」");

        // ★ 再补一把「5 维护」不是凑数：把谓词写成 status != 2 的实现能通过上面那条
        //   （故障 4 ≠ 2），却会同时放行「5 维护」和「0 离线」的枪 ——
        //   而运维正是在给这把枪做保养的时候，看见有人把车停了进来。
        assertEquals(0, connectorMapper.occupy(maintenanceGun.getId(), newOrderId(), now),
                "★★ 「5 维护」的枪同样不能被下单");

        // ---- 被拒绝的那次不能留下痕迹 ----
        Connector faultAfter = reloadConnector(faultGun.getId());
        assertEquals(ConnectorStatus.FAULT.getCode(), faultAfter.getStatus(),
                "★ 被拒绝的 occupy 不能把状态从 4 故障改成 2 已占用：谓词少了 status = 1 的话，"
                        + "影响行数依然是 1，而结果是一把坏掉的枪被派了一笔订单");
        assertNull(faultAfter.getCurrentOrderId());
        assertEquals(faultVersion, faultAfter.getVersion(),
                "★ 0 行的更新不该改变这一行的任何字段 —— version 也算");
        assertEquals(ConnectorStatus.MAINTENANCE.getCode(),
                reloadConnector(maintenanceGun.getId()).getStatus());
    }

    @Test
    @DisplayName("★ 一笔订单不能占两把枪：第二把枪撞的是唯一索引，抛 DuplicateKeyException 而不是返回 0")
    void oneOrderShouldNotOccupyTwoGuns() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gunA = insertConnector(charger, "A");
        Connector gunB = insertConnector(charger, "B");

        Long order = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, connectorMapper.occupy(gunA.getId(), order, now));

        // ★★ 同一笔订单去占第二把枪：枪 B 是「1 空闲且没人占用」，occupy 的谓词在它身上
        //    <b>完全匹配</b> —— 挡住这次写入的是 uk_connector_current_order，抛的是
        //    DuplicateKeyException。
        //
        //    ★ 这是 Mapper 契约里没写明的一种失败：occupy 的 javadoc 说「0 表示枪已被占用、
        //      或状态不再是 1 空闲，调用方据此抛 ConnectorOccupiedException」，
        //      而这条路径既不返回 0、抛出的也不是业务异常。
        //      断言写成 assertThrows 而不是 assertEquals(0, …) 就是不想把这个事实盖过去：
        //      真要改，也该是先决定「同一订单占第二把枪」对用户意味着什么，
        //      再让 SQL 或调用方去表达它，而不是让一个数据库异常漏到接口层。
        assertThrows(DuplicateKeyException.class,
                () -> connectorMapper.occupy(gunB.getId(), order, now),
                "同一笔订单占第二把枪会撞唯一索引");

        // ★★ 而被撞掉的那条 UPDATE 什么都没写进去：枪 B 依旧是空闲的、version 还是 0。
        //    断言必须落在数据上而不是只落在异常上 —— 「抛异常」和「没有留下半个已占用的枪」
        //    是两件事，后者才是这里真正要保住的性质。
        Connector untouched = reloadConnector(gunB.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), untouched.getStatus());
        assertNull(untouched.getCurrentOrderId(),
                "★ 枪 B 不能被写成「已被订单 order 占用」");
        assertEquals(0, untouched.getVersion());

        // ---- 对照：换一笔订单，枪 B 立刻就能被正常占用 ----
        //   没有这一条，上面「枪 B 没被写脏」也可能只是因为枪 B 根本不能被占用。
        assertEquals(1, connectorMapper.occupy(gunB.getId(), newOrderId(), now),
                "★ 对照：另一笔订单来了，枪 B 应该照常被抢到");
    }

    @Test
    @DisplayName("★★ markCharging / markOccupied 只认自己的订单：别人的启动上报改不到这把枪")
    void markChargingAndMarkOccupiedShouldOnlyMatchTheirOwnOrder() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long mine = newOrderId();
        Long someoneElse = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, connectorMapper.occupy(gun.getId(), mine, now));

        // ---- 1) ★★ 别人的订单上报「启动成功」 ----
        assertEquals(0, connectorMapper.markCharging(gun.getId(), someoneElse, now),
                "★★ 谓词必须是 current_order_id = orderId，不能只按 id 写。"
                        + "只按 id 写的话，一笔迟到的（或者干脆是伪造的）启动上报"
                        + "会把别人的枪改成「3 充电中」，而枪上的钱是按【另一笔】订单冻的 —— "
                        + "设备在输电，账单记在另一个人头上");

        Connector stillMine = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), stillMine.getStatus(),
                "★ 0 行之后状态必须原封不动");
        assertEquals(mine, stillMine.getCurrentOrderId(),
                "★ 占用者也不能被换掉 —— 换掉了这笔订单在结算时就找不到自己的枪了");
        assertEquals(1, stillMine.getVersion(), "★ 被拒绝的上报不该推 version");

        // ---- 2) 我自己的上报：2 已占用 → 3 充电中 ----
        assertEquals(1, connectorMapper.markCharging(gun.getId(), mine, now));
        Connector charging = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.CHARGING.getCode(), charging.getStatus());
        assertEquals(mine, charging.getCurrentOrderId(),
                "★ 充电期间 current_order_id 不能被清掉 —— 它还要在结束时用来还枪");
        assertEquals(2, charging.getVersion());

        // ---- 3) ★ 别人的「结束」上报同样进不来 ----
        assertEquals(0, connectorMapper.markOccupied(gun.getId(), someoneElse, now),
                "★★ markOccupied 用的也是同一条谓词，理由同上："
                        + "让别人的上报把枪从「充电中」拨回「已占用」，"
                        + "等于替那笔正在充电的订单做了一次它没做过的状态迁移");
        assertEquals(ConnectorStatus.CHARGING.getCode(),
                reloadConnector(gun.getId()).getStatus(),
                "★ 状态必须还停在 3 充电中");
        assertEquals(2, reloadConnector(gun.getId()).getVersion());

        // ---- 4) 我自己的「结束」上报：3 充电中 → 2 已占用，枪仍然归我 ----
        assertEquals(1, connectorMapper.markOccupied(gun.getId(), mine, now));
        Connector ended = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), ended.getStatus(),
                "★★ 回到「2 已占用」而不是直接放「1 空闲」：车已经拔枪，但订单还要结算，"
                        + "而结算正是要读这把枪的起始电表读数的时候。直接放空闲，"
                        + "结算期间别人就能下第二笔单，用上同一把枪");
        assertEquals(mine, ended.getCurrentOrderId(),
                "★★ 枪还归这一单 —— 释放只能由 release 做（订单进终态时），"
                        + "不能让一次设备上报顺手把它放掉");
        assertEquals(3, ended.getVersion());
    }

    @Test
    @DisplayName("★★ release 幂等且自守卫：重复调用、对别人的枪调用，都是 0 行且无副作用")
    void releaseShouldBeIdempotentAndSelfGuarding() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderId = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, connectorMapper.occupy(gun.getId(), orderId, now));

        // ---- 1) 第一次释放：真的放开 ----
        assertEquals(1, connectorMapper.release(orderId, now));
        Connector released = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), released.getStatus(),
                "★ 释放要同时把两样东西还回去：status 回 1，current_order_id 回 NULL。"
                        + "少写任何一个，这把枪要么「空闲但被占着」（下不了单），"
                        + "要么「已占用但没人认领」（既不能删也不能下单）");
        assertNull(released.getCurrentOrderId());
        assertEquals(2, released.getVersion(), "★ occupy 推一次、release 推一次");

        int versionAfterFirstRelease = released.getVersion();
        LocalDateTime timeAfterFirstRelease = released.getUpdateTime();

        // ---- 2) ★★ 再释放一次：0 行，而且一个字节都没改 ----
        //   ★ 第二次特意传一个【5 分钟后】的 updateTime。如果它影响了 1 行，
        //     update_time 会被推后 —— 于是「update_time 没变」这条断言真的在证明
        //     「这一行没被碰过」，而不是在证明「改了但看起来一样」。
        assertEquals(0, connectorMapper.release(orderId, now.plusMinutes(5)),
                "★★ 重复释放必须影响 0 行。返回 1 说明 SQL 里被塞进了 id 之外的某个"
                        + "「总是成立」的写法（或者干脆没有 WHERE），"
                        + "那样每一条重复调用的路径都会心安理得地继续往下走");
        Connector afterSecondRelease = reloadConnector(gun.getId());
        assertEquals(versionAfterFirstRelease, afterSecondRelease.getVersion(),
                "★ 重复释放不能再推 version —— 否则管理员手上的 version 会因为"
                        + "一次「什么都没做」的调用而过期");
        assertEquals(timeAfterFirstRelease, afterSecondRelease.getUpdateTime(),
                "★★ update_time 必须原封不动。它比 version 更能证明「没碰这一行」——"
                        + "version 可以被写成 version + 0，update_time 不行");
        assertEquals(ConnectorStatus.IDLE.getCode(), afterSecondRelease.getStatus());

        // ---- 3) 用一笔从未占用过这把枪的订单去释放：0 行 ----
        assertEquals(0, connectorMapper.release(newOrderId(), now),
                "★ 释放的前提是「我现在占着它」。这个 0 行不是错误，是正常的「本来就不归我」");

        // ---- 4) ★★ 为什么「不知道上一个状态」也可以放心调用 ----
        //   上面的 1/2/3 合起来说明的性质是：release <b>幂等且自守卫</b>，
        //   而它的实现方式是把「我还要不要占着这把枪」这件事交回给出题人 ——
        //   谓词只有 current_order_id = orderId，没有状态条件。
        //
        //   ★ 正因为这样，cancel / settle / markAbnormal 三处可以各自直接调用它，
        //     不必先判断「我这笔订单还占着枪吗」：那个判断在三处里会写成三种略有
        //     出入的样子（一处查 status、一处查订单状态、一处忘了查），
        //     而每一种出入的表现都是「枪被永久占住」或者「枪被提前放掉」。
        //     把判断收进 SQL 之后，正确性只取决于一条 WHERE，而它可以被测试钉住。
        //
        //   ★ 反过来说，这条 WHERE 里【没有 id】也是一件必须知道的事：
        //     一条 release 能改到全库任意一把 current_order_id 等于该订单号的枪。
        //     所以订单号必须是唯一的 —— 这也是本类每个测试都用 {@link #newOrderId()}
        //     造新订单号的原因，写死一个 id 会让一个测试误伤另一个测试的枪。

        // ---- 5) ★ 从「3 充电中」直接释放（用户中途取消在充订单的路径） ----
        Connector chargingGun = insertConnector(charger, "B");
        Long cancelOrder = newOrderId();
        assertEquals(1, connectorMapper.occupy(chargingGun.getId(), cancelOrder, now));
        assertEquals(1, connectorMapper.markCharging(chargingGun.getId(), cancelOrder, now));
        assertEquals(1, connectorMapper.release(cancelOrder, now),
                "★★ 从 3 充电中直接释放必须成功 —— 这正是「谓词里没有 status」的证据："
                        + "取消一笔正在充电的订单时，枪要从 3 直接回 1，中间没有「回 2」这一步。"
                        + "有人「顺手」补一个 status = 2 的谓词，这条就会挂；"
                        + "而补上 status IN (2,3) 之类的实现更糟：它会留下一条释放不掉的路径 ——"
                        + "current_order_id 永久非空，这把枪从此既不能删、也不能再被下单");
        Connector backToIdle = reloadConnector(chargingGun.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), backToIdle.getStatus());
        assertNull(backToIdle.getCurrentOrderId());
    }

    @Test
    @DisplayName("★★ occupy / markCharging / markOccupied / release 都推 version：陈旧的编辑必须撞乐观锁")
    void casMethodsShouldBumpVersionSoAStaleAdminEditConflicts() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderId = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        // ---- 管理员打开编辑页，读到的 version 是 0 ----
        int adminVersion = reloadConnector(gun.getId()).getVersion();
        assertEquals(0, adminVersion);

        // ---- 订单流程跑起来，每一次迁移都推 version ----
        assertEquals(1, connectorMapper.occupy(gun.getId(), orderId, now));
        assertEquals(1, reloadConnector(gun.getId()).getVersion(), "★ occupy 推 version（0 → 1）");
        assertEquals(1, connectorMapper.markCharging(gun.getId(), orderId, now));
        assertEquals(2, reloadConnector(gun.getId()).getVersion(), "★ markCharging 也要推");

        // ---- ★★ 管理员此刻拿着那个 0 保存「4 故障」 ----
        Connector staleEdit = Connector.builder()
                .id(gun.getId())
                .status(ConnectorStatus.FAULT.getCode())
                .version(adminVersion)              // 打开页面时读到的 0，此刻已经过期
                .updateTime(LocalDateTime.now())
                .build();
        // ★★ 这是整条链路存在的理由，把它当成一次反事实推演来读：
        //   如果 occupy / release 不推 version，管理员手上那个 0 和库里一致，
        //   这次保存会【成功】—— 枪变成 4 故障。而随后订单走完流程，
        //   release 的 SET status = 1 会把这次改动【静默覆盖】回「空闲」。
        //   管理员明明看到「保存成功」，故障却消失了，谁都不知道 ——
        //   而那把枪可能真的坏了，界面上却写着它可以给下一位用户用。
        //   推 version 之后这次保存影响 0 行、上层报「请刷新后重试」，
        //   该失败的地方失败，而不是悄悄地丢失一次更新。
        assertEquals(0, connectorMapper.update(staleEdit),
                "★★ 管理员用充电前读到的 version 保存，必须影响 0 行");

        Connector untouched = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.CHARGING.getCode(), untouched.getStatus(),
                "★ 被挡住的保存不能改到任何东西 —— 枪还在充电中");
        assertEquals(orderId, untouched.getCurrentOrderId());
        assertEquals(2, untouched.getVersion());

        // ---- 最后一次状态迁移（释放）同样要推 ----
        assertEquals(1, connectorMapper.release(orderId, now));
        assertEquals(3, reloadConnector(gun.getId()).getVersion(),
                "★ release 也要推：这是管理员唯一一次机会去修正一把刚被放开的枪，"
                        + "所以释放之后，任何在释放之前读到的 version 都必须作废");

        // ---- ★ 对照组：拿【当前】version 的管理员改得动 ----
        //   少了这一条，上面所有「0 行」都可能只是因为 update 本身坏了而通过 ——
        //   而一个「谁都改不动」的乐观锁和一个「谁都能改」的乐观锁，
        //   在只有 0 行断言的测试里长得一模一样。
        Connector freshEdit = Connector.builder()
                .id(gun.getId())
                .status(ConnectorStatus.FAULT.getCode())
                .version(3)
                .updateTime(LocalDateTime.now())
                .build();
        assertEquals(1, connectorMapper.update(freshEdit),
                "★ 对照：version 对得上时，管理员应该能把这把枪改成 4 故障");
        assertEquals(ConnectorStatus.FAULT.getCode(), reloadConnector(gun.getId()).getStatus());
    }

    @Test
    @DisplayName("★★ 全生命周期：空闲 → 已占用 → 充电中 → 已占用 → 空闲，每一跳都验状态和占用者")
    void fullLifecycleShouldLandBackOnIdle() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderId = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        // ---- 0) 起点 ----
        Connector s0 = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), s0.getStatus(), "起点：1 空闲");
        assertNull(s0.getCurrentOrderId());
        assertEquals(0, s0.getVersion());

        // ---- 1) 建单抢占 ----
        assertEquals(1, connectorMapper.occupy(gun.getId(), orderId, now));
        Connector s1 = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), s1.getStatus(), "抢占后：2 已占用");
        assertEquals(orderId, s1.getCurrentOrderId(), "★ 占用者必须在这一跳就写上去");
        assertEquals(1, s1.getVersion());

        // ---- 2) 设备上报启动成功 ----
        assertEquals(1, connectorMapper.markCharging(gun.getId(), orderId, now));
        Connector s2 = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.CHARGING.getCode(), s2.getStatus(), "启动后：3 充电中");
        assertEquals(orderId, s2.getCurrentOrderId(),
                "★ 中间任何一跳都不能把 current_order_id 清掉 —— 清了之后"
                        + "markOccupied / release 的谓词就再也匹配不上，这把枪会被永久占住");
        assertEquals(2, s2.getVersion());

        // ---- 3) 设备上报结束 ----
        assertEquals(1, connectorMapper.markOccupied(gun.getId(), orderId, now));
        Connector s3 = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.OCCUPIED.getCode(), s3.getStatus(),
                "结束后：回到 2 已占用（订单还要结算，枪不能放）");
        assertEquals(orderId, s3.getCurrentOrderId());
        assertEquals(3, s3.getVersion());

        // ---- 4) 结算完释放 ----
        assertEquals(1, connectorMapper.release(orderId, now));
        Connector s4 = reloadConnector(gun.getId());
        assertEquals(ConnectorStatus.IDLE.getCode(), s4.getStatus(), "释放后：回到 1 空闲");
        assertNull(s4.getCurrentOrderId());
        assertEquals(4, s4.getVersion(), "★ 四次迁移，version 正好是 4");

        // ---- ★★ 回到起点，而且【能被下一辆车下单】 ----
        //   「空闲」的实测定义就是这个：status = 1 且 current_order_id IS NULL
        //   刚好就是 occupy 的谓词。少写任何一半，这把枪就再也接不了新订单 ——
        //   而界面上它看着完全正常。
        Long nextOrder = newOrderId();
        assertEquals(1, connectorMapper.occupy(gun.getId(), nextOrder, now),
                "★★ 上一笔订单走完之后，这把枪必须能被下一辆车抢到。"
                        + "这条失败的时候，问题一定在上面某一跳的 SET 里，而不是在这里");
        assertEquals(nextOrder, reloadConnector(gun.getId()).getCurrentOrderId());

        // ---- ★ total_charge_count 全程是 0 ----
        //   状态机不碰它（occupy 的注释里明写了「不要在这里写 total_charge_count」），
        //   它归结算，见下面两个测试。这条断言守的是「不会有人在状态迁移里顺手 +1」——
        //   那样一把枪每被抢一次就多算一次充电。
        assertEquals(0, reloadConnector(gun.getId()).getTotalChargeCount(),
                "★ 状态迁移不该动 total_charge_count");
    }

    // ==================== ★★ 五、incrementChargeCount ====================

    @Test
    @DisplayName("★★ incrementChargeCount 走 SQL 自增：连调两次是 2（不是「读出来加一写回去」）")
    void incrementChargeCountShouldAccumulateInSql() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderId = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        // ★ 前置状态照着真实场景摆：结算发生在枪还归这一单的时候（2 已占用）。
        //   万一哪天有人在 SQL 里加了一个「只对空闲枪生效」的条件，这条能挂。
        assertEquals(1, connectorMapper.occupy(gun.getId(), orderId, now));
        assertEquals(0, reloadConnector(gun.getId()).getTotalChargeCount());

        assertEquals(1, connectorMapper.incrementChargeCount(gun.getId()));
        assertEquals(1, connectorMapper.incrementChargeCount(gun.getId()));

        // ★★ 两次调用之间【没有任何读操作】—— 这个 2 只能来自 SQL 侧的
        //    SET total_charge_count = total_charge_count + 1。
        //    写成「先 getById 读出来，+1，再 update 回去」的话，两笔订单并发结算时
        //    会各自读到 5、各自写 6，实际应该是 7：一个用户的充电次数凭空消失，
        //    而且没有任何报错 —— 读数只在有人手工对账时才会发现。
        //    ★ 这和 version 放在 SQL 侧算（version = version + 1）是同一个理由：
        //      「读出来」和「写进去」之间的那个窗口就是丢更新的地方。
        assertEquals(2, reloadConnector(gun.getId()).getTotalChargeCount(),
                "★★ 连调两次必须得到 2。得到 1 说明这条 SQL 变成了读-改-写");
    }

    @Test
    @DisplayName("★★ incrementChargeCount 【不】推 version：有车充完电不该让管理员的编辑变成假冲突")
    void incrementChargeCountMustNotBumpVersion() {
        BaseContext.setCurrentId(1L);
        Charger charger = insertCharger();
        Connector gun = insertConnector(charger, "A");

        Long orderId = newOrderId();
        LocalDateTime now = LocalDateTime.now();

        assertEquals(1, connectorMapper.occupy(gun.getId(), orderId, now));
        // ★ 管理员此刻打开这把枪的编辑页，读到的 version 是抢占之后的值
        int adminVersion = reloadConnector(gun.getId()).getVersion();
        assertEquals(1, adminVersion);

        // ---- 两笔订单先后结算：各自给这把枪的自增计数器 +1 ----
        connectorMapper.incrementChargeCount(gun.getId());
        connectorMapper.incrementChargeCount(gun.getId());

        Connector afterSettles = reloadConnector(gun.getId());
        assertEquals(2, afterSettles.getTotalChargeCount());
        // ★★ version 必须原地不动。它比上面四条「必须推」更值得单独钉一条断言，
        //    因为「推」和「不推」在这里都是一行代码，而写错的方向不会报错：
        //    推了它，每次有车充完电，所有手上拿着这把枪编辑页的管理员都会收到一次
        //    「请刷新后重试」—— 而他要改的这把枪，和他读到的版本之间，
        //    根本没有别人改过任何东西。这就是假冲突，它比漏推更隐蔽：
        //    漏推丢的是更新，假冲突丢的是用户对乐观锁的信任（大家开始习惯性刷新）。
        assertEquals(adminVersion, afterSettles.getVersion(),
                "★★ total_charge_count 是计数器，不是枪的状态 —— 自增它不该动 version");

        // ---- ★ 用「管理员还改得动」来证明「不推」是对的，而不是「忘了推」 ----
        //   这两个版本的代码在「version 没变」这条断言下长得一模一样，
        //   区别在于意图。下面这条把意图也钉住了：管理员拿着充电前读到的 version
        //   保存，必须成功。
        Connector adminEdit = Connector.builder()
                .id(gun.getId())
                .status(ConnectorStatus.FAULT.getCode())
                .version(adminVersion)
                .updateTime(LocalDateTime.now())
                .build();
        assertEquals(1, connectorMapper.update(adminEdit),
                "★★ 计数器自增之后，管理员拿着充电前的 version 仍然应该改得动。"
                        + "返回 0 说明 incrementChargeCount 推了 version ——"
                        + "而 total_charge_count 根本不在 update 的 SET 子句里，"
                        + "两个方向都不会丢失更新，推它纯属给管理员制造假冲突");
        assertEquals(ConnectorStatus.FAULT.getCode(), reloadConnector(gun.getId()).getStatus());
    }
}
