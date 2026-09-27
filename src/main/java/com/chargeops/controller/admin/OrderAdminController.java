package com.chargeops.controller.admin;

import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.dto.OrderEndDTO;
import com.chargeops.dto.OrderStartDTO;
import com.chargeops.dto.OrderRefundDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargingOrderService;
import com.chargeops.vo.AdminOrderDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单流程接口。路径 {@code /api/admin/orders/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id。
 *
 * <p>四个 POST 是设备接入层和定时任务的替身：真实系统里 start / end 由充电桩推上来、
 * settle 由定时任务触发，这里由运维代录 —— 所以计费依据的电量是人填的。
 * 接上真实设备层时它们应当被移除，而不是留一个改电量的后门。
 *
 * <p>没有列表查询，取 {@code {id}} 只能走 {@code GET /page}；
 * 这四个也不挂在车主端：车主能填电表读数就等于能决定自己用了多少电。
 */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "管理端-订单流程（设备上报替身）")
@Slf4j
@RequiredArgsConstructor
public class OrderAdminController {

    private final ChargingOrderService orderService;

    /**
     * 管理端唯一的订单取 id 入口。{@code @Valid} 在 GET 上同样生效，不要因为「没有请求体」去掉它。
     * 不带条件时是一次全表 filesort，所以前端应默认带上「最近 7 天」。
     */
    @GetMapping
    @Operation(summary = "订单分页查询",
            description = "管理端唯一的订单取 id 入口；不传条件时按建单时间倒序扫全表，"
                    + "前端应默认带 createTimeFrom/createTimeTo")
    public Result<PageResult> page(@Valid AdminOrderPageQueryDTO query) {
        return Result.success(orderService.pageForAdmin(query));
    }

    /** 异常挂账的原因在 {@code events} 里（{@code detail} 字段写了具体原因），这是运维排查的唯一依据 */
    @GetMapping("/{id}")
    @Operation(summary = "订单详情",
            description = "含分段明细与完整流转历史；异常挂账的原因在 events 里")
    public Result<AdminOrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getForAdmin(id));
    }

    /** {@code startMeterKwh} 是累计读数，设备没上报时留空 —— 填 0 会让总电量等于整个电表读数 */
    @PostMapping("/{id}/start")
    @Operation(summary = "启动充电（设备上报替身）",
            description = "1待启动→2充电中，不动钱（钱在建单时已冻结）")
    public Result<Void> start(@PathVariable Long id, @Valid @RequestBody OrderStartDTO dto) {
        orderService.start(id, dto);
        return Result.success();
    }

    /** 读数缺失或倒退时，「充电结束了」照常落库，订单紧接着转 9 异常挂账并带上原因，两个事实都不丢 */
    @PostMapping("/{id}/end")
    @Operation(summary = "结束充电（设备上报替身）",
            description = "2充电中→3待结算；读数异常时转9异常挂账并记录原因")
    public Result<Void> end(@PathVariable Long id, @Valid @RequestBody OrderEndDTO dto) {
        orderService.end(id, dto);
        return Result.success();
    }

    /** 电价规则有缺口时抛异常、整个事务回滚，订单留在 3 待结算可以重试 */
    @PostMapping("/{id}/settle")
    @Operation(summary = "结算（定时任务替身）",
            description = "3待结算→4待支付；按订单冻结的计费模板计价，不动钱")
    public Result<Void> settle(@PathVariable Long id) {
        orderService.settle(id);
        return Result.success();
    }

    /** 只有「已支付」能退；「待支付」的时候钱还没扣，要退的是冻结，走取消 */
    @PostMapping("/{id}/refund")
    @Operation(summary = "退款", description = "仅「已支付」可退款；金额必须为正")
    public Result<Void> refund(@PathVariable Long id, @Valid @RequestBody OrderRefundDTO dto) {
        orderService.refund(id, dto.getAmount());
        return Result.success();
    }
}
