package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 新增车辆的请求体
 *
 * <h2>★★ 一、{@code isDefault} 的默认值为什么要写成公开常量</h2>
 *
 * <p>因为<b>字段初始值只挡得住「JSON 里没有这个键」</b>，挡不住显式的
 * {@code "isDefault": null} —— 后者会走 setter 把初始值覆盖成 null。
 * 而 {@code vehicle.is_default} 是 {@code NOT NULL}，{@code VehicleMapper.xml}
 * 又是<b>全列插入</b>（{@code DEFAULT} 只在列没出现在语句里时才生效），
 * 传 null 就是往 {@code NOT NULL} 列里显式写 NULL，MySQL 报 errno 1048。
 *
 * <p>★ 所以 Service 里必须用<b>同一个值</b>再兜一次底，这就是它被写成
 * {@code public static final} 而不是内联字面量的原因：两处各写一个 {@code 0}，
 * 哪天有人改了一处（比如想默认成「第一辆车就是默认车」），另一处就成了
 * 一个静默的不一致。同样的手法见 {@code ChargePackageDTO} 的四个 DEFAULT_* 常量。
 *
 * <h2>★★ 二、{@code plateNo} <b>不做格式正则</b>，但入库会转大写</h2>
 *
 * <p><b>不做正则</b>的理由和 {@code StationDTO.code} 一致：真正的约束是
 * <b>唯一性</b>（{@code uk_vehicle_member_plate}），而那由数据库守。车牌格式有
 * 新能源、使馆、港澳、教练、警车等多种，写死正则会挡住合法输入，
 * 而它挡住的东西<b>没有一条是数据库拦不住的</b>。
 *
 * <p><b>入库转大写</b>（{@code VehicleServiceImpl.normalizePlateNo}）<b>不是</b>
 * 为了唯一性 —— 排序规则 {@code utf8mb4_0900_ai_ci} 本来就大小写不敏感，唯一索引
 * 早就把 {@code 粤BD12345} 和 {@code 粤bd12345} 当成同一个值了（已真机验证）。
 * 三条真实理由见 {@code Vehicle} 的类注释第二节，其中「尾随空格不受唯一索引约束」
 * 那一条是<b>正确性</b>问题。
 *
 * <h2>★ 三、{@code isDefault} 可以传，但<b>第一辆车会被强制置 1</b></h2>
 *
 * <p>刚注册的车主加第一辆车时，「设为默认」没有意义（只有一个选择），而那辆车
 * 不是默认车的话前端还得引导他再点一次，所以 Service 会在
 * {@code countByMemberId == 0} 时覆盖这个字段。★ 这件事写在这里而不是留给前端，
 * 是因为它是<b>不变量的一部分</b>：「有车的车主一定有且只有一辆默认车」如果靠
 * 前端维持，任何直接调 API 的客户端都能破坏它。
 */
@Data
@Schema(description = "新增车辆")
public class VehicleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** ★ {@code is_default} 的默认值；为什么是常量见类注释第一节 */
    public static final Integer DEFAULT_IS_DEFAULT = 0;

    /**
     * ★ 只校验非空和长度、<b>不做格式正则</b>（见类注释第二节）；
     * 上限 {@code 16} 与 {@code vehicle.plate_no} 的列宽对齐。
     */
    @Schema(description = "车牌号，入库统一转大写", example = "粤BD12345")
    @NotBlank(message = "车牌号不能为空")
    @Size(max = 16, message = "车牌号不能超过 16 个字符")
    private String plateNo;

    /** 可空；取值见 {@code PlateColor} */
    @Schema(description = "车牌颜色：1蓝 2黄 3绿 4渐变绿", example = "3")
    @Min(value = 1, message = "车牌颜色只能是 1蓝、2黄、3绿、4渐变绿")
    @Max(value = 4, message = "车牌颜色只能是 1蓝、2黄、3绿、4渐变绿")
    private Integer plateColor;

    @Schema(description = "品牌", example = "比亚迪")
    @Size(max = 50, message = "品牌不能超过 50 个字符")
    private String brand;

    @Schema(description = "型号", example = "汉 EV")
    @Size(max = 50, message = "型号不能超过 50 个字符")
    private String model;

    /**
     * ★ <b>不参与计费</b>，只用于估算充满需要多少电，所以可空、不设上界。
     * 下界 {@code 0} 排掉负数：{@code -80} 度电的电池算出来的预计电量是负的。
     */
    @Schema(description = "电池容量，kWh", example = "85.00")
    @DecimalMin(value = "0", message = "电池容量不能为负数")
    private BigDecimal batteryCapacityKwh;

    /**
     * ★ 不传 = {@code 0}（非默认），但<b>第一辆车会被 Service 强制置 1</b> ——
     * 见类注释第三节。
     */
    @Schema(description = "是否默认车：1是 0否；不传为 0", example = "1", defaultValue = "0")
    @Min(value = 0, message = "是否默认车只能是 0 或 1")
    @Max(value = 1, message = "是否默认车只能是 0 或 1")
    private Integer isDefault = DEFAULT_IS_DEFAULT;

    @Schema(description = "备注", example = "家用车")
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
