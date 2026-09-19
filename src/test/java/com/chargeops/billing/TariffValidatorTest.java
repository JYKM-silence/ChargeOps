package com.chargeops.billing;

import com.chargeops.enumeration.PeriodType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.TariffNotCoveredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TariffValidator} 的测试
 *
 * <p>同样的规矩：不启动 Spring、不连数据库。
 *
 * <p><b>★ 这个类里最重要的一条测试是「8 行种子数据通过」。</b>
 * 它看起来什么都没验证，其实是「相邻相接不算重叠」的回归测试 ——
 * 8 行种子里每一对的 {@code 前一条.end} 都恰好等于 {@code 后一条.start}
 * （08:00:00 接 08:00:00、10:00:00 接 10:00:00 ……）。
 * 只要有人把连续性判断从 {@code ==} 改成别的写法、
 * 或者误以为「相接」也该报重叠，这条测试会第一个挂。
 */
@DisplayName("TariffValidator 计费规则校验")
class TariffValidatorTest {

    private static TariffRuleSnapshot rule(String start, String end, PeriodType type) {
        return TariffRuleSnapshot.of(start, end, type.getCode(),
                new BigDecimal("0.5000"), new BigDecimal("0.3000"));
    }

    /** 与 sql/chargeops.sql 的 8 行种子完全一致 */
    private static List<TariffRuleSnapshot> shenzhenRules() {
        return new ArrayList<>(List.of(
                rule("00:00:00", "08:00:00", PeriodType.VALLEY),
                rule("08:00:00", "10:00:00", PeriodType.FLAT),
                rule("10:00:00", "12:00:00", PeriodType.SHARP_PEAK),
                rule("12:00:00", "14:00:00", PeriodType.FLAT),
                rule("14:00:00", "19:00:00", PeriodType.PEAK),
                rule("19:00:00", "21:00:00", PeriodType.SHARP_PEAK),
                rule("21:00:00", "23:00:00", PeriodType.PEAK),
                rule("23:00:00", "24:00:00", PeriodType.FLAT)));
    }

    private static List<TariffRuleSnapshot> without(String startTime) {
        List<TariffRuleSnapshot> rules = shenzhenRules();
        rules.removeIf(r -> com.chargeops.utils.TimeCodec.toMinuteOfDay(startTime) == r.startMinute());
        return rules;
    }

    // ========================================================================
    // 通过路径
    // ========================================================================

    @Test
    @DisplayName("★ 8 行种子数据通过 —— 这条就是「相邻相接不算重叠」的回归测试")
    void seedRulesPass() {
        assertThatCode(() -> TariffValidator.validate(shenzhenRules())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("乱序输入通过：校验器自己排序，不要求调用方先排好")
    void unorderedRulesPass() {
        List<TariffRuleSnapshot> shuffled = shenzhenRules();
        Collections.shuffle(shuffled, new java.util.Random(20260918L));

        assertThatCode(() -> TariffValidator.validate(shuffled)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("两条规则的极简模板也能通过")
    void minimalRuleSetPasses() {
        assertThatCode(() -> TariffValidator.validate(List.of(
                rule("00:00:00", "07:00:00", PeriodType.VALLEY),
                rule("07:00:00", "24:00:00", PeriodType.FLAT)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单条规则覆盖整天也合法")
    void singleAllDayRulePasses() {
        assertThatCode(() -> TariffValidator.validate(List.of(
                rule("00:00:00", "24:00:00", PeriodType.FLAT)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("同一 period_type 出现多次是正常的（种子里平段出现 4 次）")
    void repeatedPeriodTypesAreFine() {
        assertThatCode(() -> TariffValidator.validate(shenzhenRules())).doesNotThrowAnyException();
    }

    // ========================================================================
    // 缺口
    // ========================================================================

    @Test
    @DisplayName("★ 缺一行：消息点名那个缺口")
    void missingOneRuleNamesTheGap() {
        // 抽掉 12:00-14:00 的平段
        assertThatThrownBy(() -> TariffValidator.validate(without("12:00:00")))
                .isInstanceOf(TariffNotCoveredException.class)
                .hasMessageContaining("未完整覆盖 24 小时")
                .hasMessageContaining("12:00:00-14:00:00");
    }

    @Test
    @DisplayName("★ 两个缺口一次性全报 —— 管理员不用来回两趟")
    void twoGapsReportedTogether() {
        List<TariffRuleSnapshot> rules = without("12:00:00");
        rules.removeIf(r -> r.startMinute() == com.chargeops.utils.TimeCodec.toMinuteOfDay("19:00:00"));

        assertThatThrownBy(() -> TariffValidator.validate(rules))
                .isInstanceOf(TariffNotCoveredException.class)
                .hasMessageContaining("12:00:00-14:00:00")
                .hasMessageContaining("19:00:00-21:00:00");
    }

    @Test
    @DisplayName("★ 首条不从 00:00 开始：这是「没有前一条可以比」的缺口")
    void firstRuleMustStartAtMidnight() {
        // 抽掉 00:00-08:00 的低谷。剩下的规则两两之间全是相接的，
        // 逐对比较发现不了问题 —— 必须单独检查「首条从哪开始」
        assertThatThrownBy(() -> TariffValidator.validate(without("00:00:00")))
                .isInstanceOf(TariffNotCoveredException.class)
                .hasMessageContaining("00:00:00-08:00:00");
    }

    @Test
    @DisplayName("★ 末条不到 24:00：这是「没有后一条可以比」的缺口")
    void lastRuleMustEndAtMidnight() {
        // 抽掉 23:00-24:00 的平段。同样地，逐对比较看不出任何问题
        assertThatThrownBy(() -> TariffValidator.validate(without("23:00:00")))
                .isInstanceOf(TariffNotCoveredException.class)
                .hasMessageContaining("23:00:00-24:00:00");
    }

    @Test
    @DisplayName("只有一条规则且没覆盖整天 → 首尾两个缺口都报出来")
    void singlePartialRuleReportsBothEnds() {
        assertThatThrownBy(() -> TariffValidator.validate(List.of(
                rule("06:00:00", "18:00:00", PeriodType.FLAT))))
                .isInstanceOf(TariffNotCoveredException.class)
                .hasMessageContaining("00:00:00-06:00:00")
                .hasMessageContaining("18:00:00-24:00:00");
    }

    @Test
    @DisplayName("空列表 → ParamErrorException")
    void emptyRulesRejected() {
        assertThatThrownBy(() -> TariffValidator.validate(new ArrayList<>()))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> TariffValidator.validate(null))
                .isInstanceOf(ParamErrorException.class);
    }

    // ========================================================================
    // 重叠
    // ========================================================================

    @Test
    @DisplayName("★ 重叠 → DataConflictException，消息列出冲突的两条")
    void overlappingRulesRejected() {
        List<TariffRuleSnapshot> rules = shenzhenRules();
        // 把 12:00-14:00 的平段往前挪到 11:00，于是它和 10:00-12:00 的尖峰重叠一小时
        rules.removeIf(r -> r.startMinute() == 720);
        rules.add(rule("11:00:00", "14:00:00", PeriodType.FLAT));

        assertThatThrownBy(() -> TariffValidator.validate(rules))
                .isInstanceOf(DataConflictException.class)
                .hasMessageContaining("重叠")
                .hasMessageContaining("尖峰")
                .hasMessageContaining("平段");
    }

    @Test
    @DisplayName("★ 同时有缺口和重叠时，优先报重叠 —— 缺口列表此时没有意义")
    void overlapTakesPrecedenceOverGap() {
        // 10:00-12:00 尖峰 + 11:00-14:00 平段（重叠） + 抽掉 19:00-21:00（缺口）
        List<TariffRuleSnapshot> rules = new ArrayList<>(List.of(
                rule("00:00:00", "08:00:00", PeriodType.VALLEY),
                rule("08:00:00", "10:00:00", PeriodType.FLAT),
                rule("10:00:00", "12:00:00", PeriodType.SHARP_PEAK),
                rule("11:00:00", "14:00:00", PeriodType.FLAT),
                rule("14:00:00", "23:00:00", PeriodType.PEAK),
                rule("23:00:00", "24:00:00", PeriodType.FLAT)));

        // 一旦存在重叠，「缺口」就是覆盖关系被算乱之后的副产品，
        // 先修重叠才有意义
        assertThatThrownBy(() -> TariffValidator.validate(rules))
                .isInstanceOf(DataConflictException.class);
    }

    @Test
    @DisplayName("完全重复的两条规则也算重叠")
    void identicalRulesAreOverlap() {
        assertThatThrownBy(() -> TariffValidator.validate(List.of(
                rule("00:00:00", "12:00:00", PeriodType.FLAT),
                rule("00:00:00", "12:00:00", PeriodType.PEAK),
                rule("12:00:00", "24:00:00", PeriodType.VALLEY))))
                .isInstanceOf(DataConflictException.class);
    }

    // ========================================================================
    // 单行约束 —— 由 TariffRuleSnapshot 的构造器守，不经过 validate
    // ========================================================================

    @Test
    @DisplayName("★ 单行约束在快照的构造器里就守住了，非法快照根本构造不出来")
    void perRowInvariantsAreEnforcedAtConstruction() {
        // 这就是 validate 里没有「起点早于终点」「不越界」「单价非负」的原因 ——
        // 一个 TariffRuleSnapshot 存在，就意味着这些都已经成立
        assertThatThrownBy(() -> rule("10:00:00", "10:00:00", PeriodType.FLAT))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("起点必须早于终点");

        assertThatThrownBy(() -> rule("12:00:00", "10:00:00", PeriodType.FLAT))
                .isInstanceOf(ParamErrorException.class);

        assertThatThrownBy(() -> TariffRuleSnapshot.of("25:00:00", "26:00:00", 3, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(ParamErrorException.class);

        // 24:30:00 也不行 —— 24 点之后只能是次日 0 点。
        // MySQL 的 TIME 能存到 838 小时是它的时长语义，不是这几列想要的
        assertThatThrownBy(() -> TariffRuleSnapshot.of("00:00:00", "24:30:00", 3, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(ParamErrorException.class);

        assertThatThrownBy(() -> new TariffRuleSnapshot(0, 1440, PeriodType.FLAT,
                new BigDecimal("-0.1"), BigDecimal.ONE))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("单价不能为负");

        assertThatThrownBy(() -> new TariffRuleSnapshot(0, 1440, null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("时段类型不能为空");

        assertThatThrownBy(() -> TariffRuleSnapshot.of("00:00:00", "24:00:00", 99,
                BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("未知的计费时段类型");
    }

    @Test
    @DisplayName("24:00:00 解析为 1440，且能原样格式化回去")
    void midnightBoundaryRoundTrips() {
        TariffRuleSnapshot rule = rule("23:00:00", "24:00:00", PeriodType.FLAT);

        assertThat(rule.endMinute()).isEqualTo(1440);
        assertThat(rule.durationMinutes()).isEqualTo(60);
        assertThat(com.chargeops.utils.TimeCodec.toTimeString(rule.endMinute())).isEqualTo("24:00:00");
        // 1440 落在 [start, end) 之外 —— 半开区间下它就是「当日结束」这个位置本身
        assertThat(rule.contains(1440)).isFalse();
        assertThat(rule.contains(1439)).isTrue();
        assertThat(rule.contains(1380)).isTrue();
    }

    @Test
    @DisplayName("不带秒的 HH:mm 也接受（<input type=\"time\"> 提交的格式）")
    void hhmmFormatAccepted() {
        TariffRuleSnapshot rule = TariffRuleSnapshot.of("08:00", "10:00", 3,
                new BigDecimal("0.5"), new BigDecimal("0.3"));

        assertThat(rule.startMinute()).isEqualTo(480);
        assertThat(rule.endMinute()).isEqualTo(600);
        assertThat(rule.describe()).isEqualTo("平段 08:00:00-10:00:00");
    }
}
