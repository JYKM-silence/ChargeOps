package com.chargeops.job;

import com.chargeops.entity.ChargingOrder;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.properties.SchedulingProperties;
import com.chargeops.service.ChargingOrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电订单的兜底扫描任务：把卡在 {@code 4 待支付} 太久的订单标成 {@code 6 支付失败}
 *
 * <p> 只调 {@code markPayFailed}，绝不重试 {@code pay()}：{@code pay()} 刻意不查
 * 「车主是否被禁用」，自动重试等于一次未经同意的扣款。
 *
 * <p> 本方法不能标 {@code @Transactional}：一轮扫几百笔放在同一个事务里，会在 {@code charging_order}
 * 上累积行锁直到方法结束，而这期间真实的用户在支付、在建单；每笔 {@code markPayFailed} 自成一笔事务，
 * 第 37 笔失败不回滚前 36 笔。
 *
 * <p> {@code orderService} 必须是容器注入的 Bean 而不是 {@code this} —— 改成自己类里的私有方法，
 * 代理就不生效、每笔挤回外层事务，且不会有任何报错。循环里的 try/catch 也是刻意的
 * （异常是代理回滚之后才抛回来的）。多实例不需要分布式锁：{@code WHERE ... AND status = 4} 本身就是 CAS。
 */
@Component
// 没有 havingValue：用 Spring 的宽松布尔解析。写成 havingValue = "true" 的话
// "TRUE"/"1"/"yes" 都不匹配。matchIfMissing 默认 false，即配置里没写这个键就不跑任务。
@ConditionalOnProperty(name = "chargeops.scheduling.enabled")
@RequiredArgsConstructor
@Slf4j
public class ChargingOrderSweepJob {

    /**
     * 写进 {@code charging_order.remark} 的失败原因。不放 {@code MessageConstant}（那个类管的是能透给
     * 前端的异常消息），也不带具体分钟数：那个值来自配置、会变，不能固化进历史数据。
     */
    private static final String STALE_REASON =
            "超过支付时限仍未支付，系统标记为支付失败；充值后可在订单详情重新支付";

    private final ChargingOrderMapper orderMapper;

    /** 从容器注入，不是 {@code this} —— 理由见类注释 */
    private final ChargingOrderService orderService;

    private final SchedulingProperties scheduling;

    /**
     * {@code fixedDelay} 而不是 {@code cron}：前者从上一次执行结束开始计时，一次慢扫描不会和下一次重叠。
     * 占位符故意不给默认值，配置缺失时启动失败而不是静默用一个硬编码的间隔。
     */
    @Scheduled(fixedDelayString = "${chargeops.scheduled.pay-timeout-scan-ms}")
    public void sweepStalePendingPay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = now.minusMinutes(scheduling.getPayTimeoutMinutes());

        List<ChargingOrder> stale = orderMapper.listStalePendingPay(
                before, scheduling.getPayTimeoutBatchSize());

        // 空结果不打日志：每 5 分钟醒一次，绝大多数轮次都是 0 笔，刷屏会把真正的异常挤出日志轮转窗口
        if (stale.isEmpty()) {
            return;
        }

        log.info("支付失败兜底扫描：待支付超过 {} 分钟的有 {} 笔，开始处理",
                scheduling.getPayTimeoutMinutes(), stale.size());

        int called = 0;
        for (ChargingOrder order : stale) {
            try {
                orderService.markPayFailed(order.getId(), STALE_REASON);
                called++;
            } catch (Exception e) {
                // 单笔失败不影响其余。不重试 —— 下一轮扫描会再看到它
                log.error("支付失败兜底扫描：标记订单失败，跳过这一笔：orderId={}, orderNo={}",
                        order.getId(), order.getOrderNo(), e);
            }
        }

        // 不能写成「成功标记 N 笔」：markPayFailed 对「订单已经不在 4 上」（用户刚付掉）
        // 也返回正常、影响 0 行，算在这里面。准确的说法是「调用未抛异常」。
        log.info("支付失败兜底扫描完成：取件 {} 笔，已提交处理 {} 笔（含被用户抢先支付的）",
                stale.size(), called);
    }
}
