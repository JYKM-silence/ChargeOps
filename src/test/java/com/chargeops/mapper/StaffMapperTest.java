package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.Staff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StaffMapper} 的集成测试
 *
 * <h2>这个测试要证明什么</h2>
 *
 * <p>P0 的验收标准（登录 → token → /me）只走通了<b>查询</b>这一条路。
 * 而 {@code AutoFillInterceptor} 只对<b>写入</b>起作用 —— 也就是说，
 * 那三条验收全部通过，也不能说明自动填充是好的。
 *
 * <p>自动填充失败的表现是「审计字段是 null，但接口正常返回成功」，
 * 不会报错、不会失败，只会在几个月后有人想查「这条记录是谁建的」时才发现。
 * <b>所以它必须被断言，不能靠「跑起来看着没问题」。</b>
 *
 * <h2>★★ 这个测试真的抓到过一个 bug ★★</h2>
 *
 * <p>写出来第一次跑，三条全红：{@code Column 'create_time' cannot be null}。
 * 那三条 P0 验收当时是全绿的 —— 这就是 <b>「接口能通」和「功能正确」是两件事</b>
 * 的具体样子。顺着这条线索查下去，发现的是「AOP 切面切不到 MyBatis Mapper」
 * 这个机制问题（完整的推导见 {@code AutoFillInterceptor} 的类注释）。
 *
 * <p>所以下面这些断言不是形式主义：它们是这套机制<b>唯一的探针</b>。
 * 删掉它们，插件哪天失效了不会有人知道。
 *
 * <h2>为什么用 {@code @SpringBootTest} 而不是 Mockito 单元测试</h2>
 *
 * <p>要验证的东西横跨三层，缺一层就证明不了：
 * <pre>
 *   BaseContext（ThreadLocal） → AutoFillInterceptor（MyBatis 插件） → StaffMapper.xml（SQL）
 * </pre>
 * 用 mock 的话，插件不会被触发（mock 的是接口，没有 SqlSession，
 * 也就没有 Executor 可以拦），SQL 也不会被真正执行 ——
 * 那测的就不是这个系统，而是测试自己写的假设。
 *
 * <p>代价是这个测试<b>需要本机有 MySQL 和 Redis</b>。
 * 对当前阶段（本地跑通的单人项目）是划算的；
 * 等到要接 CI 时，正确做法是配 Testcontainers 起一个一次性的 MySQL 容器，
 * 而不是把测试删掉或者改成 mock。
 *
 * <h2>★ 测试数据要自己收拾干净</h2>
 *
 * <p>下面插入的是一个随机用户名的临时 staff 记录，测试结束在
 * {@code @AfterEach} 里删掉。不删的话跑几次本地库就多几条垃圾数据，
 * 而且下次跑时 {@code uk_staff_username} 会撞唯一键 ——
 * 表现为「第一次跑过，第二次跑就失败」，很烦人。
 */
@SpringBootTest
class StaffMapperTest {

    @Autowired
    private StaffMapper staffMapper;

    /**
     * 直接用 JdbcTemplate 清理，而不是给 Mapper 加一个 delete 方法。
     *
     * <p>理由：为了测试方便而往生产代码的接口上加方法，是<b>让测试污染设计</b>。
     * 运维人员本来就不该被物理删除（应该是改 status 禁用，
     * 因为订单、告警里都引用了 staff_id，删了之后那些记录就变成孤儿）。
     * 所以 {@code deleteById} 这个方法是这个业务<b>不该有</b>的，
     * 不能因为测试需要就加上去。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试插入的账号名，用于收尾清理 */
    private String testUsername;

    @AfterEach
    void cleanUp() {
        if (testUsername != null) {
            jdbcTemplate.update("DELETE FROM staff WHERE username = ?", testUsername);
            testUsername = null;
        }
        // ★ 别忘了把 ThreadLocal 清掉。这个测试直接调 Mapper，
        //   没有拦截器帮忙做 afterCompletion —— 不清的话，
        //   同一个 JVM 里后续的测试可能读到这次设的值。
        //   （这正是 JwtTokenAdminInterceptor.afterCompletion 存在的原因，
        //     在测试里同样适用）
        BaseContext.removeCurrentId();
    }

    @Test
    @DisplayName("INSERT：@AutoFill 应填满审计四列，且 create_time 来自 Java 侧的时钟")
    void insertShouldFillAllFourAuditColumns() {
        testUsername = "test_autofill_" + System.nanoTime();

        // 模拟「已登录的运维人员」，id=1 是种子数据里的 admin。
        // 切面从 BaseContext 读的就是这个值
        BaseContext.setCurrentId(1L);

        LocalDateTime before = LocalDateTime.now();

        Staff staff = Staff.builder()
                .username(testUsername)
                .password("$2a$10$placeholder-not-a-real-hash")
                .name("自动填充测试")
                .phone("13900000000")
                .sex("1")
                .role(2)
                .stationId(0L)
                .status(1)
                .build();

        staffMapper.insert(staff);

        LocalDateTime after = LocalDateTime.now();

        // 1) 主键被回填（useGeneratedKeys 生效）
        assertNotNull(staff.getId(), "insert 后 id 应被回填，否则 useGeneratedKeys 没生效");

        // 2) 四个审计字段都被切面填上了
        assertNotNull(staff.getCreateTime(), "create_time 未被 AutoFillInterceptor 填充");
        assertNotNull(staff.getCreateUser(), "create_user 未被 AutoFillInterceptor 填充");
        assertNotNull(staff.getUpdateTime(), "update_time 未被 AutoFillInterceptor 填充");
        assertNotNull(staff.getUpdateUser(), "update_user 未被 AutoFillInterceptor 填充");

        // 3) 填的是当前登录者，不是 0 也不是别的什么人
        assertEquals(1L, staff.getCreateUser(), "create_user 应该是当前登录者 id");
        assertEquals(1L, staff.getUpdateUser(), "update_user 应该是当前登录者 id");

        // 4) ★ 时间是 Java 侧生成的，落在 before/after 之间。
        //    这一条比「不为 null」强得多：DDL 里 create_time 有
        //    DEFAULT CURRENT_TIMESTAMP(3)，所以即使切面完全没生效，
        //    字段也可能**在数据库里**有值 —— 但那是数据库的时钟。
        //    断言它落在这个区间，才真正证明了值是从 Java 传下去的。
        assertTrue(staff.getCreateTime().isAfter(before.minusSeconds(1))
                        && staff.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 不在本次调用的时间区间内，可能是数据库默认值而不是切面填的："
                        + staff.getCreateTime());

        // 5) INSERT 时 update_* 应与 create_* 一致（同一时刻同一人）
        assertEquals(staff.getCreateTime(), staff.getUpdateTime(),
                "INSERT 时 update_time 应与 create_time 相同");
    }

    @Test
    @DisplayName("UPDATE：只填 update_*，绝不碰 create_*（否则「谁创建的」这个历史事实就被改了）")
    void updateShouldNotTouchCreateColumns() {
        // 先插一条，拿到它原始的 create_time / create_user
        testUsername = "test_autofill_upd_" + System.nanoTime();
        BaseContext.setCurrentId(1L);

        Staff staff = Staff.builder()
                .username(testUsername)
                .password("$2a$10$placeholder-not-a-real-hash")
                .name("原名字")
                .role(2)
                .stationId(0L)
                .status(1)
                .build();
        staffMapper.insert(staff);

        // ★ 基准值必须从**数据库**读，不能用插入后内存里的那个对象。
        //
        //   这是写这个测试时踩到的：LocalDateTime.now() 是纳秒精度
        //   （实测拿到 2026-09-18T14:56:34.818918500），而 DDL 里的列是
        //   datetime(3) 只有毫秒。落库时超出部分被**四舍五入**（不是截断）：
        //       .818918500  →  .819
        //   于是「内存值 == 库值」永远不成立，断言必然失败 ——
        //   而失败信息长得像「create_time 被改掉了」，很容易误导到错误方向。
        //
        //   这个精度差是**设计如此**，不是 bug：datetime(3) 的毫秒精度
        //   对审计字段绰绰有余，换 datetime(6) 只为迁就测试是舍本逐末。
        //   要在意的是别拿内存值去比库值 —— 两边都从库里取，比较才是苹果对苹果。
        //
        //   （同一个坑对所有 datetime(3) 列都成立，包括 charging_order.start_time）
        Staff afterInsert = staffMapper.getById(staff.getId());
        assertNotNull(afterInsert, "刚插入的记录应该能查到");

        LocalDateTime originalCreateTime = afterInsert.getCreateTime();
        Long originalCreateUser = afterInsert.getCreateUser();
        assertNotNull(originalCreateTime);

        // 换一个登录者来改（模拟「另一个管理员修改了这个账号」），
        // 这样如果 UPDATE 误填了 create_user，值会从 1 变成 2 —— 一眼能看出来
        BaseContext.setCurrentId(2L);

        Staff toUpdate = Staff.builder()
                .id(staff.getId())
                .name("改过的名字")
                .build();
        staffMapper.update(toUpdate);

        // 从数据库重新读，而不是看内存里的对象 ——
        // XML 里的 <set> 有没有真的把 update_time 写进 SQL，只有查库才知道
        Staff reloaded = staffMapper.getById(staff.getId());
        assertNotNull(reloaded, "更新后记录应该还在");

        // 1) name 确实改了（证明 update 语句本身是通的）
        assertEquals("改过的名字", reloaded.getName());

        // 2) ★ update_user 变成了新的操作者
        assertEquals(2L, reloaded.getUpdateUser(),
                "update_user 应该是本次的操作者 id=2");

        // 3) ★★ create_* 一点没变。
        //    这是这个测试里最重要的断言：AutoFillInterceptor 的 UPDATE 分支
        //    如果写成了「四个字段全填」，这里就会变成 2L 和新时间，
        //    而那种数据污染查起来极其痛苦 —— 字段还在、值也合法，
        //    只是语义被悄悄换掉了
        assertEquals(originalCreateTime, reloaded.getCreateTime(),
                "create_time 被 UPDATE 改掉了，这是一个不该发生的数据污染");
        assertEquals(originalCreateUser, reloaded.getCreateUser(),
                "create_user 被 UPDATE 改掉了，『谁创建的』这个事实被篡改了");
    }

    @Test
    @DisplayName("getById 不返回密码，getByUsername 才会返回（登录是唯一需要密码的路径）")
    void getByIdShouldNeverReturnPassword() {
        testUsername = "test_pwd_" + System.nanoTime();
        BaseContext.setCurrentId(1L);

        Staff staff = Staff.builder()
                .username(testUsername)
                .password("$2a$10$secret-must-not-leak")
                .name("密码测试")
                .role(2)
                .stationId(0L)
                .status(1)
                .build();
        staffMapper.insert(staff);

        // getById 的 SQL 里没有 password 列 → 结果为 null
        Staff byId = staffMapper.getById(staff.getId());
        assertNotNull(byId);
        assertNull(byId.getPassword(),
                "getById 不应该查出 password。"
                        + "这是防止密码哈希随普通查询接口泄露出去的那层保险");

        // getByUsername 的 SQL 里有 password 列 → 拿得到，登录校验靠它
        Staff byName = staffMapper.getByUsername(testUsername);
        assertNotNull(byName);
        assertEquals("$2a$10$secret-must-not-leak", byName.getPassword(),
                "getByUsername 必须能拿到密码，否则登录没法校验");
    }
}
