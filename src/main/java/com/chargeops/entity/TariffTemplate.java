package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 计费模板头（对应 {@code tariff_template} 表）
 *
 * <p>模板 = 一套分时电价规则。本表只存「头」，具体时段在
 * {@link TariffRule}（{@code tariff_rule}）里，一个模板对应 8 行左右。
 *
 * <p>模板的规则一旦被任何站点绑定（{@code station_tariff} 有一行引用它）就不能再改：
 * {@code TariffTemplateService.saveRules} 会拒绝（{@code DataConflictException(TEMPLATE_IN_USE)}）。
 * 模板 id 在下单那一刻冻结进 {@code charging_order}，规则还能改的话，
 * 「电价调整不影响在途订单」就是静默失效 —— 订单金额会算出来，只是和用户启动时看到的不一样。
 * 要调价就新建一个模板版本，用 {@code station_tariff.effective_from} 绑定生效期。
 *
 * <p>{@code status} 的语义是「这个模板还能不能被新绑定」：停用不影响已经用它的站点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TariffTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 模板编码，唯一索引 {@code uk_tariff_template_code}。如 {@code "SZ-2026-GYF"} */
    private String code;

    /** 如「深圳 2026 工商业分时电价」。 允许重名，改名不影响已冻结的引用 */
    private String name;

    /** 适用地区，如「深圳市」 */
    private String region;

    private String description;

    /** {@code 1启用 0停用}。 停用不影响已绑定的站点 */
    private Integer status;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
