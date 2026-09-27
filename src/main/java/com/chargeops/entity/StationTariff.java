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
 * <p>本表回答的是「下单那一刻该把哪个模板 id 冻结进订单」；真正计费用的是订单上
 * 冻结的那个 {@code tariff_template_id}，所以电价调整不影响历史/在途订单。
 *
 * <p>它也是模板规则不可变性的依据：{@code TariffTemplateService.saveRules} 会查本表
 * （{@code StationTariffMapper.countByTemplateId}），只要有任何一行引用这个模板，
 * 就拒绝修改它的规则；要调价只能新建一个模板版本、在本表加一行新绑定，
 * 用 {@link #effectiveFrom} 划定生效期 —— 老绑定自然失效，在途订单仍指向老模板。
 *
 * <p>{@code [effectiveFrom, effectiveTo)} 半开，{@code effectiveTo} 为 {@code NULL}
 * 表示长期有效；解析（{@code resolveTemplateAt}）同站同刻多条时 {@code priority} 大者胜。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StationTariff implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long stationId;

    /** 被绑定的模板 id。 它会被冻结进 {@code charging_order}，所以规则必须不可变 */
    private Long templateId;

    /** 生效时间（含）。与 {@code station_id} 组成唯一键 {@code uk_station_tariff_start} */
    private LocalDateTime effectiveFrom;

    /** 失效时间（不含）；{@code NULL} 表示长期有效 */
    private LocalDateTime effectiveTo;

    /** 同站同刻多条时的优先级，大者胜 */
    private Integer priority;

    /** {@code 1启用 0停用} */
    private Integer status;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
