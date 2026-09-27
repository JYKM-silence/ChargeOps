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
 * <h2>★ 一、模板的规则一旦被站点绑定，就不能再改</h2>
 *
 * <p>模板 id 在<b>下单那一刻</b>冻结进 {@code charging_order}，
 * 所以电价调整不会影响在途订单 —— 但这条保证<b>只在规则不可变时才成立</b>：
 * 规则还能被改的话，已冻结的 {@code tariff_template_id} 就会指向被改过的规则，
 * 「电价调整不影响在途订单」变成一句空话，而且是<b>静默失效</b> ——
 * 订单金额会算出来，只是和用户启动时看到的不一样。
 *
 * <p>所以 {@code TariffTemplateService.saveRules} 必须有守卫：
 * <b>模板已被任何 {@code station_tariff} 行引用时，拒绝修改它的规则</b>
 * （抛 {@code DataConflictException(TEMPLATE_IN_USE)}）。
 * 要调价就<b>新建一个模板版本</b>、用 {@code station_tariff.effective_from}
 * 绑定生效期 —— 模板是版本化的，站点通过绑定表选择「此刻用哪个版本」。
 * 错误消息里必须说清这一点，否则管理员只会觉得「为什么不让我改」。
 *
 * <h2>★ 二、{@code code} 是唯一索引，{@code name} 不是</h2>
 *
 * <p>{@code uk_tariff_template_code}：{@code code}（如 {@code "SZ-2026-GYF"}）
 * 是给程序和运维看的稳定标识，{@code name} 是给人看的、允许重名。
 * 改名不影响任何已冻结的引用（订单冻结的是 id），但 {@code code} 改了就是另一个模板了。
 *
 * <h2>★ 三、{@code status} 的语义是「这个模板还能不能被新绑定」</h2>
 *
 * <p>停用一个模板<b>不会</b>影响已经用它的站点，要真正下线必须先把
 * {@code station_tariff} 的绑定改掉。这是刻意的：让「停用模板」成为一个
 * 不会意外改变现有站点计费的动作。
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

    /** 如「深圳 2026 工商业分时电价」。★ 允许重名，改名不影响已冻结的引用 */
    private String name;

    /** 适用地区，如「深圳市」 */
    private String region;

    private String description;

    /** {@code 1启用 0停用}。★ 停用不影响已绑定的站点，见类注释第三节 */
    private Integer status;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill
    //
    // ★ 规则（TariffRule）是另一张表，实体刻意不持有 List<TariffRule>（那是一个 join）：
    //   校验 24 小时覆盖需要「模板 + 规则全集」一起看，由 Service 查两次再组装
    //   （见 TariffTemplateVO）

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
