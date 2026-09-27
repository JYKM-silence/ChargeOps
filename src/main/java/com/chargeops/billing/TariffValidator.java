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
 * 校验一组计费规则是否完整、无缝地铺满 24 小时。
 *
 * <p>在写入时调用（新建/修改模板的时段规则），不是计费时 —— 缺口一旦进库会影响所有在途订单。
 *
 * <p>{@link BillingEngine} 刻意不调用这个类：引擎只要求「这个窗口被覆盖」，
 * 这里要求「整个 24 小时无缝覆盖」，塞进引擎会让它拒绝掉一批其实算得出来的输入。
 * 单行约束已由 {@link TariffRuleSnapshot} 的构造器守住，这里只做跨行的那件事。
 *
 * <p>排序后逐对比较 {@code 前一条.end} 与 {@code 后一条.start}，{@code ==} 才合法：
 * 一条判断同时排除缺口和重叠。
 *
 * <p>一次报全部而不是报第一个：管理员在编辑一张 8 行的表，只报第一个缺口的话，
 * 每个来回都是一次完整的表单填写。但重叠优先 —— 存在重叠时「差集」不代表真实缺失。
 */
public final class TariffValidator {

    private TariffValidator() {
    }

    /**
     * 校验规则集能否无缝铺满 24 小时，不通过直接抛异常。入参顺序任意，内部会排序后比较。
     *
     * @throws ParamErrorException       规则为空
     * @throws DataConflictException     存在重叠，消息里列出全部重叠处
     * @throws TariffNotCoveredException 存在缺口，消息里列出全部缺口
     */
    public static void validate(List<TariffRuleSnapshot> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new ParamErrorException("计费规则不能为空，必须完整覆盖 00:00:00 ~ 24:00:00");
        }

        // 自己排序而不是要求调用方排好序：校验器是最后一道关，不该有前置条件
        List<TariffRuleSnapshot> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(TariffRuleSnapshot::startMinute));

        List<String> gaps = new ArrayList<>();
        List<String> overlaps = new ArrayList<>();

        // 首条必须从 00:00 开始，否则 00:00 到首条起点之间是空的。
        // 这一条相邻比较发现不了 —— 它没有「前一条」
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
        }

        // 同理，末条必须到 24:00 结束 —— 它没有「后一条」
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
