package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.entity.ChargePackage;
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
 * {@link ChargePackageMapper} 的集成测试
 *
 * <p>通用写法见 {@link StaffMapperTest}。本类的两个重点：
 *
 * <h2>★★ 一、{@code station_id = 0} 是一个有意义的值，不是「没传」</h2>
 *
 * <p>套餐有两个作用域：{@code 0} = 全平台通用，非 0 = 限定某个站点。
 * 这带来一个容易写错的动态 SQL：{@code <if test="stationId != null">}
 * 和 {@code <if test="stationId != 0">} 一字之差，后者会让
 * 「查全平台套餐」这个查询条件被<b>静默忽略</b>，返回所有站点的套餐 ——
 * 不报错，只是结果多了一堆不该有的行。
 *
 * <h2>★★ 二、{@code uk_pkg_name_station (name, station_id)} 的作用域语义</h2>
 *
 * <p>同名套餐在「全平台」和「某个站点」两个作用域下可以并存
 * （它们是不同的商品）；但同一个作用域内不能重名。
 *
 * <p>★ 这也是 {@code station_id} <b>必须非空</b>的理由：
 * MySQL 的唯一索引不约束 {@code NULL}，所以只要有一行的
 * {@code station_id} 是 NULL，同名的全平台套餐就能被无限重复创建 ——
 * 「套餐名不重复」这条约束会静默失效。
 */
@SpringBootTest
class ChargePackageMapperTest {

    @Autowired
    private ChargePackageMapper chargePackageMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的套餐 id，收尾时按 id 删 */
    private final List<Long> createdIds = new ArrayList<>();

    private final List<Long> createdStationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM charge_package WHERE id = ?", id);
        }
        for (Long id : createdStationIds) {
            jdbcTemplate.update("DELETE FROM station WHERE id = ?", id);
        }
        createdIds.clear();
        createdStationIds.clear();
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private String uniqueName() {
        return "测试套餐-" + System.nanoTime();
    }

    private Station insertStation() {
        Station station = Station.builder()
                .code("ST-PKG-" + System.nanoTime())
                .name("套餐测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        createdStationIds.add(station.getId());
        return station;
    }

    /**
     * 建一个「电量包」（{@code package_type = 1}）
     *
     * <p>★ 四个 {@code NOT NULL} 且带 DDL 默认值的字段在这里全部显式给出
     * （{@code stationId / validDays / status / sort}）——
     * 因为全列插入下 DDL 默认值不生效。生产代码里它们来自
     * {@code ChargePackageDTO} 的字段初始值，清单见 {@link ChargePackageMapper#insert}。
     *
     * <p>另外三个类型专属列（{@code durationMinutes / times / serviceDiscount}）
     * <b>刻意保持 null</b>：{@code package_type=1} 的套餐就该是这样，
     * 而「另外三个必须为 null」这条规则由 Service 校验，不是数据库。
     */
    private ChargePackage insertEnergyPackage(String name, long stationId) {
        ChargePackage pkg = ChargePackage.builder()
                .name(name)
                .categoryId(1L)
                .packageType(1)
                .stationId(stationId)
                .price(new BigDecimal("100.00"))
                .energyKwh(new BigDecimal("150.00"))
                .validDays(90)
                .status(1)
                .sort(0)
                .build();
        chargePackageMapper.insert(pkg);
        assertNotNull(pkg.getId(), "insert 后 id 应被回填");
        createdIds.add(pkg.getId());
        return pkg;
    }

    // ==================== INSERT / @AutoFill ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填审计四列，create_time 来自 Java 侧")
    void insertShouldFillAuditColumns() {
        BaseContext.setCurrentId(1L);
        LocalDateTime before = LocalDateTime.now();

        ChargePackage pkg = insertEnergyPackage(uniqueName(), 0L);

        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, pkg.getCreateUser());
        assertEquals(1L, pkg.getUpdateUser());
        assertTrue(pkg.getCreateTime().isAfter(before.minusSeconds(1))
                        && pkg.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 应该是 Java 侧的时钟，不是数据库默认值");
        assertEquals(pkg.getCreateTime(), pkg.getUpdateTime());
    }

    @Test
    @DisplayName("★★ service_discount 的 decimal(5,4)：0.8000 的 scale 必须原样保留")
    void serviceDiscountShouldRoundTripWithScaleFour() {
        BaseContext.setCurrentId(1L);

        // 服务费折扣包：type=3，只有 service_discount 有值
        ChargePackage pkg = ChargePackage.builder()
                .name(uniqueName())
                .categoryId(1L)
                .packageType(3)
                .stationId(0L)
                .price(new BigDecimal("9.90"))
                .serviceDiscount(new BigDecimal("0.8000"))
                .validDays(0)
                .status(1)
                .sort(0)
                .build();
        chargePackageMapper.insert(pkg);
        createdIds.add(pkg.getId());

        ChargePackage reloaded = chargePackageMapper.getById(pkg.getId());

        // ★ 数值相等
        assertEquals(0, new BigDecimal("0.8000").compareTo(reloaded.getServiceDiscount()));

        // ★★ 而且 scale 也是 4。
        //   这一条比 compareTo 更严：decimal(5,4) 决定了「八折」
        //   在库里就是 0.8000 四位小数。如果哪天有人把列改成
        //   decimal(5,2)，0.8000 会变成 0.80 —— 数值还相等，
        //   但 0.8550 这样的折扣会被四舍五入成 0.86，
        //   也就是**折扣率被静默改掉了**，而账单上差的钱不会有人发现。
        //   折扣是乘在服务费上的，精度要求比它看起来高。
        assertEquals(4, reloaded.getServiceDiscount().scale(),
                "service_discount 的 scale 应该是 4（decimal(5,4)）。"
                        + "变成 2 的话，0.8550 这类折扣会被静默舍入");

        // ★ 其余三个类型专属列应该是 null —— 它们和 packageType=3 不匹配。
        //   「不匹配就报错」是 Service 的 assertTypeFields 管的，
        //   Mapper 只负责如实存取。
        assertNull(reloaded.getEnergyKwh());
        assertNull(reloaded.getDurationMinutes());
        assertNull(reloaded.getTimes());
        assertEquals(3, reloaded.getPackageType());

        // ★ 顺带验一下 energy_kwh 的 decimal(10,2) 也是精确的
        ChargePackage energy = insertEnergyPackage(uniqueName(), 0L);
        assertEquals(0, new BigDecimal("150.00").compareTo(
                chargePackageMapper.getById(energy.getId()).getEnergyKwh()));
    }

    @Test
    @DisplayName("★ NOT NULL 字段漏传直接报错（DTO 必须给默认值：stationId/validDays/status/sort）")
    void missingNotNullFieldsShouldFailLoudly() {
        BaseContext.setCurrentId(1L);

        // ★ 这是「全列插入 vs DDL 默认值」那个坑在本表的四个位置。
        //   stationId 是第一个被撞的（它在 SQL 里排在前面），
        //   但四个字段有同样的问题 —— 逐一看的话见
        //   ChargePackageMapper.insert 的 javadoc 里那张表。
        assertThrows(DataIntegrityViolationException.class,
                () -> chargePackageMapper.insert(ChargePackage.builder()
                        .name(uniqueName())
                        .categoryId(1L)
                        .packageType(1)
                        .price(new BigDecimal("10.00"))
                        // stationId 不给 —— DDL 写的是 NOT NULL DEFAULT 0，
                        // 但这条 SQL 是全列插入，DEFAULT 不生效
                        .validDays(0)
                        .status(1)
                        .sort(0)
                        .build()),
                "station_id 是 NOT NULL，漏传应该报错而不是用 DDL 默认值 0");
    }

    @Test
    @DisplayName("★★ uk(name, station_id)：同名套餐在「全平台」和「某站点」可以并存，同一作用域内不行")
    void nameShouldBeUniqueWithinScopeOnly() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        String name = uniqueName();

        // 全平台作用域下建一个
        insertEnergyPackage(name, 0L);

        // ★ 同一个名字 + 某个站点 → 合法。它们对车主来说是两个不同的商品：
        //   「150 度电包（全平台通用）」和「新站首充 50 度（仅限科技园站）」
        assertNotNull(insertEnergyPackage(name, station.getId()).getId(),
                "同名套餐在不同 station_id 下应该允许 —— 唯一键是 (name, station_id) 不是 name");

        // 但同一作用域内再来一个同名 → 失败
        assertThrows(DuplicateKeyException.class,
                () -> insertEnergyPackage(name, 0L),
                "全平台作用域内套餐名不能重复");

        assertThrows(DuplicateKeyException.class,
                () -> insertEnergyPackage(name, station.getId()),
                "同一站点作用域内套餐名也不能重复");
    }

    // ==================== page ====================

    @Test
    @DisplayName("★★ page：stationId=0 必须被当成【查询条件】而不是「没传」")
    void pageShouldTreatStationIdZeroAsARealFilter() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();

        insertEnergyPackage(uniqueName(), 0L);
        insertEnergyPackage(uniqueName(), station.getId());

        // ---- 1) 传 0：只要全平台套餐 ----
        ChargePackagePageQueryDTO q0 = new ChargePackagePageQueryDTO();
        q0.setStationId(0L);
        PageHelper.startPage(1, 50);
        List<ChargePackage> rows0 = chargePackageMapper.page(q0);

        // ★ 只断言「不包含那个站点专属的套餐」，不断言总数 ——
        //   种子里本来就有 station_id=0 的套餐（「150度电包」），
        //   断言总数会变成一条依赖种子数据的脆弱测试。
        //   真正要守住的性质是：**站内套餐不能出现在「全平台」的查询结果里**。
        assertTrue(rows0.stream().noneMatch(p -> p.getStationId() != 0),
                "★ stationId=0 的查询返回了非 0 的套餐，说明 <if test=\"stationId != null\"> "
                        + "被写成了 != 0 —— 那会让「查全平台套餐」这个条件被静默忽略");

        // ---- 2) 传站点 id：只要该站点的套餐 ----
        ChargePackagePageQueryDTO q1 = new ChargePackagePageQueryDTO();
        q1.setStationId(station.getId());
        PageHelper.startPage(1, 50);
        List<ChargePackage> rows1 = chargePackageMapper.page(q1);
        assertEquals(1, rows1.size(), "本次只给这个站点建了 1 个套餐");
        assertEquals(station.getId(), rows1.get(0).getStationId());

        // ---- 3) 不传 stationId：不限站点，两类都能查到 ----
        ChargePackagePageQueryDTO q2 = new ChargePackagePageQueryDTO();
        q2.setName("测试套餐-");
        PageHelper.startPage(1, 50);
        List<ChargePackage> rows2 = chargePackageMapper.page(q2);
        assertEquals(2, rows2.size(), "不传 stationId 时应该两类都能查到");
        assertTrue(((Page<ChargePackage>) rows2).getTotal() >= 2);
    }

    @Test
    @DisplayName("page：name 包含匹配、packageType / categoryId / status 过滤")
    void pageShouldFilterByOtherConditions() {
        BaseContext.setCurrentId(1L);
        String tag = Long.toString(System.nanoTime() % 10_000_000L, 36);

        // 三个不同 packageType 的套餐，名字里都带同一个 tag
        for (int type = 1; type <= 3; type++) {
            ChargePackage pkg = ChargePackage.builder()
                    .name("套餐" + tag + "-" + type)
                    .categoryId((long) type)
                    .packageType(type)
                    .stationId(0L)
                    .price(new BigDecimal("10.00"))
                    .status(type == 3 ? 0 : 1)
                    .validDays(0)
                    .sort(0)
                    .build();
            chargePackageMapper.insert(pkg);
            createdIds.add(pkg.getId());
        }

        // ---- name 是【包含】匹配（套餐名里「150度」这种词可能出现在中间） ----
        ChargePackagePageQueryDTO q1 = new ChargePackagePageQueryDTO();
        q1.setName(tag);
        PageHelper.startPage(1, 50);
        assertEquals(3, chargePackageMapper.page(q1).size(),
                "name 是包含匹配，tag 在名字中间也应该命中");

        // ---- packageType ----
        ChargePackagePageQueryDTO q2 = new ChargePackagePageQueryDTO();
        q2.setName(tag);
        q2.setPackageType(2);
        PageHelper.startPage(1, 50);
        assertEquals(1, chargePackageMapper.page(q2).size());

        // ---- categoryId ----
        ChargePackagePageQueryDTO q3 = new ChargePackagePageQueryDTO();
        q3.setName(tag);
        q3.setCategoryId(3L);
        PageHelper.startPage(1, 50);
        assertEquals(1, chargePackageMapper.page(q3).size());

        // ---- status（第三个是下架的） ----
        ChargePackagePageQueryDTO q4 = new ChargePackagePageQueryDTO();
        q4.setName(tag);
        q4.setStatus(0);
        PageHelper.startPage(1, 50);
        assertEquals(1, chargePackageMapper.page(q4).size());

        ChargePackagePageQueryDTO q5 = new ChargePackagePageQueryDTO();
        q5.setName(tag);
        q5.setStatus(1);
        PageHelper.startPage(1, 50);
        assertEquals(2, chargePackageMapper.page(q5).size());
    }

    @Test
    @DisplayName("page 的排序：sort ASC, id DESC，sort 相同时按 id 倒序（稳定排序）")
    void pageShouldSortBySortThenIdDesc() {
        BaseContext.setCurrentId(1L);
        String tag = Long.toString(System.nanoTime() % 10_000_000L, 36);

        // 两条 sort=5、一条 sort=1 —— 故意让 sort 撞车
        int[] sorts = {5, 1, 5};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < sorts.length; i++) {
            ChargePackage pkg = ChargePackage.builder()
                    .name("排序" + tag + "-" + i)
                    .categoryId(1L)
                    .packageType(1)
                    .stationId(0L)
                    .price(new BigDecimal("10.00"))
                    .energyKwh(new BigDecimal("10.00"))
                    .status(1)
                    .validDays(0)
                    .sort(sorts[i])
                    .build();
            chargePackageMapper.insert(pkg);
            createdIds.add(pkg.getId());
            ids.add(pkg.getId());
        }

        ChargePackagePageQueryDTO q = new ChargePackagePageQueryDTO();
        q.setName("排序" + tag);
        PageHelper.startPage(1, 10);
        List<ChargePackage> rows = chargePackageMapper.page(q);

        assertEquals(3, rows.size());
        // sort=1 的那条（索引 1）排最前
        assertEquals(ids.get(1), rows.get(0).getId());
        // ★ 两条 sort=5 的按 id 倒序 —— 索引 2 是后插的，id 更大
        assertEquals(ids.get(2), rows.get(1).getId());
        assertEquals(ids.get(0), rows.get(2).getId());

        // ★ 如果哪天有人把 ORDER BY 简化成只有 sort ASC，
        //   上面两条 sort 相同的断言会开始随机失败（MySQL 对相同排序键
        //   的行序不作保证）—— 表现是「翻页会重复或漏行」。
        //   这条测试把 id DESC 这个 tiebreak 固定下来。
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("UPDATE：改 update_* 不碰 create_*；四个类型专属列的 <if> 语义")
    void updateShouldBehaveAsDocumented() {
        BaseContext.setCurrentId(1L);
        ChargePackage pkg = insertEnergyPackage(uniqueName(), 0L);

        ChargePackage afterInsert = chargePackageMapper.getById(pkg.getId());
        LocalDateTime originalCreateTime = afterInsert.getCreateTime();
        Long originalCreateUser = afterInsert.getCreateUser();

        BaseContext.setCurrentId(2L);
        chargePackageMapper.update(ChargePackage.builder()
                .id(pkg.getId())
                .price(new BigDecimal("88.88"))
                .energyKwh(new BigDecimal("200.00"))
                .build());

        ChargePackage reloaded = chargePackageMapper.getById(pkg.getId());
        assertEquals(0, new BigDecimal("88.88").compareTo(reloaded.getPrice()));
        assertEquals(0, new BigDecimal("200.00").compareTo(reloaded.getEnergyKwh()));
        assertEquals(2L, reloaded.getUpdateUser(), "update_user 应该是 id=2");
        assertEquals(originalCreateTime, reloaded.getCreateTime(),
                "create_time 被 UPDATE 改掉了");
        assertEquals(originalCreateUser, reloaded.getCreateUser(),
                "create_user 被 UPDATE 改掉了");

        // ★ 没传的字段保持不变（局部更新的已知限制 / 行为）
        assertEquals(pkg.getName(), reloaded.getName(), "没传 name，名字不该变");
        assertEquals(1, reloaded.getPackageType(), "packageType 不在 <set> 里，改不了");
        assertEquals(0L, reloaded.getStationId(), "stationId 不在 <set> 里，改不了");
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("★ deleteById 后那个名字可以被重新使用 —— 这是硬删必须存在的理由")
    void deleteShouldFreeTheName() {
        BaseContext.setCurrentId(1L);
        String name = uniqueName();
        ChargePackage pkg = insertEnergyPackage(name, 0L);

        assertThrows(DuplicateKeyException.class, () -> insertEnergyPackage(name, 0L));

        assertEquals(1, chargePackageMapper.deleteById(pkg.getId()));
        createdIds.remove(pkg.getId());

        // ★ uk_pkg_name_station 不看 status —— 下架一个套餐**不会**让出它的名字。
        //   所以「150度电包」打成「150度点包」之后，想用正确的名字
        //   只能把错的删掉。没有硬删的话，这个操作要去生产库手敲 DELETE。
        assertNotNull(insertEnergyPackage(name, 0L).getId(),
                "删掉之后名字应该可以被重新使用");

        assertEquals(0, chargePackageMapper.deleteById(pkg.getId()), "重复删除返回 0 行");
    }
}
