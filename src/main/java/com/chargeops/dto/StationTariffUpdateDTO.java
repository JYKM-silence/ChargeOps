package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 修改站点计费绑定的请求体
 *
 * 一、只能改三样：{@code effectiveTo}、{@code priority}、{@code status}
 *
 * <p>这三样有一个共同点：它们都不改变「从哪个时刻起用哪个模板」这个核心事实。
 * 它们只回答另外两个问题 ——「这个绑定什么时候不再适用」
 * （{@code effectiveTo}、{@code status}）和「同一时刻有多条时谁赢」
 * （{@code priority}）。这不是能力上的限制，是这张表的正常用法：
 * 一个 {@code station_tariff} 行的身份是
 * {@code (stationId, effectiveFrom)} 加上它指向的 {@code templateId}，
 * 改了其中任何一个，改的都不是这条绑定的属性，而是换了一条绑定。
 *
 * 二、{@code stationId} 和 {@code effectiveFrom} 不在，因为它们是身份
 *
 * <p>两者组成唯一键 {@code uk_station_tariff_start}。
 *
 * <p> 更要紧的一点：{@code effectiveFrom} 是本表里唯一能制造出「空窗期」
 * 的编辑。把它往后推，{@code [老 effectiveFrom, 新 effectiveFrom)} 这一段就
 * 没有任何绑定，而 {@code resolveTemplateAt} 对这种情况返回 {@code null}。
 *
 * <p>注意区别：{@code tariff_rule} 有「必须完整覆盖 24 小时」的校验器挡在写入口，
 * 但 {@code station_tariff} 没有对应的校验器（它的时间轴是绝对时间、
 * 不是每日循环，一个站点也不要求「任何时刻都必须有价」—— 新建的站点在绑定之前
 * 本来就是没有电价的）。所以这里一旦出现空窗，没有任何东西会报错，
 * 它只会在下单时表现为「这个站点现在不能充电」。
 *
 * <p>结论：让 {@code effectiveFrom} 可改，等于允许在不触发任何校验的情况下
 * 把一个正在运营的站点变得不能充电。真正需要「改生效时刻」时，
 * 正确做法是删掉这条绑定重新建 —— 那是一次明示的、有审计痕迹的操作。
 *
 * 三、{@code templateId} 不在，因为换模板 = 新建绑定
 *
 * <p>「把这条绑定改成指向另一个模板」看起来方便，但它把这个表的版本化语义
 * 抹掉了：一条创建于 2025 年、指向模板 A 的绑定，在 2026 年被就地改成指向模板 B
 * 之后，它的 {@code create_time} / {@code create_user} 说的还是 2025 年那次操作，
 * 审计列描述的事件和这一行现在的内容对不上了。
 *
 * <p>而正确做法只有一步之遥：新建一行绑定（新 {@code templateId} +
 * 新 {@code effectiveFrom}），老绑定用 {@code effectiveTo} 或 {@code status=0}
 * 收尾。三步都是本接口已经支持的编辑，所以这条路不是绕的 ——
 * 「要换模板就新建绑定」不是限制，是这张表被设计成这样用的。
 *
 * 四、{@code effectiveTo} 可以改，这是【退役一条绑定】的正常手段
 *
 * <p>和 {@code effectiveFrom} 正好相反：把 {@code effectiveTo} 提前，只会让
 * 后面的时间没有绑定 —— 而那本来就是「这条绑定到期了」的意思。
 *
 * <p> 但「设了之后抹不掉」这个限制在这里后果比别处严重一点：一条被设了
 * {@code effectiveTo} 的绑定，想再变回「长期有效」只能填一个足够远的未来时刻
 * （比如 {@code 9999-12-31}），因为 {@code null} 和「不传」在 Java 对象上
 * 是同一个状态。修法见 {@code StationUpdateDTO} 类注释第一节。
 *
 * <p> 跨字段校验（{@code effectiveTo} 必须晚于库里的 {@code effectiveFrom}）
 * 放在 Service，而且必须拿库里那一行的 {@code effectiveFrom} 比 ——
 * 这个 DTO 里没有那个字段，所以校验的对象是合并后的行。
 */
@Data
@Schema(description = "修改站点计费绑定")
public class StationTariffUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**  必须晚于这条绑定库里的 {@code effectiveFrom}，由 Service 校验 */
    @Schema(description = "失效时刻（不含）；不传表示不修改。注意：设了之后无法再改回「长期有效」",
            example = "2026-12-31 23:59:59")
    private LocalDateTime effectiveTo;

    /**
     * 优先级，大者胜
     *
     * <p> 可以改成 {@code 0}（有意义的值：最低优先级，任何同刻的其他绑定都赢过它），
     * 所以 {@code <if>} 只能判 null。
     */
    @Schema(description = "优先级，同站同刻多条时大者胜；不传表示不修改", example = "0")
    private Integer priority;

    /**
     * 停用是退役一条绑定的首选手段，优先于删除：停用保留了
     * 「这个站点曾经用过这套价」这条历史，而删除之后那段时期就变成空白 ——
     * 事后追查一笔老订单为什么用了这个价时，会找不到依据。
     * 删除接口主要服务于「绑定建错了、从来没生效过」这种场景。
     */
    @Schema(description = "状态：1启用 0停用；不传表示不修改", example = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status;
}
