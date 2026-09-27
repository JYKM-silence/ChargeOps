package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计费规则 —— 一条尖峰/平/谷时段（对应 {@code tariff_rule} 表）
 *
 * <h2>★ 一、这张表的实体<b>绝不能</b>标 {@code @AutoFill}</h2>
 *
 * <p>和 {@link Connector} 同一个原因：本表<b>只有 {@code create_time} /
 * {@code update_time} 两列，没有 {@code create_user} / {@code update_user}</b>。
 * {@code AutoFillInterceptor} 反射找 {@code setCreateUser(Long)} 找不到会
 * <b>直接抛 {@code IllegalStateException}</b>，所以标错了是所有写操作当场炸掉。
 * {@code TariffRuleMapper.batchInsert} <b>不标</b>，两个时间列由
 * {@code TariffTemplateServiceImpl.saveRules} 显式 {@code set}。
 * DDL 省掉这两列是因为规则是<b>整体替换</b>的
 * （{@code deleteByTemplateId} + {@code batchInsert}），
 * 逐行记「谁创建的」没有意义 —— 那是同一次操作。
 *
 * <h2>★ 二、半开区间 {@code [startTime, endTime)}，用 {@code 24:00:00} 收尾</h2>
 *
 * <p>两条从 DDL 继承、必须原样遵守的约定：
 *
 * <ol>
 *   <li><b>半开区间。</b>{@code 08:00-10:00} 和 {@code 10:00-12:00} 相接而不重叠 ——
 *       10:00 这个时刻属于后者</li>
 *   <li><b>最后一段的 {@code endTime} 是 {@code '24:00:00'}，不是 {@code '00:00:00'}。</b>
 *       写成后者会让 {@code 23:00-24:00} 变成一个零长度段，而零长度段
 *       既不产生切点也不被任何时刻命中 —— 表现为「23 点到 24 点之间没有电价」，
 *       订单金额莫名其妙少了</li>
 * </ol>
 *
 * <p>这也是 {@link #startTime} / {@link #endTime} 用 {@link String} 而不是
 * {@code LocalTime} 的原因：{@code '24:00:00'} 在 {@code LocalTime} 的定义域之外。
 *
 * <h2>★ 三、一个模板必须完整、无重叠地覆盖 24 小时</h2>
 *
 * <p>不满足的话某个时刻没有对应时段，计费时那一段就没有价格。校验由
 * {@link com.chargeops.billing.TariffValidator#validate} 在<b>保存模板时</b>做 ——
 * 挡在写入口，因为缺口一旦进了库，受影响的是所有用这个模板的在途订单。
 *
 * <h2>★ 四、唯一键约束的是「起点不重复」，不是「区间不重叠」</h2>
 *
 * <p>{@code uk_tariff_rule_tpl_start (template_id, start_time)} 保证同一模板内
 * 某个起始时刻只属于一个时段。不把 {@code end_time} 也放进唯一键，是因为那样
 * {@code [08:00,12:00)} 和 {@code [08:00,10:00)} 就能并存，唯一键反而失去意义。
 * 「无重叠」是<b>跨行</b>的性质，只能由应用层在保存时校验 ——
 * 这正是 {@code TariffValidator} 里那个 {@code prev.end == next.start} 判断的理由。
 *
 * <h2>★ 五、两个单价分列，绝不合并</h2>
 *
 * <p>{@code energy_price} 是电网收的、{@code service_price} 是运营商收的，
 * 对账时要分别和电网账单、财务报表核对 —— 合成一个「综合单价」再拆开做不到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TariffRule implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long templateId;

    /** {@code 1尖峰 2高峰 3平段 4低谷} —— 见 {@link com.chargeops.enumeration.PeriodType} */
    private Integer periodType;

    /**
     * 时段开始（含），形如 {@code "00:00:00"}
     *
     * <p>★ {@link String} 而不是 {@code LocalTime}：见类注释第二节。
     */
    private String startTime;

    /**
     * 时段结束（<b>不含</b>）。★ 最后一段必须是 {@code "24:00:00"}，见类注释第二节
     */
    private String endTime;

    /** 电费单价，元/kWh。{@code decimal(10,4)} */
    private BigDecimal energyPrice;

    /** 服务费单价，元/kWh。{@code decimal(10,4)}，默认 0 */
    private BigDecimal servicePrice;

    /** 展示顺序。{@code uk(template_id, start_time)} 才是约束，这个字段只管排序 */
    private Integer seq;

    // ==================== 审计两列 ====================
    // ★ 没有 create_user / update_user —— 见类注释第一节，写方法绝不能标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
