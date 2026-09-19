package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.StationPageQueryDTO;
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
 * {@link StationMapper} 的集成测试
 *
 * <p>写法、理由和「为什么必须用真数据库」见 {@link StaffMapperTest} 的类注释，
 * 这里不重复。差异只在下面两点。
 *
 * <h2>★★ 一、本类的核心断言是 {@code close_time = '24:00:00'} 的原样往返</h2>
 *
 * <p>这是 P1a 里最有价值的一条断言，因为它是<b>唯一一个找不到替代验证方式</b>的坑：
 *
 * <ul>
 *   <li>写错了<b>不会报错</b>。存成 {@code '00:00:00'} 一样是合法的 {@code TIME}</li>
 *   <li>后果是<b>业务性的</b>：站点变成「0 点开门 0 点关门」，
 *       营业时间校验会算出「今天的营业时长为 0 分钟」</li>
 *   <li>验证成本极高：MySQL 的 {@code TIME} 是<b>有符号时长</b>（±838:59:59）
 *       而不是「一天中的时刻」，所以 {@code '24:00:00'} 本来就是合法的 ——
 *       这件事从 DDL 上看不出来，从 Java 类型上也看不出来</li>
 * </ul>
 *
 * <p>已经用真机验证过「SQL 字面量 {@code '24:00:00'} 能存能读」。
 * 但那条路径和「Java 的 {@code String} 经 JDBC 参数绑定进来」是<b>两条不同的代码路径</b>——
 * 前者证明不了后者。<b>这个测试证明的就是后者。</b>
 *
 * <h2>★ 二、{@code page} 要真的走一次 PageHelper</h2>
 *
 * <p>因为 {@code application.yml} 里 {@code support-methods-arguments: false}，
 * 分页完全依赖调用方显式调用 {@code PageHelper.startPage}。
 * 只测「返回了 N 行」证明不了分页是好的（数据量小的时候全表和分页长得一样），
 * 必须断言 {@code getTotal()} 和 {@code size()} 两个值。
 */
@SpringBootTest
class StationMapperTest {

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的站点 id，收尾时按 id 删 */
    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM station WHERE id = ?", id);
        }
        createdIds.clear();
        // ★ 清掉分页的 ThreadLocal。startPage 的残留参数会被
        //   「该线程上的下一条查询」消费掉，不清的话会污染下一个测试 ——
        //   而症状是「单独跑通过，一起跑失败」，最难查的那一类。
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private String uniqueCode() {
        return "ST-TEST-" + System.nanoTime();
    }

    /**
     * 建一个只填必填字段的站点
     *
     * <p>★ {@code openTime} / {@code closeTime} 是<b>显式</b>写的。
     * 它们不能省 —— 见 {@link #omittingOpenTimeShouldFailLoudly()}。
     * 生产代码里这两个值同样不是省出来的，是 {@code StationDTO}
     * 的字段初始值填进来的。
     */
    private Station insertMinimal(String code) {
        Station station = Station.builder()
                .code(code)
                .name("集成测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        assertNotNull(station.getId(), "insert 后 id 应被回填，否则 useGeneratedKeys 没生效");
        createdIds.add(station.getId());
        return station;
    }

    private Station insertFull(String code) {
        Station station = Station.builder()
                .code(code)
                .name("完整站点")
                .operator("测试运营商")
                .province("广东省")
                .city("深圳市")
                .district("南山区")
                .address("科技园路 1 号")
                .longitude(new BigDecimal("113.934500"))
                .latitude(new BigDecimal("22.540800"))
                .servicePhone("0755-88888888")
                .openTime("06:30:00")
                .closeTime("23:30:00")
                .parkingFee(new BigDecimal("5.00"))
                .status(1)
                .build();
        stationMapper.insert(station);
        assertNotNull(station.getId());
        createdIds.add(station.getId());
        return station;
    }

    // ==================== INSERT / @AutoFill ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填满审计四列，且 create_time 来自 Java 侧的时钟")
    void insertShouldFillAllFourAuditColumns() {
        BaseContext.setCurrentId(1L);
        LocalDateTime before = LocalDateTime.now();

        Station station = insertMinimal(uniqueCode());

        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, station.getCreateUser(), "create_user 应该是当前登录者 id");
        assertEquals(1L, station.getUpdateUser(), "update_user 应该是当前登录者 id");
        // ★ 这条比「不为 null」强得多：DDL 里 create_time 有
        //   DEFAULT CURRENT_TIMESTAMP(3)，所以即使切面完全没生效，
        //   字段也可能在库里被填上 —— 但那是数据库的时钟。
        assertTrue(station.getCreateTime().isAfter(before.minusSeconds(1))
                        && station.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 不在本次调用的时间区间内，可能是数据库默认值而不是切面填的："
                        + station.getCreateTime());
        assertEquals(station.getCreateTime(), station.getUpdateTime(),
                "INSERT 时 update_time 应与 create_time 相同");
    }

    // ==================== ★★ 本类存在的理由 ====================

    @Test
    @DisplayName("★★ close_time='24:00:00' 必须原样往返，不能被回绕成 00:00:00")
    void closeTime24HoursShouldSurviveRoundTrip() {
        BaseContext.setCurrentId(1L);

        // 显式设成 24:00:00（而不是靠 DDL 默认值），
        // 这样测的是「Java String → JDBC 参数 → TIME 列 → 读回 Java String」这一整条路径
        Station station = Station.builder()
                .code(uniqueCode())
                .name("24 小时站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        createdIds.add(station.getId());

        Station reloaded = stationMapper.getById(station.getId());
        assertNotNull(reloaded);

        // ★★ 字符串逐字节相等。写成 assertEquals(0, compareTo) 会放过
        //    "00:00:00"，而那两个值在业务上是**相反的**意思：
        //    "24:00:00" 是「营业到当天结束」，"00:00:00" 是「零点就关门」。
        assertEquals("24:00:00", reloaded.getCloseTime(),
                "24:00:00 被改写成别的值了。MySQL 的 TIME 是有符号时长（±838:59:59），"
                        + "24:00:00 本来就是合法值，不该发生回绕。"
                        + "真出这个问题的话，营业时间校验会算出「营业时长 0 分钟」");
        assertEquals("00:00:00", reloaded.getOpenTime());

        // ★ 顺带证明 TIME 列上的时刻值也能正常走这条路径（不只是 24 点这个特例）
        Station full = insertFull(uniqueCode());
        Station fullReloaded = stationMapper.getById(full.getId());
        assertEquals("06:30:00", fullReloaded.getOpenTime());
        assertEquals("23:30:00", fullReloaded.getCloseTime());
    }

    @Test
    @DisplayName("decimal / varchar 往返：经纬度、停车费、可空字段")
    void shouldRoundTripDecimalAndNullableColumns() {
        BaseContext.setCurrentId(1L);

        Station station = insertFull(uniqueCode());
        Station reloaded = stationMapper.getById(station.getId());
        assertNotNull(reloaded);

        // ★ BigDecimal 比较用 compareTo，不用 equals ——
        //   equals 会连 scale 一起比，而 decimal(10,6) 读回来是 113.934500
        //   （6 位小数），内存里写的也是 6 位，这种时候两者相同；
        //   但只要哪天写法变成 "113.9345"，equals 就会失败而 compareTo 不会。
        //   数值相等才是业务要的，scale 是存储细节。
        assertEquals(0, new BigDecimal("113.934500").compareTo(reloaded.getLongitude()),
                "经度往返后数值应相等");
        assertEquals(0, new BigDecimal("22.540800").compareTo(reloaded.getLatitude()));
        assertEquals(0, new BigDecimal("5.00").compareTo(reloaded.getParkingFee()));

        assertEquals("测试运营商", reloaded.getOperator());
        assertEquals("深圳市", reloaded.getCity());
        assertEquals("0755-88888888", reloaded.getServicePhone());

        // ★ 没填的可空字段应该是 null，而不是空字符串 ——
        //   这两种值在 MySQL 里是不同的东西，混起来之后
        //   「这个字段是没填还是被清空了」就没法回答了
        Station minimal = insertMinimal(uniqueCode());
        Station minimalReloaded = stationMapper.getById(minimal.getId());
        assertNull(minimalReloaded.getOperator());
        assertNull(minimalReloaded.getCity());
        assertNull(minimalReloaded.getLongitude());
        assertNull(minimalReloaded.getParkingFee());
    }

    @Test
    @DisplayName("★★ 不传 open/close 时【报错】而不是用 DDL 默认值 —— 这个坑必须被固定下来")
    void omittingOpenTimeShouldFailLoudly() {
        BaseContext.setCurrentId(1L);

        // ★★ 这条测试是本次集成测试**第一次跑就抓到的真问题**，值得完整说明：
        //
        //   DDL 写的是 open_time TIME NOT NULL DEFAULT '00:00:00'，
        //   看上去「不传就有默认值」。但 SQL 的默认值语义是：
        //   **只有当该列没有出现在 INSERT 语句里时，DEFAULT 才生效。**
        //   而 StationMapper.xml 的 insert 是全列插入 —— 列名就在语句里，
        //   所以传 null 等于往一个 NOT NULL 列里显式写 NULL，直接 errno 1048。
        //
        //   真机验证过这一对行为：
        //     INSERT INTO t (a) VALUES (NULL)      -- ERROR 1048
        //     INSERT INTO t (a) VALUES (DEFAULT)   -- 24:00:00
        //
        //   第一次跑时 9 个用例全红，报的就是 "Column 'open_time' cannot be null" ——
        //   而当时 P0 的三条验收是全绿的。这就是「接口能通」和「功能正确」的距离。
        //
        // ★ 断言这个行为（而不是改成让 null 走默认值）是刻意的：
        //   生产路径上 openTime/closeTime 由 StationDTO 的字段初始值保证非空，
        //   这个分支**不可达**。与其把它改成一层 <trim> 动态 SQL 去接住一个
        //   到不了的分支，不如把它固定成「一旦有人真的漏传，当场报错」。
        //   把 DDL 的默认值语义当成一个能被依赖的保证，才是真正的陷阱。
        assertThrows(DataIntegrityViolationException.class,
                () -> stationMapper.insert(Station.builder()
                        .code(uniqueCode())
                        .name("漏传营业时间的站点")
                        .status(1)
                        .build()),
                "全列插入时传 null 应该撞 NOT NULL 约束，而不是静默使用 DDL 默认值");

        // ★ 而一旦显式传了值，DDL 默认值「全天营业」的意图就由调用方承担了 ——
        //   insertMinimal 传的正是这两个值
        Station station = insertMinimal(uniqueCode());
        Station reloaded = stationMapper.getById(station.getId());
        assertEquals("00:00:00", reloaded.getOpenTime());
        assertEquals("24:00:00", reloaded.getCloseTime());
    }

    // ==================== UPDATE / @AutoFill ====================

    @Test
    @DisplayName("UPDATE：只填 update_*，绝不碰 create_*")
    void updateShouldNotTouchCreateColumns() {
        BaseContext.setCurrentId(1L);
        Station station = insertMinimal(uniqueCode());

        // ★ 基准值从数据库读，不能用插入后内存里的那个对象 ——
        //   datetime(3) 只有毫秒，LocalDateTime.now() 有纳秒，
        //   落库时超出部分被四舍五入，内存值永远不等于库值。
        //   完整的推导见 StaffMapperTest 里的同名注释。
        Station afterInsert = stationMapper.getById(station.getId());
        LocalDateTime originalCreateTime = afterInsert.getCreateTime();
        Long originalCreateUser = afterInsert.getCreateUser();

        BaseContext.setCurrentId(2L);
        stationMapper.update(Station.builder()
                .id(station.getId())
                .name("改过的站点名")
                .status(2)
                .build());

        Station reloaded = stationMapper.getById(station.getId());
        assertEquals("改过的站点名", reloaded.getName());
        assertEquals(2, reloaded.getStatus());
        assertEquals(2L, reloaded.getUpdateUser(), "update_user 应该是本次的操作者 id=2");
        assertEquals(originalCreateTime, reloaded.getCreateTime(),
                "create_time 被 UPDATE 改掉了，这是一个不该发生的数据污染");
        assertEquals(originalCreateUser, reloaded.getCreateUser(),
                "create_user 被 UPDATE 改掉了，『谁创建的』这个事实被篡改了");
    }

    @Test
    @DisplayName("★ update 不会把未传的字段清掉（局部更新的已知限制，这里把它固定成行为）")
    void updateShouldLeaveUnsetFieldsAlone() {
        BaseContext.setCurrentId(1L);
        Station station = insertFull(uniqueCode());

        // 只改 name，运营商/电话/经纬度都不传
        stationMapper.update(Station.builder()
                .id(station.getId())
                .name("只改了名字")
                .build());

        Station reloaded = stationMapper.getById(station.getId());
        assertEquals("只改了名字", reloaded.getName());
        // ★ 这不是「正确」，是「已知并接受的限制」：
        //   <set> + <if> 的写法下传 null 的字段不出现在 SQL 里，
        //   所以**没法把字段改成空**（清空运营商后保存，刷新还是旧值）。
        //   把它的行为固定下来，是为了让哪天改成全行更新时
        //   这条测试**失败**，从而逼着修改者去读那段注释。
        assertEquals("测试运营商", reloaded.getOperator(),
                "局部更新把未传的字段清掉了 —— 如果这是有意改成了全行更新，"
                        + "请一并更新 StationMapper.update 的 javadoc 和这条测试");
    }

    // ==================== 唯一约束 ====================

    @Test
    @DisplayName("★ 重复 code 抛 DuplicateKeyException（GlobalExceptionHandler 就是靠它翻译成「已存在」的）")
    void duplicateCodeShouldThrowDuplicateKeyException() {
        BaseContext.setCurrentId(1L);
        String code = uniqueCode();
        insertMinimal(code);

        // ★ 断言的是具体的异常类型，不是 Exception。
        //   GlobalExceptionHandler.handleDuplicateKeyException 只认
        //   org.springframework.dao.DuplicateKeyException ——
        //   如果哪天换成了原生 MyBatis 而不带 Spring 的异常翻译，
        //   这里会变成 PersistenceException，那个 handler 就接不住了，
        //   接口会返回 500 而不是「xxx 已存在」。
        //   这条断言守的就是那个翻译层的存在。
        assertThrows(DuplicateKeyException.class,
                () -> insertMinimal(code),
                "重复 code 应该抛 DuplicateKeyException");
    }

    // ==================== listByIds ====================

    @Test
    @DisplayName("listByIds 批量查（消除 N+1），返回数量与传入 id 数一致")
    void listByIdsShouldReturnAllRequestedRows() {
        BaseContext.setCurrentId(1L);
        Station a = insertMinimal(uniqueCode());
        Station b = insertMinimal(uniqueCode());

        List<Station> rows = stationMapper.listByIds(List.of(a.getId(), b.getId()));

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(s -> s.getId().equals(a.getId())));
        assertTrue(rows.stream().anyMatch(s -> s.getId().equals(b.getId())));
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：过滤条件、总数、排序 —— 并且真的走了一次 PageHelper")
    void pageShouldFilterAndCountCorrectly() {
        BaseContext.setCurrentId(1L);

        // 用两个不可能与库里既有数据相撞的标签来圈定本次测试的行
        String tag = "IT" + Long.toString(System.nanoTime() % 100_000_000L, 36);
        String city = "测" + tag;
        String codePrefix = "ST-" + tag;

        // 3 条待查 + 1 条不该被查到的（同 city 但 status=2）
        for (int i = 0; i < 3; i++) {
            Station s = insertMinimal(codePrefix + "-" + i);
            s.setCity(city);
            stationMapper.update(s);
        }
        Station other = insertMinimal(codePrefix + "-X");
        other.setCity(city);
        other.setStatus(2);
        stationMapper.update(other);

        // ---- 1) city + status 组合过滤 ----
        StationPageQueryDTO q = new StationPageQueryDTO();
        q.setCity(city);
        q.setStatus(1);

        // ★ startPage 必须紧挨着 page() —— 中间插任何别的 MyBatis 查询，
        //   分页参数会被那条查询消费掉。见 StationMapper.page 的 javadoc。
        PageHelper.startPage(1, 10);
        List<Station> rows = stationMapper.page(q);

        long total = ((Page<Station>) rows).getTotal();

        // ★ size() 和 getTotal() 是两个不同的东西：
        //   size() 只证明「过滤条件对了」，getTotal() 才证明「PageHelper 真的接上了」。
        //   只断言 size() 的话，把 page() 换成一个不做分页的普通查询也能通过。
        assertEquals(3, rows.size(), "city + status 应该只筛出 3 条");
        assertEquals(3, total, "getTotal() 应该是 3 —— 不等于 3 说明 PageHelper 没接上");

        // ---- 2) status 单独过滤（不同 status 的那条现在应该出现） ----
        StationPageQueryDTO q2 = new StationPageQueryDTO();
        q2.setCity(city);
        q2.setStatus(2);
        PageHelper.startPage(1, 10);
        List<Station> rows2 = stationMapper.page(q2);
        assertEquals(1, rows2.size());

        // ---- 3) code 前缀匹配 ----
        StationPageQueryDTO q3 = new StationPageQueryDTO();
        q3.setCode(codePrefix);
        q3.setCity(city);   // 圈定本次测试的行，避免命中库里既有的同前缀数据
        PageHelper.startPage(1, 10);
        List<Station> rows3 = stationMapper.page(q3);
        assertEquals(4, rows3.size(), "code 是前缀匹配，应该命中全部 4 条");

        // ---- 4) 分页真的生效：每页 2 条，第 2 页只剩 1 条 ----
        StationPageQueryDTO q4 = new StationPageQueryDTO();
        q4.setCity(city);
        PageHelper.startPage(1, 2);
        List<Station> page1 = stationMapper.page(q4);
        assertEquals(2, page1.size());
        assertEquals(4, ((Page<Station>) page1).getTotal(), "总数应始终是 4，不受 pageSize 影响");

        PageHelper.startPage(2, 2);
        List<Station> page2 = stationMapper.page(q4);
        assertEquals(2, page2.size());

        // ---- 4b) ★ 页码越界：application.yml 里 reasonable: true，返回【最后一页】 ----
        //
        //   写这条测试之前这里断言的本来是「第 3 页应该返回 0 行」——
        //   跑起来是 2 行，去翻配置才发现 reasonable 被显式打开过，
        //   而且旁边写了理由（后台管理页删除数据后页码容易越界，
        //   返回空列表会让用户以为「数据都没了」）。
        //   所以**是我的断言错了，不是代码错了** —— 测试的价值正在于
        //   它逼着人去把这条行为查清楚，而不是想当然。
        //
        //   ★ 这个开关的代价要记住：调用方**无法区分**
        //     「这就是最后一页」和「你要的那页不存在」。
        //     前端要做空态判断，只能拿 getTotal() 自己算总页数，
        //     不能靠「返回空列表」这个信号。
        PageHelper.startPage(3, 2);
        List<Station> page3 = stationMapper.page(q4);
        assertEquals(2, page3.size(),
                "reasonable=true 时第 3 页应该返回最后一页的内容，而不是空列表。"
                        + "改成了 reasonable: false 的话这条会失败 —— 那时请一并更新 application.yml 的注释");
        assertEquals(4, ((Page<Station>) page3).getTotal(),
                "即使页码被修正，getTotal() 仍然是最初的总数 —— 前端要靠它算总页数");

        PageHelper.startPage(999, 2);
        assertEquals(2, stationMapper.page(q4).size(), "任意大的页码同样收敛到最后一页");

        // ---- 5) ★ 翻页不重不漏：两页的 id 集合必须不相交 ----
        //   没有 ORDER BY 时行序未定义，这是最容易出现的分页 bug
        //   （表现是某条记录在第 1、2 页各出现一次，另一条从不出现）
        PageHelper.startPage(1, 2);
        List<Station> first = stationMapper.page(q4);
        PageHelper.startPage(2, 2);
        List<Station> second = stationMapper.page(q4);

        List<Long> ids1 = first.stream().map(Station::getId).toList();
        List<Long> ids2 = second.stream().map(Station::getId).toList();
        assertTrue(ids1.stream().noneMatch(ids2::contains),
                "第 1 页和第 2 页出现了重复记录，说明排序不稳定。"
                        + "page1=" + ids1 + " page2=" + ids2);

        // ---- 6) ORDER BY id DESC ----
        assertTrue(ids1.get(0) > ids1.get(1), "应该按 id 倒序，最新的排最前");
    }

    @Test
    @DisplayName("page：不带任何条件时，LIMIT 和 COUNT 都必须生效")
    void pageWithoutAnyFilterShouldStillWork() {
        BaseContext.setCurrentId(1L);
        // 先自己造 3 条，这样「总数至少是 3」不依赖种子数据的规模
        // （种子里只有 1 个站点 —— 依赖它会写成一条只在本地成立的断言）
        for (int i = 0; i < 3; i++) {
            insertMinimal(uniqueCode());
        }

        PageHelper.startPage(1, 2);
        List<Station> rows = stationMapper.page(new StationPageQueryDTO());

        // ★ 这一对断言分别守着 PageHelper 的两个半边，缺一个都说明不了问题：
        //   size() 证明 LIMIT 生效（没有 LIMIT 的话会返回全表）
        //   getTotal() 证明 COUNT 查询生效（写死的 total 也是 total）
        assertEquals(2, rows.size(), "pageSize=2 应该只返回 2 行 —— 这是 LIMIT 生效的证据");
        assertTrue(((Page<Station>) rows).getTotal() >= 3,
                "总数应该至少包含本次插入的 3 条 —— 这是 Count 查询生效的证据，实际："
                        + ((Page<Station>) rows).getTotal());
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("deleteById 是真删，删完 getById 返回 null")
    void deleteByIdShouldRemoveTheRow() {
        BaseContext.setCurrentId(1L);
        Station station = insertMinimal(uniqueCode());
        Long id = station.getId();

        assertEquals(1, stationMapper.deleteById(id));
        assertNull(stationMapper.getById(id), "删除后应该查不到");

        // 再删一次返回 0 —— 调用方靠这个值判断「这条记录本来就不存在」
        assertEquals(0, stationMapper.deleteById(id), "重复删除应返回 0 行");
    }
}
