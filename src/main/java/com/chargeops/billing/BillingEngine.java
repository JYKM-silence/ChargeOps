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
 * 分时计费引擎 —— 把一个充电窗口按电价时段切开，逐段计价
 *
 * <h2>★ 它是一个静态工具类，不是 {@code @Service}</h2>
 *
 * <p>这不是风格偏好，是为了<b>让某类错误在形状上无法表达</b>：计费最危险的失效
 * 方式是「算到一半去数据库查了下当前电价」，那一刻起同一笔订单重跑会算出不同金额。
 * {@code station_tariff} 表把模板 id 冻结进订单就是为了防这件事，而一个
 * <b>不可能持有 Mapper 的静态类</b>从根上堵死了那条路。顺带的好处是单测不需要
 * 启动 Spring 和数据库，计费正确性可以被真正穷举。
 *
 * <h2>★ 契约：纯函数</h2>
 *
 * <p>相同输入必得相同输出，不读时钟，不读库，不写日志。输出 {@link BillingResult}
 * 与 {@code charging_segment} 表逐列对应。
 *
 * <h2>算法</h2>
 *
 * <p><b>① 归一化</b>：起止时间截到整秒、电量对齐 4 位小数，由 {@link BillingRequest}
 * 的构造器保证，这里不再处理。
 *
 * <p><b>② 绝对时间切点集</b>：把每一天的每条规则边界都换算成<b>绝对时间</b>
 * 丢进一个 {@link TreeSet}，而不按「天 × 规则」双重循环分段。
 * {@code dayStart.plusMinutes(1440)} 天然落到次日零点，所以
 * <b>「跨天」不需要任何特判</b> —— 这是选这个算法的全部理由。
 *
 * <p><b>③ 相邻切点即分段</b>：切点严格递增且去重，跳过任何非正长度的对，
 * <b>零长度分段被结构性排除</b>而不是靠事后 {@code if} 过滤。
 * DDL 把「零长度段撞 {@code uk(order_id, start_time)}」点名为头号坑
 * （订单 10:00 整开始就会产生 {@code [10:00,10:00)}），这保证没有路径能漏出来。
 *
 * <p><b>④ 用区间起点反查规则</b>（要求恰好命中一条）；<b>⑤ 铺满断言</b>
 * 见 {@link #assertTiling}。
 *
 * <h2>★ 电量分摊：望远镜式累进</h2>
 *
 * <p><b>明写的假设：全程恒功率，电量按时间正比分摊。</b>这<b>不符合现实</b>
 * （直流快充 80% SoC 之后降功率），但 P1b 的输入只有窗口 + 总电量 + 单价，
 * 真实曲线要等 P1d 的 {@code charging_telemetry}。所以分摊抽成一个具名私有方法
 * {@link #allocateEnergy}，P1d 落地时只改它一个。
 *
 * <h2>★ 舍入：{@code Σ分段 == 总额} 是恒等式，不是巧合</h2>
 *
 * <p>三个约定，每个都在防一个具体的错：
 * <ol>
 *   <li>{@code amount = energyFee + serviceFee}，两个分量<b>各自先舍入</b>，
 *       绝不是 {@code round2(电量 × (电费单价 + 服务费单价))}。库里三列都存，
 *       对账 SQL {@code WHERE amount <> energy_fee + service_fee} 必须返回 0 行。</li>
 *   <li><b>总额定义为已舍入值的求和</b>：{@code totalAmount = Σ amount}，
 *       系统里不存在第二个独立算出来的总额。</li>
 *   <li><b>调用方不许重算。</b></li>
 * </ol>
 * 消灭的经典错误：三段真值各 {@code 0.005} 元，先加后舍得 {@code 0.02}，
 * 先舍后加得 {@code 0.03}。本引擎产出 {@code 0.03}，且总额定义就是 {@code 0.03}。
 *
 * <p><b>用 {@code HALF_UP} 而不是 {@code HALF_EVEN}</b>：这是<b>用户会拿计算器
 * 逐段核对</b>的账单金额，{@code HALF_UP} 确定、符合「分」的习惯。
 * 舍入模式固定成 {@link #ROUNDING} 一个常量，让这个选择只有一处可见。
 */
public final class BillingEngine {

    /** 单价小数位，对齐 {@code decimal(10,4)} */
    public static final int PRICE_SCALE = 4;

    /** 电量小数位，对齐 {@code decimal(12,4)} */
    public static final int ENERGY_SCALE = 4;

    /** 金额小数位，对齐 {@code decimal(12,2)} */
    public static final int AMOUNT_SCALE = 2;

    /** 见类注释：账单金额是给人核对的，用 HALF_UP 而不是银行家舍入 */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    private static final BigDecimal ZERO_ENERGY = BigDecimal.ZERO.setScale(ENERGY_SCALE);

    private BillingEngine() {
    }

    /**
     * 计算一次充电的费用
     *
     * @param request 计费窗口、总电量、规则集（见 {@link BillingRequest}，
     *                它已保证时间整秒、电量 4 位小数、规则有序）
     * @throws com.chargeops.exception.ParamErrorException 输入本身不合法
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

            // ★ 两个分量各自舍入到分，绝不用 round2(energy × (电费+服务费单价)) ——
            //   真值带半分位时两者差一分，而库里三列都存，对账 SQL 一眼能发现。
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

    // ========================================================================
    // ② 绝对时间切点集
    // ========================================================================

    /**
     * 构造窗口内的全部切点：{@code {S, E} ∪ {每条规则在每一天的边界} ∩ (S, E)}
     *
     * <p>用 {@link TreeSet} 而不是 {@code List}：去重是<b>结构性的需求</b> ——
     * 相邻规则的 {@code prev.end} 和 {@code next.start} 是同一时刻（12:00 会被两条
     * 规则各贡献一次），不去重就会产生零长度的 {@code [12:00, 12:00)} 把分段数算错。
     *
     * <p><b>★ 为什么只需遍历 {@code [S.date, E.date]}：</b>更早的一天能产生的最晚切点
     * 是 {@code dayStart + 1440 分钟 = 次日零点 <= S}，不满足「严格晚于 S」，更晚的一天
     * 同理。所以这个范围恰好覆盖所有可能落在 {@code (S, E)} 内的切点。
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

    /**
     * 只收严格落在 {@code (S, E)} 内部的切点
     *
     * <p>两个端点已在集合里，重复加入无害（TreeSet 去重），但显式的开区间判断
     * 让「窗口外产生的切点全部丢弃」这件事在代码里看得见。
     */
    private static void addCutIfStrictlyInside(TreeSet<LocalDateTime> cuts, LocalDateTime candidate,
                                               LocalDateTime start, LocalDateTime end) {
        if (candidate.isAfter(start) && candidate.isBefore(end)) {
            cuts.add(candidate);
        }
    }

    // ========================================================================
    // ③④ 相邻切点成段 + 用段起点反查规则
    // ========================================================================

    /** 一个待计价的窗口：起止 + 秒数 + 它所适用的那条规则 */
    private record Window(LocalDateTime start, LocalDateTime end, int seconds, TariffRuleSnapshot rule) {
    }

    /**
     * 把排好序的切点两两配成窗口，并为每个窗口定出唯一的规则
     *
     * <p><b>★ 为什么用「区间起点」而不是「中点」反查规则：</b>窗口起点只有两种来源，
     * 两种都指向正确答案 —— <b>等于 S</b> 时 S 已截到整秒（见 {@link BillingRequest}），
     * 分钟数就是它代表的时刻；<b>等于某条规则的边界</b>时，半开区间
     * {@code [start, end)} 下边界必然属于起点等于它的那一条。中点也行，但要处理
     * 「中点恰好也是边界」的一堆情况。
     *
     * <p>一次判断同时覆盖「有缺口」和「有重叠」：命中 0 条即某段时间没人管，
     * 命中 2 条以上即两套价格同时生效，两者都意味着电价没有唯一答案。
     */
    private static List<Window> toWindows(List<LocalDateTime> cuts, List<TariffRuleSnapshot> rules) {
        List<Window> windows = new ArrayList<>(cuts.size() - 1);

        for (int i = 0; i < cuts.size() - 1; i++) {
            LocalDateTime windowStart = cuts.get(i);
            LocalDateTime windowEnd = cuts.get(i + 1);

            // ★ 切点已去重，这里理论上永不成立。留着是把「零长度段」从
            //   「靠 TreeSet 的性质」变成「代码里看得见的判断」：哪天有人把 cuts
            //   换成 List，这里会挡住，而不是让 UK(order_id, start_time) 在结算重跑时才炸。
            if (!windowStart.isBefore(windowEnd)) {
                continue;
            }

            int seconds = (int) Duration.between(windowStart, windowEnd).getSeconds();
            windows.add(new Window(windowStart, windowEnd, seconds, resolveRule(rules, windowStart)));
        }
        return windows;
    }

    /**
     * 反查某一时刻适用的规则，要求<b>恰好一条</b>
     *
     * <p>线性扫描而不是二分：规则数是个位数（种子数据 8 条），线性扫描能顺手数出
     * 命中几条，重叠检测就不用单独写一遍。
     */
    private static TariffRuleSnapshot resolveRule(List<TariffRuleSnapshot> rules, LocalDateTime moment) {
        // 秒被丢弃是安全的：窗口起点要么已截到整秒，要么是整分钟的规则边界，
        // 秒数不影响它落在哪条规则的半开区间里（分钟的粗细就够了）
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

    // ========================================================================
    // ★ 电量分摊
    // ========================================================================

    /**
     * 把总电量按各段时长正比分摊，用<b>望远镜式累进</b>保证「分段电量之和精确等于总电量」
     *
     * <pre>
     *   cum_i   = 前 i 段的秒数累加        （cum_0 = 0，cum_n = 总秒数）
     *   alloc_i = round4(E × cum_i / S总) − round4(E × cum_{i-1} / S总)
     * </pre>
     *
     * <p><b>三条性质由构造保证，不需要事后修正：</b>
     * <ol>
     *   <li><b>{@code Σ alloc_i == E} 精确相等</b> —— 求和时中间项两两相消，
     *       只剩 {@code round4(E × S总 / S总) = round4(E) = E}。最后一步用到
     *       「E 已是 4 位小数」（{@link BillingRequest} 归一化的），
     *       这就是 {@link #assertEnergyConserved} 在守的那条跨文件依赖。</li>
     *   <li><b>{@code alloc_i >= 0}</b> —— {@code cum_i} 单调不减，
     *       且 {@code round4(E × x / S)} 关于 x 单调。</li>
     *   <li><b>每段与公平份额的偏差 ≤ 0.0001 kWh</b> —— 每段都是「两个绝对值的差」，
     *       而不是「在上一次的误差上继续加」，误差不累积。</li>
     * </ol>
     *
     * <p><b>★ 对比「每段 {@code round4(E/n)}、最后一段吃余数」：</b>那个也能凑出和为 E，
     * 但小电量时<b>最后一段会变成负数</b>（7 段各进位 0.00005 就超过了每段的份额）。
     * {@code energy_kwh} 是 append-only 表的 {@code NOT NULL} 列，
     * 一条负电量记录会让「这个月一共充了多少度」少算，比舍入误差严重得多。
     *
     * <p><b>P1d 的接入点：</b>真实电量曲线到位后换成「按遥测点积分 + 按段归属」即可，
     * 方法签名不变，其余代码一行不动。
     */
    private static List<BigDecimal> allocateEnergy(BigDecimal totalEnergy, List<Window> windows) {
        int count = windows.size();
        List<BigDecimal> allocation = new ArrayList<>(count);

        // 累加秒数。总跨度上限 30 天（见 BillingRequest），最大 2592000 秒，
        // int 装得下，且各段秒数均为正，所以 totalSeconds >= count >= 1
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

    // ========================================================================
    // ⑤ 不变式断言
    // ========================================================================

    /**
     * 分段必须首尾严格铺满整个窗口
     *
     * <p>这三条在构造上必然成立（分段就是从相邻切点配出来的），留着是<b>用两行换掉
     * 一整类算术 bug</b>：哪天有人改了切点集的构造方式，只要边界算术出一点偏差，
     * 这里会立刻炸在明确的断言上，而不是让一个「少了最后 3 秒」的分段悄悄进库 ——
     * 那种数据事后只能靠人工比对发现。
     *
     * <p>抛 {@code IllegalStateException} 而不是业务异常：走到这里说明是引擎自己的
     * bug，不是调用方给的数据有问题，两者在前端应该有不同的表现（这个是 500）。
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
     * 分段电量之和必须精确等于输入总电量
     *
     * <p>同上，这是望远镜相消的必然结果。留着是<b>钉住一条跨文件依赖</b>：
     * 相等的前提是 {@code totalEnergyKwh} 已对齐到 4 位小数（{@link BillingRequest}
     * 构造器做的）。如果有人把那个归一化删掉，输入 {@code 10.00005} 会让最后一段
     * 差 0.00005 —— 不报错，只是「订单电量」和「分段电量之和」差一点点。
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
