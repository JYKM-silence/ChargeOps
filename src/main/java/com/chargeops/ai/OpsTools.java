package com.chargeops.ai;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargerAlarm;
import com.chargeops.entity.Connector;
import com.chargeops.entity.MaintenanceTicket;
import com.chargeops.enumeration.ChargerStatus;
import com.chargeops.enumeration.ChargerType;
import com.chargeops.enumeration.ConnectorStatus;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerAlarmMapper;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.MaintenanceTicketMapper;
import com.chargeops.utils.BizNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 运维助手的工具集（Tool Calling 那一层）。
 *
 * <p> 模型自己决定什么时候调哪个方法、参数填什么。这意味着每个方法都是一个
 * 没有校验层的公开入口：参数是模型从用户那句自然语言里猜出来的，id 可能是它编的。
 * 所以每个方法进来第一件事是查存在性。
 *
 * <p> 一律返回 {@code String}，不返回实体，也不抛异常。返回实体的话，序列化会把
 * {@code create_user}、{@code version} 这些模型用不上的字段一起塞进上下文；抛异常
 * 的话框架把它转成一段模型看不懂的错误文本，它多半会拿同一个错误参数重试。
 * 返回一句「未找到 id 为 999 的充电桩」，模型会改成向用户要正确的 id。
 *
 * <p> 时间都补一个「距今多久」。模型不擅长做日期减法，而「最后心跳是 3 小时前」
 * 和「是 3 小时前的某个绝对时刻」对它判断离线与否完全是两回事。
 *
 * <p> 这个类上不要再加 {@code @Transactional} 这类会生成代理的注解。ChatClient 的
 * {@code .tools(bean)} 最终走 {@code ToolCallbacks.from}，它按 bean 的类直接读方法上的
 * {@code @Tool}，不做 AOP 的方法解析（做解析的是另一个入口 {@code MethodToolCallbackProvider}）。
 * 代理之后注解读不到，失败方式是工具静默地一个都不注册，日志里什么都没有。
 * 这里的写操作只有一条 INSERT，本来也不需要事务。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpsTools {

    /** 最近告警取几条。给多了会把上下文占满，而排查一台桩看 10 条够定位到是哪类故障 */
    private static final int RECENT_ALARM_LIMIT = 10;

    /** 历史工单取几条。要的是「上次怎么修的」，同故障码下 3 条足够看出共性 */
    private static final int SIMILAR_TICKET_LIMIT = 3;

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChargerMapper chargerMapper;
    private final ConnectorMapper connectorMapper;
    private final ChargerAlarmMapper alarmMapper;
    private final MaintenanceTicketMapper ticketMapper;
    private final BizNoGenerator bizNoGenerator;

    @Tool(description = """
            查询充电桩的实时状态：桩本身的状态、型号、额定功率、最后心跳时间，
            以及它下面每一把枪的状态。用户提到「几号桩」「某台设备」时用这个工具。""")
    String getPileStatus(@ToolParam(description = "充电桩 id，整数") Long pileId) {
        if (pileId == null) {
            return "没有给出充电桩 id，无法查询。请向用户确认是几号桩。";
        }

        Charger charger = chargerMapper.getById(pileId);
        if (charger == null) {
            return String.format("未找到 id 为 %d 的充电桩。请向用户确认 id，不要自己猜。", pileId);
        }

        List<Connector> connectors = connectorMapper.listByChargerId(pileId);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("充电桩 %s（id=%d，设备编号 %s）%n",
                charger.getName(), charger.getId(), charger.getDeviceNo()));
        sb.append(String.format("状态：%s%n", labelOfStatus(charger.getStatus())));
        sb.append(String.format("类型：%s，额定功率 %s kW%n",
                labelOfType(charger.getType()), charger.getRatedPowerKw()));
        sb.append(String.format("最后心跳：%s%n", withAgo(charger.getLastHeartbeatTime())));

        if (connectors.isEmpty()) {
            sb.append("该桩下没有充电枪。");
            return sb.toString();
        }

        sb.append("充电枪：");
        for (Connector connector : connectors) {
            sb.append(String.format("%n- %s（id=%d）：%s，累计充电 %d 次",
                    connector.getGunNo(), connector.getId(),
                    labelOfConnectorStatus(connector.getStatus()),
                    connector.getTotalChargeCount() == null ? 0 : connector.getTotalChargeCount()));
        }
        return sb.toString();
    }

    @Tool(description = """
            查询某台充电桩最近的告警记录，新的在前，最多 10 条。
            每条包含故障码、级别、是否已恢复。诊断故障时先调它拿到故障码，
            再用故障码去知识库和历史工单里查处理办法。""")
    String getRecentAlarms(@ToolParam(description = "充电桩 id，整数") Long pileId) {
        if (pileId == null) {
            return "没有给出充电桩 id，无法查询告警。请向用户确认是几号桩。";
        }

        Charger charger = chargerMapper.getById(pileId);
        if (charger == null) {
            return String.format("未找到 id 为 %d 的充电桩。请向用户确认 id，不要自己猜。", pileId);
        }

        List<ChargerAlarm> alarms = alarmMapper.listRecentByChargerId(pileId, RECENT_ALARM_LIMIT);
        if (alarms.isEmpty()) {
            return String.format("充电桩 %s（id=%d）没有告警记录。", charger.getName(), pileId);
        }

        StringBuilder sb = new StringBuilder(String.format("充电桩 %s（id=%d）最近的告警，新的在前：",
                charger.getName(), pileId));
        for (ChargerAlarm alarm : alarms) {
            sb.append(String.format("%n- 告警id=%d [%s] %s %s（%s）：%s",
                    alarm.getId(),
                    alarm.getStatus() != null && alarm.getStatus() == 1 ? "已恢复" : "未恢复",
                    alarm.getAlarmTime() == null ? "" : alarm.getAlarmTime().format(MINUTE),
                    alarm.getAlarmCode(),
                    labelOfLevel(alarm.getAlarmLevel()),
                    alarm.getContent()));
            if (alarm.getStatus() != null && alarm.getStatus() == 1 && alarm.getRecoverTime() != null) {
                sb.append(String.format("，恢复于 %s", alarm.getRecoverTime().format(MINUTE)));
            }
        }
        return sb.toString();
    }

    @Tool(description = """
            按故障码查历史上同类故障的处理记录，最多 3 条，都是已完成的工单。
            用于回答「以前这种故障是怎么解决的」。故障码形如 E-021。""")
    String findSimilarResolvedTickets(
            @ToolParam(description = "故障码，形如 E-021") String alarmCode) {
        if (alarmCode == null || alarmCode.isBlank()) {
            return "没有给出故障码，无法查询历史工单。";
        }

        List<MaintenanceTicket> tickets = ticketMapper.listResolvedByAlarmCode(alarmCode, SIMILAR_TICKET_LIMIT);
        if (tickets.isEmpty()) {
            return String.format("没有故障码 %s 的历史处理记录。这条故障可能没处理过，"
                    + "请依据知识库里的故障码说明给出建议，并说明这是首次出现的判断依据。", alarmCode);
        }

        StringBuilder sb = new StringBuilder(String.format("故障码 %s 的历史处理记录：", alarmCode));
        for (MaintenanceTicket ticket : tickets) {
            sb.append(String.format("%n- 工单 %s《%s》%n  处理结果：%s",
                    ticket.getTicketNo(), ticket.getTitle(),
                    ticket.getContent() == null ? "（无内容）" : ticket.getContent()));
        }
        return sb.toString();
    }

    @Tool(description = """
            创建一条运维工单，派给运维人员处理。
            只在已经查清故障现象、并给出建议的处理步骤之后才调用；同一个故障不要重复建单。
            建单前先把要写进工单的内容说给用户听。""")
    String createMaintenanceTicket(
            @ToolParam(description = "充电桩 id，整数") Long chargerId,
            @ToolParam(description = "工单标题，一句话，不超过 50 字") String title,
            @ToolParam(description = "工单内容：故障现象、诊断依据、建议的处理步骤") String content,
            @ToolParam(description = "关联的告警 id，如果这次故障来自某条告警就填；没有就不填",
                    required = false) Long alarmId) {

        if (chargerId == null || title == null || title.isBlank()) {
            return "充电桩 id 和工单标题都不能为空，工单没有创建。";
        }

        Charger charger = chargerMapper.getById(chargerId);
        if (charger == null) {
            return String.format("未找到 id 为 %d 的充电桩，工单没有创建。请向用户确认 id。", chargerId);
        }

        MaintenanceTicket ticket = MaintenanceTicket.builder()
                .ticketNo(bizNoGenerator.ticketNo())
                .chargerId(charger.getId())
                .stationId(charger.getStationId())
                .alarmId(verifyAlarmId(alarmId, charger.getId()))
                .title(title)
                .content(content)
                // 2 AI助手。区分来源是为了排查：AI 建的工单内容不对时，
                // 第一嫌疑是检索没召回文档，不是运维人员理解错了
                .source(2)
                // 0 待处理。建单即已处理是两回事，改状态由运维在管理端点着走
                .status(0)
                .build();

        // 四个审计字段由 @AutoFill 从 BaseContext 取当前登录的 staff id，这里不填
        ticketMapper.insert(ticket);

        log.info("AI 建单成功：ticketNo={}, chargerId={}, staffId={}",
                ticket.getTicketNo(), charger.getId(), BaseContext.getCurrentId());

        return String.format("工单已创建，单号 %s，标题《%s》，指派给 %s 的运维人员，当前状态待处理。",
                ticket.getTicketNo(), ticket.getTitle(), charger.getName());
    }

    /**
     * 核实模型给的告警 id。
     *
     * <p> 对不上就丢掉这个关联而不是拒绝建单：{@code alarm_id} 只影响
     * 「同类故障历史」能不能召回，而工单本身是该建的。但也不能不查，
     * 挂到别的桩的告警上会让历史记录张冠李戴，而且从数据上看不出来。
     */
    private Long verifyAlarmId(Long alarmId, Long chargerId) {
        if (alarmId == null) {
            return null;
        }
        ChargerAlarm alarm = alarmMapper.getById(alarmId);
        if (alarm == null || !chargerId.equals(alarm.getChargerId())) {
            log.warn("AI 给的告警 id 与充电桩对不上，已丢弃关联：alarmId={}, chargerId={}", alarmId, chargerId);
            return null;
        }
        return alarmId;
    }

    /** 枚举查不到时不抛：工具方法抛出去，模型只会换个说法再撞一次 */
    private String labelOfStatus(Integer status) {
        try {
            return ChargerStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            return "未知状态(" + status + ")";
        }
    }

    private String labelOfType(Integer type) {
        try {
            return ChargerType.of(type).getLabel();
        } catch (ParamErrorException e) {
            return "未知类型(" + type + ")";
        }
    }

    private String labelOfConnectorStatus(Integer status) {
        try {
            return ConnectorStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            return "未知状态(" + status + ")";
        }
    }

    private String labelOfLevel(Integer level) {
        if (level == null) {
            return "未知级别";
        }
        return switch (level) {
            case 1 -> "提示";
            case 2 -> "一般";
            case 3 -> "严重";
            default -> "未知级别(" + level + ")";
        };
    }

    private String withAgo(LocalDateTime time) {
        if (time == null) {
            return "无记录（该桩从未上报过心跳）";
        }
        Duration elapsed = Duration.between(time, LocalDateTime.now());
        long minutes = elapsed.toMinutes();
        String ago = minutes < 60
                ? minutes + " 分钟前"
                : (minutes < 60 * 24 ? minutes / 60 + " 小时前" : minutes / (60 * 24) + " 天前");
        return time.format(MINUTE) + "（" + ago + "）";
    }
}
