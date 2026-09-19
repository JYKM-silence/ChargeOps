package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.Station;
import com.chargeops.entity.StationTariff;
import com.chargeops.entity.TariffTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StationTariffMapper} 的集成测试
 *
 * <h2>★★ 本类的核心是 {@code resolveTemplateAt}</h2>
 *
 * <p>它是 <b>P1c 下单时冻结模板要调用的那个方法</b>，也是整张
 * {@code station_tariff} 表的唯一出口。它答错一次的后果不是报错，
 * 而是<b>一笔订单用错了电价</b> —— 钱算出来了，只是算错了，
 * 而且没有任何迹象提示你。
 *
 * <p>所以下面把一个时间轴上的各种情形分开断言：
 * 边界前一刻、边界当刻、优先级覆盖、同优先级撞车、停用、完全没有绑定。
 *
 * <h2>★★ 二、半开区间 {@code [effective_from, effective_to)} 是这里最要紧的约定</h2>
 *
 * <p>见 {@link #handoverInstantShouldMatchExactlyOneBinding()} ——
 * 那一条断言守的是「恰好衔接的两个绑定在交接时刻只命中一个」。
 * 写成闭区间的话会同时命中两个，而这时结果由 {@code priority} 决定，
 * 默认优先级又都是 0 —— 于是那一天的整个电价变成不可预测的东西。
 */
@SpringBootTest
class StationTariffMapperTest {

    @Autowired
    private StationTariffMapper stationTariffMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private TariffTemplateMapper tariffTemplateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdStationId;
    private Long createdTemplateId1;
    private Long createdTemplateId2;

    /** 时间轴的起点，用固定的日期而不是 now() —— 断言才有确定的期望值 */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    @AfterEach
    void cleanUp() {
        if (createdStationId != null) {
            jdbcTemplate.update("DELETE FROM station_tariff WHERE station_id = ?", createdStationId);
            jdbcTemplate.update("DELETE FROM station WHERE id = ?", createdStationId);
            createdStationId = null;
        }
        for (Long id : new Long[]{createdTemplateId1, createdTemplateId2}) {
            if (id != null) {
                jdbcTemplate.update("DELETE FROM tariff_rule WHERE template_id = ?", id);
                jdbcTemplate.update("DELETE FROM tariff_template WHERE id = ?", id);
            }
        }
        createdTemplateId1 = null;
        createdTemplateId2 = null;
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private Station insertStation() {
        Station station = Station.builder()
                .code("ST-TAR-" + System.nanoTime())
                .name("绑定测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);
        createdStationId = station.getId();
        return station;
    }

    private Long insertTemplate() {
        TariffTemplate template = TariffTemplate.builder()
                .code("TT-TAR-" + System.nanoTime())
                .name("绑定测试模板")
                .region("测试市")
                .status(1)
                .build();
        tariffTemplateMapper.insert(template);
        if (createdTemplateId1 == null) {
            createdTemplateId1 = template.getId();
        } else {
            createdTemplateId2 = template.getId();
        }
        return template.getId();
    }

    /** 建一条绑定并返回它。{@code effectiveTo} 传 null 表示长期有效 */
    private StationTariff bind(Long stationId, Long templateId,
                               LocalDateTime from, LocalDateTime to, int priority) {
        StationTariff binding = StationTariff.builder()
                .stationId(stationId)
                .templateId(templateId)
                .effectiveFrom(from)
                .effectiveTo(to)
                .priority(priority)
                .status(1)
                .build();
        stationTariffMapper.insert(binding);
        assertNotNull(binding.getId(), "insert 后 id 应被回填");
        return binding;
    }

    /** 解析并返回命中的模板 id；没有任何绑定时返回 null */
    private Long resolveAt(Long stationId, LocalDateTime at) {
        StationTariff hit = stationTariffMapper.resolveTemplateAt(stationId, at);
        return hit == null ? null : hit.getTemplateId();
    }

    // ==================== INSERT / @AutoFill ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填审计四列（本表四列齐全，和 connector/tariff_rule 不同）")
    void insertShouldFillAuditColumns() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        LocalDateTime before = LocalDateTime.now();
        StationTariff binding = bind(station.getId(), templateId, T0, null, 0);
        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, binding.getCreateUser());
        assertEquals(1L, binding.getUpdateUser());
        assertTrue(binding.getCreateTime().isAfter(before.minusSeconds(1))
                        && binding.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 应该是 Java 侧的时钟");
        assertEquals(binding.getCreateTime(), binding.getUpdateTime());
    }

    @Test
    @DisplayName("uk_station_tariff_start：同站点同生效时刻不能有两条绑定")
    void sameStationAndStartShouldBeRejected() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long t1 = insertTemplate();
        Long t2 = insertTemplate();

        bind(station.getId(), t1, T0, null, 0);

        // ★ 同一起点开两条 → 拒绝。业务上是对的：
        //   它们的先后关系没有任何字段能表达。
        //   调价用的是「另一条不同 effectiveFrom 的绑定」，不是同起点的两条。
        assertThrows(DuplicateKeyException.class,
                () -> bind(station.getId(), t2, T0, null, 0),
                "同站点同 effective_from 应该撞 uk_station_tariff_start");

        // 换个起点就可以了
        assertNotNull(bind(station.getId(), t2, T0.plusDays(1), null, 0).getId());
    }

    // ==================== ★★ resolveTemplateAt ====================

    @Test
    @DisplayName("★★ 交接时刻：恰好衔接的两个绑定【只命中一个】，不靠 priority 裁决")
    void handoverInstantShouldMatchExactlyOneBinding() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long oldTemplate = insertTemplate();
        Long newTemplate = insertTemplate();

        LocalDateTime handover = LocalDateTime.of(2026, 7, 1, 0, 0);

        // 老绑定：1/1 起，到 7/1 止（不含）。优先级【故意给高】，用来证明
        // 「交接时刻只命中一个」不是靠优先级凑出来的
        bind(station.getId(), oldTemplate, T0, handover, 99);
        // 新绑定：7/1 起（含），长期有效。优先级 0
        bind(station.getId(), newTemplate, handover, null, 0);

        // ---- 1) 交接前一天 / 前一秒：老绑定 ----
        assertEquals(oldTemplate, resolveAt(station.getId(), handover.minusDays(1)));
        assertEquals(oldTemplate, resolveAt(station.getId(), handover.minusSeconds(1)),
                "7/1 00:00:00 的前一秒仍然属于老绑定");

        // ---- 2) ★★ 交接当刻：必须是新绑定 ----
        //   老绑定在 effective_to = 7/1 00:00:00 上，半开区间下
        //   「到 7/1 00:00:00 为止」意味着 7/1 00:00:00 这个时刻**不含**。
        //   新绑定的 effective_from 就是 7/1 00:00:00，是**含**的。
        //   所以恰好一个命中 —— 不需要 priority 介入。
        assertEquals(newTemplate, resolveAt(station.getId(), handover),
                "★★ 交接当刻应该命中【新】绑定。"
                        + "如果这里拿到老模板，说明 WHERE 里的 effective_to > ? 被写成了 >= —— "
                        + "那会让两个绑定同时命中，结果由 priority 裁决，"
                        + "而默认优先级都是 0，于是这一天的电价变成一个不可预测的值");

        // ---- 3) 交接之后：新绑定 ----
        assertEquals(newTemplate, resolveAt(station.getId(), handover.plusSeconds(1)));
        assertEquals(newTemplate, resolveAt(station.getId(), handover.plusYears(5)),
                "新绑定的 effective_to 是 NULL，表示长期有效");
    }

    @Test
    @DisplayName("★ effective_to 为 NULL（长期有效）必须能被匹配到，不能被静默排除")
    void nullEffectiveToShouldMeanLongTerm() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        bind(station.getId(), templateId, T0, null, 0);

        // ★★ 这一条守的是 SQL 里那个看起来多余的 OR 分支：
        //     WHERE ... AND (effective_to IS NULL OR effective_to > ?)
        //   在 SQL 的三值逻辑里，`NULL > '2027-01-01'` 的结果是 NULL 而不是 true，
        //   所以少了 IS NULL 那一半，**所有长期有效的绑定都会被排除**。
        //   症状：种子里明明有一条长期绑定，解析结果却是 null。
        //   而 null 在 P1c 里意味着「这个站点不能充电」——
        //   一个配置完整的站点会突然拒绝服务。
        assertEquals(templateId, resolveAt(station.getId(), T0.plusYears(3)),
                "长期有效的绑定（effective_to IS NULL）必须能匹配到远期时刻");
    }

    @Test
    @DisplayName("★ 完全没有绑定时返回 null —— P1c 必须当作「不能充电」处理，不能回退默认价")
    void noBindingShouldReturnNull() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        // 绑定从 T0 才开始，那么 T0 之前就是「没有任何生效绑定」
        bind(station.getId(), templateId, T0, null, 0);

        assertNull(stationTariffMapper.resolveTemplateAt(station.getId(), T0.minusDays(1)),
                "★ 这时必须返回 null，而不能回退到某个默认价格 —— "
                        + "「配置缺失」应该是一个立刻可见的失败，"
                        + "不是一笔算错了但看起来正常的账单");

        // ★ 顺带确认：查一个不存在的 stationId 也是 null，而不是抛异常。
        //   这条的价值在 P1c：订单表里的 station_id 若指向一个已被删除的站点，
        //   解析会安静地返回 null —— 于是「站点被删了」和「站点没配模板」
        //   在调用方看来是同一件事，都需要一句明确的「该站点未配置计费模板」。
        assertNull(resolveAt(999_999_999L, LocalDateTime.now()));
    }

    @Test
    @DisplayName("★ status=0 的绑定不参与解析（停用不影响已经冻结了模板的订单）")
    void disabledBindingShouldNotResolve() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        StationTariff binding = bind(station.getId(), templateId, T0, null, 0);
        assertEquals(templateId, resolveAt(station.getId(), T0.plusDays(1)));

        // 停用
        stationTariffMapper.update(StationTariff.builder()
                .id(binding.getId())
                .status(0)
                .build());

        assertNull(resolveAt(station.getId(), T0.plusDays(1)),
                "停用的绑定不该被解析到");

        // ★ listByStationId 仍然要看得到它 —— 停用不是删除，
        //   「这个站点以前用过什么模板」必须还能回答
        List<StationTariff> all = stationTariffMapper.listByStationId(station.getId());
        assertEquals(1, all.size(), "listByStationId 不该过滤 status");
        assertEquals(0, all.get(0).getStatus());
    }

    @Test
    @DisplayName("★★ priority DESC 大者胜：临时促销绑定能盖住长期绑定")
    void higherPriorityShouldWin() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long baseTemplate = insertTemplate();
        Long promoTemplate = insertTemplate();

        LocalDateTime promoFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime promoTo = LocalDateTime.of(2026, 9, 1, 0, 0);

        // 一条长期有效的底价
        bind(station.getId(), baseTemplate, T0, null, 0);
        // 一条高优先级的促销期覆盖
        bind(station.getId(), promoTemplate, promoFrom, promoTo, 10);

        // 促销期外：底线
        assertEquals(baseTemplate, resolveAt(station.getId(), promoFrom.minusDays(1)));
        assertEquals(baseTemplate, resolveAt(station.getId(), promoTo));

        // 促销期内：两条都命中，priority 大的赢
        assertEquals(promoTemplate, resolveAt(station.getId(), promoFrom),
                "促销绑定从 promoFrom 起生效");
        assertEquals(promoTemplate, resolveAt(station.getId(), promoFrom.plusDays(15)));

        // ★ 促销期是半开区间：promoTo 当刻已经不属于促销
        assertEquals(baseTemplate, resolveAt(station.getId(), promoTo),
                "promoTo 当刻促销已经结束（半开区间），应该回到长期绑定");
    }

    @Test
    @DisplayName("★★ 同优先级的两条重叠绑定：结果必须是【确定】的，不能随机")
    void equalPriorityOverlapShouldBeDeterministic() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long t1 = insertTemplate();
        Long t2 = insertTemplate();

        LocalDateTime later = LocalDateTime.of(2026, 10, 15, 0, 0);

        // 两条长期绑定，起点不同（满足唯一键），priority 都是 0
        StationTariff earlierBinding = bind(station.getId(), t1, T0, null, 0);
        bind(station.getId(), t2, later, null, 0);

        // ---- 1) 后者加入之前：前者命中 ----
        //      （先断言一次，确认后面换成 t2 确实是「加入新绑定」导致的）
        assertEquals(t1, resolveAt(station.getId(), T0.plusDays(1)));

        // ---- 2) ★★ later 之后两条都命中且优先级相同 ----
        //   SQL 里的 ORDER BY priority DESC, effective_from DESC, id DESC
        //   让「后建立的绑定赢」。
        //
        //   ★ 关键不在于「谁赢」，而在于**每次都赢的是同一个**。
        //     如果只写 ORDER BY priority DESC，那么 `LIMIT 1` 取哪一行
        //     是未定义的 —— 同一个请求发起两次可能结算出两个不同的价格。
        //     这条断言把「确定」固定下来。
        Long winner = resolveAt(station.getId(), later.plusDays(1));
        assertEquals(t2, winner, "同优先级时后建立的绑定应该赢（effective_from DESC）");

        // ---- 3) 反复解析多次，结果必须一致 ----
        for (int i = 0; i < 5; i++) {
            assertEquals(winner, resolveAt(station.getId(), later.plusDays(1)),
                    "第 " + i + " 次解析得到了不同的结果 —— "
                            + "排序不稳定，同一笔订单的两次请求会算出两个价格");
        }

        // ---- 4) 把后一条删掉，前一条应该重新被解析到 ----
        //   这一步证明 tiebreak 是在两条都命中时真的做了选择，
        //   而不是「后一条把前一条覆盖掉了」
        assertEquals(1, stationTariffMapper.deleteById(
                stationTariffMapper.listByStationId(station.getId()).get(0).getId()));
        assertEquals(t1, resolveAt(station.getId(), later.plusDays(1)),
                "删掉后建立的绑定之后，先建立那条应该重新生效");
        assertNotNull(earlierBinding.getId());
    }

    // ==================== listByStationId ====================

    @Test
    @DisplayName("listByStationId：按生效时间倒序，且不过滤 status")
    void listByStationIdShouldReturnAllOrderedByEffectiveFromDesc() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long t1 = insertTemplate();
        Long t2 = insertTemplate();

        bind(station.getId(), t1, T0, LocalDateTime.of(2026, 6, 1, 0, 0), 0);
        bind(station.getId(), t2, LocalDateTime.of(2026, 6, 1, 0, 0), null, 0);
        StationTariff disabled = bind(station.getId(), t1, LocalDateTime.of(2026, 12, 1, 0, 0), null, 0);
        stationTariffMapper.update(StationTariff.builder().id(disabled.getId()).status(0).build());

        List<StationTariff> rows = stationTariffMapper.listByStationId(station.getId());

        assertEquals(3, rows.size(), "★ 停用的绑定也要列出来");
        // 最近生效的排最前
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), rows.get(0).getEffectiveFrom());
        assertEquals(LocalDateTime.of(2026, 6, 1, 0, 0), rows.get(1).getEffectiveFrom());
        assertEquals(T0, rows.get(2).getEffectiveFrom());

        // 别的站点的不该混进来
        Station other = Station.builder()
                .code("ST-OTHER-" + System.nanoTime())
                .name("另一个站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(other);
        bind(other.getId(), t1, T0, null, 0);

        assertEquals(3, stationTariffMapper.listByStationId(station.getId()).size(),
                "★ 如果 SQL 漏了 WHERE station_id，这里会变成 4");

        jdbcTemplate.update("DELETE FROM station_tariff WHERE station_id = ?", other.getId());
        jdbcTemplate.update("DELETE FROM station WHERE id = ?", other.getId());
    }

    // ==================== 计数（模板不可变性的执行者） ====================

    @Test
    @DisplayName("★★ countByTemplateId：它就是「模板规则不可变」这条保证的执行者")
    void countByTemplateIdShouldGuardTemplateImmutability() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        // ★ 用一个刚建的模板，计数才是确定的（种子里的模板 1 已经被绑定了）
        assertEquals(0, stationTariffMapper.countByTemplateId(templateId),
                "刚建的模板还没被任何站点绑定 —— 这时它的规则可以随便改");

        StationTariff binding = bind(station.getId(), templateId, T0, null, 0);

        assertEquals(1, stationTariffMapper.countByTemplateId(templateId),
                "★★ 一旦 > 0，TariffTemplateService.saveRules 就必须拒绝修改规则。"
                        + "没有这个守卫，station_tariff 存在的意义就是假的："
                        + "订单冻结了模板 id，而那个 id 指向的规则已经被改过了");

        // ---- ★ 停用绑定【不会】让计数归零 ----
        stationTariffMapper.update(StationTariff.builder().id(binding.getId()).status(0).build());
        assertEquals(1, stationTariffMapper.countByTemplateId(templateId),
                "★ 停用的绑定同样说明这个模板被用过，可能还有在途订单引用着它 —— "
                        + "所以计数不该过滤 status");

        // 删掉之后才归零
        stationTariffMapper.deleteById(binding.getId());
        assertEquals(0, stationTariffMapper.countByTemplateId(templateId),
                "绑定删掉之后规则又可以改了（这个模板没有被任何订单引用过）");
    }

    @Test
    @DisplayName("countByStationId：站点删除守卫读的是它")
    void countByStationIdShouldCountBindings() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long t1 = insertTemplate();
        Long t2 = insertTemplate();

        assertEquals(0, stationTariffMapper.countByStationId(station.getId()));

        bind(station.getId(), t1, T0, null, 0);
        bind(station.getId(), t2, LocalDateTime.of(2026, 6, 1, 0, 0), null, 0);

        assertEquals(2, stationTariffMapper.countByStationId(station.getId()));
    }

    // ==================== UPDATE ====================

    @Test
    @DisplayName("★ update：能改 templateId/priority/status，但【没法把 effectiveTo 改回 NULL】")
    void updateShouldBehaveAsDocumented() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long t1 = insertTemplate();
        Long t2 = insertTemplate();

        LocalDateTime until = LocalDateTime.of(2026, 6, 1, 0, 0);
        StationTariff binding = bind(station.getId(), t1, T0, until, 0);

        StationTariff afterInsert = stationTariffMapper.getById(binding.getId());
        LocalDateTime originalCreateTime = afterInsert.getCreateTime();

        BaseContext.setCurrentId(2L);
        // ★ 改 templateId 是安全的 —— 在途订单已经冻结了自己的模板，
        //   改绑定只影响之后启动的订单。这正是整张表要买的保险。
        stationTariffMapper.update(StationTariff.builder()
                .id(binding.getId())
                .templateId(t2)
                .priority(5)
                .build());

        StationTariff reloaded = stationTariffMapper.getById(binding.getId());
        assertEquals(t2, reloaded.getTemplateId());
        assertEquals(5, reloaded.getPriority());
        assertEquals(2L, reloaded.getUpdateUser(), "update_user 应该是 id=2");
        assertEquals(originalCreateTime, reloaded.getCreateTime(),
                "create_time 被 UPDATE 改掉了");

        // ★★ 已知边界：effectiveTo 是 NOT NULL 的列但它可以为 NULL（长期有效），
        //    而 <if test="effectiveTo != null"> 的写法意味着传 null 会被【跳过】——
        //    所以「改成长期有效」这个操作做不到。
        //    把行为固定下来，让哪天改成全行更新时这条测试会失败。
        assertEquals(until, reloaded.getEffectiveTo(),
                "传 null 的 effectiveTo 被跳过了 —— 这是局部更新写法的固有边界，"
                        + "不能把一条有期限的绑定改回「长期有效」，只能删掉重建");

        // 反方向（NULL → 某个时间）是可以的
        StationTariff longTerm = bind(station.getId(), t1, T0.plusYears(1), null, 0);
        assertNull(stationTariffMapper.getById(longTerm.getId()).getEffectiveTo());
        stationTariffMapper.update(StationTariff.builder()
                .id(longTerm.getId())
                .effectiveTo(LocalDateTime.of(2030, 1, 1, 0, 0))
                .build());
        assertEquals(LocalDateTime.of(2030, 1, 1, 0, 0),
                stationTariffMapper.getById(longTerm.getId()).getEffectiveTo());
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("deleteById：解绑可以放心删（不影响任何已算出来的钱），删完起点可以重新使用")
    void deleteShouldFreeTheStartInstant() {
        BaseContext.setCurrentId(1L);
        Station station = insertStation();
        Long templateId = insertTemplate();

        StationTariff binding = bind(station.getId(), templateId, T0, null, 0);
        assertThrows(DuplicateKeyException.class,
                () -> bind(station.getId(), templateId, T0, null, 0));

        assertEquals(1, stationTariffMapper.deleteById(binding.getId()));

        // ★ 起点空出来了。绑定的存在与否不影响任何一笔已经算出来的钱 ——
        //   在途订单冻结的是模板 id，不是绑定 id —— 所以解绑不需要额外守卫。
        assertNotNull(bind(station.getId(), templateId, T0, null, 0).getId(),
                "删掉之后同一个生效时刻应该可以重新绑定");

        assertEquals(0, stationTariffMapper.deleteById(binding.getId()), "重复删除返回 0 行");
    }
}
