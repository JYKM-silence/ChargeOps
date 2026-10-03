package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.MaintenanceTicketPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.MaintenanceTicket;
import com.chargeops.entity.Station;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.MaintenanceTicketMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.MaintenanceTicketService;
import com.chargeops.vo.MaintenanceTicketVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code MaintenanceTicketServiceImpl} 的集成测试。
 *
 * <p> 重点是 {@code updateStatus} 那个提前返回。MySQL 在「新值和旧值相同」时
 * 受影响行数也是 0，和「where 没匹配到行」长得一模一样 —— 靠影响行数判断存在性的话，
 * 运维重复点一次「标记完成」会看到「工单不存在」，然后去找人报障。
 *
 * <p> 所以第二个用例不满足于「不抛异常」，它断言 {@code update_user} 没变：
 * 这才分得开「提前返回了」和「照样写了一遍同值」。
 */
@SpringBootTest
class MaintenanceTicketServiceImplTest {

    /** 12e12 这一段是本类独占的 */
    private static final long TEST_ID_BASE = 12_000_000_000_000L;

    private static final String STATION_CODE_PREFIX = "ST-MTSVC-";
    private static final String DEVICE_NO_PREFIX = "DEV-MTSVC-";
    private static final String TICKET_NO_PREFIX = "MT-MTSVC-";

    @Autowired
    private MaintenanceTicketService ticketService;

    @Autowired
    private MaintenanceTicketMapper ticketMapper;

    @Autowired
    private ChargerMapper chargerMapper;

    @Autowired
    private StationMapper stationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static long SEQ = 0;

    /** 前后各扫一次，理由见 {@code OpsToolsTest#cleanUp}：崩过的上一轮不能毒到这一轮 */
    @BeforeEach
    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();

        jdbcTemplate.update("DELETE FROM maintenance_ticket WHERE ticket_no LIKE ?",
                TICKET_NO_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM charger WHERE station_id IN "
                + "(SELECT id FROM station WHERE code LIKE ?)", STATION_CODE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM station WHERE code LIKE ?", STATION_CODE_PREFIX + "%");
    }

    // ==================== 夹具 ====================

    private long login() {
        long id = TEST_ID_BASE + (SEQ++);
        BaseContext.setCurrentId(id);
        return id;
    }

    private Charger givenCharger() {
        Station station = Station.builder()
                .code(STATION_CODE_PREFIX + (SEQ++))
                .name("工单服务测试站点")
                .openTime("00:00:00")
                .closeTime("24:00:00")
                .status(1)
                .build();
        stationMapper.insert(station);

        Charger charger = Charger.builder()
                .stationId(station.getId())
                .deviceNo(DEVICE_NO_PREFIX + (SEQ++))
                .name("工单服务测试桩")
                .type(1)
                .ratedPowerKw(new BigDecimal("60.00"))
                .gunCount(0)
                .status(1)
                .build();
        chargerMapper.insert(charger);
        return charger;
    }

    private MaintenanceTicket givenTicket(Long chargerId, Long stationId, int status) {
        login();
        MaintenanceTicket ticket = MaintenanceTicket.builder()
                .ticketNo(TICKET_NO_PREFIX + (SEQ++))
                .chargerId(chargerId)
                .stationId(stationId)
                .title("测试工单")
                .content("测试内容")
                .source(1)
                .status(status)
                .build();
        ticketMapper.insert(ticket);
        return ticket;
    }

    private Map<String, Object> rowOf(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, update_user FROM maintenance_ticket WHERE id = ?", id);
    }

    // ==================== updateStatus ====================

    @Test
    @DisplayName("改成同一个值：不抛异常，而且一行都没写")
    void updatingToSameStatusShouldBeANoOp() {
        Charger charger = givenCharger();
        MaintenanceTicket ticket = givenTicket(charger.getId(), charger.getStationId(), 2);
        Long fixtureUser = (Long) rowOf(ticket.getId()).get("update_user");

        // 换一个登录身份再调：写没写过，看 update_user 是不是变成了新的
        login();
        ticketService.updateStatus(ticket.getId(), 2);

        assertThat(rowOf(ticket.getId()).get("update_user")).isEqualTo(fixtureUser);
    }

    @Test
    @DisplayName("改成别的值：状态落库，update_user 推到当前登录人")
    void updatingToDifferentStatusShouldPersist() {
        Charger charger = givenCharger();
        MaintenanceTicket ticket = givenTicket(charger.getId(), charger.getStationId(), 0);

        long operator = login();
        ticketService.updateStatus(ticket.getId(), 2);

        Map<String, Object> row = rowOf(ticket.getId());
        assertThat(row.get("status")).isEqualTo(2);
        assertThat(row.get("update_user")).isEqualTo(operator);
    }

    @Test
    @DisplayName("工单不存在：报「工单不存在」，不是静默成功")
    void updatingMissingTicketShouldThrow() {
        login();

        assertThatThrownBy(() -> ticketService.updateStatus(999_999_999L, 1))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.TICKET_NOT_FOUND);
    }

    @Test
    @DisplayName("0/1/2 以外的状态：在进数据库之前就被拦下")
    void updatingWithUnknownStatusShouldThrow() {
        Charger charger = givenCharger();
        MaintenanceTicket ticket = givenTicket(charger.getId(), charger.getStationId(), 0);
        login();

        assertThatThrownBy(() -> ticketService.updateStatus(ticket.getId(), 7))
                .isInstanceOf(ParamErrorException.class);

        // 反证：抛在翻译那一步，连读都没读，状态自然没动
        assertThat(rowOf(ticket.getId()).get("status")).isEqualTo(0);
    }

    // ==================== page ====================

    @Test
    @DisplayName("列表补上桩名和两个中文标签")
    void pageShouldFillChargerNameAndLabels() {
        Charger charger = givenCharger();
        givenTicket(charger.getId(), charger.getStationId(), 0);
        givenTicket(charger.getId(), charger.getStationId(), 2);

        PageResult result = ticketService.page(queryOf(charger.getId()));

        assertThat(result.getTotal()).isEqualTo(2);
        List<MaintenanceTicketVO> rows = rowsOf(result);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(vo -> {
            assertThat(vo.getChargerName()).isEqualTo("工单服务测试桩");
            assertThat(vo.getSourceLabel()).isEqualTo("人工");
        });
        assertThat(rows).extracting(MaintenanceTicketVO::getStatusLabel)
                .containsExactlyInAnyOrder("待处理", "已完成");
    }

    @Test
    @DisplayName("按状态筛：只回传匹配的那些")
    void pageShouldFilterByStatus() {
        Charger charger = givenCharger();
        givenTicket(charger.getId(), charger.getStationId(), 0);
        givenTicket(charger.getId(), charger.getStationId(), 2);
        givenTicket(charger.getId(), charger.getStationId(), 2);

        MaintenanceTicketPageQueryDTO query = queryOf(charger.getId());
        query.setStatus(2);

        PageResult result = ticketService.page(query);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(rowsOf(result)).extracting(MaintenanceTicketVO::getStatus)
                .containsOnly(2);
    }

    @Test
    @DisplayName("桩被删掉的工单：桩名给 null，不能让整页 500")
    void pageShouldSurviveTicketWhoseChargerIsGone() {
        Charger charger = givenCharger();
        // 指向一个不存在的桩。DDL 里没有外键，删桩不会级联删工单，
        // 而工单是历史记录，也不该跟着桩一起消失
        givenTicket(999_999_999L, charger.getStationId(), 0);

        PageResult result = ticketService.page(queryOf(999_999_999L));

        assertThat(rowsOf(result)).hasSize(1);
        assertThat(rowsOf(result).get(0).getChargerName()).isNull();
        // 桩名没了，其余字段照常给
        assertThat(rowsOf(result).get(0).getStatusLabel()).isEqualTo("待处理");
    }

    // ==================== 辅助 ====================

    /** 用 chargerId 而不是 stationId 收窄：别的测试类往库里留的工单不能影响 total */
    private MaintenanceTicketPageQueryDTO queryOf(Long chargerId) {
        MaintenanceTicketPageQueryDTO query = new MaintenanceTicketPageQueryDTO();
        query.setChargerId(chargerId);
        query.setPageSize(100);
        return query;
    }

    /** {@code records} 声明成 {@code List<?>}，这里显式收窄，免得每个用例都强转一次 */
    private List<MaintenanceTicketVO> rowsOf(PageResult result) {
        return result.getRecords().stream()
                .map(MaintenanceTicketVO.class::cast)
                .toList();
    }
}
