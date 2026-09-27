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
 * 一次计费的输入：计费窗口 + 总电量 + 该窗口适用的规则集
 *
 * <p>只有这四样东西。<b>没有订单、没有枪、没有会员、没有 Mapper</b> ——
 * 刻意的：计费引擎必须是纯函数，才能在没有基础设施的情况下被单测穷举。
 *
 * <h2>★ 构造器做了三件归一化，下游不必再想这三件事</h2>
 *
 * <ol>
 *   <li><b>时间截断到整秒</b>。不截的话：两个各 1.6 秒的分段分别截成 1 秒，
 *       合并后的 3.2 秒截成 3 秒 —— {@code Σ segment.duration_seconds} 就和
 *       {@code charging_order.duration_seconds} 对不上（1+1=2 ≠ 3）。
 *       这种「加起来差一秒」看起来像数据损坏，排查却要一路追到毫秒。</li>
 *   <li><b>电量对齐到 4 位小数</b>，与 {@code decimal(12,4)} 列宽一致。多出来的精度
 *       只可能来自「把瓦时当成千瓦时」这类单位换算。</li>
 *   <li><b>规则按起点排序并做不可变拷贝</b>，于是「规则乱序」在类型层面就不存在了。</li>
 * </ol>
 *
 * <h2>★ 为什么要设跨度上限</h2>
 *
 * <p>切点集大小是 {@code 天数 × 规则数}：30 天 × 8 条 = 240 个无所谓，但一个写成
 * {@code 9999-12-31} 的结束时间会生成上千万个 {@code LocalDateTime} 然后 OOM ——
 * 报出来是堆栈溢出，真正原因却是一个填错的字段。30 天对「一次充电」宽到离谱
 * （现实中几小时），超过它一定是单位换算错误或哨兵值泄漏，早失败早好。
 *
 * <h2>★ 空规则集不在这里拦</h2>
 *
 * <p>刻意不写「规则不能为空」的检查，让它流到引擎的规则解析那一步 —— 那里的消息会
 * 点名<b>具体哪个时间段没有规则覆盖</b>，比「规则列表为空」精确得多，
 * 也少一处可能和引擎判断不一致的地方。
 */
public record BillingRequest(
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal totalEnergyKwh,
        List<TariffRuleSnapshot> rules) {

    /** 与 {@code charging_segment.energy_kwh} 的 {@code decimal(12,4)} 对齐 */
    private static final int ENERGY_SCALE = 4;

    /** 见类注释：超过这个跨度几乎只可能来自坏数据，而代价是 OOM */
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
            // 电量为 0 合法（插上就拔），只有负值不合法。负电量来自设备上报异常或
            // 结算重跑的重复扣减，它会让金额为负 —— append-only 表里一条负金额记录
            // 比一次响亮的失败严重得多
            throw new ParamErrorException("充电电量不能为负：" + totalEnergyKwh);
        }

        LocalDateTime normalizedStart = startTime.truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime normalizedEnd = endTime.truncatedTo(ChronoUnit.SECONDS);

        // ★ 先后关系必须在截秒<b>之后</b>判，不能拿原始值判：
        //   10:00:00.500 ~ 10:00:00.900 满足「结束晚于开始」，截到整秒后两者都是
        //   10:00:00。截秒前放行的话，这个请求会走到引擎、产出 0 个分段，
        //   最后炸在「分段未铺满窗口」的断言上（HTTP 500）——
        //   而它明明是个可以指名的入参问题。校验顺序本身就是正确性的一部分。
        if (!normalizedEnd.isAfter(normalizedStart)) {
            // 整个窗口零长度也非法：DDL 的 uk(order_id, start_time) 要求分段算法
            // 跳过零长度段，在入口挡住才不会出现「算出 0 段」要下游各自处理的情况
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
        // ★ 只按 startMinute 排序。起点相同的规则保留任意相对顺序没关系：
        //   起点相同必然重叠（半开区间下不可能都合法），而重叠在规则解析那一步被拒绝，
        //   两种顺序都会走到同一个异常
        sorted.sort(Comparator.comparingInt(TariffRuleSnapshot::startMinute));
        rules = List.copyOf(sorted);
    }
}
