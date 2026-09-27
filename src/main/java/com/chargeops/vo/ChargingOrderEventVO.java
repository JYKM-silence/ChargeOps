package com.chargeops.vo;

import com.chargeops.entity.ChargingOrderEvent;
import com.chargeops.enumeration.EventSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态流转事件
 *
 * <p> 列表接口不返回它，只有详情接口返回。一笔订单的事件通常 5~10 条，
 * 塞进列表会让「我的订单」这个页面的响应体大出一个量级，而列表上根本不会展示它。
 *
 * <p> 给车主看的是去掉 {@code operatorId} 的版本 —— 运维人员的工号对车主
 * 没有意义。但 {@code detail} 保留，它是「这笔订单为什么是这样」的唯一线索，
 * 客服和用户看同一份数据才谈得下去。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOrderEventVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer fromStatus;

    private Integer toStatus;

    private String eventType;

    private Integer source;

    private String sourceLabel;

    /** 设备原始报文 / 异常信息。 进 9 异常挂账时这里是原因 */
    private String detail;

    private LocalDateTime createTime;

    public static ChargingOrderEventVO from(ChargingOrderEvent event) {
        return ChargingOrderEventVO.builder()
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .eventType(event.getEventType())
                .source(event.getSource())
                .sourceLabel(EventSource.of(event.getSource()).getLabel())
                .detail(event.getDetail())
                .createTime(event.getCreateTime())
                .build();
    }
}
