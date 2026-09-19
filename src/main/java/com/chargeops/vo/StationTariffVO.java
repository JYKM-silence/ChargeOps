package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站点计费绑定信息
 *
 * <h2>★★ 一、{@code templateName} / {@code templateCode} 必须补，而且理由和充电桩补站名不同</h2>
 *
 * <p>{@code station_tariff} 表里只有 {@code template_id}。
 * 一行绑定数据显示成「模板 7：2026-01-01 起生效」是<b>没有信息量的</b>——
 * 没有人记得模板 7 是哪个价，而这正是管理员打开这个页面要回答的问题。
 *
 * <p>★ 和 {@code ChargerVO.stationName} 的区别值得说清楚，因为结论相同、
 * 推理不同：<b>桩列表补站名是为了「能认出这根桩」，绑定列表补模板名是为了
 * 「这次改价改成了什么」。</b>前者是对象的标识（没有它，对象不可辨认），
 * 后者是变更的内容（没有它，这条记录没有意义）。
 *
 * <p>补的方式是批量：{@code listByStationId} 拿到一页绑定之后，
 * 去重收集 {@code templateId}，调一次
 * {@code TariffTemplateMapper.listByIds} 换取 id → (name, code) 的映射。
 *
 * <p>★ 这就是为什么 {@code TariffTemplateMapper} 里有 {@code listByIds}：
 * 「按 id 批量取名」是它的唯一用途，和 {@code StationMapper.listByIds}
 * 服务 {@code ChargerVO.stationName} 是同一件事。
 * <b>两个 {@code listByIds} 的存在理由，就是 VO 里那两个补出来的名字字段。</b>
 *
 * <p>★ 同样注意 {@code PageHelper} 的时机问题：
 * 补名字的查询<b>必须</b>在 {@code startPage} 之后的那条查询<b>之后</b>执行，
 * 否则它会把 {@code LIMIT} 消费掉。见 {@code ChargerVO} 类注释里那段
 * 完整的调用顺序，以及那个 bug 的三个特征（静默、依赖数据量、看起来像前端问题）。
 *
 * <h2>★ 二、{@code stationId} 在，{@code stationName} 不在</h2>
 *
 * <p>绑定的列表接口永远按站点查（{@code GET /api/admin/station-tariff?stationId=…}），
 * 而且 {@code StationTariffMapper} 里根本没有 {@code page} 方法 ——
 * 只有一个 {@code listByStationId}。
 *
 * <p>所以「这一页的所有行属于哪个站点」是调用方自己传进来的参数，
 * 补站名是让数据回答一个它已经被回答过的问题。
 * <b>补名的那一列，必须是这一页数据自己回答不了的问题</b> ——
 * 这是判断的依据，不是「顺手就补」。
 *
 * <h2>★ 三、{@code effectiveTo} 为 null 表示长期有效</h2>
 *
 * <p>界面必须把 null 渲染成「长期有效」而不是空白。
 * 半开区间 {@code [effectiveFrom, effectiveTo)}：null 是<b>无限远</b>，
 * 不是「不生效」—— 渲染错误会让管理员以为这条绑定有问题，
 * 然后去做一些不必要的修改。
 */
@Data
@Schema(description = "站点计费绑定信息")
public class StationTariffVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "绑定 id")
    private Long id;

    @Schema(description = "站点 id")
    private Long stationId;

    @Schema(description = "计费模板 id")
    private Long templateId;

    /** ★ Service 用 {@code TariffTemplateMapper.listByIds} 批量补齐，见类注释第一节 */
    @Schema(description = "计费模板名称，由 Service 批量补齐")
    private String templateName;

    /**
     * 模板编码
     *
     * <p>★ 除了名称之外还返回编码，因为名称<b>允许重名</b>
     * （见 {@code TariffTemplate} 类注释第二节）而编码唯一。
     * 当列表里出现两个叫「深圳分时电价」的模板时，编码是唯一能区分它们的字段。
     */
    @Schema(description = "计费模板编码，由 Service 批量补齐")
    private String templateCode;

    @Schema(description = "生效时刻（含）")
    private LocalDateTime effectiveFrom;

    /**
     * 失效时刻（不含）
     *
     * <p>★ <b>null 表示长期有效</b>，不是「不生效」。界面上要渲染成文字。
     */
    @Schema(description = "失效时刻（不含）；null 表示长期有效")
    private LocalDateTime effectiveTo;

    @Schema(description = "优先级，同站同刻多条时大者胜")
    private Integer priority;

    @Schema(description = "状态：1启用 0停用")
    private Integer status;

    /** ★ 派生自 {@code status} */
    @Schema(description = "状态文案，如「启用」")
    private String statusLabel;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
