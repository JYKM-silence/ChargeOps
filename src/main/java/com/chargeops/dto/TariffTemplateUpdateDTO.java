package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 修改计费模板的请求体
 *
 * <h2>★★ 一、{@code rules} 是可选的，但一旦传了就受「模板不可变」守卫约束</h2>
 *
 * <p>不传 {@code rules} = <b>只改头信息</b>（名称、地区、说明、状态），规则原样不动；
 * 传了 = 走 {@code TariffTemplateServiceImpl.saveRules} 的整体替换路径。
 *
 * <p><b>★ 整体替换之前会先查 {@code StationTariffMapper.countByTemplateId}：
 * 只要有任何一行 {@code station_tariff} 引用这个模板，就拒绝改规则。</b>
 *
 * <p>为什么必须有这道守卫 —— {@code charging_order} 冻结了
 * {@code tariff_template_id}，所以「电价调整不影响在途订单」看起来是成立的。
 * 但如果模板的规则还能改，那个已冻结的 id 就会指向<b>被改过的规则</b>：
 * 一笔 10:00 开始的订单，结算时按新规则算出一个和用户启动时看到的不一样的金额，
 * 而且<b>没有任何报错</b>。保证不是被违反了，是<b>从来没有成立过</b>。
 *
 * <p>要调价的正确做法是<b>新建一个模板版本</b>，在 {@code station_tariff} 里加一行
 * 新绑定、用 {@code effectiveFrom} 划定生效期 —— 老绑定自然失效，在途订单仍然
 * 指向老模板。这不是绕路：<b>模板是版本化的，站点通过绑定表选择「此刻用哪个
 * 版本」</b>。★ 错误消息里必须说清「新建模板版本」这条出路，否则管理员只会觉得
 * 「为什么不让我改」，然后去找绕过它的办法。
 *
 * <h2>★ 二、{@code code} 保留可改，而且理由是具体的</h2>
 *
 * <p>「{@code code} 改了就是另一个模板了」是语义上的判断，但不构成禁止修改的理由 ——
 * 真正的约束是唯一性，而那由 {@code uk_tariff_template_code} 守：改成已存在的编码
 * 会得到一条 {@code DuplicateKeyException} 翻译出来的「已存在」。
 *
 * <p>反过来说，<b>禁掉它才是个陷阱</b>：编码打错一个字母之后，如果编码不可改，
 * 而模板又已经被 {@code station_tariff} 引用（于是删不掉），这个错别字就
 * <b>永久留在库里</b>，只能去生产库手敲 UPDATE。这和站点编码保留可改是同一个
 * 判断：<b>能被唯一索引守住的字段，就不需要在 DTO 层禁掉；而禁掉它反而堵死了
 * 一条正常的纠错路径。</b>
 *
 * <h2>★ 三、{@code status=0} 不会影响已经用它的站点</h2>
 *
 * <p>这是刻意的（值域定义是「这个模板还能不能被<b>新绑定</b>」）：已经绑定的站点，
 * 它的 {@code station_tariff} 行还在，解析出来的还是这个模板。这让「停用模板」
 * 不会意外改变现有站点的计费 —— 否则运维想下架一个不再需要的模板时，
 * 会一次性改变一批正在运营的站点的电价。要真正下线，必须先把
 * {@code station_tariff} 的绑定改掉，那是明示的动作。
 */
@Data
@Schema(description = "修改计费模板")
public class TariffTemplateUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "模板编码，唯一；不传表示不修改", example = "SZ-2026-GYF")
    @Size(max = 32, message = "模板编码不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "模板编码只能是字母、数字和连字符")
    private String code;

    @Schema(description = "模板名称，允许重名；不传表示不修改", example = "深圳 2026 工商业分时电价")
    @Size(max = 64, message = "模板名称不能超过 64 个字符")
    private String name;

    @Schema(description = "适用地区；不传表示不修改", example = "深圳市")
    @Size(max = 64, message = "适用地区不能超过 64 个字符")
    private String region;

    @Schema(description = "模板说明；不传表示不修改", example = "按深圳市 2026 年工商业分时电价编制")
    @Size(max = 512, message = "模板说明不能超过 512 个字符")
    private String description;

    /**
     * ★ 改这个字段<b>不需要</b>经过「模板不可变」守卫 —— 它不改变任何一条规则，
     * 所以不影响已冻结的订单。守卫拦的是「改规则」，不是「改模板」：
     * <b>冻结的是规则，不是模板头。</b>
     */
    @Schema(description = "状态：1启用 0停用；不传表示不修改", example = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status;

    /**
     * 分时时段规则，整体替换
     *
     * <p>★ <b>不传 = 规则不动</b>，而不是「清空规则」。传了就必须是完整覆盖
     * 24 小时的全集 —— 这里是整体替换，不存在「只改其中一段」的用法。
     *
     * <p>★ 模板已被任何 {@code station_tariff} 引用时，传这个字段会被拒绝
     * （{@code DataConflictException}，消息指向「新建模板版本」）。见类注释第一节。
     *
     * <p>★ 这里<b>没有 {@code @NotEmpty}</b>：空列表和 null 都是「不修改」，
     * 而创建时 {@code @NotEmpty} 拦的是「一个没有规则的模板」。传空数组会在
     * Service 里被当成「没有规则要保存」而跳过 —— 这个行为是有意选择的：
     * 让「表单没勾任何时段」和「没动规则」等价的代价，小于让一个空数组
     * 去清空一个正在用的模板的规则。
     */
    @Schema(description = "分时时段规则，整体替换；不传表示不修改规则（模板已被站点引用时不允许修改）")
    @Valid
    private List<TariffRuleDTO> rules;
}
