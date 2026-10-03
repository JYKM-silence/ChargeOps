package com.chargeops.ai;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.Charger;
import com.chargeops.entity.MaintenanceTicket;
import com.chargeops.entity.Station;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.MaintenanceTicketMapper;
import com.chargeops.mapper.StationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code OpsTools} 的集成测试。
 *
 * <p> 这些方法的调用方是模型，不是人写的代码：参数是它从自然语言里猜的，id 可能是编的。
 * 所以本类测的不是「正常路径返回什么」，而是<b>坏输入下的行为</b> ——
 * 每个方法都不许抛异常、不许写脏数据，要返回一句模型看得懂的说明。
 *
 * <p> 断言用 {@code contains} 而不是等值：这些文案是给模型读的，不是接口契约，
 * 改措辞不该让测试变红。要钉的是「有没有说清是哪一种失败」。
 *
 * <p> 这些用例一条都不需要 Ollama —— 工具是普通 Spring bean，和模型无关。
 * 真正需要模型的是 {@code AiOpsServiceImpl} 那一层。
 */
@SpringBootTest
class OpsToolsTest {

    /** 11e12 这一段是本类独占的：0~9e12 已被别的测试类占满 */
    private static final long TEST_ID_BASE = 11_000_000_000_000L;

    private static final String STATION_CODE_PREFIX = "ST-AI-";
    private static final String DEVICE_NO_PREFIX = "DEV-AI-";

    /** 只给夹具造的工单用。{@code OpsTools} 建的单认不出前缀，见 {@link #OWN_TICKETS} */
    private static final String TICKET_NO_PREFIX = "MT-AI-";

    /** 告警码前缀。不用 V5 种子里的 E-021 那些：本类要断言「只召回同码工单」，混进种子行会让断言失去意义 */
    private static final String ALARM_CODE_PREFIX = "E-99";

    /**
     * 本类造出来的工单，供清理和计数用。
     *
     * <p> 不能用工单号前缀认领：{@code OpsTools} 建的单走 {@code BizNoGenerator}，
     * 号是 MT+日期+4 位序号，没有测试前缀。只能顺着 charger → station 找回自己那几行。
     * 靠前缀的话，恰恰是「AI 建的单」这一组断言会数到 0，而且清理不掉、一次次攒在开发库里。
     */
    private static final String OWN_TICKETS =
            "charger_id IN (SELECT id FROM charger WHERE station_id IN "
                    + "(SELECT id FROM station WHERE code LIKE '" + STATION_CODE_PREFIX + "%'))";

    @Autowired
    private OpsTools opsTools;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private MaintenanceTicketMapper ticketMapper;

    /** 告警没有 insert：Mapper 只读（本项目不接设备直连），夹具只能直接写库 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static long SEQ = 0;

    /**
     * 前后各扫一次。
     *
     * <p> 前面那次不只是对称：{@code SEQ} 每次 JVM 启动都从 0 开始，而 {@code device_no}
     * 是唯一索引 —— 上一次跑到一半崩了、没来得及清理的行，会让这一次的第一个夹具
     * 撞唯一键失败，症状看着像代码写错了。
     */
    @BeforeEach
    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();

        // 子 → 父。工单和告警都挂在桩上
        jdbcTemplate.update("DELETE FROM maintenance_ticket WHERE " + OWN_TICKETS);
        jdbcTemplate.update("DELETE FROM charger_alarm WHERE alarm_code LIKE ?",
                ALARM_CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM charger WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE ?)", STATION_CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM station WHERE code LIKE ?", STATION_CODE_PREFIX + "%");
    }

    // ==================== 夹具 ====================

    private void login() {
        BaseContext.setCurrentId(TEST_ID_BASE + (SEQ++));
    }

    private Charger givenCharger() {
        Station station = Station.builder()
                .code(STATION_CODE_PREFIX + (SEQ++))
                .name("AI 工具测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);

        Charger charger = Charger.builder()
                .stationId(station.getId())
                .deviceNo(DEVICE_NO_PREFIX + (SEQ++))
                .name("AI 测试桩")
                .type(1)
                .ratedPowerKw(new BigDecimal("120.00"))
                .gunCount(0)
                .status(3)
                .lastHeartbeatTime(LocalDateTime.now().minusHours(3))
                .build();
        chargerMapper.insert(charger);
        return charger;
    }

    private long givenAlarm(Long chargerId, String alarmCode, int status, LocalDateTime alarmTime) {
        jdbcTemplate.update("INSERT INTO charger_alarm "
                        + "(charger_id, alarm_code, alarm_level, content, status, alarm_time) "
                        + "VALUES (?, ?, 3, ?, ?, ?)",
                chargerId, alarmCode, "测试用告警 " + alarmCode, status, alarmTime);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void givenTicket(Long chargerId, Long stationId, Long alarmId, String title, int status) {
        login();
        MaintenanceTicket ticket = MaintenanceTicket.builder()
                .ticketNo(TICKET_NO_PREFIX + (SEQ++))
                .chargerId(chargerId)
                .stationId(stationId)
                .alarmId(alarmId)
                .title(title)
                .content("历史处理结论：" + title)
                .source(1)
                .status(status)
                .build();
        ticketMapper.insert(ticket);
    }

    /** 本类造出来的工单行数，用来证「不该写的没写」 */
    private long ticketCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM maintenance_ticket WHERE " + OWN_TICKETS, Long.class);
    }

    // ==================== getPileStatus ====================

    @Test
    @DisplayName("查不存在的桩：返回一句说明，不抛异常")
    void pileStatusOfMissingChargerShouldNotThrow() {
        // 模型编一个 id 是常态。抛异常的话框架会把它转成一段模型看不懂的错误文本，
        // 它多半会拿同一个 id 再试一次
        String answer = opsTools.getPileStatus(999_999_999L);

        assertThat(answer).contains("未找到").contains("999999999");
    }

    @Test
    @DisplayName("查存在的桩：带上状态、型号和心跳")
    void pileStatusShouldDescribeTheCharger() {
        Charger charger = givenCharger();

        String answer = opsTools.getPileStatus(charger.getId());

        assertThat(answer).contains(charger.getName()).contains(charger.getDeviceNo());
        assertThat(answer).contains("故障");
        // 心跳补了「距今多久」：模型做不了日期减法，只有绝对时刻它判断不出离线
        assertThat(answer).contains("小时前");
    }

    // ==================== getRecentAlarms ====================

    @Test
    @DisplayName("桩没有告警时说明「没有记录」，不是返回空串")
    void alarmsOfCleanChargerShouldSaySo() {
        Charger charger = givenCharger();

        String answer = opsTools.getRecentAlarms(charger.getId());

        assertThat(answer).contains("没有告警记录");
    }

    @Test
    @DisplayName("告警按时间倒序列出，未恢复的要标出来")
    void alarmsShouldBeListedNewestFirst() {
        Charger charger = givenCharger();
        LocalDateTime now = LocalDateTime.now();
        givenAlarm(charger.getId(), ALARM_CODE_PREFIX + "1", 0, now.minusHours(2));
        givenAlarm(charger.getId(), ALARM_CODE_PREFIX + "2", 1, now.minusDays(1));

        String answer = opsTools.getRecentAlarms(charger.getId());

        assertThat(answer).contains(ALARM_CODE_PREFIX + "1").contains(ALARM_CODE_PREFIX + "2");
        assertThat(answer).contains("未恢复").contains("已恢复");
        // 新的必须排在旧的前面，否则「最近发生了什么」要靠模型自己去比时间
        assertThat(answer.indexOf(ALARM_CODE_PREFIX + "1"))
                .isLessThan(answer.indexOf(ALARM_CODE_PREFIX + "2"));
    }

    // ==================== findSimilarResolvedTickets ====================

    @Test
    @DisplayName("同类故障：只召回已完成的工单，未完成的不能当成结论")
    void similarTicketsShouldOnlyIncludeResolvedOnes() {
        login();
        Charger charger = givenCharger();
        long alarmId = givenAlarm(charger.getId(), ALARM_CODE_PREFIX + "3", 0, LocalDateTime.now());
        givenTicket(charger.getId(), charger.getStationId(), alarmId, "已经修好的那一次", 2);
        givenTicket(charger.getId(), charger.getStationId(), alarmId, "还没人处理的这一次", 0);

        String answer = opsTools.findSimilarResolvedTickets(ALARM_CODE_PREFIX + "3");

        assertThat(answer).contains("已经修好的那一次");
        // 未完成工单的 content 是「打算做什么」，喂给模型会被当成「做过什么」
        assertThat(answer).doesNotContain("还没人处理的这一次");
    }

    @Test
    @DisplayName("没处理过的故障码：说明是首次出现，不要编造历史")
    void unknownAlarmCodeShouldSayItWasNeverHandled() {
        String answer = opsTools.findSimilarResolvedTickets(ALARM_CODE_PREFIX + "9");

        assertThat(answer).contains("没有").contains("历史处理记录");
    }

    // ==================== createMaintenanceTicket ====================

    @Test
    @DisplayName("桩不存在时不建单，而且一行脏数据都不留")
    void creatingTicketOnMissingChargerShouldWriteNothing() {
        login();
        long before = ticketCount();

        String answer = opsTools.createMaintenanceTicket(999_999_999L, "编出来的桩", "内容", null);

        assertThat(answer).contains("未找到").contains("没有创建");
        assertThat(ticketCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("标题为空时不建单")
    void creatingTicketWithoutTitleShouldWriteNothing() {
        login();
        Charger charger = givenCharger();
        long before = ticketCount();

        String answer = opsTools.createMaintenanceTicket(charger.getId(), "   ", "内容", null);

        assertThat(answer).contains("不能为空");
        assertThat(ticketCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("建单成功：来源是 AI、状态是待处理、站点从桩上取")
    void creatingTicketShouldFillSourceAndStation() {
        login();
        Charger charger = givenCharger();
        long alarmId = givenAlarm(charger.getId(), ALARM_CODE_PREFIX + "4", 0, LocalDateTime.now());

        String answer = opsTools.createMaintenanceTicket(
                charger.getId(), "通信中断，需要现场检查 SIM 卡", "建议：先远程复位，无效再赴现场", alarmId);

        assertThat(answer).contains("工单已创建");
        assertThat(ticketCount()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT ticket_no, station_id, alarm_id, source, status FROM maintenance_ticket "
                        + "WHERE " + OWN_TICKETS);

        // 站点不从参数来，从桩上推：模型多填一个参数就多一个填错的机会
        assertThat(row.get("station_id")).isEqualTo(charger.getStationId());
        assertThat(row.get("alarm_id")).isEqualTo(alarmId);
        assertThat(row.get("source")).isEqualTo(2);
        assertThat(row.get("status")).isEqualTo(0);
        assertThat((String) row.get("ticket_no")).startsWith("MT");
    }

    @Test
    @DisplayName("告警 id 属于别的桩时丢掉这个关联，但工单照建")
    void alarmFromAnotherChargerShouldBeDroppedButTicketStillCreated() {
        login();
        Charger mine = givenCharger();
        Charger other = givenCharger();
        long foreignAlarmId = givenAlarm(other.getId(), ALARM_CODE_PREFIX + "5", 0, LocalDateTime.now());

        String answer = opsTools.createMaintenanceTicket(
                mine.getId(), "误挂了别人告警的工单", "内容", foreignAlarmId);

        // 工单本身是该建的：alarm_id 只影响「同类故障历史」能不能召回，
        // 为它拒绝建单等于让一次有用的诊断白做
        assertThat(answer).contains("工单已创建");
        assertThat(ticketCount()).isEqualTo(1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT alarm_id, charger_id FROM maintenance_ticket WHERE " + OWN_TICKETS);

        assertThat(rows).hasSize(1);
        // 但不能留着这个关联：挂到别人桩的告警上会让历史记录张冠李戴，而且从数据上看不出来
        assertThat(rows.get(0).get("alarm_id")).isNull();
        assertThat(rows.get(0).get("charger_id")).isEqualTo(mine.getId());
    }

    @Test
    @DisplayName("两次建单拿到两个不同的工单号")
    void ticketNumbersShouldBeUnique() {
        login();
        Charger charger = givenCharger();

        opsTools.createMaintenanceTicket(charger.getId(), "第一次", "内容", null);
        opsTools.createMaintenanceTicket(charger.getId(), "第二次", "内容", null);

        // uk_ticket_no 撞了会抛 DuplicateKeyException，而这条路径上抛异常
        // 会变成「模型收到一段看不懂的错误」
        Long distinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ticket_no) FROM maintenance_ticket WHERE " + OWN_TICKETS,
                Long.class);

        assertThat(distinct).isEqualTo(2);
    }
}
