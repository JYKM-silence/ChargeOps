package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆信息
 *
 * <p>{@code /api/member/vehicle/**} 的所有读接口都返回它。
 *
 * <h2>★★ 一、{@code isDefault} 的读侧约定：按排序取第一条，不要按它过滤</h2>
 *
 * <p>列表接口返回的车辆是<b>按 {@code is_default DESC, id DESC} 排序</b>的，
 * 所以需要「默认车」时<b>取第一条</b>就好。
 *
 * <p>★ <b>不要写「查到 {@code isDefault == true} 的那条」这种逻辑。</b>
 * 「每个车主最多一辆默认车」这条不变量由两条条件更新维护
 * （见 {@code VehicleMapper} 的类注释），它<b>不是数据库约束</b> ——
 * 手工 SQL 可以直接造出两辆默认车。
 *
 * <p>取第一条的写法在数据坏了的时候只是「多显示一个标记」；
 * 「找到那条默认车」的写法会拿到一个不确定的结果，
 * 或者在没有默认车时拿到 null。
 *
 * <h2>★ 二、{@code batteryCapacityKwh} 不参与计费</h2>
 *
 * <p>它只用来<b>估算</b>充满需要多少电（下单页那个「预计需要 60 度」）。
 * 实际计费按充电枪上报的度数走。
 *
 * <p>★ 这个区别很重要，因为它决定了这个字段的准确性要求：
 * 填错了只会让预估不准，<b>不会影响任何金额</b>。
 * 所以它可以为空（不填就是估不出来），也不需要什么校验。
 *
 * <h2>★ 三、没有 {@code memberId}</h2>
 *
 * <p>车主端的所有接口都只能查到自己名下的车（SQL 里带了
 * {@code member_id} 条件），所以返回的每一辆车<b>必然</b>属于当前登录者 ——
 * 再返回一次 {@code memberId} 是冗余的。
 *
 * <p>★ 而且不返回它有一个额外好处：前端没有机会「顺手」把它回传到
 * 修改接口里。{@code VehicleUpdateDTO} 里也没有这个字段，
 * 两处一起构成了「归属不可改」这件事的完整防线。
 */
@Data
@Schema(description = "车辆信息")
public class VehicleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "车辆 id")
    private Long id;

    /**
     * 车牌号
     *
     * <p>★ 存的是<b>规范化之后</b>的值（已 trim + 转大写），
     * 所以列表页不会同时出现 {@code 粤BD12345} 和 {@code 粤bd12345}。
     * 规范化只在写入侧做 —— 见 {@code Vehicle} 的类注释第二节。
     */
    @Schema(description = "车牌号")
    private String plateNo;

    /** {@code 1蓝 2黄 3绿 4渐变绿} —— 原始值，见 {@link #plateColorLabel} */
    @Schema(description = "车牌颜色：1蓝 2黄 3绿 4渐变绿")
    private Integer plateColor;

    /**
     * 车牌颜色的中文标签
     *
     * <p>★ 可空，而且<b>有两种情况都会是 null</b>，前端要知道区别：
     * <ol>
     *   <li>车主没填颜色（{@code plateColor} 也是 null）——
     *       正常的「未填」，前端应该显示「未设置」</li>
     *   <li>{@code plateColor} 有值但标签派生失败（库里有脏数据）——
     *       这是异常情况，但只读路径<b>降级不抛</b>，
     *       一行脏数据不该让整个列表 500</li>
     * </ol>
     * 两种情况前端都按「没标签」渲染即可，不需要区分。
     *
     * <p>派生方式见 {@code VehicleServiceImpl}（照
     * {@code StationServiceImpl.labelOf} 的降级做法）。
     */
    @Schema(description = "车牌颜色标签：蓝色/黄色/绿色/渐变绿色")
    private String plateColorLabel;

    @Schema(description = "品牌")
    private String brand;

    @Schema(description = "型号")
    private String model;

    /** 电池容量，kWh。★ 只用于估算，不参与计费 —— 见类注释第二节 */
    @Schema(description = "电池容量，kWh")
    private BigDecimal batteryCapacityKwh;

    /**
     * 是否默认车：{@code 1} 是 / {@code 0} 否
     *
     * <p>★ 读侧约定见类注释第一节：要取默认车就取列表第一条。
     */
    @Schema(description = "是否默认车：1 是 0 否")
    private Integer isDefault;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "添加时间")
    private LocalDateTime createTime;
}
