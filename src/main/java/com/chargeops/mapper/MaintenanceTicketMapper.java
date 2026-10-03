package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.MaintenanceTicketPageQueryDTO;
import com.chargeops.entity.MaintenanceTicket;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 运维工单数据访问。
 *
 * <p> 工单状态没有状态机：{@code 0 → 1 → 2} 是人工在管理端点着走的，转错了改回来就行，
 * 不像订单那样牵扯冻结资金。为它建一套 CAS 守卫是过度设计。
 */
@Mapper
public interface MaintenanceTicketMapper {

    /** {@code ticketNo} 唯一，重复会撞 {@code uk_ticket_no} —— 调用方负责在插入前生成不重复的号 */
    @AutoFill(OperationType.INSERT)
    int insert(MaintenanceTicket ticket);

    /**
     * 查同类故障码的历史处理记录，用于「上次这种故障是怎么解决的」。
     *
     * <p> 只取已完成的（{@code status = 2}）：未完成的工单里 {@code content} 是待办描述，
     * 不是结论，喂给模型会把「打算做什么」当成「做过什么」。
     */
    List<MaintenanceTicket> listResolvedByAlarmCode(@Param("alarmCode") String alarmCode,
                                                    @Param("limit") int limit);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<MaintenanceTicket> page(MaintenanceTicketPageQueryDTO query);

    MaintenanceTicket getById(@Param("id") Long id);

    /**
     * 改状态，四个审计列里只推 {@code update_time} / {@code update_user}。
     *
     * <p> 返回影响行数 0 <b>不一定是「工单不存在」</b>：MySQL 在「新值和旧值相同」时
     * 也返回 0。所以调用方必须先 {@link #getById} 拿到原状态，用「状态没变」提前返回，
     * 不能拿 0 行当「找不到」报错。
     */
    @AutoFill(OperationType.UPDATE)
    int updateStatus(MaintenanceTicket ticket);
}
