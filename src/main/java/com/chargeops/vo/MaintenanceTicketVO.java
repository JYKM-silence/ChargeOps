package com.chargeops.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维工单（列表和详情共用）
 *
 * <p> 比实体多三个展示字段：{@code chargerName} 由 Service 批量补齐（同 {@code ChargerVO.stationName}），
 * {@code statusLabel} / {@code sourceLabel} 由枚举翻译。列表页带上 {@code content}：
 * 工单表是万级，一页 10 条的正文加起来也就几十 KB，为它单开一个详情接口不划算。
 */
@Data
public class MaintenanceTicketVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String ticketNo;

    private Long chargerId;

    /** 冗余在 Service 里按 {@code chargerId} 批量查出来，不来自工单表 */
    private String chargerName;

    private Long stationId;

    /** 由哪条告警生成；人工建单为 null */
    private Long alarmId;

    private String title;

    private String content;

    /** {@code 1人工 2AI助手} */
    private Integer source;

    private String sourceLabel;

    /** {@code 0待处理 1处理中 2已完成} */
    private Integer status;

    private String statusLabel;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
