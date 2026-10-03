package com.chargeops.controller.admin;

import com.chargeops.dto.MaintenanceTicketPageQueryDTO;
import com.chargeops.dto.MaintenanceTicketStatusDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.MaintenanceTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 运维工单
 *
 * <p> 只有查列表和改状态两个接口，没有建单和改内容：建单的入口是 AI 助手对话
 * （{@code OpsTools.createMaintenanceTicket}）和后续要加的人工表单，
 * 而工单是「已经发生过的事」的记录，改内容等于篡改历史。
 *
 * <p> 把状态推到 2 已完成是有下游意义的：只有已完成的工单会被
 * {@code MaintenanceTicketMapper.listResolvedByAlarmCode} 召回，当同类故障的历史案例。
 */
@RestController
@RequestMapping("/api/admin/maintenance-ticket")
@Tag(name = "管理端-运维工单")
@Slf4j
public class MaintenanceTicketController {

    private final MaintenanceTicketService ticketService;

    public MaintenanceTicketController(MaintenanceTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 四个过滤条件都可选，最常用的是 {@code status = 0} 看待处理的 */
    @GetMapping("/page")
    @Operation(summary = "分页查询运维工单")
    public Result<PageResult> page(@Valid MaintenanceTicketPageQueryDTO query) {
        return Result.success(ticketService.page(query));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "修改工单状态")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody MaintenanceTicketStatusDTO statusDTO) {
        ticketService.updateStatus(id, statusDTO.getStatus());
        return Result.success();
    }
}
