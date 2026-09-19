package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.entity.TariffRule;
import com.chargeops.entity.TariffTemplate;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TariffTemplateMapper} 与 {@link TariffRuleMapper} 的集成测试
 *
 * <h2>为什么两个 Mapper 放在一个测试类里</h2>
 *
 * <p>因为模板头和它的时段规则<b>不是一个可以分开的东西</b>：
 * {@code TariffValidator} 必须一次看到全部 8 行才判断得了 24 小时覆盖，
 * 写入时也是「删旧 + 批量插新」一次完成。
 * 分开测的话，每个类都要自己造一个「另一半」出来 ——
 * 那些造出来的数据反而会变成测试里最容易出错的部分。
 *
 * <h2>★ 本类唯一的、也是最重要的断言：{@code '24:00:00'} 能穿过 {@code <foreach>} 批插</h2>
 *
 * <p>数据库里的种子数据能存下 {@code '24:00:00'}，那是用 <b>SQL 字面量</b>插进去的。
 * 而 {@code batchInsert} 走的是另一条路径：
 * <pre>
 *   Java 的 String "24:00:00" → JDBC 预处理语句参数 → VARCHAR → TIME 列
 * </pre>
 * <b>两条完全不同的代码路径。</b>前者的成功不能证明后者。
 * 而如果 {@code '24:00:00'} 在这一步被拒绝或回绕成 {@code '00:00:00'}，
 * 后果是最后一段（23:00-24:00）变成零长度 ——
 * 表现为「23 点之后充电的订单金额莫名其妙少了」，不会报任何错。
 *
 * <p>所以 {@link #batchInsertShouldPreserve24Hours()} 是必须存在的。
 */
@SpringBootTest
class TariffTemplateMapperTest {

    @Autowired
    private TariffTemplateMapper tariffTemplateMapper;

    @Autowired
    private TariffRuleMapper tariffRuleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdTemplateIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdTemplateIds) {
            jdbcTemplate.update("DELETE FROM tariff_rule WHERE template_id = ?", id);
            jdbcTemplate.update("DELETE FROM tariff_template WHERE id = ?", id);
        }
        createdTemplateIds.clear();
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private String uniqueCode() {
        return "TT-" + System.nanoTime();
    }

    private TariffTemplate insertTemplate(String code) {
        TariffTemplate template = TariffTemplate.builder()
                .code(code)
                .name("测试模板")
                .region("测试市")
                .description("集成测试用")
                .status(1)
                .build();
        tariffTemplateMapper.insert(template);
        assertNotNull(template.getId(), "insert 后 id 应被回填");
        createdTemplateIds.add(template.getId());
        return template;
    }

    private TariffRule rule(Long templateId, int seq, String start, String end,
                            int periodType, String energyPrice, String servicePrice) {
        LocalDateTime now = LocalDateTime.now();
        return TariffRule.builder()
                .templateId(templateId)
                .seq(seq)
                .startTime(start)
                .endTime(end)
                .periodType(periodType)
                .energyPrice(new BigDecimal(energyPrice))
                .servicePrice(new BigDecimal(servicePrice))
                // ★ tariff_rule 没有 create_user/update_user，两个时间由调用方给出 ——
                //   batchInsert 不标 @AutoFill，没人会替你填
                .createTime(now)
                .updateTime(now)
                .build();
    }

    /** 种子里的那 8 行，端到端照抄一遍 */
    private List<TariffRule> shenzhenRules(Long templateId) {
        return List.of(
                rule(templateId, 1, "00:00:00", "08:00:00", 4, "0.3188", "0.3000"),
                rule(templateId, 2, "08:00:00", "10:00:00", 3, "0.7188", "0.3000"),
                rule(templateId, 3, "10:00:00", "12:00:00", 1, "1.2188", "0.4000"),
                rule(templateId, 4, "12:00:00", "14:00:00", 3, "0.7188", "0.3000"),
                rule(templateId, 5, "14:00:00", "19:00:00", 2, "1.0188", "0.3500"),
                rule(templateId, 6, "19:00:00", "21:00:00", 1, "1.2188", "0.4000"),
                rule(templateId, 7, "21:00:00", "23:00:00", 2, "1.0188", "0.3500"),
                rule(templateId, 8, "23:00:00", "24:00:00", 3, "0.7188", "0.3000"));
    }

    // ==================== 模板头 ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填审计四列，create_time 来自 Java 侧")
    void insertTemplateShouldFillAuditColumns() {
        BaseContext.setCurrentId(1L);
        LocalDateTime before = LocalDateTime.now();

        TariffTemplate template = insertTemplate(uniqueCode());

        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, template.getCreateUser());
        assertEquals(1L, template.getUpdateUser());
        assertTrue(template.getCreateTime().isAfter(before.minusSeconds(1))
                        && template.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 应该是 Java 侧的时钟，不是数据库默认值");
        assertEquals(template.getCreateTime(), template.getUpdateTime());
    }

    @Test
    @DisplayName("重复 code 抛 DuplicateKeyException（uk_tariff_template_code）")
    void duplicateCodeShouldThrow() {
        BaseContext.setCurrentId(1L);
        String code = uniqueCode();
        insertTemplate(code);

        assertThrows(DuplicateKeyException.class, () -> insertTemplate(code));
    }

    @Test
    @DisplayName("★★ batchInsert：8 行规则必须原样入库，包括最后一段的 '24:00:00'")
    void batchInsertShouldPreserve24Hours() {
        BaseContext.setCurrentId(1L);
        TariffTemplate template = insertTemplate(uniqueCode());

        tariffRuleMapper.batchInsert(shenzhenRules(template.getId()));

        List<TariffRule> rows = tariffRuleMapper.listByTemplateId(template.getId());
        assertEquals(8, rows.size(), "8 行规则应该全部入库");

        // ---- 1) ★★ 最后一段的 end_time 必须是 "24:00:00" ----
        TariffRule last = rows.get(rows.size() - 1);
        assertEquals("24:00:00", last.getEndTime(),
                "★★ '24:00:00' 没有穿过 batchInsert 的 JDBC 参数绑定。"
                        + "得到 '00:00:00' 的话，23:00-24:00 会变成一个零长度段 —— "
                        + "既不产生切点也不被任何时刻命中，表现为「23 点后充电的订单金额少了」");
        assertEquals("23:00:00", last.getStartTime());

        // ---- 2) ★ 排序：零填充的 "HH:mm:ss" 字典序 = 时间序，'24:00:00' 排最后 ----
        //   如果末段写成 '00:00:00'，它会**排到最前面** ——
        //   这个排序断言就是「末段必须是 24:00:00」的第二道保险
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getStartTime().compareTo(rows.get(i).getStartTime()) < 0,
                    "规则应该按 start_time 升序：" + rows.get(i - 1).getStartTime()
                            + " 应该排在 " + rows.get(i).getStartTime() + " 前面");
        }

        // ---- 3) 时段合计必须正好 24 小时 ----
        //   在 Java 侧把字符串换算回分钟数再累加，不复用 TimeCodec ——
        //   复用被测代码会让这条断言变成「自己证明自己」
        int totalMinutes = 0;
        for (TariffRule r : rows) {
            totalMinutes += toMinute(r.getEndTime()) - toMinute(r.getStartTime());
        }
        assertEquals(1440, totalMinutes, "8 行时段应该合计 24 小时（1440 分钟）");

        // ---- 4) 单价精度：decimal(10,4) ----
        assertEquals(0, new BigDecimal("1.2188").compareTo(rows.get(2).getEnergyPrice()));
        assertEquals(4, rows.get(2).getEnergyPrice().scale());
        assertEquals(0, new BigDecimal("0.4000").compareTo(rows.get(2).getServicePrice()));

        // ---- 5) 主键没有被回填（batchInsert 没有 useGeneratedKeys） ----
        //   ★ 这不是缺陷：tariff_rule.id 不被任何表引用，
        //     所以规则才能被「整体替换」。见 TariffRuleMapper 的类注释第四节。
        //   （注意 listByTemplateId 查出来的行当然有 id —— 这里说的是
        //     batchInsert 传进去的那个 List 里的对象没被回填）
        rows.forEach(r -> assertNotNull(r.getId(), "查出来的规则应该有 id"));
    }

    /** 只给测试用的小换算，"HH:mm:ss" → 当日分钟数 */
    private static int toMinute(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    @Test
    @DisplayName("★★ 空列表的 batchInsert 必须响亮地失败，不能静默什么都不插")
    void batchInsertWithEmptyListShouldFail() {
        BaseContext.setCurrentId(1L);
        TariffTemplate template = insertTemplate(uniqueCode());

        // ★ <foreach> 展开成 `INSERT INTO ... VALUES` 后面没有值 —— SQL 语法错误。
        //   断言的是【它会抛】这个事实，而不是某个具体异常类型：
        //   异常类型是 MyBatis 的 SQL 构建 + Spring 的异常翻译两层的结果，
        //   换个 MyBatis 版本可能就变了；而「拒绝执行」这个语义是稳定的。
        //
        //   为什么值得专门测：另一种实现是「空列表就跳过、什么都不做」，
        //   那会让「保存一个没有任何规则的模板」静默成功 ——
        //   然后这个模板的每个订单都算不出钱。
        assertThrows(DataAccessException.class,
                () -> tariffRuleMapper.batchInsert(List.of()),
                "空列表应该被拒绝，而不是静默什么都不插");

        assertTrue(tariffRuleMapper.listByTemplateId(template.getId()).isEmpty());
    }

    @Test
    @DisplayName("★ batchInsert 是整体替换：deleteByTemplateId + batchInsert 之后行数正好是新的行数")
    void replaceRulesShouldNotLeaveOldRows() {
        BaseContext.setCurrentId(1L);
        TariffTemplate template = insertTemplate(uniqueCode());

        tariffRuleMapper.batchInsert(shenzhenRules(template.getId()));
        assertEquals(8, tariffRuleMapper.listByTemplateId(template.getId()).size());

        // 换成一套只有 2 行的规则（不覆盖 24 小时 —— Mapper 层不管这个，
        // 那是 TariffValidator 的事，这里只验「旧行被清干净了」）
        assertEquals(8, tariffRuleMapper.deleteByTemplateId(template.getId()));

        tariffRuleMapper.batchInsert(List.of(
                rule(template.getId(), 1, "00:00:00", "12:00:00", 3, "0.5000", "0.3000"),
                rule(template.getId(), 2, "12:00:00", "24:00:00", 2, "0.8000", "0.3000")));

        List<TariffRule> rows = tariffRuleMapper.listByTemplateId(template.getId());
        assertEquals(2, rows.size(),
                "★ 应该只剩 2 行。剩下 10 行说明 deleteByTemplateId 没生效，"
                        + "或者是「只插不删」——那样两套规则会同时存在，"
                        + "同一个时刻命中两条规则，而结算时用的是哪一条完全看运气");
        assertEquals("00:00:00", rows.get(0).getStartTime());
        assertEquals("12:00:00", rows.get(1).getStartTime());

        // ★ 删一个【已经没有规则】的模板不该报错，返回 0 就行 ——
        //   「整体替换」时第一次保存就是这种情况（还没有旧行可以删）
        assertEquals(2, tariffRuleMapper.deleteByTemplateId(template.getId()));
        assertEquals(0, tariffRuleMapper.deleteByTemplateId(template.getId()),
                "再删一次应该返回 0 行，而不是报错");
    }

    @Test
    @DisplayName("uk(template_id, start_time)：同一模板内起点不能重复，不同模板可以")
    void startTimeShouldBeUniqueWithinTemplateOnly() {
        BaseContext.setCurrentId(1L);
        TariffTemplate t1 = insertTemplate(uniqueCode());
        TariffTemplate t2 = insertTemplate(uniqueCode());

        tariffRuleMapper.batchInsert(List.of(
                rule(t1.getId(), 1, "00:00:00", "12:00:00", 3, "0.5000", "0.3000")));

        // ★ 同一模板内再来一条起点相同的 → 失败
        assertThrows(DuplicateKeyException.class,
                () -> tariffRuleMapper.batchInsert(List.of(
                        rule(t1.getId(), 2, "00:00:00", "06:00:00", 4, "0.3000", "0.3000"))),
                "同一模板内 start_time 不能重复（uk_tariff_rule_tpl_start）");

        // ★ 另一个模板用同样的起点 → 可以
        assertNotNull(t2.getId());
        tariffRuleMapper.batchInsert(List.of(
                rule(t2.getId(), 1, "00:00:00", "12:00:00", 3, "0.5000", "0.3000")));
        assertEquals(1, tariffRuleMapper.listByTemplateId(t2.getId()).size());
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("★ 删除模板不级联删规则：孤儿规则是刻意的（级联会顺手抹掉计价数据）")
    void deleteTemplateShouldNotCascadeToRules() {
        BaseContext.setCurrentId(1L);
        TariffTemplate template = insertTemplate(uniqueCode());
        tariffRuleMapper.batchInsert(shenzhenRules(template.getId()));

        assertEquals(1, tariffTemplateMapper.deleteById(template.getId()));
        createdTemplateIds.remove(template.getId());

        // ★ 规则还在，只是 template_id 指向了一个不存在的模板。
        //   和 ChargerMapperTest 里「删桩不级联删枪」是同一个判断：
        //   数据库不替你做业务决策，守卫放在 Service 层。
        //   在这里，级联的后果更具体 —— 「删掉一个模板」会在后台
        //   顺手抹掉 8 行计价数据，而孤儿规则至少是可查、可人工清理的。
        assertEquals(8, tariffRuleMapper.listByTemplateId(template.getId()).size(),
                "规则不应该被级联删除。要清就显式调 deleteByTemplateId");
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：code 前缀匹配、region 精确匹配、status 过滤")
    void pageShouldFilterCorrectly() {
        BaseContext.setCurrentId(1L);
        String tag = "T" + Long.toString(System.nanoTime() % 10_000_000L, 36);

        // ★ region 用唯一值，这样精确匹配的断言不受种子数据影响
        String region = "测试地区" + tag;
        TariffTemplate theA = insertTemplateWith(tag + "-A", region, 1);
        insertTemplateWith(tag + "-B", region, 1);
        TariffTemplate disabled = insertTemplateWith(tag + "-C", region, 0);

        // ---- 1) region 精确匹配 ----
        TariffTemplatePageQueryDTO q1 = new TariffTemplatePageQueryDTO();
        q1.setRegion(region);
        PageHelper.startPage(1, 10);
        List<TariffTemplate> rows1 = tariffTemplateMapper.page(q1);
        assertEquals(3, rows1.size());
        assertEquals(3, ((Page<TariffTemplate>) rows1).getTotal());

        // ★★ region 必须是【精确】匹配，不是 LIKE。
        //   它和 status 组成 idx_tariff_template_region_status (region, status)，
        //   写成 LIKE 会让这个联合索引整个失效 ——
        //   而「哪些模板能用在这个地区」正是最主要的查询路径。
        //
        //   用一个「是 region 子串、但不是任何 region」的值去查，
        //   就能区分精确匹配和包含匹配：
        TariffTemplatePageQueryDTO q2 = new TariffTemplatePageQueryDTO();
        q2.setRegion(region.substring(0, 2));   // 只是前缀，不是完整的地区名
        PageHelper.startPage(1, 10);
        assertEquals(0, tariffTemplateMapper.page(q2).size(),
                "region 应该是精确匹配。这里查到了数据说明实现变成了 LIKE，"
                        + "那会让 idx_tariff_template_region_status 失效");

        // ---- 2) region + status 组合 ----
        TariffTemplatePageQueryDTO q3 = new TariffTemplatePageQueryDTO();
        q3.setRegion(region);
        q3.setStatus(1);
        PageHelper.startPage(1, 10);
        assertEquals(2, tariffTemplateMapper.page(q3).size());

        TariffTemplatePageQueryDTO q4 = new TariffTemplatePageQueryDTO();
        q4.setRegion(region);
        q4.setStatus(0);
        PageHelper.startPage(1, 10);
        List<TariffTemplate> rows4 = tariffTemplateMapper.page(q4);
        assertEquals(1, rows4.size());
        assertEquals(disabled.getId(), rows4.get(0).getId());

        // ---- 3) ★ code 是【前缀】匹配 ----
        TariffTemplatePageQueryDTO q5 = new TariffTemplatePageQueryDTO();
        q5.setCode("TT-");            // uniqueCode() 生成的都是 TT- 开头
        PageHelper.startPage(1, 100);
        assertTrue(tariffTemplateMapper.page(q5).size() >= 3,
                "以前缀查应该命中本次建的模板");

        // ---- 4) code 前缀 + region 组合。-A 那条的编码是 "TT-<nano>-<tag>-A"，
        //        前缀匹配是从左往右的，所以要拿它的完整编码当前缀才命中 ——
        //        （第一次写这条断言时用的是 "<tag>-A"，那一段在编码中间而不在开头，
        //          于是查到 0 条。是断言写错了，不是实现错了。）
        TariffTemplatePageQueryDTO q6 = new TariffTemplatePageQueryDTO();
        q6.setCode(theA.getCode());
        q6.setRegion(region);
        PageHelper.startPage(1, 10);
        List<TariffTemplate> rows6 = tariffTemplateMapper.page(q6);
        assertEquals(1, rows6.size());
        assertEquals(theA.getId(), rows6.get(0).getId());

        // ★ 而用编码【中间】那一段去查应该是 0 条 —— 这条才是真正区分
        //   「前缀匹配」和「包含匹配」的断言
        TariffTemplatePageQueryDTO q7 = new TariffTemplatePageQueryDTO();
        q7.setCode(tag + "-A");
        q7.setRegion(region);
        PageHelper.startPage(1, 10);
        assertEquals(0, tariffTemplateMapper.page(q7).size(),
                "code 应该是前缀匹配。这里查到数据说明实现变成了 LIKE '%x%'");
    }

    private TariffTemplate insertTemplateWith(String codeSuffix, String region, int status) {
        TariffTemplate template = TariffTemplate.builder()
                .code(uniqueCode() + "-" + codeSuffix)
                .name("模板 " + codeSuffix)
                .region(region)
                .description("page 测试")
                .status(status)
                .build();
        tariffTemplateMapper.insert(template);
        createdTemplateIds.add(template.getId());
        return template;
    }

    // ==================== listAllEnabled ====================

    @Test
    @DisplayName("listAllEnabled 只返回启用中的模板（停用的不出现在「新建绑定」的下拉框里）")
    void listAllEnabledShouldExcludeDisabled() {
        BaseContext.setCurrentId(1L);
        String tag = "L" + Long.toString(System.nanoTime() % 10_000_000L, 36);

        TariffTemplate enabled = insertTemplateWith(tag + "-ON", "下拉框测试", 1);
        TariffTemplate off = insertTemplateWith(tag + "-OFF", "下拉框测试", 0);

        List<TariffTemplate> all = tariffTemplateMapper.listAllEnabled();

        assertTrue(all.stream().anyMatch(t -> t.getId().equals(enabled.getId())),
                "启用的模板应该出现在下拉框里");
        assertTrue(all.stream().noneMatch(t -> t.getId().equals(off.getId())),
                "★ 停用的模板不该出现在「新建绑定」的选项里。"
                        + "注意这不影响已经绑定它的站点 —— 那些绑定有自己的行");

        // ★ 不分页：这是一个下拉框的数据源
        assertTrue(all.size() >= 2, "应该返回全部启用中的模板，不受分页影响");
    }

    // ==================== listByIds ====================

    @Test
    @DisplayName("listByIds 按 id 批量取名，★ 且【不过滤 status】—— 这正是它和 listAllEnabled 的分界线")
    void listByIdsShouldNotFilterStatus() {
        BaseContext.setCurrentId(1L);
        String tag = "B" + Long.toString(System.nanoTime() % 10_000_000L, 36);

        TariffTemplate enabled = insertTemplateWith(tag + "-ON", "批量取名测试", 1);
        TariffTemplate off = insertTemplateWith(tag + "-OFF", "批量取名测试", 0);

        List<TariffTemplate> rows = tariffTemplateMapper.listByIds(
                List.of(enabled.getId(), off.getId()));

        assertEquals(2, rows.size(), "两个 id 都应该查出来");

        // ★★ 这条断言是本测试存在的理由。
        //    停用的模板【必须】被返回 —— 这个方法服务的是「给已经存在的绑定补名字」，
        //    而模板后来停用了不改变「那条绑定指向它」这个事实。
        //    要是哪天有人「顺手」给它加上 status = 1，这里的断言会立刻失败。
        assertTrue(rows.stream().anyMatch(t -> t.getId().equals(off.getId())),
                "★ listByIds 不能过滤 status。服务的是已有绑定补名字，"
                        + "停用的模板被过滤掉只会让列表里多出一个空白的名字");

        // 和 listAllEnabled 的行为正好相反 —— 两条 SQL 的用途不同，不是冗余
        assertTrue(tariffTemplateMapper.listAllEnabled().stream()
                        .noneMatch(t -> t.getId().equals(off.getId())),
                "而 listAllEnabled 必须过滤掉它（那条 SQL 服务的是新建绑定的下拉框）");

        // ★ 顺带验证 name / code 都带回来了：补名字靠的就是这两列，
        //   只补回来一个 id 的话调用方还得再查一次
        TariffTemplate reloaded = rows.stream()
                .filter(t -> t.getId().equals(off.getId()))
                .findFirst().orElseThrow();
        assertNotNull(reloaded.getName(), "name 要带回来，否则补名字这件事没完成");
        assertNotNull(reloaded.getCode(), "code 要带回来 —— 名称允许重名，编码才是区分依据");
    }

    @Test
    @DisplayName("listByIds 遇到不存在的 id 只是少一行，不报错")
    void listByIdsShouldTolerateMissingIds() {
        BaseContext.setCurrentId(1L);
        String tag = "M" + Long.toString(System.nanoTime() % 10_000_000L, 36);

        TariffTemplate exists = insertTemplateWith(tag + "-A", "缺 id 测试", 1);

        // 一个真实存在的 + 一个绝不存在的（Long.MAX_VALUE 不可能被自增用到）
        List<TariffTemplate> rows = tariffTemplateMapper.listByIds(
                List.of(exists.getId(), Long.MAX_VALUE));

        assertEquals(1, rows.size(),
                "不存在的 id 静默缺席即可 —— SQL 的 IN 语义本来就是这样，"
                        + "调用方拿它做 id → 名字的映射，查不到就是补不上名");
        assertEquals(exists.getId(), rows.get(0).getId());
    }
}
