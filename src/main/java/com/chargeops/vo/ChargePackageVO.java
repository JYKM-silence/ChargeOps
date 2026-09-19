package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电套餐信息
 *
 * <h2>★★ 一、四个类型专属字段<b>全部</b>返回，其中三个必然是 null</h2>
 *
 * <p>这不是冗余。前端要根据 {@code packageType} 决定显示什么 ——
 * 而「其余三个是 null」本身就是一条<b>需要被看到</b>的信息。
 *
 * <p>老实说，一个更严格的设计是只返回本类型的那个字段。
 * 但那样做有一个具体的坏处：管理员在编辑页上看到的是
 * 「这个套餐有电量、没有时长、没有折扣」，
 * 而「没有」和「后端根本没返回这个字段」在 JSON 里长得一样
 * （都是键不存在）。<b>把 null 显式返回，至少让「这个字段确实为空」
 * 和「这个字段不适用」在调试时是可区分的。</b>
 *
 * <h2>★ 二、{@code stationId=0} 是「全平台」，前端要渲染成文字</h2>
 *
 * <p>{@code stationId} 直接返回数字。{@code 0} 的含义是「全平台通用」，
 * 而它<b>不能</b>被渲染成「站点 0」——
 * 「0 号站点」这个东西不存在，界面上出现它就是一个 bug。
 *
 * <p>★ 这里<b>没有</b> {@code stationName}，和 {@code ChargerVO} 形成对照，
 * 值得说清楚为什么：桩列表不带站名是<b>不可读</b>的
 * （「A区-01」每个站都有，设备编号也没人记得住），所以那里必须补；
 * 而套餐列表不带站名是<b>可读的</b>，只是少了「这个套餐属于谁」这个次要属性 ——
 * 而它由 {@code stationId=0} 这个约定表达得足够清楚。
 *
 * <p>补站名的代价不是一次查询，而是那个查询<b>必须放在正确的时机</b>
 * （{@code PageHelper.startPage} 之后、{@code toVo} 之前，
 * 见 {@code ChargerVO} 类注释）。把这个坑留在唯一必要的那一处，
 * 比为了显示方便在每个列表接口上都复制一遍要好。
 *
 * <h2>★ 三、{@code serviceDiscount} 是四位小数</h2>
 *
 * <p>返回时不要格式化掉末尾的 0 —— {@code 0.8000} 和 {@code 0.8}
 * 在 JSON 里是同一个数，但前端的表格如果按字符串长度对齐，
 * 四位的好处是列宽稳定。更重要的是：<b>
 * 这个字段的精度和 {@code decimal(5,4)} 是一致的，
 * 中间任何一步四舍五入都会造成金额误差。</b>
 */
@Data
@Schema(description = "充电套餐信息")
public class ChargePackageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "套餐 id")
    private Long id;

    @Schema(description = "套餐名称")
    private String name;

    @Schema(description = "分类 id")
    private Long categoryId;

    @Schema(description = "类型：1电量包 2时长包 3服务费折扣包 4次卡")
    private Integer packageType;

    /** ★ 派生自 {@code packageType}。前端不该自己维护 code → label 的映射 */
    @Schema(description = "类型文案，如「电量包」")
    private String packageTypeLabel;

    /** ★ {@code 0} 表示全平台通用，前端必须渲染成文字而不是「站点 0」，见类注释第二节 */
    @Schema(description = "限定站点 id；0 表示全平台通用")
    private Long stationId;

    @Schema(description = "售价，元")
    private BigDecimal price;

    @Schema(description = "划线价（原价），元；null 表示不展示划线")
    private BigDecimal originPrice;

    /** 仅 {@code packageType=1} 时非 null */
    @Schema(description = "电量，kWh；仅电量包有值")
    private BigDecimal energyKwh;

    /** 仅 {@code packageType=2} 时非 null */
    @Schema(description = "时长，分钟；仅时长包有值")
    private Integer durationMinutes;

    /** 仅 {@code packageType=4} 时非 null */
    @Schema(description = "次数；仅次卡有值")
    private Integer times;

    /** 仅 {@code packageType=3} 时非 null */
    @Schema(description = "服务费折扣，0.8 表示八折；仅服务费折扣包有值")
    private BigDecimal serviceDiscount;

    @Schema(description = "有效期天数；0 表示永久有效")
    private Integer validDays;

    @Schema(description = "套餐说明")
    private String description;

    @Schema(description = "图片地址")
    private String image;

    @Schema(description = "状态：1上架 0下架")
    private Integer status;

    /** ★ 派生自 {@code status}。这个字段的值域是 0/1，对应 {@code StatusConstant} */
    @Schema(description = "状态文案，如「上架」")
    private String statusLabel;

    @Schema(description = "排序值，小的在前")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
