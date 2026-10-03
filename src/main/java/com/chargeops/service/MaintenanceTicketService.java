package com.chargeops.service;

import com.chargeops.dto.MaintenanceTicketPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.MaintenanceTicketVO;

/**
 * 运维工单服务（管理端）
 *
 * <p> 工单有两个写入方：运维人员在这里手工建，和 AI 助手在对话里建（走 {@code OpsTools}）。
 * 两条路径共用 {@code MaintenanceTicketMapper.insert}，区别只在 {@code source} 字段。
 *
 * <p> 这里是「把工单推向完成」的唯一入口，而完成才有资格被当成历史案例召回 ——
 * 换句话说，这个接口不被人用，AI 下一轮遇到同类故障时就没有历史可参考。
 */
public interface MaintenanceTicketService {

    PageResult page(MaintenanceTicketPageQueryDTO query);

    /**
     * 改状态。
     *
     * <p> 允许任意 0/1/2 之间互转，包括从「已完成」退回「处理中」：工单是人填的，
     * 填错了就得能改，这和订单不一样 —— 那边从「已支付」退回会牵扯已经结算的钱。
     */
    void updateStatus(Long id, Integer status);
}
