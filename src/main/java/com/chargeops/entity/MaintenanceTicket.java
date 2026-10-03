package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维工单（对应 {@code maintenance_ticket} 表）
 *
 * <p> {@code source} 区分人建的还是 AI 建的。区分它不是为了统计，是为了排查：
 * AI 生成的内容只在 {@code content} 里，{@code title} 也是模型给的，
 * 所以看到 {@code source = 2} 的工单时，「写得不对」的第一嫌疑是检索没召回文档，
 * 而不是运维人员理解错了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String ticketNo;

    private Long chargerId;

    /** 冗余站点 id：工单列表几乎都按站点筛，免去每次 join charger */
    private Long stationId;

    /** 由哪条告警生成；人工建单为 null */
    private Long alarmId;

    private String title;

    private String content;

    /** {@code 1人工 2AI助手} */
    private Integer source;

    /** {@code 0待处理 1处理中 2已完成} */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
