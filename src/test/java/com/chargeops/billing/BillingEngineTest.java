package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.TariffNotCoveredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BillingEngine} 的穷举测试
 *
 * <h2>★ 为什么这个测试类不启动 Spring、不连数据库</h2>
 *
 * <p>规则集全部在代码里构造。引擎是纯函数，所以它的正确性可以被
 * <b>真正证明</b>，而不是「跑几个例子看着对」——
 * 这正是 {@code BillingEngine} 被写成静态工具类的回报。
 *
 * <p>如果哪天这个类需要 {@code @SpringBootTest} 才能跑，
 * 那说明有人往引擎里加了 I/O，那本身就是必须立刻修掉的退化。
 *
 * <h2>规则集</h2>
 *
 * <p>默认用 {@link #shenzhenRules()}，与 {@code sql/chargeops.sql} 的种子数据一致：
 * 尖峰 4h / 高峰 7h / 平段 5h / 低谷 8h，合计 24h。
 * 「非种子规则集」的用例用别处构造的小规则集。
 */
@DisplayName("BillingEngine 计费引擎")
class BillingEngineTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 18);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final LocalDate D3 = D1.plusDays(2);
    private static final LocalDate D4 = D1.plusDays(3);

    // ========================================================================
    // 规则集构造
    // ========================================================================

    private static TariffRuleSnapshot rule(String start, String end, PeriodType type,
                                           String energyPrice, String servicePrice) {
        return TariffRuleSnapshot.of(start, end, type.getCode(),
                new BigDecimal(energyPrice), new BigDecimal(servicePrice));
    }

    /** 与 sql/chargeops.sql 里 {@code tariff_template_id=1} 的 8 行完全一致 */
    private static List<TariffRuleSnapshot> shenzhenRules() {
        return new ArrayList<>(List.of(
                rule("00:00:00", "08:00:00", PeriodType.VALLEY, "0.3188", "0.3000"),
                rule("08:00:00", "10:00:00", PeriodType.FLAT, "0.7188", "0.3000"),
                rule("10:00:00", "12:00:00", PeriodType.SHARP_PEAK, "1.2188", "0.4000"),
                rule("12:00:00", "14:00:00", PeriodType.FLAT, "0.7188", "0.3000"),
                rule("14:00:00", "19:00:00", PeriodType.PEAK, "1.0188", "0.3500"),
                rule("19:00:00", "21:00:00", PeriodType.SHARP_PEAK, "1.2188", "0.4000"),
                rule("21:00:00", "23:00:00", PeriodType.PEAK, "1.0188", "0.3500"),
                rule("23:00:00", "24:00:00", PeriodType.FLAT, "0.7188", "0.3000")));
    }

    private static BillingRequest request(LocalDateTime start, LocalDateTime end, String energyKwh) {
        return new BillingRequest(start, end, new BigDecimal(energyKwh), shenzhenRules());
    }

    private static BillingRequest request(LocalDateTime start, LocalDateTime end, String energyKwh,
                                          List<TariffRuleSnapshot> rules) {
        return new BillingRequest(start, end, new BigDecimal(energyKwh), rules);
    }

    /** 用种子规则集跑一次计费 —— 绝大多数用例走这条 */
    private static BillingResult calc(LocalDateTime start, LocalDateTime end, String energyKwh) {
        return BillingEngine.calculate(request(start, end, energyKwh));
    }

    /** 用指定规则集跑一次计费 */
    private static BillingResult calc(LocalDateTime start, LocalDateTime end, String energyKwh,
                                      List<TariffRuleSnapshot> rules) {
        return BillingEngine.calculate(request(start, end, energyKwh, rules));
    }

    private static LocalDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute);
    }

    /** 总秒数 —— 高频断言，抽出来 */
    private static int totalSeconds(BillingResult result) {
        return result.segments().stream().mapToInt(BillingSegment::durationSeconds).sum();
    }

    /**
     * ★ {@code uk(order_id, start_time)} 的数据前提：所有分段的起点互不相同
     *
     * <p>这条断言直接对着一等公民级别的约束写 —— 只要它成立，
     * 结算重跑时批量插入就不可能撞唯一键。
     */
    private static void assertDistinctStartTimes(BillingResult result) {
        long distinct = result.segments().stream().map(BillingSegment::startTime).distinct().count();
        assertThat(distinct)
                .as("分段起点必须互不相同，否则批量插入会撞 uk(order_id, start_time)")
                .isEqualTo(result.segmentCount());
    }

    /** 金额三列的关系是恒等式 —— 对账 SQL 会这么查 */
    private static void assertAmountIsConsistent(BillingSegment segment) {
        assertThat(segment.amount())
                .as("第 %d 段：amount 必须等于 energyFee + serviceFee", segment.seq())
                .isEqualByComparingTo(segment.energyFee().add(segment.serviceFee()));
    }

    // ========================================================================
    // 边界与结构
    // ========================================================================

    @Nested
    @DisplayName("切分结构")
    class Structure {

        @Test
        @DisplayName("单段落在低谷：30 度电，9.564 舍成 9.56（真正用到 HALF_UP）")
        void singleSegmentInValley() {
            BillingResult result = calc(at(D1, 1, 0), at(D1, 2, 0), "30");

            assertThat(result.segmentCount()).isEqualTo(1);
            BillingSegment segment = result.segments().get(0);
            assertThat(segment.seq()).isEqualTo(1);
            assertThat(segment.periodType()).isEqualTo(PeriodType.VALLEY);
            assertThat(segment.durationSeconds()).isEqualTo(3600);
            assertThat(segment.energyKwh()).isEqualByComparingTo("30.0000");
            assertThat(segment.energyPrice()).isEqualByComparingTo("0.3188");

            // 30 × 0.3188 = 9.5640 —— 第三位是 4，HALF_UP 舍去
            assertThat(segment.energyFee()).isEqualByComparingTo("9.56");
            // 30 × 0.3000 = 9.0000
            assertThat(segment.serviceFee()).isEqualByComparingTo("9.00");
            assertThat(segment.amount()).isEqualByComparingTo("18.56");

            assertThat(result.totalEnergyFee()).isEqualByComparingTo("9.56");
            assertThat(result.totalServiceFee()).isEqualByComparingTo("9.00");
            assertThat(result.totalAmount()).isEqualByComparingTo("18.56");
        }

        @Test
        @DisplayName("★ 起止恰好落在时段边界：10:00-12:00 恰好 1 段，没有 [10:00,10:00) 幽灵段")
        void exactlyOnBothBoundaries() {
            BillingResult result = calc(at(D1, 10, 0), at(D1, 12, 0), "60");

            // 若切点集没去重，12:00 会被「10:00-12:00 的 end」和「12:00-14:00 的 start」
            // 各贡献一次，产生一个零长度的 [12:00,12:00)，段数就变成 2
            assertThat(result.segmentCount()).isEqualTo(1);
            assertThat(result.segments().get(0).periodType()).isEqualTo(PeriodType.SHARP_PEAK);
            assertThat(result.segments().get(0).startTime()).isEqualTo(at(D1, 10, 0));
            assertThat(result.segments().get(0).endTime()).isEqualTo(at(D1, 12, 0));
            assertThat(totalSeconds(result)).isEqualTo(7200);

            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("起点落在边界上：10:00 属于它开始的那一段，不属于上一段")
        void startExactlyOnBoundary() {
            // 半开区间 [start, end)：10:00 属于 10:00-12:00 的尖峰，不属于 08:00-10:00 的平段。
            // 写反了的话这两条断言会各挂一条
            BillingResult startingAtTen = calc(at(D1, 10, 0), at(D1, 11, 0), "10");
            assertThat(startingAtTen.segments().get(0).periodType()).isEqualTo(PeriodType.SHARP_PEAK);

            // 对照组：09:59 属于平段
            BillingResult startingAtNineFiftyNine =
                    calc(at(D1, 9, 59), at(D1, 10, 1), "10");
            assertThat(startingAtNineFiftyNine.segments().get(0).periodType()).isEqualTo(PeriodType.FLAT);
        }

        @Test
        @DisplayName("跨两段：09:30-11:30 = 平段 30min + 尖峰 90min，电量按 1:3 分")
        void acrossTwoPeriods() {
            BillingResult result = calc(at(D1, 9, 30), at(D1, 11, 30), "12");

            assertThat(result.segmentCount()).isEqualTo(2);

            BillingSegment flat = result.segments().get(0);
            assertThat(flat.periodType()).isEqualTo(PeriodType.FLAT);
            assertThat(flat.startTime()).isEqualTo(at(D1, 9, 30));
            assertThat(flat.endTime()).isEqualTo(at(D1, 10, 0));
            assertThat(flat.durationSeconds()).isEqualTo(1800);

            BillingSegment sharp = result.segments().get(1);
            assertThat(sharp.seq()).isEqualTo(2);
            assertThat(sharp.periodType()).isEqualTo(PeriodType.SHARP_PEAK);
            assertThat(sharp.startTime()).isEqualTo(at(D1, 10, 0));
            assertThat(sharp.endTime()).isEqualTo(at(D1, 11, 30));
            assertThat(sharp.durationSeconds()).isEqualTo(5400);

            // 12 度按 1800:5400 = 1:3 分
            assertThat(flat.energyKwh()).isEqualByComparingTo("3.0000");
            assertThat(sharp.energyKwh()).isEqualByComparingTo("9.0000");

            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("★ 跨天：22:00 D1 - 02:00 D2 切成 3 段，日期正确翻到次日")
        void acrossMidnight() {
            BillingResult result = calc(at(D1, 22, 0), at(D2, 2, 0), "16");

            assertThat(result.segmentCount()).isEqualTo(3);

            // 22:00 落在 21:00-23:00 的高峰里
            assertThat(result.segments().get(0).periodType()).isEqualTo(PeriodType.PEAK);
            assertThat(result.segments().get(0).startTime()).isEqualTo(at(D1, 22, 0));
            assertThat(result.segments().get(0).endTime()).isEqualTo(at(D1, 23, 0));

            // 23:00 跨进了 23:00-24:00 的平段 —— 它的终点是次日零点，
            // 这个「24:00:00」的 end 换算成绝对时间就是 D2 的 00:00
            assertThat(result.segments().get(1).periodType()).isEqualTo(PeriodType.FLAT);
            assertThat(result.segments().get(1).startTime()).isEqualTo(at(D1, 23, 0));
            assertThat(result.segments().get(1).endTime()).isEqualTo(at(D2, 0, 0));
            assertThat(result.segments().get(1).durationSeconds()).isEqualTo(3600);

            // ★ 第三段的日期必须是 D2，不是 D1。
            //   如果切点集的日期循环范围写错（只遍历 start 那一天），
            //   这一段会缺失，段数变成 2 —— 而且不会有任何异常
            assertThat(result.segments().get(2).startTime()).isEqualTo(at(D2, 0, 0));
            assertThat(result.segments().get(2).endTime()).isEqualTo(at(D2, 2, 0));
            assertThat(totalSeconds(result)).isEqualTo(4 * 3600);

            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("整天：00:00 D1 - 00:00 D2 = 8 段，时段序列与种子一致")
        void wholeDay() {
            BillingResult result = calc(at(D1, 0, 0), at(D2, 0, 0), "100");

            assertThat(result.segmentCount()).isEqualTo(8);
            assertThat(result.segments())
                    .extracting(BillingSegment::periodType)
                    .containsExactly(
                            PeriodType.VALLEY, PeriodType.FLAT, PeriodType.SHARP_PEAK, PeriodType.FLAT,
                            PeriodType.PEAK, PeriodType.SHARP_PEAK, PeriodType.PEAK, PeriodType.FLAT);

            assertThat(totalSeconds(result)).isEqualTo(86400);

            // ★ 同一 period_type 出现多次是常态（平段出现 3 次）。
            //   分段是按<b>时间</b>切的，不是按类型合并的 ——
            //   合并会让 uk(order_id, start_time) 失去意义，
            //   而且 08:00-10:00 和 23:00-24:00 虽然都是平段，
            //   却是两段互不相邻的时间
            //
            //   ★ 顺带记一段已经修掉的历史：DDL 注释和
            //     tariff_template.description 原先都写「尖峰 4h / 高峰 4h / 平段 8h / 低谷 8h」，
            //     而实际的 8 行是尖峰 4h、高峰 7h（14:00-19:00 是 5 小时不是 4）、
            //     平段 5h、低谷 8h。四者合计都是 24h，所以覆盖性校验查不出这个错。
            //     2026-09-18 已按这 8 行改正（文案 + 库里的 description）。
            //
            //   ★ 这件事值得记一笔的原因是：<b>本测试类和 TariffValidatorTest
            //     都是照这 8 行写的，不是照那句描述写的</b> ——
            //     所以描述写错的时候，17 条校验测试一条都不会挂。
            //     数据才是事实来源，注释和 description 只是它的说明；
            //     两者冲突时改说明，不要改数据
            assertThat(result.segments())
                    .extracting(BillingSegment::periodType)
                    .filteredOn(PeriodType.FLAT::equals)
                    .hasSize(3);

            assertThat(result.segments().get(0).seq()).isEqualTo(1);
            assertThat(result.segments().get(7).seq()).isEqualTo(8);
            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("★ 结束在次日零点：23:00-00:00 恰好 1 段 3600 秒")
        void endingAtNextMidnight() {
            BillingResult result = calc(at(D1, 23, 0), at(D2, 0, 0), "5");

            // 这里的陷阱和 10:00-12:00 那条同源：次日 00:00 是
            // 「23:00-24:00 的 end」和「00:00-08:00 的 start」两个身份，
            // 去重没做好就会切出一个零长度段
            assertThat(result.segmentCount()).isEqualTo(1);
            assertThat(result.segments().get(0).periodType()).isEqualTo(PeriodType.FLAT);
            assertThat(result.segments().get(0).durationSeconds()).isEqualTo(3600);
            assertThat(totalSeconds(result)).isEqualTo(3600);

            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("多日：3 天 = 24 段，总时长 259200 秒")
        void multipleDays() {
            BillingResult result = calc(at(D1, 0, 0), at(D4, 0, 0), "300");

            assertThat(result.segmentCount()).isEqualTo(24);
            assertThat(totalSeconds(result)).isEqualTo(3 * 86400);
            assertThat(result.segments().get(0).startTime()).isEqualTo(at(D1, 0, 0));
            assertThat(result.segments().get(23).endTime()).isEqualTo(at(D4, 0, 0));
            assertThat(result.segments().get(23).seq()).isEqualTo(24);

            assertDistinctStartTimes(result);
        }

        @Test
        @DisplayName("单段订单：窗口完全落在一条规则内部")
        void singleSegmentOrder() {
            BillingResult result = calc(at(D1, 15, 17), at(D1, 15, 47), "8");

            assertThat(result.segmentCount()).isEqualTo(1);
            assertThat(result.segments().get(0).periodType()).isEqualTo(PeriodType.PEAK);
            assertThat(result.segments().get(0).durationSeconds()).isEqualTo(1800);
        }
    }

    // ========================================================================
    // 规则解析的失败路径
    // ========================================================================

    @Nested
    @DisplayName("规则解析")

    class RuleResolution {

        @Test
        @DisplayName("★ 规则有缺口 → TariffNotCoveredException，消息点名缺口所在的时刻")
        void gapInRules() {
            // 故意挖掉 12:00-14:00
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "08:00:00", PeriodType.VALLEY, "0.3188", "0.3000"),
                    rule("08:00:00", "12:00:00", PeriodType.FLAT, "0.7188", "0.3000"),
                    rule("14:00:00", "24:00:00", PeriodType.PEAK, "1.0188", "0.3500")));

            assertThatThrownBy(() -> calc(at(D1, 11, 0), at(D1, 13, 0), "10", rules))
                    .isInstanceOf(TariffNotCoveredException.class)
                    .hasMessageContaining("缺口")
                    .hasMessageContaining("12:00");
        }

        @Test
        @DisplayName("★ 规则重叠 → DataConflictException，消息列出冲突的两条")
        void overlappingRules() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "12:00:00", PeriodType.FLAT, "0.7188", "0.3000"),
                    rule("10:00:00", "24:00:00", PeriodType.SHARP_PEAK, "1.2188", "0.4000")));

            assertThatThrownBy(() -> calc(at(D1, 10, 0), at(D1, 11, 0), "10", rules))
                    .isInstanceOf(DataConflictException.class)
                    .hasMessageContaining("重叠")
                    .hasMessageContaining("平段")
                    .hasMessageContaining("尖峰");
        }

        @Test
        @DisplayName("★ 规则输入乱序：结果与有序输入逐字段一致")
        void unorderedRulesGiveIdenticalResult() {
            BillingResult ordered = calc(at(D1, 9, 0), at(D1, 13, 0), "24");

            // BillingRequest 的构造器会排序，所以调用方乱序传入也不影响结果。
            // 这条测试守的是「引擎不依赖调用方给的顺序」——
            // 而调用方是 Service，它读库的顺序取决于有没有 ORDER BY
            List<TariffRuleSnapshot> shuffled = shenzhenRules();
            Collections.shuffle(shuffled, new java.util.Random(20260918L));
            BillingResult fromShuffled = calc(at(D1, 9, 0), at(D1, 13, 0), "24", shuffled);

            assertThat(fromShuffled.segments()).isEqualTo(ordered.segments());
            assertThat(fromShuffled.totalAmount()).isEqualByComparingTo(ordered.totalAmount());
        }

        @Test
        @DisplayName("非种子规则集：两条规则的极简模板也能算")
        void customRuleSet() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "07:00:00", PeriodType.VALLEY, "0.2000", "0.1000"),
                    rule("07:00:00", "24:00:00", PeriodType.FLAT, "0.5000", "0.2000")));

            BillingResult result = calc(at(D1, 6, 0), at(D1, 8, 0), "4", rules);

            assertThat(result.segmentCount()).isEqualTo(2);
            assertThat(result.segments().get(0).energyKwh()).isEqualByComparingTo("2.0000");
            assertThat(result.segments().get(1).energyKwh()).isEqualByComparingTo("2.0000");
            assertThat(result.segments().get(0).energyFee()).isEqualByComparingTo("0.40");
            assertThat(result.segments().get(1).energyFee()).isEqualByComparingTo("1.00");
        }
    }

    // ========================================================================
    // 舍入契约
    // ========================================================================

    @Nested
    @DisplayName("舍入契约")

    class RoundingContract {

        /** 三段等长、单价 0.01，让每段的真值恰好是 0.005 —— 半分位 */
        private List<TariffRuleSnapshot> halfCentRules() {
            return new ArrayList<>(List.of(
                    rule("00:00:00", "01:00:00", PeriodType.SHARP_PEAK, "0.01", "0"),
                    rule("01:00:00", "02:00:00", PeriodType.PEAK, "0.01", "0"),
                    rule("02:00:00", "03:00:00", PeriodType.FLAT, "0.01", "0"),
                    rule("03:00:00", "24:00:00", PeriodType.VALLEY, "0.01", "0")));
        }

        @Test
        @DisplayName("★ 三段真值各 0.005 → 每段 0.01、总额 0.03（先加后舍的实现会得 0.02）")
        void halfCentPerSegment() {
            BillingResult result =
                    calc(at(D1, 0, 0), at(D1, 3, 0), "1.5", halfCentRules());

            assertThat(result.segmentCount()).isEqualTo(3);
            // 三段各 0.5 度，0.5000 × 0.0100 = 0.005000，HALF_UP 到分 → 0.01
            assertThat(result.segments())
                    .extracting(BillingSegment::amount)
                    .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("0.01"));

            // ★ 这条是整个舍入契约的核心断言。
            //   错误实现 round2(Σ(0.005, 0.005, 0.005)) = round2(0.015) = 0.02，会挂在这里
            assertThat(result.totalAmount()).isEqualByComparingTo("0.03");
            assertThat(result.totalEnergyFee()).isEqualByComparingTo("0.03");
            assertThat(result.totalServiceFee()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("★ Σ分段金额 == totalAmount，逐分相等")
        void segmentSumEqualsTotal() {
            BillingResult result = calc(at(D1, 6, 30), at(D1, 22, 45), "87.6543");

            BigDecimal sum = result.segments().stream()
                    .map(BillingSegment::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 总额的定义就是「已舍入分段之和」，所以这里必然相等 ——
            // 断言它是在守「不存在第二个独立算出来的总额」这条设计约定
            assertThat(result.totalAmount()).isEqualByComparingTo(sum);
            assertThat(result.segments()).allSatisfy(BillingEngineTest::assertAmountIsConsistent);
        }

        @Test
        @DisplayName("★ 每段 amount == energyFee + serviceFee（对账 SQL 的判据）")
        void amountEqualsFeesSum() {
            BillingResult result = calc(at(D1, 9, 30), at(D1, 20, 30), "55.5");

            assertThat(result.segmentCount()).isGreaterThan(1);
            assertThat(result.segments()).allSatisfy(BillingEngineTest::assertAmountIsConsistent);
        }

        @Test
        @DisplayName("小数位：金额 2 位、电量与单价 4 位")
        void scales() {
            BillingResult result = calc(at(D1, 9, 30), at(D1, 11, 30), "12");

            assertThat(result.totalAmount().scale()).isEqualTo(2);
            assertThat(result.totalEnergyFee().scale()).isEqualTo(2);
            assertThat(result.totalServiceFee().scale()).isEqualTo(2);

            for (BillingSegment segment : result.segments()) {
                assertThat(segment.energyFee().scale()).as("第 %d 段电费", segment.seq()).isEqualTo(2);
                assertThat(segment.serviceFee().scale()).as("第 %d 段服务费", segment.seq()).isEqualTo(2);
                assertThat(segment.amount().scale()).as("第 %d 段金额", segment.seq()).isEqualTo(2);
                assertThat(segment.energyKwh().scale()).as("第 %d 段电量", segment.seq()).isEqualTo(4);
                assertThat(segment.energyPrice().scale()).as("第 %d 段电费单价", segment.seq()).isEqualTo(4);
                assertThat(segment.servicePrice().scale()).as("第 %d 段服务费单价", segment.seq()).isEqualTo(4);
            }
        }

        @Test
        @DisplayName("服务费单价为 0：只收电费")
        void zeroServicePrice() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "24:00:00", PeriodType.FLAT, "0.7188", "0")));

            BillingResult result = calc(at(D1, 0, 0), at(D1, 1, 0), "10", rules);

            assertThat(result.totalServiceFee()).isEqualByComparingTo("0.00");
            assertThat(result.totalAmount()).isEqualByComparingTo(result.totalEnergyFee());
            assertThat(result.totalAmount()).isEqualByComparingTo("7.19");
        }

        @Test
        @DisplayName("电费单价为 0：只收服务费")
        void zeroEnergyPrice() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "24:00:00", PeriodType.FLAT, "0", "0.3000")));

            BillingResult result = calc(at(D1, 0, 0), at(D1, 1, 0), "10", rules);

            assertThat(result.totalEnergyFee()).isEqualByComparingTo("0.00");
            assertThat(result.totalAmount()).isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("两个单价都为 0：全零金额，分段结构仍然完整")
        void bothPricesZero() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "24:00:00", PeriodType.FLAT, "0", "0")));

            BillingResult result = calc(at(D1, 0, 0), at(D1, 1, 0), "10", rules);

            assertThat(result.totalAmount()).isEqualByComparingTo("0.00");
            assertThat(result.totalAmount().scale()).isEqualTo(2);
            assertThat(result.segmentCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // 电量分摊
    // ========================================================================

    @Nested
    @DisplayName("电量分摊")

    class EnergyAllocation {

        @Test
        @DisplayName("★ Σ分段电量 == 输入电量，精确相等（10 度三等分，除不尽）")
        void energyIsExactlyConserved() {
            List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                    rule("00:00:00", "01:00:00", PeriodType.SHARP_PEAK, "1.0000", "0"),
                    rule("01:00:00", "02:00:00", PeriodType.PEAK, "1.0000", "0"),
                    rule("02:00:00", "03:00:00", PeriodType.FLAT, "1.0000", "0"),
                    rule("03:00:00", "24:00:00", PeriodType.VALLEY, "1.0000", "0")));

            BillingResult result = calc(at(D1, 0, 0), at(D1, 3, 0), "10", rules);

            assertThat(result.segmentCount()).isEqualTo(3);
            // 10 / 3 = 3.3333... 除不尽。望远镜式累进让中间项相消，
            // 所以和精确等于 10.0000 —— 而不是 9.9999 或 10.0001
            assertThat(result.segments())
                    .extracting(BillingSegment::energyKwh)
                    .containsExactly(
                            new BigDecimal("3.3333"),
                            new BigDecimal("3.3334"),
                            new BigDecimal("3.3333"));

            BigDecimal sum = result.segments().stream()
                    .map(BillingSegment::energyKwh)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(result.totalEnergyKwh());
            assertThat(sum).isEqualByComparingTo("10.0000");
        }

        @Test
        @DisplayName("★ 每段电量非负，且与公平份额的偏差不超过 0.0001")
        void allocationIsFairAndNonNegative() {
            BillingResult result = calc(at(D1, 0, 0), at(D1, 23, 59), "7.7777");

            BigDecimal totalEnergy = result.totalEnergyKwh();
            int totalSecs = totalSeconds(result);

            for (BillingSegment segment : result.segments()) {
                assertThat(segment.energyKwh().signum())
                        .as("第 %d 段电量不能为负", segment.seq())
                        .isNotNegative();

                // 公平份额 = 总电量 × 本段秒数 / 总秒数
                BigDecimal fairShare = totalEnergy
                        .multiply(BigDecimal.valueOf(segment.durationSeconds()))
                        .divide(BigDecimal.valueOf(totalSecs), 10, java.math.RoundingMode.HALF_UP);
                assertThat(segment.energyKwh().subtract(fairShare).abs())
                        .as("第 %d 段与公平份额的偏差", segment.seq())
                        .isLessThanOrEqualTo(new BigDecimal("0.0001"));
            }
        }

        @Test
        @DisplayName("★ 极小电量摊到多段：没有一段为负，且和精确相等")
        void tinyEnergyAcrossManySegmentsStaysNonNegative() {
            // 7 段、总电量 0.0004 度。
            //
            // ★ 这正是「每段各自 round4(E/n)、最后一段吃余数」那种写法会崩的输入：
            //   E/7 = 0.0000571... 每段各自 HALF_UP 到 0.0001，
            //   前 6 段加起来 0.0006 已经超过了总电量 0.0004，
            //   最后一段只能吐出 -0.0002 来配平 —— 一个负电量。
            //
            //   望远镜式累进不会有这个问题：cum_i 单调不减，
            //   而 round4 又是单调的，所以差值必然非负。
            List<TariffRuleSnapshot> rules = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                rules.add(rule(String.format("%02d:00:00", i), String.format("%02d:00:00", i + 1),
                        PeriodType.FLAT, "0.0100", "0"));
            }

            // 00:00 到 07:00 → 正好压在 6 条内部边界上，切成 7 段
            BillingResult result = calc(at(D1, 0, 0), at(D1, 7, 0), "0.0004", rules);

            assertThat(result.segmentCount()).isEqualTo(7);
            assertThat(result.segments())
                    .as("任何一段都不能是负电量 —— energy_kwh 是 append-only 表的 NOT NULL 列")
                    .extracting(BillingSegment::energyKwh)
                    .allSatisfy(energy -> assertThat(energy.signum()).isNotNegative());

            BigDecimal sum = result.segments().stream()
                    .map(BillingSegment::energyKwh)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("0.0004");
        }

        @Test
        @DisplayName("电量为 0（插上就拔）：分段结构保持完整，金额全为 0.00")
        void zeroEnergy() {
            BillingResult result = calc(at(D1, 9, 30), at(D1, 11, 30), "0");

            // 结构不受电量影响 —— 这一点很重要：P1c 会先按时间切分段，
            // 电量为 0 的订单仍然要留下完整的分段记录，否则
            // 「这笔订单为什么没有分段」会变成一个需要专门排查的问题
            assertThat(result.segmentCount()).isEqualTo(2);
            assertThat(totalSeconds(result)).isEqualTo(7200);
            assertThat(result.segments())
                    .extracting(BillingSegment::energyKwh)
                    .allSatisfy(energy -> assertThat(energy).isEqualByComparingTo("0.0000"));
            assertThat(result.totalAmount()).isEqualByComparingTo("0.00");
            assertThat(result.totalAmount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("输入电量小数位超过 4 位：先归一化，Σ分段仍然精确等于归一化后的值")
        void overPreciseEnergyIsNormalized() {
            BillingResult result = calc(at(D1, 1, 0), at(D1, 2, 0), "10.00005");

            assertThat(result.totalEnergyKwh()).isEqualByComparingTo("10.0001");
            assertThat(result.totalEnergyKwh().scale()).isEqualTo(4);
            assertThat(result.segments().get(0).energyKwh()).isEqualByComparingTo("10.0001");
        }
    }

    // ========================================================================
    // 输入契约
    // ========================================================================

    @Nested
    @DisplayName("输入契约")

    class InputContract {

        @Test
        @DisplayName("结束不晚于开始 → ParamErrorException")
        void endNotAfterStart() {
            assertThatThrownBy(() -> calc(at(D1, 10, 0), at(D1, 10, 0), "1"))
                    .isInstanceOf(ParamErrorException.class)
                    .hasMessageContaining("结束时间必须晚于开始时间");

            assertThatThrownBy(() -> calc(at(D1, 12, 0), at(D1, 10, 0), "1"))
                    .isInstanceOf(ParamErrorException.class);
        }

        @Test
        @DisplayName("★ 截秒后长度为 0：10:00:00.500 ~ 10:00:00.900 必须报入参错，不能是 500")
        void subSecondWindowCollapsesToZero() {
            // 原始值满足「结束晚于开始」，截到整秒后两者都是 10:00:00。
            // 如果先后关系在截秒之前判，这个请求会一路走到引擎产出 0 个分段，
            // 最后炸在「分段未铺满窗口」的 IllegalStateException 上（HTTP 500）
            assertThatThrownBy(() -> {
                LocalDateTime start = LocalDateTime.of(2026, 9, 18, 10, 0, 0, 500_000_000);
                LocalDateTime end = LocalDateTime.of(2026, 9, 18, 10, 0, 0, 900_000_000);
                calc(start, end, "1");
            })
                    .isInstanceOf(ParamErrorException.class)
                    .hasMessageContaining("截断到整秒");
        }

        @Test
        @DisplayName("负电量 → ParamErrorException")
        void negativeEnergy() {
            assertThatThrownBy(() -> calc(at(D1, 10, 0), at(D1, 11, 0), "-1"))
                    .isInstanceOf(ParamErrorException.class)
                    .hasMessageContaining("电量不能为负");
        }

        @Test
        @DisplayName("null 入参 → ParamErrorException")
        void nullArguments() {
            assertThatThrownBy(() -> new BillingRequest(null, at(D1, 11, 0), BigDecimal.ONE, shenzhenRules()))
                    .isInstanceOf(ParamErrorException.class);
            assertThatThrownBy(() -> new BillingRequest(at(D1, 10, 0), null, BigDecimal.ONE, shenzhenRules()))
                    .isInstanceOf(ParamErrorException.class);
            assertThatThrownBy(() -> new BillingRequest(at(D1, 10, 0), at(D1, 11, 0), null, shenzhenRules()))
                    .isInstanceOf(ParamErrorException.class);
            assertThatThrownBy(() -> new BillingRequest(at(D1, 10, 0), at(D1, 11, 0), BigDecimal.ONE, null))
                    .isInstanceOf(ParamErrorException.class);
        }

        @Test
        @DisplayName("★ 秒截断：10:00:00.500 的起点，结果里是 10:00:00")
        void secondsAreTruncated() {
            BillingResult result = calc(
                    LocalDateTime.of(2026, 9, 18, 10, 0, 0, 500_000_000),
                    LocalDateTime.of(2026, 9, 18, 11, 0, 0, 700_000_000),
                    "10");

            assertThat(result.startTime()).isEqualTo(at(D1, 10, 0));
            assertThat(result.endTime()).isEqualTo(at(D1, 11, 0));
            // 3600 秒而不是 3600.2 秒 —— 不截的话
            // Σ segment.duration_seconds 会对不上 charging_order.duration_seconds
            assertThat(result.segments().get(0).durationSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("★ 跨度过大（30 天以上）→ ParamErrorException，而不是 OOM")
        void oversizedSpan() {
            assertThatThrownBy(() -> calc(at(D1, 0, 0), at(D1.plusDays(400), 0, 0), "1"))
                    .isInstanceOf(ParamErrorException.class)
                    .hasMessageContaining("计费跨度超过");

            // 边界内：正好 30 天可以算
            BillingResult result = calc(at(D1, 0, 0), at(D1.plusDays(30), 0, 0), "100");
            assertThat(result.segmentCount()).isEqualTo(240);
        }

        @Test
        @DisplayName("规则列表不可变：结果里的列表改不动，也不会被外部改到")
        void rulesAreImmutable() {
            List<TariffRuleSnapshot> mutable = shenzhenRules();
            BillingRequest req = request(at(D1, 10, 0), at(D1, 11, 0), "10", mutable);

            mutable.clear();

            // 构造器做了 List.copyOf，所以清空原列表不影响已构造的请求 ——
            // 否则「算到一半规则被改了」这种事真的会发生
            assertThat(req.rules()).hasSize(8);
            assertThat(req.rules()).isEqualTo(shenzhenRules());
            assertThatThrownBy(() -> req.rules().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("分段结果不可变")
        void segmentsAreImmutable() {
            BillingResult result = calc(at(D1, 10, 0), at(D1, 11, 0), "10");

            assertThatThrownBy(() -> result.segments().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ========================================================================
    // 与 DDL 的对齐
    // ========================================================================

    @Nested
    @DisplayName("与 charging_segment 表的对齐")

    class DdlAlignment {

        @Test
        @DisplayName("★ 分段是半开区间且首尾相接，严格铺满窗口")
        void segmentsTileTheWindowExactly() {
            LocalDateTime start = at(D1, 9, 17);
            LocalDateTime end = at(D2, 3, 43);
            BillingResult result = calc(start, end, "123.4567");

            assertThat(result.segments().get(0).startTime()).isEqualTo(start);
            assertThat(result.segments().get(result.segmentCount() - 1).endTime()).isEqualTo(end);

            for (int i = 0; i < result.segmentCount() - 1; i++) {
                assertThat(result.segments().get(i).endTime())
                        .as("第 %d 段的终点必须等于第 %d 段的起点", i + 1, i + 2)
                        .isEqualTo(result.segments().get(i + 1).startTime());
            }

            // Σ duration_seconds 必须等于窗口的真实秒数 ——
            // 这个是 charging_order.duration_seconds 的值，对不上就是数据不一致
            long expected = java.time.Duration.between(start, end).getSeconds();
            assertThat(totalSeconds(result)).isEqualTo((int) expected);
        }

        @Test
        @DisplayName("★ 没有任何零长度分段（DDL 点名的头号坑）")
        void noZeroLengthSegments() {
            // ★ 起止都压在时段边界上，这是最容易切出 [t, t) 的输入。
            //   种子规则集的边界是 00/08/10/12/14/19/21/23/24 点，
            //   下面每一对都拿边界当端点，覆盖了「起点即边界」「终点即边界」
            //   「两端都是边界」「终点落在次日零点」四种情况。
            //
            //   零长度段的后果不是「多一行」，而是撞 uk(order_id, start_time)：
            //   分段批量插入会失败，而重跑结算时同样会失败 ——
            //   一笔订单从此永远结算不了。
            LocalDateTime[][] windows = {
                    {at(D1, 10, 0), at(D1, 12, 0)},   // 两端都是边界
                    {at(D1, 23, 0), at(D2, 0, 0)},    // 终点是 24:00:00，即次日零点
                    {at(D1, 0, 0), at(D2, 0, 0)},     // 整整一天，首个边界即起点
                    {at(D1, 8, 0), at(D1, 10, 0)},    // 起止都是边界，跨 0 段
                    {at(D1, 19, 0), at(D1, 21, 0)},
                    {at(D1, 10, 0), at(D2, 10, 0)},   // 跨天且两端同刻
                    {at(D1, 21, 0), at(D1, 23, 0)},
            };

            for (LocalDateTime[] window : windows) {
                BillingResult result = calc(window[0], window[1], "10");

                assertThat(result.segments())
                        .as("窗口 %s ~ %s 不应产生零长度分段", window[0], window[1])
                        .allSatisfy(segment -> assertThat(segment.durationSeconds()).isPositive());
                assertDistinctStartTimes(result);
                // 结构上的复核：Σ 秒数必须等于窗口真实秒数。
                // 多切一个零长度段不会改变这个和，所以它挡不住零长度段 ——
                // 两者是互补的断言，都要有
                assertThat(totalSeconds(result))
                        .as("窗口 %s ~ %s 的分段总时长", window[0], window[1])
                        .isEqualTo((int) java.time.Duration.between(window[0], window[1]).getSeconds());
            }
        }

        @Test
        @DisplayName("分段序号从 1 连续递增到 n")
        void seqIsOneBasedAndContiguous() {
            BillingResult result = calc(at(D1, 0, 0), at(D3, 12, 30), "200");

            for (int i = 0; i < result.segmentCount(); i++) {
                assertThat(result.segments().get(i).seq()).isEqualTo(i + 1);
            }
        }
    }
}
