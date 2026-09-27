package com.chargeops.controller.user;

import com.chargeops.dto.OrderCreateDTO;
import com.chargeops.dto.OrderPageQueryDTO;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargingOrderService;
import com.chargeops.vo.ChargingOrderDetailVO;
import com.chargeops.vo.ChargingOrderVO;
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
 * 车主端 - 我的订单。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护，
 * BaseContext 里是车主 id，没有 {@code memberId} 参数。
 *
 * <p>这里只有建单、查单、取消、支付；启动 / 结束 / 结算 / 退款挂在管理端，
 * 因为它们的入参是电表读数 —— 车主能填就等于能自己决定用了多少电。
 */
@RestController
@RequestMapping("/api/member/orders")
@Tag(name = "车主端-我的订单")
@Slf4j
@RequiredArgsConstructor
public class OrderController {

    private final ChargingOrderService orderService;

    /** 这一刻冻结计费模板和预估金额 —— 冻结不是扣款，结算时按实付精确扣、剩下的还回去 */
    @PostMapping
    @Operation(summary = "创建充电订单",
            description = "冻结计费模板与预估金额；返回的订单处于「待启动」")
    public Result<ChargingOrderVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success(orderService.create(dto));
    }

    /** 我的订单。 status 可空，不传即全部 */
    @GetMapping
    @Operation(summary = "分页查询我的订单", description = "按建单时间倒序")
    public Result<PageResult> pageMine(@Valid OrderPageQueryDTO query) {
        return Result.success(orderService.pageMine(query));
    }

    /** 订单详情。 含分段明细和完整流转历史 */
    @GetMapping("/{id}")
    @Operation(summary = "查询订单详情",
            description = "含计费分段明细与状态流转历史；查别人的订单一律返回「订单不存在」")
    public Result<ChargingOrderDetailVO> getMine(@PathVariable Long id) {
        return Result.success(orderService.getMine(id));
    }

    /** 取消。 仅「待启动」可取消，充电中只能结束 */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消订单",
            description = "仅「待启动」可取消；会退回冻结金额，不消耗任何权益")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id);
        return Result.success();
    }

    /**
     * 这个 catch 必须留在 Controller，也不能挪进 Service：{@code pay} 抛异常时，
     * Spring 的事务拦截器已经把那个事务标成 rollback-only，在里面记「支付失败」是写不进去的。
     * 先让失败的 pay 彻底回滚，再在新事务里记下这次尝试。失败后订单停在 4 待支付，钱还冻着。
     */
    @PostMapping("/{id}/pay")
    @Operation(summary = "支付订单",
            description = "仅「待支付」可支付；余额不足时订单转为「支付失败」，冻结金额不动")
    public Result<Void> pay(@PathVariable Long id) {
        try {
            orderService.pay(id);
        } catch (BalanceNotEnoughException e) {
            // 新事务 —— 旧的那个已经回滚完了，这里才写得进去
            orderService.markPayFailed(id, e.getMessage());
            throw e;
        }
        return Result.success();
    }
}
