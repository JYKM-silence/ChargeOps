package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电订单数据访问。
 *
 * <p>所有状态流转都是带 from 的条件更新（{@code UPDATE ... WHERE id = ? AND status = ?}）：
 * 返回 0 行是正常业务结果「这一步已经有人做过了」，不是异常，调用方必须检查 ——
 * 否则一次重复上报的结束事件会把同一笔订单结算两次。
 *
 * <p>不能写成「先 SELECT 查 status 再 UPDATE」；状态流转不标 {@code @AutoFill}，查询一律带 {@code member_id}。
 */
@Mapper
public interface ChargingOrderMapper {

    /** 唯一一个可以标 {@code @AutoFill(INSERT)} 的语句：订单一定是某个登录用户建的，不存在「设备创建订单」 */
    @AutoFill(OperationType.INSERT)
    int insert(ChargingOrder order);

    /** 无归属校验，只给订单流程内部用（结算、退款、设备上报）。车主端必须用 {@link #getByIdAndMember} */
    ChargingOrder getById(@Param("id") Long id);

    /** 车主端唯一的详情入口。查不到和「不是你的」都返回 {@code null}，区分它们等于免费探测订单号是否存在 */
    ChargingOrder getByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /** 走 {@code idx_order_member_create}，不写 LIMIT（分页由 {@code PageHelper.startPage} 在拦截器层加） */
    List<ChargingOrder> pageByMember(@Param("memberId") Long memberId,
                                     @Param("status") Integer status);

    /**
     * 管理端订单分页，四个流程接口的取 id 入口。{@code memberIds} 独立于 DTO 传参，
     * 空集合时返回 0 行而不是不过滤 —— XML 里有显式的 {@code AND 1 = 0} 分支。
     */
    List<ChargingOrder> pageForAdmin(@Param("query") AdminOrderPageQueryDTO query,
                                     @Param("memberIds") List<Long> memberIds);

    /** 走 {@code idx_order_connector_status}。返回 {@code List} 而不是 {@code LIMIT 1}：真有两条时调用方必须看见 */
    List<ChargingOrder> listHoldingByConnector(@Param("connectorId") Long connectorId,
                                               @Param("statuses") List<Integer> statuses);

    /** 同一时刻一辆车只能有一笔进行中的订单。{@code vehicle_id} 上没有索引是刻意的：先按 {@code member_id} 收窄再过滤更便宜 */
    List<ChargingOrder> listHoldingByVehicle(@Param("memberId") Long memberId,
                                             @Param("vehicleId") Long vehicleId,
                                             @Param("statuses") List<Integer> statuses);

    /**
     * 站点删除守卫。SQL 里 {@code status} 必须写在 {@code station_id} 前面 —— 反过来写要扫完这个站的全部历史订单。
     */
    int countByStation(@Param("stationId") Long stationId,
                       @Param("statuses") List<Integer> statuses);

    /** 桩的删除守卫。{@code charger_id} 是残余过滤而不是前导列 —— 改 SQL 时别把 {@code status} 挪到后面 */
    int countByCharger(@Param("chargerId") Long chargerId,
                       @Param("statuses") List<Integer> statuses);

    /**
     * 某份权益被多少笔订单用过 —— 套餐退款口径的权威判据。用补集 {@code status <> 8} 而不是
     * {@code IN (...)}：只有「已取消」意味着权益没被消耗，写成 {@code IN} 会让新加的状态默认落到「没用过」。
     */
    int countByPackage(@Param("packageId") Long packageId);

    /**
     * 卡在 {@code 4 待支付} 太久的订单。账龄必须看 {@code settle_time} 而不是 {@code create_time}：
     * 充了 8 小时、刚结束的订单会被按创建时间当场判成支付失败。{@code limit} 是必备参数，不是优化。
     */
    List<ChargingOrder> listStalePendingPay(@Param("before") LocalDateTime before,
                                            @Param("limit") int limit);

    /** 补写冻结的权益 id（订单 id 只有 insert 之后才有）。条件里的 {@code status = 1} 不能少：迟到的补写会给一笔已在充电的订单换掉权益 */
    int bindMemberPackage(@Param("id") Long id,
                          @Param("memberPackageId") Long memberPackageId,
                          @Param("updateTime") LocalDateTime updateTime);

    /** {@code start_meter_kwh} 是设备累计读数、不是从 0 开始；读表失败传 NULL 而不是 0（填 0 会让总电量 = 整个电表读数） */
    int markStarted(@Param("id") Long id,
                    @Param("startTime") LocalDateTime startTime,
                    @Param("startMeterKwh") BigDecimal startMeterKwh,
                    @Param("startSoc") Integer startSoc,
                    @Param("updateTime") LocalDateTime updateTime);

    /** 只写「充电结束」这个事实，不写金额 —— 结算失败不该让物理世界发生过的事回滚 */
    int markEnded(@Param("id") Long id,
                  @Param("endTime") LocalDateTime endTime,
                  @Param("durationSeconds") Integer durationSeconds,
                  @Param("endMeterKwh") BigDecimal endMeterKwh,
                  @Param("totalEnergyKwh") BigDecimal totalEnergyKwh,
                  @Param("endSoc") Integer endSoc,
                  @Param("stopReason") Integer stopReason,
                  @Param("updateTime") LocalDateTime updateTime);

    /** {@code total_amount} 必须是分段金额之和，不能写「总电量 × 综合单价」，否则会和分段明细对不上 */
    int markSettled(@Param("id") Long id,
                    @Param("energyFee") BigDecimal energyFee,
                    @Param("serviceFee") BigDecimal serviceFee,
                    @Param("totalAmount") BigDecimal totalAmount,
                    @Param("discountAmount") BigDecimal discountAmount,
                    @Param("payAmount") BigDecimal payAmount,
                    @Param("settleTime") LocalDateTime settleTime,
                    @Param("updateTime") LocalDateTime updateTime);

    /** 来源含 {@code 6 支付失败}（用户充值后再点一次支付）。必须在 {@code walletService.settle} 之前、同事务执行 */
    int markPaid(@Param("id") Long id,
                 @Param("payTime") LocalDateTime payTime,
                 @Param("updateTime") LocalDateTime updateTime);

    /** 钱还冻着，既没扣掉也没退回；用户充值后再支付走 {@link #markPaid} 的 {@code 6 → 5} */
    int markPayFailed(@Param("id") Long id,
                      @Param("remark") String remark,
                      @Param("updateTime") LocalDateTime updateTime);

    /** 只有 {@code 5 已支付} 能退款。{@code 4 待支付} 的钱还没扣，由取消路径解冻 */
    int markRefunded(@Param("id") Long id,
                     @Param("refundAmount") BigDecimal refundAmount,
                     @Param("refundTime") LocalDateTime refundTime,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 只有 {@code 1 待启动} 能取消；解冻钱包由调用方在同一个事务里完成 */
    int markCancelled(@Param("id") Long id,
                      @Param("updateTime") LocalDateTime updateTime);

    /** 用「钱还冻着的状态集」而不是固定的 from。集合必须传 {@code OrderStatus.holdingMoneyCodes()}，不是 {@code holdingConnectorCodes()}（后者漏掉 4 和 6） */
    int markAbnormal(@Param("id") Long id,
                     @Param("statuses") List<Integer> statuses,
                     @Param("remark") String remark,
                     @Param("updateTime") LocalDateTime updateTime);
}
