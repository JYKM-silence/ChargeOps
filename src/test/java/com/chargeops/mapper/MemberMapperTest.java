package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.entity.Member;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemberMapper} 的集成测试
 *
 * <p>写法、理由和「为什么必须用真数据库」见 {@link StaffMapperTest} 的类注释，
 * 这里不重复。本类只写 member 这张表特有的部分。
 *
 * <h2>★★ 一、本类存在的头号理由：证明「password 不会从 XML 那条路漏出来」</h2>
 *
 * <p>{@code MemberMapper} 的类注释和 {@code MemberMapper.xml} 的
 * {@code Base_Column_List} 上各写了一整段，声称：
 * <b>走 XML 的读路径（{@code getById} / {@code page}）返回的 {@code Member}，
 * {@code password} 恒为 null，只有 {@code getByPhone} 和
 * {@code getByIdWithPassword} 两个注解方法才取得到密文。</b>
 *
 * <p>★ <b>这句话在单元测试里证明不了</b>：
 * <ul>
 *   <li>它不是 Java 代码的性质，是<b>一段 SQL 的性质</b> ——
 *       {@code SELECT} 的列清单里有没有 {@code password}</li>
 *   <li>它也不是「启动就能发现」的错误：多查一列不报错、不警告，
 *       只是把一个 BCrypt 密文放进了对象里</li>
 *   <li>而它的后果（实体被直接返回给前端）会在<b>另一个类</b>里发生</li>
 * </ul>
 *
 * <p>所以这条断言只能这么写：插一条真的带密码的行，
 * 分别用四条读路径去取，看谁的 {@code password} 是 null。
 * 它同时钉住了两件事 —— XML 的列清单，以及两个注解方法<b>确实</b>查了密码。
 *
 * <h2>★★ 二、第二条：{@code updateLastLoginTime} 不许碰 {@code update_user}</h2>
 *
 * <p>这是 D5 的落地。它<b>不是</b>「万一有人给它加了 {@code @AutoFill}」的
 * 假想防御 —— 加 {@code @AutoFill(UPDATE)} 是这类「只写一列」的方法最自然的
 * 写法冲动（{@code insert} / {@code update} 都标了，凭什么它不标），
 * 而后果是<b>静默的</b>：接口返回 200，只有几个月后有人查
 * 「这行是谁改的」，才会发现那一列变成了 NULL。
 *
 * <p>★ 所以这里断言 {@code update_user} 在登录前后<b>一模一样</b>，
 * 而不是「不为 null」。前者能抓住「被覆盖成 null」，后者不能。
 *
 * <h2>★★ 三、第三条：{@code update} 一个字段都不许越界</h2>
 *
 * <p>{@code MemberMapper.update} 的注释说「不碰 phone / status / password」
 * 是<b>三层叠加</b>的结果。测试能证明的只有第三层（SQL），
 * 而这一层恰恰是最不该被信任的那一层 —— 它离数据库最近，
 * 却没有编译期检查（{@code <set>} 里多写一列不会有任何提示）。
 *
 * <p>★ 测法是<b>故意传一个「全都设了值」的实体</b>去调 {@code update}，
 * 而不是传一个只带昵称的干净实体。传干净的实体是测不出问题的：
 * 一个「全列更新」的实现也能通过，而它正是要防的东西。
 */
@SpringBootTest
class MemberMapperTest {

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 本次测试创建的车主 id，收尾时按 id 删 */
    private final List<Long> createdIds = new ArrayList<>();

    /** 明文密码，只存在于测试里 —— 用来断言「库里存的不是它」 */
    private static final String RAW_PASSWORD = "rawPwd123";

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM member WHERE id = ?", id);
        }
        createdIds.clear();
        // ★ 清掉分页的 ThreadLocal 和登录上下文。理由见 StationMapperTest.cleanUp。
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /**
     * 生成一个不会和种子数据相撞的手机号
     *
     * <p>★ 用 {@code 15} 开头而不是 {@code 13}：种子车主是 {@code 13800000001}，
     * 冒烟测试用的是 {@code 13900000001}，躲开它们比事后排查
     * 「为什么这条测试第一次跑就撞唯一键」便宜得多。
     *
     * <p>长度是 11 位（{@code 15} + 9 位），和 {@code MemberRegisterDTO}
     * 的 {@code ^1[3-9]\d{9}$} 一致 —— 这不是必须的（DDL 不校验格式），
     * 但让测试数据在形状上和真数据一样，能避免「库里有一批长得不像手机号的
     * 测试数据，后来被当成脏数据清了」。
     */
    private String uniquePhone() {
        return "15" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
    }

    /**
     * 插一条带头像/性别/密码的完整车主
     *
     * <p>★ {@code status} 是<b>显式</b>写的，不能省 —— 见
     * {@link #insertWithoutStatusShouldFailLoudly()}。
     * 生产代码里这个值同样不是省出来的，是 {@code MemberServiceImpl.register}
     * 显式设的 {@code StatusConstant.ENABLE}。
     */
    private Member insertFull(String phone, String rawPassword) {
        Member member = Member.builder()
                .phone(phone)
                .password(rawPassword == null ? null : passwordEncoder.encode(rawPassword))
                .nickname("集成测试车主")
                .avatar("https://example.com/a.png")
                .gender(1)
                .status(1)
                .lastLoginTime(LocalDateTime.now())
                .build();
        memberMapper.insert(member);
        assertNotNull(member.getId(), "insert 后 id 应被回填，否则 useGeneratedKeys 没生效");
        createdIds.add(member.getId());
        return member;
    }

    // ==================== ★★ 一、password 的可见性 ====================

    @Test
    @DisplayName("★★ 四条读路径的密码可见性：只有 getByPhone / getByIdWithPassword 取得到密文")
    void onlyTwoReadPathsShouldReturnPassword() {
        String phone = uniquePhone();
        Member inserted = insertFull(phone, RAW_PASSWORD);
        Long id = inserted.getId();

        // ---- 1) getByPhone（登录入口）：★ 必须取得到 ----
        Member byPhone = memberMapper.getByPhone(phone);
        assertNotNull(byPhone, "getByPhone 是登录入口，取不到就登不进来");
        assertNotNull(byPhone.getPassword(),
                "getByPhone 必须查 password，否则登录没法比对 —— "
                        + "如果这条挂了，去看它的 @Select 列清单");
        // ★ 断言库里存的是密文、不是明文。这一条看着像废话，
        //   但它是「注册时忘了 encode」这个 bug 唯一能被抓到的地方 ——
        //   那个 bug 的症状是登录永远失败，而失败提示是「手机号或密码错误」。
        assertNotEquals(RAW_PASSWORD, byPhone.getPassword(),
                "库里存的是明文密码！注册路径必须经过 passwordEncoder.encode");
        assertTrue(byPhone.getPassword().startsWith("$2"),
                "BCrypt 密文应该以 $2a$/$2b$/$2y$ 开头，实际：" + byPhone.getPassword());
        // ★ 顺带证明这个密文是真的能比对上的（而不是一段格式对但内容错的串）
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, byPhone.getPassword()),
                "存进去的密文和原密码对不上 —— encode 和 matches 用的不是同一套");

        // ---- 2) getByIdWithPassword（改密码专用）：★ 必须取得到 ----
        Member withPassword = memberMapper.getByIdWithPassword(id);
        assertNotNull(withPassword);
        assertNotNull(withPassword.getPassword(),
                "getByIdWithPassword 必须查 password，否则改密码没法验旧密码 —— "
                        + "而它的失败方式很隐蔽：matches(旧密码, null) 恒为 false，"
                        + "表现是「旧密码明明是对的却总说不对」");

        // ---- 3) getById（其余所有场景）：★★ 必须【取不到】 ----
        Member byId = memberMapper.getById(id);
        assertNotNull(byId);
        assertEquals(phone, byId.getPhone());
        assertNull(byId.getPassword(),
                "★★ getById 把 password 查出来了。这条读路径服务的是"
                        + "「查我的信息」和「管理端查车主详情」，而它们的出口是"
                        + "MemberVO —— 一旦这行断言失守，"
                        + "「实体永远不直接返回」就只剩一层口头约定了。"
                        + "修法：看 MemberMapper.xml 的 Base_Column_List 里是不是多了 password");

        // ---- 4) page（管理端列表）：★★ 必须【取不到】 ----
        MemberPageQueryDTO query = new MemberPageQueryDTO();
        query.setPhone(phone);
        PageHelper.startPage(1, 10);
        List<Member> rows = memberMapper.page(query);

        assertEquals(1, rows.size(), "按手机号前缀应该只查到这一条");
        assertNull(rows.get(0).getPassword(),
                "★★ page 把 password 查出来了 —— 这条路径直接喂给管理端列表，"
                        + "是四者里最危险的一个。修法同上：Base_Column_List");
        // ★ 顺便确认 page 确实走的是 XML 那条列清单（而不是碰巧查到了别的行）
        assertEquals(id, rows.get(0).getId());
    }

    // ==================== INSERT / @AutoFill / 响哑对照 ====================

    @Test
    @DisplayName("★ INSERT：注册场景（无登录上下文）→ create_user 是 NULL，而 create_time 必须非空")
    void insertShouldLeaveCreateUserNullWhenNobodyIsLoggedIn() {
        // ★ 注册接口在 /api/auth/** 下，压根不经过 JWT 拦截器，
        //   所以执行到 insert 时 BaseContext 是空的。这里显式清一次，
        //   让这条测试不依赖「前面的测试有没有留下上下文」。
        BaseContext.removeCurrentId();

        LocalDateTime before = LocalDateTime.now();
        Member member = insertFull(uniquePhone(), RAW_PASSWORD);
        LocalDateTime after = LocalDateTime.now();

        // ★★ 这一对断言是「响 vs 哑」那条通用判据在 member 表上的样本：
        //   create_user 可空 → 哑（NULL，插入成功，接口 200）
        assertNull(member.getCreateUser(),
                "自助注册的 create_user 应该是 NULL —— 见 Member 类注释第二节。"
                        + "如果这条挂了，说明有人「顺手补上了它」，"
                        + "而那个 NULL 正是「自注册 ⇔ create_user IS NULL」这个信号的来源");
        assertNull(member.getUpdateUser());

        //   create_time NOT NULL → 响（但这里由 @AutoFill 正常填上）
        assertNotNull(member.getCreateTime());
        assertTrue(member.getCreateTime().isAfter(before.minusSeconds(1))
                        && member.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 不在本次调用的时间区间内，可能是数据库默认值而不是切面填的："
                        + member.getCreateTime());
        assertEquals(member.getCreateTime(), member.getUpdateTime(),
                "INSERT 时 update_time 应与 create_time 相同");
    }

    @Test
    @DisplayName("★ INSERT：登录上下文存在时 create_user 就是那个 id（同一段代码，两种结果）")
    void insertShouldFillCreateUserWhenLoggedIn() {
        // ★ 和上一条构成对照。它证明了 create_user 的 NULL 不是「插件坏了」，
        //   而是「那一刻确实没有登录者」—— 两者读同样的代码，
        //   差别只在 BaseContext 里有没有值。
        BaseContext.setCurrentId(42L);

        Member member = insertFull(uniquePhone(), RAW_PASSWORD);

        assertEquals(42L, member.getCreateUser());
        assertEquals(42L, member.getUpdateUser());
    }

    @Test
    @DisplayName("★★ 漏传 status 时【报错】而不是用 DDL 的 DEFAULT 1 —— 全列插入的后果")
    void insertWithoutStatusShouldFailLoudly() {
        // ★★ 这是「响」的那一半，和上面 create_user 的「哑」构成一对。
        //
        //   DDL 写的是 status tinyint NOT NULL DEFAULT 1，
        //   看上去「不传就是启用」。但 SQL 的默认值语义是：
        //   **只有当该列没有出现在 INSERT 语句里时，DEFAULT 才生效。**
        //   而 MemberMapper.xml 的 insert 是全列插入，传 null 就是
        //   往一个 NOT NULL 列里显式写 NULL → errno 1048 → 插入直接失败。
        //
        //   生产路径上 status 由 MemberServiceImpl.register 显式设成
        //   StatusConstant.ENABLE，这个分支到不了。
        //   把它固定成「一旦有人真的漏传，当场报错」而不是改成动态 SQL 去接住
        //   一个到不了的分支 —— 理由同 StationMapperTest 里的同名用例。
        assertThrows(DataIntegrityViolationException.class,
                () -> memberMapper.insert(Member.builder()
                        .phone(uniquePhone())
                        .nickname("漏传 status 的车主")
                        .build()),
                "全列插入时传 null 应该撞 NOT NULL 约束，而不是静默使用 DDL 默认值");
    }

    // ==================== ★★ D5：登录时间 ====================

    @Test
    @DisplayName("★★ updateLastLoginTime 只写 last_login_time，绝不碰 update_user")
    void updateLastLoginTimeShouldNotTouchUpdateUser() {
        BaseContext.setCurrentId(1L);
        Member member = insertFull(uniquePhone(), RAW_PASSWORD);
        Long id = member.getId();
        LocalDateTime originalLoginTime = member.getLastLoginTime();

        // ★ 基准值从数据库读，不用插入后内存里的那个对象：
        //   datetime(3) 只有毫秒，LocalDateTime.now() 有纳秒，落库时被截断，
        //   内存值永远不等于库值。完整的推导见 StaffMapperTest。
        Member afterInsert = memberMapper.getById(id);
        Long originalUpdateUser = afterInsert.getUpdateUser();
        assertEquals(1L, originalUpdateUser, "前置条件：插入时 update_user 已经是 1");

        // ★★ 模拟真实的登录时刻：那一刻还没有 token，BaseContext 是空的。
        //    登录接口在 /api/auth/** 下，不经过 JWT 拦截器。
        BaseContext.removeCurrentId();

        LocalDateTime loginAt = LocalDateTime.now().plusSeconds(1);
        assertEquals(1, memberMapper.updateLastLoginTime(id, loginAt),
                "影响行数应该是 1 —— 0 的话说明这条 UPDATE 没匹配到行");

        Member reloaded = memberMapper.getById(id);

        // ★ 这条是 D5 的核心断言。
        //   假如哪天有人给这个方法标上 @AutoFill(UPDATE)（因为 insert/update
        //   都标了，看着它不标很奇怪），插件会把
        //   update_user = BaseContext.getCurrentId() = null 写进去，
        //   于是这一列变成 NULL，而接口返回 200、一个错都不报。
        //   用 assertEquals(1L, ...) 而不是 assertNotNull 就是为了抓住这个 ——
        //   「被覆盖成 null」和「被覆盖成别的 id」都应该失败。
        assertEquals(1L, reloaded.getUpdateUser(),
                "★★ update_user 被 updateLastLoginTime 改动了。"
                        + "登录是鉴权事件、不是数据编辑，这次写入不应该留下「谁改的」的痕迹 —— "
                        + "尤其是不能把它改成 NULL。检查是不是给它加了 @AutoFill");

        // ★ 时间确实前进了。用 not-equals 而不是 equals 是刻意的：
        //   这是这个方法的【全部职责】，它必须真的写进去。
        assertNotNull(reloaded.getLastLoginTime());
        assertTrue(reloaded.getLastLoginTime().isAfter(originalLoginTime),
                "last_login_time 没有前进：" + reloaded.getLastLoginTime()
                        + "（原来 " + originalLoginTime + "）");

        // ★ 顺带固定一个已知后果：update_time 会【照常前进】，
        //   因为 DDL 上有 ON UPDATE CURRENT_TIMESTAMP(3)，压不住也不该压。
        //   Member.lastLoginTime 的注释里写清了这一点，
        //   以及随之而来的结论：member.update_time 的含义是
        //   「这一行最后一次变化」，不是「资料最后一次被编辑」。
        assertNotNull(reloaded.getUpdateTime(),
                "update_time 受 ON UPDATE 影响应当照常前进，不该是 null");
    }

    // ==================== ★★ 三、update 的边界 ====================

    @Test
    @DisplayName("★★ update 全字段传入也改不动 phone / status / password")
    void updateShouldNotBeAbleToTouchPhoneStatusOrPassword() {
        BaseContext.setCurrentId(1L);
        String phone = uniquePhone();
        Member member = insertFull(phone, RAW_PASSWORD);
        Long id = member.getId();

        Member before = memberMapper.getByPhone(phone);
        String originalPassword = before.getPassword();

        // ★★ 关键：传一个【什么都设了】的实体，而不是只带 nickname 的干净实体。
        //   传干净实体的话，一个「全列更新」的实现也能通过测试 ——
        //   而「全列更新」正是这里要防的东西。
        //   这三个字段在真实调用路径上到不了这里（MemberUpdateDTO 里没有它们），
        //   但测试要问的是：【即使它们到了，SQL 拦不拦得住】。
        memberMapper.update(Member.builder()
                .id(id)
                .nickname("改过的昵称")
                .phone("19999999999")
                .status(0)
                .password("$2a$10$someoneElsesHashInjectedHere000000000000000000000000000000")
                .build());

        Member after = memberMapper.getByIdWithPassword(id);

        // ---- 该改的改了 ----
        assertEquals("改过的昵称", after.getNickname());

        // ---- 不该改的一个都没改 ----
        assertEquals(phone, after.getPhone(),
                "★★ phone 被 update 改掉了。phone 同时是登录账号和 uk_member_phone，"
                        + "改它等于换登录凭据 —— 而换凭据需要短信验证，本轮没有短信设施。"
                        + "检查 MemberMapper.xml 的 <set> 里是不是多了 phone 这一列");
        assertEquals(1, after.getStatus(),
                "★★ status 被 update 改掉了。车主不能改自己的 status（否则「禁用」"
                        + "就是一个请求就能绕过的功能），管理端改 status 走的是"
                        + "独立的 updateStatus 窄语句。检查 <set> 里是不是多了 status");
        assertEquals(originalPassword, after.getPassword(),
                "★★ password 被 update 改掉了 —— 这一条最严重：它意味着" +
                        "「改昵称」这个请求可以顺手把密码换成攻击者已知的哈希，"
                        + "也就是一次完整的账号接管。检查 <set> 里是不是多了 password");
    }

    @Test
    @DisplayName("★★ updatePassword 只写 password + 审计两列，不碰 nickname / phone / status")
    void updatePasswordShouldOnlyTouchPassword() {
        BaseContext.setCurrentId(1L);
        String phone = uniquePhone();
        Member member = insertFull(phone, RAW_PASSWORD);
        Long id = member.getId();

        String newRaw = "brandNewPwd456";
        String newHash = passwordEncoder.encode(newRaw);

        // ★ 调用方只需要 setId + setPassword，两个审计列由 @AutoFill 填。
        //   同时故意把 nickname 也设上 —— 一个「顺手复制整个对象」的实现
        //   会把它一起写进去，而那条 UPDATE 里根本没有 nickname 这一列。
        BaseContext.setCurrentId(7L);
        assertEquals(1, memberMapper.updatePassword(Member.builder()
                .id(id)
                .password(newHash)
                .nickname("不该被写进去的昵称")
                .build()));

        Member after = memberMapper.getByIdWithPassword(id);

        // ---- 密码真的换了，而且新密码能验证通过 ----
        assertTrue(passwordEncoder.matches(newRaw, after.getPassword()),
                "新密码验证不通过 —— updatePassword 写的不是传进来的那个密文");
        assertFalse(passwordEncoder.matches(RAW_PASSWORD, after.getPassword()),
                "旧密码仍然能登录，说明密码没换成功");

        // ---- 审计列 ----
        assertEquals(7L, after.getUpdateUser(),
                "update_user 应该是本次改密码的人（车主自己）。"
                        + "★ 这也是「改密码走独立方法」的一个顺带收益："
                        + "它的审计语义比混进通用 update 清晰");

        // ---- 越界检查 ----
        assertEquals("集成测试车主", after.getNickname(),
                "★ nickname 被 updatePassword 改掉了 —— 那条 SQL 里不该有这一列。"
                        + "它会造成一个很难查的现象：改密码顺手把昵称重置了");
        assertEquals(phone, after.getPhone());
        assertEquals(1, after.getStatus());
    }

    @Test
    @DisplayName("★ updateStatus 只写 status —— 管理端禁用车主时不可能顺手改掉昵称")
    void updateStatusShouldOnlyTouchStatus() {
        BaseContext.setCurrentId(1L);
        Member member = insertFull(uniquePhone(), RAW_PASSWORD);
        Long id = member.getId();

        // 管理端的身份是 staff，这里用一个明显的 staff id 值来区分
        BaseContext.setCurrentId(99L);
        assertEquals(1, memberMapper.updateStatus(Member.builder()
                .id(id)
                .status(0)
                .nickname("不该被写进去的昵称")
                .build()));

        Member after = memberMapper.getById(id);

        assertEquals(0, after.getStatus(), "status 应该被改成禁用");
        assertEquals("集成测试车主", after.getNickname(),
                "★ nickname 被 updateStatus 改掉了 —— 管理端对车主的权限面"
                        + "恰好是 1 bit，那条 SQL 里不该有 nickname");

        // ★★ 顺带把 update_user 的多态固定下来：这里存的是【staff id】。
        //   member.update_user 因此没有类型信息 —— 值是 99 时，
        //   可能是车主 99 也可能是运维 99，排查时必须两张表都查一次。
        //   这是已知并接受的代价，见 MemberMapper.updateStatus 的注释。
        assertEquals(99L, after.getUpdateUser(),
                "★ 这里存的应该是 staff id（管理端的操作者），不是车主自己的 id");
    }

    // ==================== 唯一约束 ====================

    @Test
    @DisplayName("★ 重复 phone 抛 DuplicateKeyException（注册接口的「已存在」就是靠它）")
    void duplicatePhoneShouldThrowDuplicateKeyException() {
        String phone = uniquePhone();
        insertFull(phone, RAW_PASSWORD);

        // ★ 断言具体的异常类型：GlobalExceptionHandler.handleDuplicateKeyException
        //   只认 org.springframework.dao.DuplicateKeyException。
        //   哪天 MyBatis 的异常翻译层没了，这里会变成 PersistenceException，
        //   那个 handler 就接不住 —— 注册接口会返回 500 而不是「xxx已存在」。
        //   而且它的 message 会被正则 `Duplicate entry '(.*?)' for key` 提取，
        //   所以断言里顺带确认了 message 的形状没变。
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> insertFull(phone, RAW_PASSWORD),
                "重复手机号应该抛 DuplicateKeyException");

        // ★ 这个 message 形状是 GlobalExceptionHandler 的正则依赖的，
        //   它决定了前端看到的是「138xxxx已存在」还是「未知错误」。
        assertTrue(ex.getMessage().contains("uk_member_phone"),
                "DuplicateKeyException 的 message 里应该含唯一键名，实际：" + ex.getMessage());
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：phone 前缀 / nickname 模糊 / status 精确 —— 三种匹配方式各不相同")
    void pageShouldUseThreeDifferentMatchingStyles() {
        BaseContext.setCurrentId(1L);

        // ★ 用一段不可能和种子数据相撞的前缀圈定本次测试的行。
        //   注意 phone 是【前缀】匹配，所以这个前缀必须真的是手机号的开头。
        String phonePrefix = uniquePhone().substring(0, 7);
        String nicknameTag = "IT" + Long.toString(System.nanoTime() % 100_000_000L, 36);

        // 两条 status=1，一条 status=0。
        // ★ 后缀补 4 位让手机号凑满 11 位 —— DDL 不校验格式，
        //   但让测试数据在形状上和真数据一样，能避免它被后来的人当成脏数据清掉。
        Member normal1 = insertFull(phonePrefix + "0001", RAW_PASSWORD);
        Member normal2 = insertFull(phonePrefix + "0002", RAW_PASSWORD);
        Member disabled = insertFull(phonePrefix + "0003", RAW_PASSWORD);

        // 昵称统一带上 tag，这样 nickname 模糊匹配能圈到同一批行
        for (Member m : List.of(normal1, normal2, disabled)) {
            memberMapper.update(Member.builder().id(m.getId()).nickname("车主" + nicknameTag).build());
        }
        // 其中一条禁用
        memberMapper.updateStatus(Member.builder().id(disabled.getId()).status(0).build());

        // ---- 1) phone 前缀匹配 + status 精确 ----
        MemberPageQueryDTO q1 = new MemberPageQueryDTO();
        q1.setPhone(phonePrefix);
        q1.setStatus(1);

        PageHelper.startPage(1, 10);
        List<Member> rows1 = memberMapper.page(q1);
        long total1 = ((Page<Member>) rows1).getTotal();

        assertEquals(2, rows1.size(), "前缀 + status=1 应该只筛出 2 条");
        // ★ size() 只证明过滤条件对了；getTotal() 才证明 PageHelper 真的接上了。
        //   只断言 size() 的话，把 page() 换成不做分页的普通查询也能通过。
        assertEquals(2, total1, "getTotal() 应该是 2 —— 不等于 2 说明 PageHelper 没接上");

        // ---- 2) ★ phone 是【前缀】匹配，不是模糊匹配 ----
        //   传完整手机号是对的；但传号码【中间】的一段必须查不到 ——
        //   这一条是「两侧加 % 会让 uk_member_phone 完全用不上」那个决定的运行时形态。
        MemberPageQueryDTO q2 = new MemberPageQueryDTO();
        q2.setPhone(phonePrefix.substring(2));
        PageHelper.startPage(1, 10);
        assertEquals(0, memberMapper.page(q2).size(),
                "phone 是前缀匹配 —— 号码中间的一段不该查到任何行。"
                        + "查到说明 SQL 里写成了两侧 %，那个入口就注定走全表扫描了");

        // ---- 3) nickname 是【模糊】匹配（中间匹配也要能命中） ----
        MemberPageQueryDTO q3 = new MemberPageQueryDTO();
        // 只取 tag 的中间一段 —— 前缀和后缀都不完整
        q3.setNickname(nicknameTag);
        PageHelper.startPage(1, 10);
        List<Member> rows3 = memberMapper.page(q3);
        assertEquals(3, rows3.size(),
                "nickname 是模糊匹配，应该命中全部 3 条（不看 status）");

        // ---- 4) status 精确匹配 ----
        MemberPageQueryDTO q4 = new MemberPageQueryDTO();
        q4.setPhone(phonePrefix);
        q4.setStatus(0);
        PageHelper.startPage(1, 10);
        List<Member> rows4 = memberMapper.page(q4);
        assertEquals(1, rows4.size(), "status=0 应该只筛出那一条被禁用的");
        assertEquals(disabled.getId(), rows4.get(0).getId());

        // ---- 5) ORDER BY id DESC（分页正确性的前提） ----
        MemberPageQueryDTO q5 = new MemberPageQueryDTO();
        q5.setPhone(phonePrefix);
        PageHelper.startPage(1, 10);
        List<Member> rows5 = memberMapper.page(q5);
        assertEquals(3, rows5.size());
        // ★ 没有 ORDER BY 时 MySQL 的行序是未定义的，
        //   翻页会出现「第 1 页的第 3 条在第 2 页又出现一次」。
        //   这条断言是第一道哨兵。
        assertTrue(rows5.get(0).getId() > rows5.get(1).getId(),
                "应该按 id 倒序，最新的排最前。实际顺序："
                        + rows5.stream().map(Member::getId).toList());

        // ---- 6) 分页真的生效，且翻页不重不漏 ----
        MemberPageQueryDTO q6 = new MemberPageQueryDTO();
        q6.setPhone(phonePrefix);

        PageHelper.startPage(1, 2);
        List<Member> page1 = memberMapper.page(q6);
        PageHelper.startPage(2, 2);
        List<Member> page2 = memberMapper.page(q6);

        assertEquals(2, page1.size(), "pageSize=2 应该只返回 2 行 —— 这是 LIMIT 生效的证据");
        assertEquals(3, ((Page<Member>) page1).getTotal(), "总数不受 pageSize 影响");
        assertEquals(1, page2.size());

        List<Long> ids1 = page1.stream().map(Member::getId).toList();
        List<Long> ids2 = page2.stream().map(Member::getId).toList();
        assertTrue(ids1.stream().noneMatch(ids2::contains),
                "第 1 页和第 2 页出现了重复记录，说明排序不稳定。page1=" + ids1 + " page2=" + ids2);
    }

    @Test
    @DisplayName("page：不带任何条件时 LIMIT 和 COUNT 都要生效")
    void pageWithoutFilterShouldStillWork() {
        BaseContext.setCurrentId(1L);
        // ★ 先自己造 3 条，这样「总数至少是 3」不依赖库里既有数据的规模 ——
        //   依赖种子数据会写成一条只在本地成立的断言。
        for (int i = 0; i < 3; i++) {
            insertFull(uniquePhone(), RAW_PASSWORD);
        }

        PageHelper.startPage(1, 2);
        List<Member> rows = memberMapper.page(new MemberPageQueryDTO());

        assertEquals(2, rows.size(), "pageSize=2 应该只返回 2 行 —— LIMIT 生效的证据");
        assertTrue(((Page<Member>) rows).getTotal() >= 3,
                "总数应该至少包含本次插入的 3 条 —— COUNT 生效的证据，实际："
                        + ((Page<Member>) rows).getTotal());
    }

    // ==================== 种子数据的前置事实 ====================

    @Test
    @DisplayName("★ 种子车主 1 的 password 是 NULL —— 登录那条「未设置密码」分支的前提")
    void seedMemberShouldHaveNullPassword() {
        // ★ 这条测试看着像在测种子数据，其实是在【钉住一个前提】：
        //   MemberServiceImpl.login 里有第三道判断，专门处理
        //   「这个账号没设过密码」，而它存在的唯一理由是
        //   DDL 注释说的「验证码登录的用户为 NULL」。
        //
        //   如果哪天有人给种子车主补上了密码（比如为了冒烟测试方便），
        //   那条分支在本地就再也不可测了 —— 而它恰恰是最容易被写成
        //   「顺带删掉」的一段代码。这条断言会让那次修改被看见。
        Member seed = memberMapper.getByPhone("13800000001");
        if (seed == null) {
            // 种子数据没导入时跳过式的断言 —— 不用 assumeTrue 是因为
            // 这个类里的其它测试都不依赖种子数据，没必要整类跳掉。
            return;
        }
        assertNull(seed.getPassword(),
                "种子车主 1 的密码应该是 NULL（DDL 注释：「验证码登录的用户为 NULL」）。"
                        + "它同时是 MemberServiceImpl.login 里 PASSWORD_NOT_SET 分支的测试前提，"
                        + "以及 D9「车主不能改自己的 phone」那条论证的样例");
    }
}
