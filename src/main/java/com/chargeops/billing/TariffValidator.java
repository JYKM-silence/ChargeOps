package com.chargeops.billing;

import com.chargeops.constant.MessageConstant;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.TariffNotCoveredException;
import com.chargeops.utils.TimeCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 校验一组计费规则是否完整、无缝地铺满 24 小时
 *
 * <p>在<b>写入时</b>调用（新建/修改模板的时段规则），而不是在计费时 ——
 * 缺口一旦进了库，受影响的是所有用这个模板的在途订单。
 * 挡在写入口是唯一能保证它不扩散的位置。
 *
 * <p>{@link BillingEngine} <b>刻意不调用</b>这个类。它俩守的是不同的东西：
 * 引擎只要求「这个窗口被覆盖」（窗口可能只占一天里的 3 小时，
 * 一个只覆盖 {@code 00:00-12:00} 的规则集对它是完全够用的），
 * 而这里要求「整个 24 小时被无缝覆盖」。把这项检查塞进引擎，
 * 会让引擎拒绝掉一批它其实算得出来的输入。
 *
 * <h2>★ 职责边界：单行约束不在这里</h2>
 *
 * <p>「起点早于终点」「不越界」「单价非负」这些<b>每行自己就能判断</b>的规则，
 * 已经由 {@link TariffRuleSnapshot} 的构造器守住了 —— 一个非法的快照
 * 根本构造不出来。所以这个方法只做<b>跨行</b>的那一件事，
 * 用完这条边界之后，它短得几乎不需要注释。
 *
 * <h2>★ 连续性用一个 {@code ==} 同时证明了两件事</h2>
 *
 * <p>把规则按起点排序后，逐对比较 {@code 前一条.end == 后一条.start}：
 *
 * <ul>
 *   <li><b>{@code <} 就是缺口</b> —— 这段区间没人管，那一刻算不出电价</li>
 *   <li><b>{@code >} 就是重叠</b> —— 两套价格同时生效，引擎无法确定用哪个</li>
 *   <li><b>{@code ==} 才是合法</b> —— 而且它天然把「前一条的终点恰好是
 *       后一条的起点」判为合法，这正是 DDL 要求的半开区间语义
 *       （{@code 08:00-10:00} 和 {@code 10:00-12:00} 相接而不重叠）</li>
 * </ul>
 *
 * <p><b>你写不出一个既满足「相邻首尾相等」又有缺口的集合</b> ——
 * 这不是「顺便也检查了缺口」，而是这一条判断本身就等价于
 * 「无缺口 且 无重叠 且 铺满 24 小时」。
 *
 * <h2>★ 一次报全部，而不是报第一个</h2>
 *
 * <p>管理员在编辑一张 8 行的表。只报第一个缺口的话，他补完提交、
 * 再被打回、再补一次 —— 每个来回都是一次完整的表单填写。
 * 所以扫描完所有相邻对、把所有缺口攒起来一次抛出：
 * {@code "计费时段未完整覆盖 24 小时，缺失：12:00:00-14:00:00；16:00:00-17:00:00"}。
 *
 * <p><b>但重叠优先于缺口</b>：一旦存在重叠，「缺口」列表就是没有意义的
 * （覆盖关系已经乱了，算出来的差集不代表真实缺失）。所以两类问题分别收集，
 * 重叠存在时只报重叠 —— 那才是必须首先修掉的那个。
 */
public final class TariffValidator {

    private TariffValidator() {
    }

    /**
     * 校验规则集能否无缝铺满 24 小时，不通过直接抛异常
     *
     * @param rules 待校验的规则，<b>顺序任意</b> —— 内部会排序后比较，
     *              所以「管理员在界面上拖动了行序」不会影响校验结果
     * @throws ParamErrorException       规则为空
     * @throws DataConflictException     存在重叠（消息里列出全部重叠处）
     * @throws TariffNotCoveredException 存在缺口（消息里列出全部缺口）
     */
    public static void validate(List<TariffRuleSnapshot> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new ParamErrorException("计费规则不能为空，必须完整覆盖 00:00:00 ~ 24:00:00");
        }

        // 排序而不是要求调用方排好序：校验器是最后一道关，
        // 它不应该有一个「你得先按顺序给我」的前置条件 ——
        // 那个条件一旦没被满足，这里就会把合法数据判成非法
        List<TariffRuleSnapshot> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(TariffRuleSnapshot::startMinute));

        List<String> gaps = new ArrayList<>();
        List<String> overlaps = new ArrayList<>();

        // 首条必须从 00:00 开始 —— 否则 00:00 到首条起点之间是空的。
        // 这一条不能靠相邻比较发现：没有「前一条」和它比
        TariffRuleSnapshot first = sorted.get(0);
        if (first.startMinute() != 0) {
            gaps.add("00:00:00-" + TimeCodec.toTimeString(first.startMinute()));
        }

        for (int i = 1; i < sorted.size(); i++) {
            TariffRuleSnapshot previous = sorted.get(i - 1);
            TariffRuleSnapshot current = sorted.get(i);
            int previousEnd = previous.endMinute();
            int currentStart = current.startMinute();

            if (previousEnd < currentStart) {
                gaps.add(TimeCodec.toTimeString(previousEnd) + "-" + TimeCodec.toTimeString(currentStart));
            } else if (previousEnd > currentStart) {
                overlaps.add(previous.describe() + " 与 " + current.describe()
                        + " 在 " + TimeCodec.toTimeString(currentStart)
                        + "-" + TimeCodec.toTimeString(previousEnd) + " 重叠");
            }
            // previousEnd == currentStart：正好相接，合法。不需要 else 分支
        }

        // 末条必须到 24:00 结束。同样不能靠相邻比较发现 —— 它没有「后一条」
        TariffRuleSnapshot last = sorted.get(sorted.size() - 1);
        if (last.endMinute() != TimeCodec.MINUTES_PER_DAY) {
            gaps.add(TimeCodec.toTimeString(last.endMinute()) + "-24:00:00");
        }

        if (!overlaps.isEmpty()) {
            throw new DataConflictException(MessageConstant.TARIFF_OVERLAP + "：" + String.join("；", overlaps));
        }
        if (!gaps.isEmpty()) {
            throw new TariffNotCoveredException(
                    MessageConstant.TARIFF_NOT_COVER_24H + "，缺失：" + String.join("；", gaps));
        }
    }
}
