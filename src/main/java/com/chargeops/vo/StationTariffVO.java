package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站点计费绑定信息
 *
 * <h2>★ 一、{@code templateName} / {@code templateCode} 必须补，但理由和充电桩补站名不同</h2>
 *
 * <p>{@code station_tariff} 表里只有 {@code template_id}，一行绑定数据显示成
 * 「模板 7：2026-01-01 起生效」是<b>没有信息量的</b> —— 没有人记得模板 7 是哪个价，
 * 而这正是管理员打开这个页面要回答的问题。
 *
 * <p>★ 和 {@code ChargerVO.stationName} 的差别在于推理：<b>桩列表补站名是为了
 * 「能认出这根桩」（对象的标识），绑定列表补模板名是为了「这次改价改成了什么」
 * （变更的内容）。</b>补的方式是批量：{@code listByStationId} 拿到一页绑定之后
 * 去重收集 {@code templateId}，调一次 {@code TariffTemplateMapper.listByIds}
 * 换取 id → (name, code) 的映射。{@code listByIds} 存在的唯一用途就是这个。
 *
 * <p>★ 同样注意 {@code PageHelper} 的时机：补名字的查询<b>必须</b>在
 * {@code startPage} 之后的那条查询<b>之后</b>执行，否则它会把 {@code LIMIT} 消费掉。
 * 完整的调用顺序和那个 bug 的三个特征见 {@code ChargerVO} 类注释。
 *
 * <h2>★ 二、{@code stationId} 在，{@code stationName} 不在</h2>
 *
 * <p>绑定的列表接口永远按站点查，而且 {@code StationTariffMapper} 里根本没有
 * {@code page} 方法，只有一个 {@code listByStationId} —— 「这一页的所有行属于哪个站点」
 * 是调用方自己传进来的参数。<b>补名的那一列，必须是这一页数据自己回答不了的问题。</b>
 *
 * <h2>★ 三、{@code effectiveTo} 为 null 表示长期有效</h2>
 *
 * <p>半开区间 {@code [effectiveFrom, effectiveTo)}：null 是<b>无限远</b>，不是「不生效」。
 * 界面必须把它渲染成「长期有效」而不是空白，否则管理员会以为这条绑定有问题。
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

    @Schema(description = "计费模板名称，由 Service 批量补齐")
    private String templateName;

    /**
     * 模板编码
     *
     * <p>★ 除了名称之外还返回编码，因为名称<b>允许重名</b>而编码唯一
     * （见 {@code TariffTemplate} 类注释第二节）。列表里出现两个叫「深圳分时电价」的
     * 模板时，编码是唯一能区分它们的字段。
     */
    @Schema(description = "计费模板编码，由 Service 批量补齐")
    private String templateCode;

    @Schema(description = "生效时刻（含）")
    private LocalDateTime effectiveFrom;

    @Schema(description = "失效时刻（不含）；null 表示长期有效")
    private LocalDateTime effectiveTo;

    @Schema(description = "优先级，同站同刻多条时大者胜")
    private Integer priority;

    @Schema(description = "状态：1启用 0停用")
    private Integer status;

    @Schema(description = "状态文案，如「启用」")
    private String statusLabel;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
