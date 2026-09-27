package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 新增计费模板的请求体
 *
 * <h2>★★ 一、{@code rules} 必须内嵌在模板里，不能另开一个「加一条规则」的接口</h2>
 *
 * <p>「一个模板要完整、无重叠地覆盖 24 小时」是一条<b>跨行</b>的性质 ——
 * 单看任何一行都看不出它满不满足，所以校验器必须<b>一次看到全集</b>。
 * 如果规则能一条一条地加，那么「加第一条规则」这个时刻模板一定不满足覆盖，
 * 于是只有两个选择：每条都校验（第一条就过不去，模板永远建不起来），
 * 或者不校验（缺口进了库，受影响的是<b>所有用这个模板的在途订单</b>，
 * 表现为某些时段没有电价、金额莫名其妙少了）。
 *
 * <p>整体替换（{@code deleteByTemplateId} + {@code batchInsert}）把「中间状态」
 * 从数据模型里彻底移除了 —— 没有中间状态，就不需要为中间状态设计校验策略。
 * ★ 这也是 {@code TariffRuleMapper} 里<b>没有</b>单个 insert/update/delete 的原因。
 *
 * <h2>★★ 二、{@code @NotEmpty} 拦的是「一个没有规则的模板」</h2>
 *
 * <p>少了它，{@code rules} 为空数组时会走到 Service 的 {@code batchInsert}，
 * 而它拿到空列表有两种糟糕的可能：静默不插入（于是库里多了一个一条规则都没有的
 * 模板，它<b>看起来完全正常</b>，直到有人用它绑定站点才发现计费时全部时段无价），
 * 或者 MyBatis 拼出 {@code INSERT INTO ... VALUES} 语法错误。
 * {@code TariffRuleMapperTest} 里有一条测试专门钉住这件事：
 * 空列表必须抛 {@code DataAccessException}，不能静默成功。
 *
 * <h2>★★ 三、{@code @Valid} 不能漏，否则 rules 里的约束全部静默失效</h2>
 *
 * <p>级联校验只有在字段上标了 {@code @Valid} 时才会递归进 {@code List} 的元素，
 * 而漏掉它的后果特别隐蔽 —— <b>不报错，只是不校验</b>：{@code periodType = 99}、
 * {@code energyPrice = -1} 这样的规则会一路进库，错误要到计费时或者对账时才发现，
 * {@code TariffRuleDTO} 上那一堆 {@code @NotNull/@Min/@Pattern} 全部变成注释。
 *
 * <h2>★ 四、{@code code} 是唯一索引，{@code name} 不是</h2>
 *
 * <p>{@code uk_tariff_template_code}。{@code code}（如 {@code "SZ-2026-GYF"}）
 * 是给程序和运维看的稳定标识；{@code name} 是给人看的，<b>允许重名</b>。
 * 模板改名不影响任何已冻结的引用（{@code charging_order} 冻结的是 id），
 * 所以 {@code name} 可以随便改 —— 但 {@code code} 改了就是另一个模板了。
 */
@Data
@Schema(description = "新增计费模板")
public class TariffTemplateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ 正则和站点编码同一条，理由也一样：编码是对外可见的标识，
     * 各地的命名习惯不同，精确正则会挡在门外。真正的约束是唯一性，由数据库守。
     */
    @Schema(description = "模板编码，唯一", example = "SZ-2026-GYF")
    @NotBlank(message = "模板编码不能为空")
    @Size(max = 32, message = "模板编码不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "模板编码只能是字母、数字和连字符")
    private String code;

    @Schema(description = "模板名称，允许重名", example = "深圳 2026 工商业分时电价")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称不能超过 64 个字符")
    private String name;

    /**
     * ★ 分页查询里对它是<b>精确匹配</b>（不是模糊），因为它走
     * {@code idx_tariff_template_region_status} 的第一列：写成
     * {@code LIKE '%深圳市%'} 会让那个索引失效，而模板表虽然不大，
     * 但「按地区找模板」是运营最常用的入口。
     */
    @Schema(description = "适用地区", example = "深圳市")
    @Size(max = 64, message = "适用地区不能超过 64 个字符")
    private String region;

    @Schema(description = "模板说明", example = "按深圳市 2026 年工商业分时电价编制")
    @Size(max = 512, message = "模板说明不能超过 512 个字符")
    private String description;

    /** ★ 默认启用：一个刚建好的模板本来就是拿来用的 */
    @Schema(description = "状态：1启用 0停用", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用")
    @Max(value = 1, message = "状态只能是 0停用、1启用")
    private Integer status = 1;

    /**
     * 分时时段规则，必须完整覆盖 24 小时
     *
     * <p>★ {@code @Valid} 见类注释第三节，{@code @NotEmpty} 见第二节。
     *
     * <p>★ 校验顺序是：注解先跑（{@code @Valid} 递归校验每条规则的格式），
     * 然后 Service 把它们转成 {@code TariffRuleSnapshot} 交给
     * {@code TariffValidator} 做跨行校验（排序、首尾、相邻连续性）。
     * <b>两层分工明确</b>：注解管「单个值合不合法」，Validator 管
     * 「这一组值合不合法」。
     *
     * <p>★ 不限制条数上限：理论上一个模板可以有 1440 条规则（每分钟一段），
     * 而那是合法的 —— 加一个「最多 24 条」的猜测性限制，会在某个真的按半点
     * 切分的地区（48 段）挡住正常数据。切点数量爆炸由计费引擎自己的
     * 「跨度过大」检查负责，不在这里。
     */
    @Schema(description = "分时时段规则，必须完整覆盖 24 小时且无重叠")
    @NotEmpty(message = "计费规则不能为空")
    @Valid
    private List<TariffRuleDTO> rules;
}
