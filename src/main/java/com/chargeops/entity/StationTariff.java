package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站点与计费模板的绑定，带生效期（对应 {@code station_tariff} 表）
 *
 * <h2>★★ 一、这张表只负责「此刻该用哪个模板」，真正计费用的是订单上冻结的那个</h2>
 *
 * <p>{@code charging_order} 里冻结了 {@code tariff_template_id}，
 * 结算时按<b>那个</b>模板计价。本表回答的是另一个问题：
 * <b>下单那一刻，该把哪个模板 id 冻结进去。</b>
 *
 * <p>这条铁律（电价调整不得影响历史/在途订单）是这张表存在的全部意义。
 * 电价是电网按地区发布的，会调整；如果充电中的订单在<b>结算时</b>
 * 才去查「现在这个站点用哪个价」，那么电价一调，已经在充的订单会被按新价结算 ——
 * 用户看到的金额和启动时预估的不一样，而且没法解释。
 *
 * <h2>★★ 二、它是模板规则不可变性的依据</h2>
 *
 * <p>冻结模板 id 只解决了一半问题：如果那个模板的<b>规则</b>还能被改，
 * 已冻结的 id 就会指向被改过的规则，保证照样是假的。
 *
 * <p>所以 {@code TariffTemplateService.saveRules} 会查本表
 * （{@code StationTariffMapper.countByTemplateId}）：
 * <b>只要有任何一行引用这个模板，就拒绝修改它的规则</b>。
 * 要调价就新建一个模板版本，在本表里加一行新的绑定，
 * 用 {@link #effectiveFrom} 划定生效期 —— 老绑定自然失效，在途订单仍然指向老模板。
 *
 * <h2>★ 三、{@code effectiveFrom} / {@code effectiveTo} 是半开区间</h2>
 *
 * <p>{@code [effectiveFrom, effectiveTo)}：{@code effectiveFrom} 含、
 * {@code effectiveTo} 不含。{@code effectiveTo} 为 {@code NULL} 表示长期有效。
 *
 * <p>解析查询（{@code resolveTemplateAt}）写出来是这样：
 * <pre>
 *   WHERE station_id = ?
 *     AND status = 1
 *     AND effective_from &lt;= ?
 *     AND (effective_to IS NULL OR effective_to &gt; ?)
 *   ORDER BY priority DESC
 *   LIMIT 1
 * </pre>
 *
 * <p>走索引 {@code idx_station_tariff_lookup (station_id, status, effective_from, effective_to)} ——
 * 列的顺序和查询条件的顺序完全一致。
 *
 * <p><b>★ 这条查询永久不缓存。</b>缓存一致性可以后面再优化，
 * 但「电价解析要可审计」这件事不能妥协：这张表整套设计的意义就是让
 * 「这笔订单为什么用了这个价」有唯一、可追溯、不依赖任何缓存状态的答案。
 *
 * <h2>★ 四、{@code priority} 大者胜</h2>
 *
 * <p>同站同刻有多条绑定时（比如一条长期有效、一条促销期覆盖），
 * 取 {@code priority} 最大的那条。这是运维做临时调价的手段：
 * 不动老绑定，加一条高优先级的、带 {@code effectiveTo} 的临时绑定。
 *
 * <p>{@code uk_station_tariff_start (station_id, effective_from)} 保证
 * 同一个站点同一时刻<b>不能有两条起点相同的绑定</b> ——
 * 但没有约束「区间不重叠」，那靠 {@code priority} 消解，不靠唯一键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StationTariff implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long stationId;

    /** 被绑定的模板 id。★ 它会被冻结进 {@code charging_order}，所以规则必须不可变 */
    private Long templateId;

    /** 生效时间（<b>含</b>）。与 {@code station_id} 组成唯一键 {@code uk_station_tariff_start} */
    private LocalDateTime effectiveFrom;

    /** 失效时间（<b>不含</b>）；{@code NULL} 表示长期有效 */
    private LocalDateTime effectiveTo;

    /** 同站同刻多条时的优先级，<b>大者胜</b> */
    private Integer priority;

    /** {@code 1启用 0停用} */
    private Integer status;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
