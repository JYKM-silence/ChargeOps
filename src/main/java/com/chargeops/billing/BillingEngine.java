package com.chargeops.billing;

import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.TariffNotCoveredException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 分时计费引擎：把一个充电窗口按电价时段切开，逐段计价。
 *
 * <p>静态工具类，不是 {@code @Service}，也不持有任何 Mapper —— 计费必须是纯函数
 * （同输入同输出，不读时钟、不读库），才能脱离 Spring 和数据库穷举单测，也顺带
 * 堵死「算到一半去查当前电价」这条路。输出 {@link BillingResult} 与
 * {@code charging_segment} 表逐列对应；起止时间截秒、电量对齐 4 位小数等归一化
 * 由 {@link BillingRequest} 的构造器负责，这里不再处理。
 *
 * <p>切点是「每天每条规则的边界换算成绝对时间」去重后的集合，相邻切点配成段，
 * 再用区间起点反查规则。{@code plusMinutes(1440)} 天然落到次日零点，跨天无需特判。
 *
 * <p>金额契约：{@code totalAmount = Σ segment.amount} 是恒等式 —— 总额定义为各段
 * 金额之和，系统里不存在第二个独立算出来的总额，调用方不许重算。电费与服务费始终
 * 分列，各自舍入到分再相加，绝不合并。舍入用 {@code HALF_UP} 而不是银行家舍入：
 * 账单是给人拿计算器逐段核对的。
 *
 * <p>电量按段时长正比分摊：{@code alloc_i = round4(E × cum_i / S总) −
 * round4(E × cum_{i-1} / S总)}，相邻两项相消，所以 {@code Σ alloc_i} 精确等于总电量。
 * 全程恒功率是明写的假设，真实曲线要等 P1d 的 {@code charging_telemetry}，
 * 分摊收在 {@link #allocateEnergy} 一个方法里，到时只改它。
 */
public final class BillingEngine {

    /** 单价小数位，对齐 {@code decimal(10,4)} */
    public static final int PRICE_SCALE = 4;

    /** 电量小数位，对齐 {@code decimal(12,4)} */
    public static final int ENERGY_SCALE = 4;

    /** 金额小数位，对齐 {@code decimal(12,2)} */
    public static final int AMOUNT_SCALE = 2;

    /** HALF_UP 而非银行家舍入，见类注释 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    private static final BigDecimal ZERO_ENERGY = BigDecimal.ZERO.setScale(ENERGY_SCALE);

    private BillingEngine() {
    }

    /**
     * 计算一次充电的费用。
     *
     * @throws TariffNotCoveredException 某个时刻没有任何规则覆盖它
     * @throws DataConflictException     某个时刻被多条规则同时覆盖
     */
    public static BillingResult calculate(BillingRequest request) {
        List<LocalDateTime> cuts = buildCuts(request);
        List<Window> windows = toWindows(cuts, request.rules());
        List<BigDecimal> energyPerWindow = allocateEnergy(request.totalEnergyKwh(), windows);

        List<BillingSegment> segments = new ArrayList<>(windows.size());
        BigDecimal totalEnergyFee = ZERO_AMOUNT;
        BigDecimal totalServiceFee = ZERO_AMOUNT;
        BigDecimal totalAmount = ZERO_AMOUNT;

        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.get(i);
            TariffRuleSnapshot rule = window.rule();
            BigDecimal energy = energyPerWindow.get(i);

            // 两个分量各自舍入到分，不用 round2(energy × (电费+服务费单价))
            BigDecimal energyFee = energy.multiply(rule.energyPrice()).setScale(AMOUNT_SCALE, ROUNDING);
            BigDecimal serviceFee = energy.multiply(rule.servicePrice()).setScale(AMOUNT_SCALE, ROUNDING);
            // 两个 2 位小数相加仍是 2 位小数，不再舍入
            BigDecimal amount = energyFee.add(serviceFee);

            segments.add(new BillingSegment(
                    i + 1,                      // seq 从 1 开始，对齐 DDL 注释
                    rule.periodType(),
                    window.start(),
                    window.end(),
                    window.seconds(),
                    energy,
                    rule.energyPrice(),
                    rule.servicePrice(),
                    energyFee,
                    serviceFee,
                    amount));

            totalEnergyFee = totalEnergyFee.add(energyFee);
            totalServiceFee = totalServiceFee.add(serviceFee);
            totalAmount = totalAmount.add(amount);
        }

        assertTiling(segments, request);
        assertEnergyConserved(segments, request);

        return new BillingResult(
                request.startTime(),
                request.endTime(),
                segments,
                request.totalEnergyKwh(),
                totalEnergyFee,
                totalServiceFee,
                totalAmount);
    }

    // ---------- 切点 ----------

    /**
     * 构造窗口内的全部切点：{@code {S, E} ∪ {每条规则在每一天的边界} ∩ (S, E)}
     *
     * <p>用 {@link TreeSet} 去重是需求而不是顺手：相邻规则交界处（12:00）会被两条规则
     * 各贡献一次，不去重就产生零长度的 {@code [12:00, 12:00)}，把分段数算错。
     */
    private static List<LocalDateTime> buildCuts(BillingRequest request) {
        LocalDateTime start = request.startTime();
        LocalDateTime end = request.endTime();

        TreeSet<LocalDateTime> cuts = new TreeSet<>();
        cuts.add(start);
        cuts.add(end);

        LocalDate lastDay = end.toLocalDate();
        for (LocalDate day = start.toLocalDate(); !day.isAfter(lastDay); day = day.plusDays(1)) {
            LocalDateTime dayStart = day.atStartOfDay();
            for (TariffRuleSnapshot rule : request.rules()) {
                // plusMinutes(1440) 得到的就是次日零点，跨天无需特判
                addCutIfStrictlyInside(cuts, dayStart.plusMinutes(rule.startMinute()), start, end);
                addCutIfStrictlyInside(cuts, dayStart.plusMinutes(rule.endMinute()), start, end);
            }
        }
        return new ArrayList<>(cuts);
    }

    private static void addCutIfStrictlyInside(TreeSet<LocalDateTime> cuts, LocalDateTime candidate,
                                               LocalDateTime start, LocalDateTime end) {
        if (candidate.isAfter(start) && candidate.isBefore(end)) {
            cuts.add(candidate);
        }
    }

    // ---------- 分段 ----------

    /** 一个待计价的窗口：起止 + 秒数 + 它所适用的那条规则 */
    private record Window(LocalDateTime start, LocalDateTime end, int seconds, TariffRuleSnapshot rule) {
    }

    /**
     * 把排好序的切点两两配成窗口，并为每个窗口定出唯一的规则。
     *
     * <p>用区间起点而不是中点反查：起点只可能等于 S（已截到整秒）或某条规则的边界，
     * 半开区间下两种都必然命中正确的那一条。命中 0 条是有缺口、命中 2 条以上是重叠，
     * 一次判断同时覆盖两者。
     */
    private static List<Window> toWindows(List<LocalDateTime> cuts, List<TariffRuleSnapshot> rules) {
        List<Window> windows = new ArrayList<>(cuts.size() - 1);

        for (int i = 0; i < cuts.size() - 1; i++) {
            LocalDateTime windowStart = cuts.get(i);
            LocalDateTime windowEnd = cuts.get(i + 1);

            // 切点已去重，理论上到不了这里。留着是把「零长度段」从 TreeSet 的性质
            // 变成看得见的判断 —— 否则哪天 cuts 换成 List，会一路漏到撞 uk(order_id, start_time)
            if (!windowStart.isBefore(windowEnd)) {
                continue;
            }

            int seconds = (int) Duration.between(windowStart, windowEnd).getSeconds();
            windows.add(new Window(windowStart, windowEnd, seconds, resolveRule(rules, windowStart)));
        }
        return windows;
    }

    /**
     * 反查某一时刻适用的规则，要求恰好一条。
     *
     * <p>线性扫描而不是二分：规则数是个位数，顺手就能数出命中几条，重叠检测不用另写一遍。
     */
    private static TariffRuleSnapshot resolveRule(List<TariffRuleSnapshot> rules, LocalDateTime moment) {
        // 秒被丢弃是安全的：窗口起点要么已截到整秒，要么是整分钟的规则边界
        int minute = moment.getHour() * 60 + moment.getMinute();

        TariffRuleSnapshot matched = null;
        for (TariffRuleSnapshot rule : rules) {
            if (rule.contains(minute)) {
                if (matched != null) {
                    throw new DataConflictException(
                            "计费时段重叠，" + moment + " 同时被两条规则覆盖："
                                    + matched.describe() + " 与 " + rule.describe());
                }
                matched = rule;
            }
        }
        if (matched == null) {
            throw new TariffNotCoveredException(
                    "计费时段存在缺口，" + moment + " 没有任何计费规则覆盖它");
        }
        return matched;
    }

    // ---------- 电量分摊 ----------

    /**
     * 把总电量按各段时长正比分摊。cum_i 是前 i 段的秒数累加，则
     * {@code alloc_i = round4(E × cum_i / S总) − round4(E × cum_{i-1} / S总)}：
     * 相邻两项在求和时相消，所以分段电量之和精确等于总电量。
     *
     * <p>不用「每段 round4(E/n)、最后一段吃余数」那种写法：小电量时最后一段会算出负值，
     * 而 {@code energy_kwh} 是 append-only 表的 {@code NOT NULL} 列。
     */
    private static List<BigDecimal> allocateEnergy(BigDecimal totalEnergy, List<Window> windows) {
        int count = windows.size();
        List<BigDecimal> allocation = new ArrayList<>(count);

        // 总跨度上限 30 天 = 2592000 秒，int 装得下；各段秒数为正，故 totalSeconds >= count >= 1
        int[] cumulativeSeconds = new int[count];
        int running = 0;
        for (int i = 0; i < count; i++) {
            running += windows.get(i).seconds();
            cumulativeSeconds[i] = running;
        }
        int totalSeconds = running;

        BigDecimal divisor = BigDecimal.valueOf(totalSeconds);
        BigDecimal previous = ZERO_ENERGY;
        for (int i = 0; i < count; i++) {
            BigDecimal cumulative = totalEnergy
                    .multiply(BigDecimal.valueOf(cumulativeSeconds[i]))
                    .divide(divisor, ENERGY_SCALE, ROUNDING);
            allocation.add(cumulative.subtract(previous));
            previous = cumulative;
        }
        return allocation;
    }

    // ---------- 不变式断言 ----------

    /**
     * 分段必须首尾严格铺满整个窗口。构造上必然成立，留着挡边界算术的偏差 ——
     * 一个「少了最后 3 秒」的分段会悄悄进库，事后只能靠人工比对发现。
     *
     * <p>抛 {@code IllegalStateException} 而不是业务异常：走到这里是引擎自己的 bug，
     * 不是调用方给的数据有问题，前端表现应该是 500。
     */
    private static void assertTiling(List<BillingSegment> segments, BillingRequest request) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("计费引擎未产出任何分段，窗口："
                    + request.startTime() + " ~ " + request.endTime());
        }
        BillingSegment first = segments.get(0);
        BillingSegment last = segments.get(segments.size() - 1);
        if (!first.startTime().equals(request.startTime()) || !last.endTime().equals(request.endTime())) {
            throw new IllegalStateException("分段未铺满计费窗口：窗口 "
                    + request.startTime() + " ~ " + request.endTime()
                    + "，分段实际覆盖 " + first.startTime() + " ~ " + last.endTime());
        }
        for (int i = 0; i < segments.size() - 1; i++) {
            if (!segments.get(i).endTime().equals(segments.get(i + 1).startTime())) {
                throw new IllegalStateException("分段之间存在断裂或重叠，第 " + (i + 1)
                        + " 段结束于 " + segments.get(i).endTime()
                        + "，第 " + (i + 2) + " 段开始于 " + segments.get(i + 1).startTime());
            }
        }
    }

    /**
     * 分段电量之和必须精确等于输入总电量。相等的前提是 {@code totalEnergyKwh} 已对齐到
     * 4 位小数（{@link BillingRequest} 构造器做的），那个归一化删掉这里就会差一点点，
     * 而且不报错。
     */
    private static void assertEnergyConserved(List<BillingSegment> segments, BillingRequest request) {
        BigDecimal sum = ZERO_ENERGY;
        for (BillingSegment segment : segments) {
            sum = sum.add(segment.energyKwh());
        }
        if (sum.compareTo(request.totalEnergyKwh()) != 0) {
            throw new IllegalStateException("分段电量之和 " + sum
                    + " 与输入总电量 " + request.totalEnergyKwh() + " 不一致");
        }
    }
}
