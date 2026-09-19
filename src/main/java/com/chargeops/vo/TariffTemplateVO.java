package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费模板信息（含时段规则）
 *
 * <h2>★★ 一、{@code rules} 在详情接口里有，在分页接口里【没有】</h2>
 *
 * <p>这不是「顺手少查一次」，是一个有明确边界的决定，由 Service 分两个方法实现
 * （{@code toVo} 和 {@code toVoWithRules}）：
 *
 * <table border="1">
 *   <caption>两个接口返回的形状</caption>
 *   <tr><th>接口</th><th>{@code rules}</th><th>理由</th></tr>
 *   <tr><td>{@code GET /{id}}</td><td>有</td>
 *       <td>编辑页必须看到规则才能改 —— 而规则是<b>整体替换</b>的，
 *           拿不到全集的编辑页改出来的东西必然不完整</td></tr>
 *   <tr><td>{@code GET /page}</td><td>null</td>
 *       <td>一页 10 个模板就是 80 条规则，而列表页只显示模板名和地区。
 *           查了不用，还多 10 次往返</td></tr>
 * </table>
 *
 * <p>★ 所以这个字段的 {@code @Schema} 里必须写明「仅详情接口返回」。
 * 不写的话前端会按列表也返回它来写代码，然后在列表页上拿到 null ——
 * 一个「后端按约定返回了，但没人知道有这条约定」的失败。
 *
 * <p>★ 不做成「分页接口用 {@code @JsonInclude(NON_NULL)} 偷偷省掉」：
 * 字段存在但为 null，和字段不存在，对调用方是两种不同的信号。
 * 显式 null 至少是可预期的。
 *
 * <h2>★★ 二、{@code rules} 是「模板」这个概念不可分割的一半</h2>
 *
 * <p>{@code tariff_template} 表只存头，规则在 {@code tariff_rule} 表里 ——
 * 这是<b>存储</b>上的拆分，不是概念上的拆分。
 * 「一个模板要完整覆盖 24 小时」这条约束只有在「头 + 全部规则」
 * 一起看的时候才成立，所以它们在这张 VO 里重新合成一个整体。
 *
 * <p>★ 与之对应的是 {@code TariffTemplateDTO} 里的 {@code rules} 是
 * <b>必填且 {@code @Valid}</b> 的 —— 校验器必须一次看到全集才校验得了。
 * 实体不持有 {@code List<TariffRule>}（那会是一次 join），
 * 组装的活由 Service 查两次再做，见 {@code TariffTemplate} 类注释末尾。
 *
 * <h2>★ 三、{@code status} 的语义是「这个模板还能不能被新绑定」</h2>
 *
 * <p>停用一个模板<b>不会</b>影响已经用它的站点 —— 那些站点的
 * {@code station_tariff} 行还在，解析出来的还是这个模板。
 * 界面上应该把这个语义说清楚，否则运维会以为停用等于下线。
 * 见 {@code TariffTemplate} 类注释第三节。
 */
@Data
@Schema(description = "计费模板信息")
public class TariffTemplateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "模板 id")
    private Long id;

    @Schema(description = "模板编码，唯一")
    private String code;

    @Schema(description = "模板名称，允许重名")
    private String name;

    @Schema(description = "适用地区")
    private String region;

    @Schema(description = "模板说明")
    private String description;

    @Schema(description = "状态：1启用 0停用。停用不影响已绑定的站点")
    private Integer status;

    /** ★ 派生自 {@code status} */
    @Schema(description = "状态文案，如「启用」")
    private String statusLabel;

    /**
     * 分时时段规则
     *
     * <p>★ <b>仅 {@code GET /api/admin/tariff-template/{id}} 返回</b>，
     * 分页接口里是 null。见类注释第一节。
     */
    @Schema(description = "分时时段规则（仅详情接口返回，分页接口为 null）")
    private List<TariffRuleVO> rules;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
