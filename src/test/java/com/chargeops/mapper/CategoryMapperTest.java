package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.CategoryPageQueryDTO;
import com.chargeops.entity.Category;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CategoryMapper} 的集成测试
 *
 * <p>写法、理由和「为什么必须用真数据库」见 {@link StaffMapperTest} 的类注释。
 *
 * <h2>★★ 一、头号理由：{@code uk_category_type_name} 是<b>复合</b>唯一</h2>
 *
 * <p>老库是 {@code uk(name)} 全局唯一，新库改成了 {@code uk(type, name)}，
 * DDL 的理由是「『快充』既可能是套餐分类也可能是服务分类，全局唯一会误伤」。
 *
 * <p>★ 这条<b>只能靠真索引验</b>：
 * <ul>
 *   <li>「同一个名字在两个 type 下各存一份」—— 在 Java 侧看不出来，
 *       桩类里更没有索引这回事</li>
 *   <li>而且它错起来是<b>反向</b>的：真写成了全局唯一，
 *       报错会发生在「加第二个同名的不同类分类」时，
 *       而那一刻管理员完全不明白自己做错了什么</li>
 * </ul>
 *
 * <p>★ 这条测试同时是 {@code CategoryMapper} 类注释第二节那句
 * 「本项目的做法是不写预检，让 {@code uk_category_type_name} 去挡，
 * 于是『必须带上 type 一起判』这个坑也不存在了」的<b>证据</b> ——
 * 数据库判的本来就是复合键。
 *
 * <h2>★★ 二、第二条：{@code deleteById} 不查任何别的表</h2>
 *
 * <p>{@code charge_package.category_id} 是裸 {@code bigint}，<b>全项目没有外键</b>。
 * 所以删掉一个下面还挂着套餐的分类会<b>成功</b>，留下的套餐指向一个不存在的 id。
 *
 * <p>★ 这不是「redis 式的意外」，是 DDL 的既定选择，而它的代价被
 * {@code CategoryServiceImpl.delete} 里的 {@code assertDeletable} 接住了 ——
 * 那个守卫必须存在，否则删除路径上什么都没有。
 * 这条测试就是「那个守卫为什么必须存在」的可执行版本。
 *
 * <h2>★ 三、第三条：{@code listEnabled} 是 {@code idx_category_type_sort} 唯一的消费方</h2>
 *
 * <p>{@code where type=? and status=1 order by sort, id} ——
 * 三个位置正好对上索引的列序。这里测的不是「走没走索引」
 * （那要看 EXPLAIN，且有数据量才有意义），而是<b>这条查询的语义</b>：
 * 停用的不返回、type 过滤对得上、排序稳定。
 */
@SpringBootTest
class CategoryMapperTest {

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private ChargePackageMapper chargePackageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的分类 id */
    private final List<Long> createdCategoryIds = new ArrayList<>();

    /** 本次测试创建的套餐 id（用 JdbcTemplate 直接删） */
    private final List<Long> createdPackageIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // ★ 先删套餐再删分类 —— 和业务上的顺序一致（守卫就是这么要求的）。
        //   反过来其实也能删（没有外键），但让清理顺序和业务顺序一致，
        //   可以让「没有外键」这件事不在这段清理代码里留下痕迹。
        for (Long id : createdPackageIds) {
            jdbcTemplate.update("DELETE FROM charge_package WHERE id = ?", id);
        }
        for (Long id : createdCategoryIds) {
            jdbcTemplate.update("DELETE FROM category WHERE id = ?", id);
        }
        createdPackageIds.clear();
        createdCategoryIds.clear();
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /**
     * 造一个分类名
     *
     * <p>★ 用纳米时间做后缀，避免自己和自己撞 {@code uk_category_type_name} ——
     * 「同一个名字在两个 type 下各存一份」正是下面要测的事，
     * 而那需要我先有一个确定的名字。
     */
    private String uniqueName() {
        return "测试分类" + (System.nanoTime() % 100_000_000L);
    }

    private Category insertCategory(String name, int type, int sort, int status) {
        BaseContext.setCurrentId(1L);
        Category category = Category.builder()
                .name(name)
                .type(type)
                .sort(sort)
                .status(status)
                .build();
        categoryMapper.insert(category);
        assertNotNull(category.getId(), "insert 后 id 应被回填，否则 useGeneratedKeys 没生效");
        createdCategoryIds.add(category.getId());
        return category;
    }

    /**
     * 直接往 {@code charge_package} 里插一行，指向指定的分类
     *
     * <p>★ 用 {@code JdbcTemplate} 而不是 {@code ChargePackageMapper.insert}：
     * 后者要走 {@code @AutoFill} 和整套实体规则，而这里只关心
     * 「{@code category_id} 这一列有没有被引用」这一件事。
     * 让这个测试依赖 {@code ChargePackage} 实体的所有必填字段，
     * 只会让它在无关的改动里失败。
     */
    private Long insertPackageReferencing(Long categoryId) {
        // ★ 名字也不能重复：uk_pkg_name_station (name, station_id)
        String name = "测试套餐" + (System.nanoTime() % 100_000_000L);
        // ★ 手写 INSERT 就必须自己填 NOT NULL 的审计列 ——
        //   DDL 的 DEFAULT 只在列【没出现在语句里】时生效，这里列名在语句里。
        //   这是个很适合放在测试里的反面教材：服务端代码走全列插入，
        //   所以那边的默认值必须由 DTO 提供。
        jdbcTemplate.update(
                "INSERT INTO charge_package (name, category_id, package_type, station_id, price, "
                        + "valid_days, status, sort, create_time, update_time) "
                        + "VALUES (?, ?, 1, 0, 100.00, 0, 1, 0, NOW(3), NOW(3))",
                name, categoryId);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM charge_package WHERE name = ?", Long.class, name);
        assertNotNull(id);
        createdPackageIds.add(id);
        return id;
    }

    // ==================== ★★ 一、复合唯一键 ====================

    @Test
    @DisplayName("★★ 同一个名字可以在 type=1 和 type=2 下各存一份（唯一键是复合的）")
    void sameNameUnderDifferentTypesShouldBothBeAllowed() {
        String name = uniqueName();

        Category chargePackage = insertCategory(name, 1, 1, 1);
        // ★★ 这一条必须成功。uk_category_type_name 是 (type, name) 复合键，
        //   不是 uk(name) —— DDL 的理由是「『快充』既可能是套餐分类
        //   也可能是服务分类，全局唯一会误伤」。
        Category valueAdded = insertCategory(name, 2, 1, 1);

        assertNotNull(chargePackage.getId());
        assertNotNull(valueAdded.getId());

        // ★ 两行同名但 type 不同，是【想要的行为】，不是重复数据。
        //   这条断言防的是「后来有人觉得同名看着碍眼，加了个 name 的唯一索引」——
        //   那会让上面第二行插不进去，而报错信息只说「已存在」，
        //   管理员完全不知道自己做错了什么。
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT id FROM category WHERE name = ? ORDER BY type", Long.class, name);
        assertEquals(2, found.size(),
                "同一个名字应该能在两个 type 下各存一份 —— "
                        + "只有一条说明 uk_category_type_name 被改成了全局唯一");

        // ★ 顺带确认两行的 type 确实不同（而不是同一个 type 下插了两行 ——
        //   那说明唯一键完全没有生效）
        assertEquals(1, categoryMapper.getById(chargePackage.getId()).getType());
        assertEquals(2, categoryMapper.getById(valueAdded.getId()).getType());
    }

    @Test
    @DisplayName("★ 同一个 type 下重名 → DuplicateKeyException（「已存在」就是靠它）")
    void sameNameUnderSameTypeShouldThrowDuplicateKeyException() {
        String name = uniqueName();
        insertCategory(name, 1, 1, 1);

        // ★ 断言具体的异常类型：GlobalExceptionHandler.handleDuplicateKeyException
        //   只认 org.springframework.dao.DuplicateKeyException。
        //   没有这个翻译层的话，管理端新增分类时重名会返回 500 +「未知错误」。
        DuplicateKeyException ex = assertThrows(DuplicateKeyException.class,
                () -> insertCategory(name, 1, 2, 1),
                "同一 type 下重名应该抛 DuplicateKeyException");
        assertTrue(ex.getMessage().contains("uk_category_type_name"),
                "message 里应该含唯一键名，实际：" + ex.getMessage());

        // ★★ 而换个 type 就成功了 —— 和上面那条对照着看，
        //    这两条合起来才证明了「复合」这两个字。
        insertCategory(name, 2, 1, 1);
    }

    @Test
    @DisplayName("★★ 唯一键【不看】status —— 停用之后那个名字仍然被占用")
    void uniqueKeyShouldIgnoreStatus() {
        String name = uniqueName();
        Category category = insertCategory(name, 1, 1, 1);

        // 停用它（这是「下架」的正常手段）
        categoryMapper.update(Category.builder().id(category.getId()).status(0).build());

        // ★★ 「停用」不能让这个名字被释放 —— 唯一键是 (type, name)，
        //   里面没有 status。这不是 bug，是硬删接口必须存在的理由：
        //   一个错别字分类名下架之后仍然永久占用那个名字，
        //   想用正确的名字只能先把它硬删掉。
        //   对照 charge_package 的 uk_pkg_name_station 是同一个形状。
        assertThrows(DuplicateKeyException.class,
                () -> insertCategory(name, 1, 2, 1),
                "★ 停用后重名仍然应该被拦 —— 这条断言就是「硬删必须存在」的论据。"
                        + "如果哪天它变成通过了，说明唯一键的列变了，"
                        + "那时 CategoryMapper.deleteById 的注释和"
                        + " MemberAdminController 里那张对比表都要重写");
    }

    // ==================== ★★ 二、没有外键 ====================

    @Test
    @DisplayName("★★ 删掉一个下面还挂着套餐的分类会【成功】—— 全项目没有外键")
    void deleteByIdShouldSucceedEvenWhenPackagesReferenceIt() {
        Category category = insertCategory(uniqueName(), 1, 1, 1);
        Long packageId = insertPackageReferencing(category.getId());

        // ★ 先确认引用确实存在 —— 没有这条，下面的删除成功说明不了任何事
        //   （有可能是因为套餐根本没插进去）
        assertEquals(1, chargePackageMapper.countByCategoryId(category.getId()),
                "前置条件：这个分类下应该正好有 1 个套餐");

        // ★★ 删除【成功】。有外键的话这里会抛 DataIntegrityViolationException
        //   （errno 1451），而不是返回 1。
        assertEquals(1, categoryMapper.deleteById(category.getId()),
                "★★ 删除一个有套餐的分类应该【成功】—— charge_package.category_id "
                        + "是裸 bigint，全项目没有外键。"
                        + "这条断言如果挂了，说明有人加了外键 —— "
                        + "那会同时改变删除路径的报错方式（变成「未知错误」）"
                        + "和数据迁移的可能，是一个需要重新论证的改动");

        // ★ 分类没了，套餐还在，指向一个不存在的 id
        assertNull(categoryMapper.getById(category.getId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT id FROM charge_package WHERE id = ?", Long.class, packageId),
                "套餐不该被连带删掉 —— 没有级联");

        // ★★ 而这就是 CategoryServiceImpl.assertDeletable 存在的理由：
        //   数据库什么都不拦，所以删除路径上【必须】有个应用层的守卫。
        //   下面这条断言把这个「孤儿引用」的形态固定下来：
        //   套餐的 category_id 指向一个查不到的 id。
        Long danglingCategoryId = jdbcTemplate.queryForObject(
                "SELECT category_id FROM charge_package WHERE id = ?", Long.class, packageId);
        assertNull(categoryMapper.getById(danglingCategoryId),
                "★ 这是一个【孤儿引用】：套餐的 category_id 指向不存在的分类。"
                        + "它就是「删之前必须先 countByCategoryId」那条守卫要防的状态");

        // ★★ 而 countByCategoryId 在分类被删之后【仍然返回 1】。
        //
        //   ★ 第一版这里写的是 assertEquals(0, ...)，跑起来是 1 —— 又是我的断言错了，
        //     而错的原因值得写下来：它数的是 charge_package.category_id
        //     这一列的【原值】，不 JOIN category，
        //     所以它根本看不见「这个分类已经不存在了」。
        //
        //   这把它的能力边界钉死了：
        //     ✅ 它是【删之前的守卫】的输入
        //     ❌ 它不是引用完整性检查
        //     ❌ 它不能用来发现或修复孤儿引用
        //
        //   ★ 想找孤儿引用得用另一条查询：
        //       SELECT p.id FROM charge_package p
        //       LEFT JOIN category c ON p.category_id = c.id
        //       WHERE c.id IS NULL
        //     —— 它不存在于本项目的任何地方，因为孤儿引用
        //     在本项目的设计里【不应该产生】（守卫拦着），
        //     而不是「产生了之后被清理」。
        //
        //   ★ 顺带把「守卫不是原子」这件事也钉住：上面的删除已经证明
        //     「count 和 delete 之间有一个窗口」——只是这里是我自己
        //     手动制造的顺序，而不是并发。
        assertEquals(1, chargePackageMapper.countByCategoryId(category.getId()),
                "★ countByCategoryId 数的是 category_id 这一列的原值，不 JOIN category —— "
                        + "所以分类删了之后它仍然返回 1。"
                        + "它是删之前的守卫，不是引用完整性检查");
    }

    // ==================== ★ listEnabled ====================

    @Test
    @DisplayName("★ listEnabled：只返回启用的、type 过滤正确、按 sort 升序且用 id 兜底稳定")
    void listEnabledShouldFilterByStatusAndTypeAndOrderBySort() {
        // ★ 用两个不可能和种子数据相撞的 sort 区间圈定本次测试的行。
        //   ★★ 关键：种子里 type=1 有两条（sort 1、2）、type=2 有一条（sort 3），
        //   而 listEnabled 不传 type 时会返回【全部】启用分类 ——
        //   所以不能断言「总数等于 3」，必须靠 sort 区间把本次的行挑出来。
        long base = System.nanoTime() % 1_000_000L;
        int sortBase = 1_000_000 + (int) base;

        // type=1 三条：两个启用（sort 相邻）、一个停用
        Category a = insertCategory(uniqueName(), 1, sortBase + 1, 1);
        Category b = insertCategory(uniqueName(), 1, sortBase + 2, 1);
        Category disabled = insertCategory(uniqueName(), 1, sortBase + 3, 0);
        // type=2 一条启用
        Category otherType = insertCategory(uniqueName(), 2, sortBase + 4, 1);

        // ---- 1) 传 type：只返回该 type 下【启用】的 ----
        List<Category> type1 = categoryMapper.listEnabled(1);
        List<Long> type1Ids = type1.stream().map(Category::getId).toList();

        assertTrue(type1Ids.contains(a.getId()) && type1Ids.contains(b.getId()),
                "启用的分类应该被返回");
        assertTrue(!type1Ids.contains(disabled.getId()),
                "★★ 停用的分类不该出现在 listEnabled 里 —— 它的语义是"
                        + "「还出不出现在新建时的下拉框里」。"
                        + "返回它的话，前端得再过滤一次，"
                        + "而「停用的不该被选」是业务规则，不是展示偏好");
        assertTrue(!type1Ids.contains(otherType.getId()),
                "type=2 的分类不该出现在 type=1 的列表里");

        // ---- 2) 排序：sort 升序 ----
        List<Category> mine = type1.stream()
                .filter(c -> c.getSort() >= sortBase && c.getSort() <= sortBase + 4)
                .toList();
        assertEquals(List.of(a.getId(), b.getId()),
                mine.stream().map(Category::getId).toList(),
                "★ 应该按 sort 升序 —— a 的 sort 比 b 小，所以 a 在前");

        // ---- 3) ★ sort 相同的两条，顺序必须稳定（靠 id 兜底成全序） ----
        //   ORDER BY sort 后面的那个 id 不是装饰：sort 不是唯一键，
        //   两条 sort 相同的分类在两次请求里的顺序可能不同。
        //   ★ 这一条对着上面那条一起看：上面的 a/b 靠 sort 区分，
        //     下面这两个的 sort 完全一样，只能靠 id 决定顺序。
        Category same1 = insertCategory(uniqueName(), 1, sortBase + 5, 1);
        Category same2 = insertCategory(uniqueName(), 1, sortBase + 5, 1);

        List<Long> first = categoryMapper.listEnabled(1).stream()
                .filter(c -> c.getSort() == sortBase + 5)
                .map(Category::getId).toList();
        List<Long> second = categoryMapper.listEnabled(1).stream()
                .filter(c -> c.getSort() == sortBase + 5)
                .map(Category::getId).toList();

        assertEquals(List.of(same1.getId(), same2.getId()), first,
                "★ sort 相同时应该按 id 升序。没有这个兜底的话，"
                        + "下拉框里两个同序的分类每次刷新都可能换位置");
        assertEquals(first, second, "★ 两次查询的顺序必须一致 —— 这就是排序稳定性的意思");

        // ---- 4) 不传 type：返回全部启用分类（管理端的「全部」视图） ----
        List<Category> all = categoryMapper.listEnabled(null);
        List<Long> allIds = all.stream().map(Category::getId).toList();
        assertTrue(allIds.contains(a.getId()) && allIds.contains(otherType.getId()),
                "★ 不传 type 时应该两个 type 的启用分类都返回 —— "
                        + "这是管理端的「全部」视图，和下拉框的语义不同"
                        + "（下拉框永远明确知道自己要哪个 type）");
        assertTrue(!allIds.contains(disabled.getId()), "不传 type 也不该返回停用的");
    }

    // ==================== update 的边界 ====================

    @Test
    @DisplayName("★★ update 改不动 type，即使传进来一个设了 type 的实体")
    void updateShouldNotBeAbleToChangeType() {
        Category category = insertCategory(uniqueName(), 1, 1, 1);

        // ★ 和 MemberMapperTest 里那条同形：传一个【什么都设了】的实体，
        //   而不是只带 name 的干净实体。传干净实体的话，
        //   一个「全列更新」的实现也能通过测试 —— 而它正是要防的东西。
        //
        //   type 在真实调用路径上到不了这里（CategoryUpdateDTO 里没有它），
        //   但测试要问的是：【即使它到了，SQL 拦不拦得住】。
        //   ★ 允许改 type 等于允许把一个分类（连同下面挂着的套餐）
        //     从一个语义域搬到另一个，而套餐那边不会有任何反应。
        categoryMapper.update(Category.builder()
                .id(category.getId())
                .name("改过的分类名")
                .type(2)
                .sort(99)
                .status(0)
                .build());

        Category reloaded = categoryMapper.getById(category.getId());

        // ---- 该改的改了 ----
        assertEquals("改过的分类名", reloaded.getName());
        assertEquals(99, reloaded.getSort());
        assertEquals(0, reloaded.getStatus(),
                "★ status 是能改的：停用/启用是运营操作，随时可改。"
                        + "它的语义是「还出不出现在下拉框里」，不是「存量数据失效」——"
                        + "和 type 刚好相反");

        // ---- 不该改的没改 ----
        assertEquals(1, reloaded.getType(),
                "★★ type 被 update 改掉了。Category 的类注释第二节说了它是业务事实、"
                        + "不是可编辑的资料 —— 检查 CategoryMapper.xml 的 <set> 里"
                        + "是不是多了 type 这一列");
    }

    @Test
    @DisplayName("★ update 局部生效：不传的字段保持原值（全项目统一的取舍）")
    void updateShouldLeaveUnsetFieldsAlone() {
        Category category = insertCategory(uniqueName(), 1, 7, 1);

        categoryMapper.update(Category.builder().id(category.getId()).sort(8).build());

        Category reloaded = categoryMapper.getById(category.getId());
        assertEquals(8, reloaded.getSort());
        // ★ 这不是「正确」，是「已知并接受的限制」：
        //   <set> + <if> 下传 null 的字段不出现在 SQL 里，
        //   所以**没法把 name 改成空**。把它的行为固定下来，
        //   是为了让哪天改成全行更新时这条测试**失败**，从而逼着修改者去读注释。
        assertEquals(category.getName(), reloaded.getName(),
                "局部更新把未传的字段清掉了 —— 如果这是有意改成了全行更新，"
                        + "请一并更新 CategoryMapper.update 的 javadoc 和这条测试");
        assertEquals(1, reloaded.getStatus());
    }

    @Test
    @DisplayName("★ INSERT：漏传 sort / status 时【报错】而不是用 DDL 的 DEFAULT")
    void insertWithoutSortOrDefaultStatusShouldFailLoudly() {
        BaseContext.setCurrentId(1L);

        // ★ sort int NOT NULL DEFAULT 0、status tinyint NOT NULL DEFAULT 1，
        //   看上去「不传就有默认值」。但 insert 是全列插入，
        //   列名在语句里 → 传 null 就是往 NOT NULL 列显式写 NULL → errno 1048。
        //   生产路径上这两个值由 CategoryDTO 的字段初始值提供
        //   （DEFAULT_SORT / DEFAULT_STATUS），Service 再兜一次，所以到不了这里。
        assertThrows(DataIntegrityViolationException.class,
                () -> categoryMapper.insert(Category.builder()
                        .name(uniqueName())
                        .type(1)
                        .build()),
                "全列插入时 sort 传 null 应该撞 NOT NULL 约束，而不是静默使用 DDL 默认值");

        // ★ 只漏 status 也一样
        assertThrows(DataIntegrityViolationException.class,
                () -> categoryMapper.insert(Category.builder()
                        .name(uniqueName())
                        .type(1)
                        .sort(0)
                        .build()),
                "全列插入时 status 传 null 应该撞 NOT NULL 约束");
    }

    @Test
    @DisplayName("★ INSERT：@AutoFill 填满审计四列")
    void insertShouldFillAllFourAuditColumns() {
        BaseContext.setCurrentId(1L);

        Category category = insertCategory(uniqueName(), 1, 0, 1);

        assertEquals(1L, category.getCreateUser());
        assertEquals(1L, category.getUpdateUser());
        assertNotNull(category.getCreateTime());
        assertEquals(category.getCreateTime(), category.getUpdateTime(),
                "INSERT 时 update_time 应与 create_time 相同");
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：name 模糊 / type 精确 / status 精确 —— 三个条件全可空")
    void pageShouldHandleAllThreeOptionalConditions() {
        String tag = "IT" + Long.toString(System.nanoTime() % 100_000_000L, 36);
        String namePrefix = "分类" + tag;

        Category chargeA = insertCategory(namePrefix + "甲", 1, 1, 1);
        Category chargeB = insertCategory(namePrefix + "乙", 1, 2, 1);
        Category disabled = insertCategory(namePrefix + "丙", 1, 3, 0);
        Category valueAdded = insertCategory(namePrefix + "丁", 2, 4, 1);

        // ---- 1) name 模糊匹配 ----
        CategoryPageQueryDTO q1 = new CategoryPageQueryDTO();
        q1.setName(tag);
        PageHelper.startPage(1, 10);
        List<Category> rows1 = categoryMapper.page(q1);
        assertEquals(4, rows1.size(), "name 是模糊匹配，应该命中全部 4 条");
        // ★ size() 只证明过滤条件对了；getTotal() 才证明 PageHelper 真的接上了
        assertEquals(4, ((Page<Category>) rows1).getTotal());

        // ---- 2) name + type ----
        CategoryPageQueryDTO q2 = new CategoryPageQueryDTO();
        q2.setName(tag);
        q2.setType(1);
        PageHelper.startPage(1, 10);
        assertEquals(3, categoryMapper.page(q2).size());

        // ---- 3) name + type + status ----
        CategoryPageQueryDTO q3 = new CategoryPageQueryDTO();
        q3.setName(tag);
        q3.setType(1);
        q3.setStatus(0);
        PageHelper.startPage(1, 10);
        List<Category> rows3 = categoryMapper.page(q3);
        assertEquals(1, rows3.size());
        assertEquals(disabled.getId(), rows3.get(0).getId());

        // ---- 4) ★ 不传 type = 两个 type 都返回（和 listEnabled 的语义不同） ----
        CategoryPageQueryDTO q4 = new CategoryPageQueryDTO();
        q4.setName(tag);
        PageHelper.startPage(1, 10);
        List<Long> ids4 = categoryMapper.page(q4).stream().map(Category::getId).toList();
        assertTrue(ids4.contains(valueAdded.getId()),
                "★ 管理端不传 type 时应该两个 type 都返回 —— "
                        + "这是「所有分类」视图，和下拉框（永远明确知道要哪个 type）不同");
        assertTrue(ids4.contains(disabled.getId()),
                "★ 管理端不传 status 时应该连停用的也返回 —— "
                        + "它就是来管理停用分类的，看不到就没法启用回来");

        // ---- 5) ORDER BY id DESC ----
        CategoryPageQueryDTO q5 = new CategoryPageQueryDTO();
        q5.setName(tag);
        PageHelper.startPage(1, 10);
        List<Category> rows5 = categoryMapper.page(q5);
        assertEquals(valueAdded.getId(), rows5.get(0).getId(),
                "应该按 id 倒序，最新的排最前");
        assertTrue(rows5.get(0).getId() > rows5.get(1).getId());

        // ---- 6) 分页真的生效，且翻页不重不漏 ----
        PageHelper.startPage(1, 2);
        List<Category> page1 = categoryMapper.page(q5);
        PageHelper.startPage(2, 2);
        List<Category> page2 = categoryMapper.page(q5);

        assertEquals(2, page1.size(), "pageSize=2 应该只返回 2 行 —— LIMIT 生效的证据");
        assertEquals(4, ((Page<Category>) page1).getTotal(), "总数不受 pageSize 影响");
        assertEquals(2, page2.size());

        List<Long> ids1 = page1.stream().map(Category::getId).toList();
        List<Long> ids2 = page2.stream().map(Category::getId).toList();
        assertTrue(ids1.stream().noneMatch(ids2::contains),
                "第 1 页和第 2 页出现了重复记录，说明排序不稳定。page1=" + ids1 + " page2=" + ids2);

        // 参考变量，让上面的 id 都用上了（否则 IDE 会警告「变量未使用」，
        // 而这里的意图是「这几行是本次测试造的」）
        assertNotNull(chargeA.getId());
        assertNotNull(chargeB.getId());
    }

    @Test
    @DisplayName("page：不带任何条件时 LIMIT 和 COUNT 都要生效")
    void pageWithoutFilterShouldStillWork() {
        for (int i = 0; i < 3; i++) {
            insertCategory(uniqueName(), 1, i, 1);
        }

        PageHelper.startPage(1, 2);
        List<Category> rows = categoryMapper.page(new CategoryPageQueryDTO());

        // ★ size() 证明 LIMIT 生效（没有 LIMIT 会返回全表）；
        //   getTotal() 证明 COUNT 查询生效。缺一个都说明不了问题。
        assertEquals(2, rows.size(), "pageSize=2 应该只返回 2 行 —— LIMIT 生效的证据");
        assertTrue(((Page<Category>) rows).getTotal() >= 3,
                "总数应该至少包含本次插入的 3 条 —— COUNT 生效的证据，实际："
                        + ((Page<Category>) rows).getTotal());
    }

    // ==================== 种子数据的前置事实 ====================

    @Test
    @DisplayName("★ 种子分类：id=1 是 type=1（充电套餐分类）—— D13 校验的合法目标")
    void seedCategoryShouldBeChargePackageType() {
        // ★ 这条钉住的是 P1c-1 里一个跨模块的前提：
        //   ChargePackageServiceImpl.requireCategory 要求套餐只能挂在 type=1 下，
        //   而种子套餐的两个 category_id 都指向 1。
        //   如果 seed 里 category 1 被改成了 type=2，
        //   种子数据自己就违反了新加的校验 —— 而表现是
        //   「改一个种子套餐的价格，报「分类类型不匹配」」。
        Category seed = categoryMapper.getById(1L);
        if (seed == null) {
            return;   // 种子没导入时不整类跳过，其它用例不依赖它
        }
        assertEquals(1, seed.getType(),
                "种子分类 1 应该是「充电套餐分类」—— 种子套餐挂在它下面，"
                        + "而 D13 要求 charge_package.category_id 只能指向 type=1");

        // ★ 而种子里的「停车服务」正是 type=2 的样例，
        //   也是冒烟测试里验证 CATEGORY_TYPE_MISMATCH 要用的那个目标。
        Category parking = categoryMapper.getById(3L);
        if (parking != null) {
            assertEquals(2, parking.getType(),
                    "种子分类 3「停车服务」应该是 type=2（增值服务分类）—— "
                            + "冒烟测试用它来验证「套餐挂到 type=2 的分类上会被拒」");
        }
    }
}
