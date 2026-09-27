package com.chargeops.billing;

import com.chargeops.exception.ParamErrorException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次计费的输入：计费窗口 + 总电量 + 该窗口适用的规则集。
 *
 * <p>只有这四样，没有订单、没有枪、没有会员、没有 Mapper —— 计费引擎要能脱离
 * 基础设施单测。
 *
 * <p>构造器做三件归一化，下游不必再想：时间截到整秒、电量对齐 4 位小数、规则按
 * 起点排序后做不可变拷贝。截秒不做的话，{@code Σ segment.duration_seconds} 会和
 * {@code charging_order.duration_seconds} 差一秒（两个 1.6 秒的分段各截成 1 秒，
 * 而合并后的 3.2 秒截成 3 秒）。
 *
 * <p>跨度上限 30 天：切点集大小是「天数 × 规则数」，一个 {@code 9999-12-31} 的结束时间会 OOM。
 *
 * <p>空规则集刻意不在这里拦，让它流到引擎的规则解析那一步 —— 那里的消息会指名
 * 哪个时间段没有规则覆盖，比「规则列表为空」精确。
 */
public record BillingRequest(
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal totalEnergyKwh,
        List<TariffRuleSnapshot> rules) {

    /** 与 {@code charging_segment.energy_kwh} 的 {@code decimal(12,4)} 对齐 */
    private static final int ENERGY_SCALE = 4;

    /** 超过这个跨度几乎只可能来自坏数据，而代价是 OOM */
    private static final int MAX_SPAN_DAYS = 30;

    public BillingRequest {
        if (startTime == null || endTime == null) {
            throw new ParamErrorException("计费起止时间不能为空");
        }
        if (totalEnergyKwh == null) {
            throw new ParamErrorException("充电电量不能为空");
        }
        if (rules == null) {
            throw new ParamErrorException("计费规则不能为空");
        }
        if (totalEnergyKwh.signum() < 0) {
            // 电量为 0 合法（插上就拔），负值来自设备上报异常或结算重复扣减
            throw new ParamErrorException("充电电量不能为负：" + totalEnergyKwh);
        }

        LocalDateTime normalizedStart = startTime.truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime normalizedEnd = endTime.truncatedTo(ChronoUnit.SECONDS);

        // 先后关系必须在截秒之后判：10:00:00.500 ~ 10:00:00.900 截完是零长度窗口，
        // 截秒前放行的话它会一路走到引擎产出 0 段，最后炸在「分段未铺满窗口」的断言上（500），
        // 而它明明是个可以指名的入参问题。
        // 零长度窗口本身也非法：DDL 的 uk(order_id, start_time) 要求分段算法跳过零长度段
        if (!normalizedEnd.isAfter(normalizedStart)) {
            throw new ParamErrorException("计费结束时间必须晚于开始时间：" + startTime + " ~ " + endTime
                    + "（截断到整秒后为 " + normalizedStart + " ~ " + normalizedEnd + "）");
        }
        if (Duration.between(normalizedStart, normalizedEnd).toDays() > MAX_SPAN_DAYS) {
            throw new ParamErrorException("计费跨度超过 " + MAX_SPAN_DAYS + " 天，疑似时间字段填错："
                    + normalizedStart + " ~ " + normalizedEnd);
        }

        startTime = normalizedStart;
        endTime = normalizedEnd;
        totalEnergyKwh = totalEnergyKwh.setScale(ENERGY_SCALE, RoundingMode.HALF_UP);

        List<TariffRuleSnapshot> sorted = new ArrayList<>(rules);
        // 只按 startMinute 排序就够了：起点相同的规则必然重叠，会被规则解析那一步拒绝
        sorted.sort(Comparator.comparingInt(TariffRuleSnapshot::startMinute));
        rules = List.copyOf(sorted);
    }
}
