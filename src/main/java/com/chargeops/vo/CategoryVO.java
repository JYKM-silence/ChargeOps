package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分类信息
 *
 * <p>{@code /api/admin/category/**} 的读接口都返回它。
 *
 * <h2>★ 一、两个派生标签，都是「读路径降级」</h2>
 *
 * <p>{@code typeLabel} 和 {@code statusLabel} 都由 Service 派生，
 * 并在派生失败时<b>返回 null 而不是抛异常</b> ——
 * 照 {@code StationServiceImpl.labelOf} 的做法。
 *
 * <p>理由：这两个字段是<b>给人看的装饰</b>，不是业务数据。
 * 一行脏数据（比如 {@code type = 7}）不该让整个分类列表 500 ——
 * 那会把一个「显示不出标签」的小问题升级成「管理端打不开」的大问题。
 * 而且恰恰是出了脏数据的时候，管理员才最需要打开这个页面去修它。
 *
 * <p>★ 反过来，<b>写路径是严格的</b>：{@code CategoryServiceImpl.create}
 * 会直接用 {@code CategoryType.of(type)} 并在值非法时抛
 * {@code ParamErrorException}。写的时候不许脏，读的时候容忍脏。
 * 这条分工是全项目统一的。
 *
 * <h2>★ 二、{@code statusLabel} 的措辞是「启用 / 停用」，不是「上架 / 下架」</h2>
 *
 * <p>虽然语义相近，但 {@code charge_package.status} 用的是「上架 / 下架」
 * （那是一个商品），而分类是<b>配置</b>，用的是「启用 / 停用」。
 * 措辞不一致会让前端以为要区分两套状态机。这里对齐 DDL 里的用词。
 *
 * <h2>★ 三、没有「该分类下的套餐数」</h2>
 *
 * <p>这个数字在管理端列表页上会很有用（「这个分类能删吗」），
 * 但它需要一次额外的 {@code GROUP BY} 查询。
 *
 * <p>★ <b>将来要加的话有一个具体的坑</b>：那条补充查询必须放在
 * {@code PageHelper.startPage} 之后的那条查询<b>之后</b>，
 * 否则它会吃掉 ThreadLocal 里的分页参数 —— 表现是分类列表
 * 返回全表而那条 COUNT 被 LIMIT 10。
 * 完整的机制解释见 {@code ChargerServiceImpl} 的富化逻辑。
 * 本轮不做，但坑先记在这里。
 */
@Data
@Schema(description = "分类信息")
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "分类 id")
    private Long id;

    @Schema(description = "分类名称")
    private String name;

    /** {@code 1充电套餐分类 2增值服务分类} —— 原始值，给前端做逻辑判断 */
    @Schema(description = "类型：1充电套餐分类 2增值服务分类")
    private Integer type;

    /** ★ 由 {@code CategoryType} 派生，派生失败返回 null 而不是抛 */
    @Schema(description = "类型标签：充电套餐分类/增值服务分类")
    private String typeLabel;

    @Schema(description = "排序值，小的在前")
    private Integer sort;

    /** {@code 1启用 0停用} —— 原始值 */
    @Schema(description = "状态：1启用 0停用")
    private Integer status;

    /** ★ 措辞是「启用 / 停用」而不是「上架 / 下架」，见类注释第二节 */
    @Schema(description = "状态标签：启用/停用")
    private String statusLabel;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
