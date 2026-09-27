package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 为站点绑定计费模板的请求体
 *
 * 一、这张表只负责「此刻该用哪个模板」，真正计费用的是订单上冻结的那个
 *
 * <p>{@code charging_order} 里冻结了 {@code tariff_template_id}，结算时按
 * 那个模板计价。本表回答的是另一个问题：下单那一刻，该把哪个模板 id
 * 冻结进去。这条铁律（电价调整不得影响历史/在途订单）是这张表存在的全部意义 ——
 * 电价是电网按地区发布的，会调整；如果结算时才去查「现在这个站点用哪个价」，
 * 那么一调价，已经在充的订单会被按新价结算，用户看到的金额和启动时预估的不一样，
 * 而且没法解释。
 *
 * 二、{@code effectiveFrom} 必填，而且它是这个绑定的【身份】的一半
 *
 * <p>与 {@code stationId} 组成唯一键 {@code uk_station_tariff_start}。
 *
 * <p> 为什么不给它一个「默认现在」的字段初始值：一个绑定行的含义是
 * 「从这一刻起，这个站点用这个模板」，让服务端替调用方决定这个时刻，
 * 等于让调用方失去对生效时间的控制 —— 而这恰恰是这张表唯一的表达能力。
 * 调价通常发生在「已经和站点沟通好、明天零点生效」这样的语境里，
 * 那个时刻必须由调用方明确给出。
 *
 * <p> 传入一个已经过去的时刻是允许的：它表示「这个绑定当时就生效了」，
 * 用于补录历史绑定，{@code resolveTemplateAt} 的 {@code effective_from <= ?}
 * 会正确地把它算进去。
 *
 * 三、{@code effectiveTo} 为空表示长期有效
 *
 * <p>半开区间 {@code [effectiveFrom, effectiveTo)}：{@code effectiveFrom} 含、
 * {@code effectiveTo} 不含。于是在一个恰好的交接时刻只会匹配到一条 ——
 * 老绑定 {@code effectiveTo = 2026-01-01 00:00:00} 和新绑定
 * {@code effectiveFrom = 2026-01-01 00:00:00} 在同一时刻只有新的命中。
 * 这条性质有测试守着（见 {@code StationTariffMapperTest} 的
 * {@code handoverInstantShouldMatchExactlyOneBinding}：它把老绑定的
 * {@code priority} 设成 99、新的设成 0，于是「命中新的」这件事只能
 * 由区间语义解释，不可能被优先级掩盖过去）。
 *
 * <p> 跨字段校验（{@code effectiveTo} 必须晚于 {@code effectiveFrom}）放在
 * Service：单个字段上的注解管不了两个字段之间的关系。不校验的话会存进一个
 * 永远不会生效的绑定 —— 那个条件对任何时刻都不成立，
 * 而它看起来和一条正常绑定一模一样。
 */
@Data
@Schema(description = "绑定站点计费模板")
public class StationTariffDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "站点 id", example = "1")
    @NotNull(message = "站点不能为空")
    private Long stationId;

    /**
     * Service 会先 {@code TariffTemplateMapper.getById} 确认模板存在，
     * 并顺带校验它的 {@code status}：停用模板的意图就是「别再拿它去配新站点了」，
     * 放行等于让停用变成一个不完整的操作。两件事在同一次 {@code getById}
     * 里一并拿到，分成两次查询是白跑一趟。
     */
    @Schema(description = "计费模板 id", example = "1")
    @NotNull(message = "计费模板不能为空")
    private Long templateId;

    /**  必填，理由见类注释第二节。格式由全局 Jackson 配置决定，不需要 {@code @JsonFormat} */
    @Schema(description = "生效时刻（含）", example = "2026-01-01 00:00:00")
    @NotNull(message = "生效时刻不能为空")
    private LocalDateTime effectiveFrom;

    /**  必须晚于 {@code effectiveFrom}，由 Service 校验 */
    @Schema(description = "失效时刻（不含）；不传表示长期有效", example = "2026-12-31 23:59:59")
    private LocalDateTime effectiveTo;

    /**
     * 优先级，大者胜
     *
     * <p> 这是运维做临时调价的手段：不动老绑定，加一条高优先级的、
     * 带 {@code effectiveTo} 的临时绑定。促销期结束后那条绑定自然过期、
     * 老绑定重新生效 —— 不需要任何人记得回来把它改回去。
     * 靠生效期而不是靠人去记得。
     *
     * <p>字段初始值 {@code 0}：insert 是全列插入，不给值就是往
     * {@code NOT NULL} 列写 NULL。
     */
    @Schema(description = "优先级，同站同刻多条时大者胜", example = "0", defaultValue = "0")
    private Integer priority = 0;

    /**  初始值 {@code 1}：同 {@code priority}，不给值就是往 {@code NOT NULL} 列写 NULL */
    @Schema(description = "状态：1启用 0停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status = 1;
}
