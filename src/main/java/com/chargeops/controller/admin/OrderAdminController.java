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
 * 管理端 - 订单流程操作
 *
 * <p>路径 {@code /api/admin/orders/**}，由 {@code JwtTokenAdminInterceptor} 保护。
 *
 * <h2>★ 这四个接口是「设备接入层」和「定时任务」的替身</h2>
 *
 * <p>真实系统里 {@code start} / {@code end} 由充电桩通过 MQTT / TCP 长连接推上来，带着设备证书，
 * 写入路径完全不经过 HTTP；{@code settle} 由定时任务扫「待结算」的订单触发。<b>本项目没有那套
 * 基础设施</b>，所以把它们挂在管理端，由运维代为录入。这个替换是有代价的，必须说清楚：
 *
 * <ul>
 *   <li><b>好的一面</b>：整条订单链路可走通、可演示、可测。谁在什么时候录入了什么，
 *       {@code charging_order_event} 里有记录（{@code source = 4 运维}，带 {@code operator_id}）</li>
 *   <li><b>坏的一面</b>：<b>计费所依据的电量是人填的</b> —— 生产环境里这等于没有计费。
 *       接上真实设备层时，这四个接口应当被移除或降级为只读的排查工具，
 *       而不是留着一个「改电量」的后门</li>
 * </ul>
 *
 * <p>★ <b>为什么不挂在车主端：</b>车主能填电表读数就等于能决定自己用了多少电 ——
 * 那不是漏洞，那是免费充电。
 *
 * <h2>★ 两个 GET 是后来补的，而它们补的是一个死角</h2>
 *
 * <p>{@code page} 和 {@code detail} 加进来之前，这个类只有四个
 * {@code POST /{id}/...}，而 {@code /api/admin/**} 下<b>没有任何地方能列出订单</b> ——
 * 于是出现了「四个按钮都在，却没有页面拿得到那个 id」的闭环死角，
 * 充电链路在管理端整条走不进去，运维只能靠车主截图报单号或直接查库。
 *
 * <p>★ 它不是「顺手加的查询接口」：一个操作接口和一个能拿到操作对象的查询接口
 * 是<b>一对</b>，只有前者不构成一个可用的功能。这是接口设计里的常见缺口，
 * 因为写操作的那一半有明确的验收场景，而查询的那一半没有。
 */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "管理端-订单流程（设备上报替身）")
@Slf4j
@RequiredArgsConstructor
public class OrderAdminController {

    private final ChargingOrderService orderService;

    /**
     * 订单分页查询
     *
     * <p>★★ <b>这个 GET 是上面四个 POST 的前提</b>，不是「顺带加的查询」：
     * 那四个接口全都要一个 {@code {id}}，而在它之前
     * {@code /api/admin/**} 下没有任何地方能列出订单 ——
     * <b>四个按钮都在，却没有页面拿得到那个 id</b>。
     * 完整说明见 {@code AdminOrderPageQueryDTO} 的类注释。
     *
     * <p>★ <b>{@code @Valid} 在 GET 上同样生效</b>（Spring 把查询串绑成
     * {@code @ModelAttribute} 之后照样跑校验），所以 {@code pageSize=1000000}
     * 会被 {@code @Max(100)} 挡下。不要因为「GET 没有请求体」就去掉它。
     *
     * <p>★ <b>不带任何条件时是一次全表 filesort</b>（排序是
     * {@code create_time DESC}，而没有 {@code status} 时
     * {@code idx_order_status_create} 用不上）。所以前端应当默认带上
     * 「最近 7 天」，而不是打开页面就裸查 —— 取舍的完整论证见 DTO 第三节。
     */
    @GetMapping
    @Operation(summary = "订单分页查询",
            description = "管理端唯一的订单取 id 入口；不传条件时按建单时间倒序扫全表，"
                    + "前端应默认带 createTimeFrom/createTimeTo")
    public Result<PageResult> page(@Valid AdminOrderPageQueryDTO query) {
        return Result.success(orderService.pageForAdmin(query));
    }

    /**
     * 订单详情
     *
     * <p>★ 返回的 {@code events} 里带着 {@code abnormal} 那一行 ——
     * 一笔卡在「9 异常挂账」的订单，它「为什么变成 9」的答案就在那里
     * （{@code detail} 字段有具体原因）。这是运维排查的<b>唯一</b>依据，
     * 因为设备上报替身接口的操作日志留不到那么久。
     */
    @GetMapping("/{id}")
    @Operation(summary = "订单详情",
            description = "含分段明细与完整流转历史；异常挂账的原因在 events 里")
    public Result<AdminOrderDetailVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getForAdmin(id));
    }

    /**
     * ★ {@code startMeterKwh} 是<b>累计读数</b>，不是从 0 开始。设备没上报时留空，
     * <b>不要填 0</b> —— 那会让总电量等于整个电表读数。
     */
    @PostMapping("/{id}/start")
    @Operation(summary = "启动充电（设备上报替身）",
            description = "1待启动→2充电中，不动钱（钱在建单时已冻结）")
    public Result<Void> start(@PathVariable Long id, @Valid @RequestBody OrderStartDTO dto) {
        orderService.start(id, dto);
        return Result.success();
    }

    /**
     * ★ 电量 = 结束读数 − 起始读数。读数缺失或倒退时，「充电结束了」这个事实照常落库，
     * 但订单会紧接着转 {@code 9 异常挂账} 并带上原因 —— 两个事实都不丢。
     */
    @PostMapping("/{id}/end")
    @Operation(summary = "结束充电（设备上报替身）",
            description = "2充电中→3待结算；读数异常时转9异常挂账并记录原因")
    public Result<Void> end(@PathVariable Long id, @Valid @RequestBody OrderEndDTO dto) {
        orderService.end(id, dto);
        return Result.success();
    }

    /**
     * ★ 跑计费引擎、写分段明细、定下金额，<b>不动钱</b>。电价规则有缺口时抛异常，
     * 整个事务回滚，订单<b>留在 3 待结算</b>可以重试 —— 只有人工判断「重试也不会好」时才该转 9。
     */
    @PostMapping("/{id}/settle")
    @Operation(summary = "结算（定时任务替身）",
            description = "3待结算→4待支付；按订单冻结的计费模板计价，不动钱")
    public Result<Void> settle(@PathVariable Long id) {
        orderService.settle(id);
        return Result.success();
    }

    /**
     * ★ 只有「已支付」能退。「待支付」的时候钱还没扣 —— 那时候要退回的是冻结，
     * 走的是取消 / 挂账。
     */
    @PostMapping("/{id}/refund")
    @Operation(summary = "退款", description = "仅「已支付」可退款；金额必须为正")
    public Result<Void> refund(@PathVariable Long id, @Valid @RequestBody OrderRefundDTO dto) {
        orderService.refund(id, dto.getAmount());
        return Result.success();
    }
}
