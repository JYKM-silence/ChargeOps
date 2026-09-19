package com.chargeops.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次计费的结果：分段明细 + 四个总额
 *
 * <h2>★ 四个总额都是「已舍入分量的求和」，绝不重算</h2>
 *
 * <pre>
 *   totalEnergyFee  = Σ segment.energyFee
 *   totalServiceFee = Σ segment.serviceFee
 *   totalAmount     = Σ segment.amount      （= totalEnergyFee + totalServiceFee）
 * </pre>
 *
 * <p>这看起来是废话，但它消灭的是一整类错误。反例：
 * 三段真值各 {@code 0.005} 元，<b>先加后舍</b>得 {@code round2(0.015) = 0.02}，
 * <b>先舍后加</b>得 {@code 0.03}。引擎产出 {@code 0.03}，
 * 而它的总额<b>定义就是</b> {@code 0.03} —— 因为总额是从已经舍入的分段加出来的，
 * 不存在「另一个数」。所以 {@code Σ分段 == 总额} 不是「测出来刚好相等」，
 * 而是<b>恒等式</b>：只要它们是同一份数据，就不可能不等。
 *
 * <p><b>给 P1c 的一句话契约：</b>
 * {@code charging_order.total_amount} 写 {@link #totalAmount()}，
 * <b>永远不要</b>写「总电量 × 综合单价」。后者的值会偶尔和分段之和对不上，
 * 而那种差异出现在生产环境的财务报表上时，已经没人能还原它当初是怎么算的了。
 *
 * <h2>★ totalEnergyKwh 恒等于所有分段电量之和</h2>
 *
 * <p>这不是巧合，是电量分摊算法「望远镜式相消」的直接结果 ——
 * 见 {@link BillingEngine} 里分摊那一段的说明。所以
 * {@code charging_order.energy_kwh} 写哪个都一样，写这个字段即可。
 *
 * @param startTime       计费窗口起点（整秒，等于请求里的起点截断后）
 * @param endTime         计费窗口终点（整秒，不含）
 * @param segments        分段明细，按时间升序，{@code seq} 从 1 开始，首尾严格铺满整个窗口
 * @param totalEnergyKwh  总电量，保留 4 位小数
 * @param totalEnergyFee  电费合计，保留 2 位小数
 * @param totalServiceFee 服务费合计，保留 2 位小数
 * @param totalAmount     应付合计，保留 2 位小数
 */
public record BillingResult(
        LocalDateTime startTime,
        LocalDateTime endTime,
        List<BillingSegment> segments,
        BigDecimal totalEnergyKwh,
        BigDecimal totalEnergyFee,
        BigDecimal totalServiceFee,
        BigDecimal totalAmount) {

    /**
     * 紧凑构造器：让 {@code segments} 不可变
     *
     * <p>这个结果会被 P1c 交给批量插入、也可能被打印进日志或存成快照。
     * 如果列表还能被外面改，就出现了「已结算的分段和记录里的分段不是同一份」
     * 这种无法调试的状态。
     */
    public BillingResult {
        segments = List.copyOf(segments);
    }

    /** 分段数。{@code charging_segment} 的行数，也是 P1c 批量插入的批大小 */
    public int segmentCount() {
        return segments.size();
    }
}
