package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 充电桩告警（对应 {@code charger_alarm} 表）
 *
 * <p> 告警是设备上报的事实，只追加不改写：{@code status} 从 0 变 1 是「恢复了」，
 * 不是「删掉了」。{@code alarm_code} 是知识库的检索入口 —— AI 助手拿到它之后
 * 去《故障码说明》里查这个码该怎么处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargerAlarm implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long chargerId;

    /** 枪级告警才有值，桩级告警为 null */
    private Long connectorId;

    /** 故障码，如 E-021 */
    private String alarmCode;

    /** {@code 1提示 2一般 3严重} */
    private Integer alarmLevel;

    /** 设备上报的原文 */
    private String content;

    /** {@code 0未恢复 1已恢复} */
    private Integer status;

    private LocalDateTime alarmTime;

    private LocalDateTime recoverTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
