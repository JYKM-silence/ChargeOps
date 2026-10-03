package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.MaintenanceTicketPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.MaintenanceTicket;
import com.chargeops.enumeration.TicketStatus;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.MaintenanceTicketMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.MaintenanceTicketService;
import com.chargeops.vo.MaintenanceTicketVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运维工单实现
 *
 * <p> {@code page} 的顺序和 {@code ChargerServiceImpl} 一样是承重结构：
 * {@code PageHelper.startPage} 之后的第一条查询才会被分页，补充电桩名的那次批量查询
 * 必须排在分页查询后面。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceTicketServiceImpl implements MaintenanceTicketService {

    private static final int SOURCE_MANUAL = 1;
    private static final int SOURCE_AI = 2;

    private final MaintenanceTicketMapper ticketMapper;
    private final ChargerMapper chargerMapper;

    @Override
    public PageResult page(MaintenanceTicketPageQueryDTO query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<MaintenanceTicket> rows = ticketMapper.page(query);
        long total = ((Page<MaintenanceTicket>) rows).getTotal();

        return new PageResult(total, toVos(rows));
    }

    @Override
    @Transactional
    public void updateStatus(Long id, Integer status) {
        // 先翻译一次，非法值在这里就变成 400，不用等数据库
        TicketStatus target = TicketStatus.of(status);

        MaintenanceTicket existing = ticketMapper.getById(id);
        if (existing == null) {
            throw new NotFoundException(MessageConstant.TICKET_NOT_FOUND);
        }
        if (Objects.equals(existing.getStatus(), status)) {
            // 提前返回，不能靠「影响 0 行」判断：MySQL 在值没变时也返回 0，
            // 那样会把一次幂等的重复提交报成「工单不存在」
            return;
        }

        MaintenanceTicket update = new MaintenanceTicket();
        update.setId(id);
        update.setStatus(target.getCode());

        // 只推两个审计列，由 @AutoFill 的 UPDATE 分支填
        ticketMapper.updateStatus(update);

        log.info("工单状态变更：id={}, {} → {}", id, existing.getStatus(), target.getCode());
    }

    /** 批量补桩名，理由和空集合的处理见 {@code ChargerServiceImpl#toVos} */
    private List<MaintenanceTicketVO> toVos(List<MaintenanceTicket> tickets) {
        if (tickets.isEmpty()) {
            return List.of();
        }

        Set<Long> chargerIds = tickets.stream()
                .map(MaintenanceTicket::getChargerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 桩被删掉时名字为 null，不让整页 500
        Map<Long, String> chargerNames = chargerIds.isEmpty()
                ? Map.of()
                : chargerMapper.listByIds(List.copyOf(chargerIds)).stream()
                        .collect(Collectors.toMap(Charger::getId, Charger::getName));

        return tickets.stream()
                .map(ticket -> toVo(ticket, chargerNames.get(ticket.getChargerId())))
                .toList();
    }

    private MaintenanceTicketVO toVo(MaintenanceTicket ticket, String chargerName) {
        MaintenanceTicketVO vo = new MaintenanceTicketVO();
        BeanUtils.copyProperties(ticket, vo);
        vo.setChargerName(chargerName);
        vo.setStatusLabel(labelOfStatus(ticket.getStatus()));
        vo.setSourceLabel(labelOfSource(ticket.getSource()));
        return vo;
    }

    /** 降级策略同 {@code ChargerServiceImpl.labelOfStatus}：只读路径上翻不出来就返回 null，不抛 */
    private String labelOfStatus(Integer status) {
        try {
            return TicketStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            log.warn("工单状态无法识别，status={}", status);
            return null;
        }
    }

    /** 只有两个值，不单独建枚举：{@code source} 不进任何判断分支，只影响列表上那个标签 */
    private String labelOfSource(Integer source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case SOURCE_MANUAL -> "人工";
            case SOURCE_AI -> "AI助手";
            default -> null;
        };
    }
}
