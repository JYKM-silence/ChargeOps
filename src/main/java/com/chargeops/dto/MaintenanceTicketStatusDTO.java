package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 改工单状态
 *
 * <p> 单独一个 DTO 而不是 {@code @RequestParam}：这是一个写操作，参数要过 {@code @Valid}，
 * 而且以后要加「处理人」「处理说明」时不用改接口形状。
 *
 * <p> {@code @Min} / {@code @Max} 拦住 0/1/2 以外的值。不加的话它会一路走到
 * {@code TicketStatus.of} 才抛，同样返回 400，但消息里带的是「未知的工单状态：7」
 * 这种内部措辞 —— 校验注解的消息是写给调用方的。
 */
@Data
@Schema(description = "修改工单状态")
public class MaintenanceTicketStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "工单状态不能为空")
    @Min(value = 0, message = "工单状态只能是 0待处理、1处理中、2已完成")
    @Max(value = 2, message = "工单状态只能是 0待处理、1处理中、2已完成")
    private Integer status;
}
