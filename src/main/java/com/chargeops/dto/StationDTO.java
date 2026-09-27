package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 新增充电站的请求体
 *
 * 一、营业时间用 {@code String}，而且允许 {@code "24:00:00"}
 *
 * <p>{@code openTime} / {@code closeTime} 是字符串（{@code "HH:mm"} 或
 * {@code "HH:mm:ss"}），不是 {@code LocalTime} —— 因为 {@code "24:00:00"}
 * （表示「到当天结束」）在 {@code LocalTime} 的定义域之外，它的上限是
 * {@code 23:59:59.999999999}。更本质的说法：MySQL 的 {@code TIME} 不是
 * 「一天中的时刻」，而是有符号时长（±838:59:59），这才是 {@code '24:00:00'}
 * 合法的原因，把它映射成 {@code LocalTime} 是范畴错误。
 *
 * <p> 这两个字段有字段初始值（{@code "00:00:00"} / {@code "24:00:00"}），
 * 含义是「全天营业」—— 不能用 DDL 的 {@code DEFAULT} 兜底：insert 是全列插入，
 * 传 null 会直接撞 {@code NOT NULL}（errno 1048），这个坑集成测试第一次跑就踩到了。
 * 默认值会出现在 Swagger 文档里。
 *
 * 二、{@code closeTime} 必须晚于 {@code openTime}，所以【不支持跨夜营业】
 *
 * <p>{@code "22:00"–"06:00"} 这种夜间站点表达不了：只有一对 open/close，
 * 没有任何字段能表示「结束时间是次日」。这是从 DDL 继承来的限制，
 * 不是本轮漏掉的。校验（{@code closeMinute > openMinute}）放在
 * {@code StationServiceImpl} 里，因为它是跨字段的语义检查，
 * 单个字段上的注解管不了两个字段之间的关系。真要支持跨夜，要加「次日标记」
 * 或者改成两段营业时间 —— 那是 schema 变更。
 *
 * 三、经纬度是 {@code BigDecimal}，不是 {@code double}
 *
 * <p>{@code decimal(10,6)} 对应约 0.1 米的精度。用 {@code double} 的话经度会有
 * 浮点漂移，实际后果是<strong>地图上偏移几十米</strong> ——
 * 而「找最近的充电站」正是这个字段唯一的用途。
 */
@Data
@Schema(description = "新增充电站")
public class StationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全天营业的起始时刻
     *
     * <p> 公开常量，而不是在字段上写一遍字面量。
     * 字段初始值挡不住显式的 {@code "openTime": null}（Jackson 照常调
     * setter 把 null 写进去），所以 {@code StationServiceImpl} 必须再兜一次底，
     * 而那一次兜底要用的值和这里的初始值必须是同一个 —— 写成两个字面量的话，
     * 改了一处忘另一处，表现是「不传是全天营业、传 null 是一天 23 小时」
     * 这种没人会想到去查的事。
     */
    public static final String DEFAULT_OPEN_TIME = "00:00:00";

    /**  是 {@code "24:00:00"}，不是 {@code "00:00:00"} —— 后者会让新站点默认「零点就关门」 */
    public static final String DEFAULT_CLOSE_TIME = "24:00:00";

    /**
     * 站点编码，如 {@code "ST-440300-0001"}，唯一索引 {@code uk_station_code}
     *
     * <p> 约束刻意宽松（只要求是字母数字和连字符）：编码是对外可见的标识，
     * 各地区、各运营商的命名习惯不同，用一个精确的正则去卡
     * （比如强制 {@code ST-\\d{6}-\\d{4}}）会把合法的编码挡在门外。
     * 真正的约束是唯一性，而那由数据库守。
     */
    @Schema(description = "站点编码，唯一", example = "ST-440300-0001")
    @NotBlank(message = "站点编码不能为空")
    @Size(max = 32, message = "站点编码不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "站点编码只能是字母、数字和连字符")
    private String code;

    @Schema(description = "站点名称", example = "科技园充电站")
    @NotBlank(message = "站点名称不能为空")
    @Size(max = 64, message = "站点名称不能超过 64 个字符")
    private String name;

    @Schema(description = "运营商", example = "ChargeOps")
    @Size(max = 64, message = "运营商不能超过 64 个字符")
    private String operator;

    @Schema(description = "省", example = "广东省")
    @Size(max = 32, message = "省不能超过 32 个字符")
    private String province;

    /** 城市。 走索引 {@code idx_station_city_status} 的第一列，所以按城市筛选很快 */
    @Schema(description = "市", example = "深圳市")
    @Size(max = 32, message = "市不能超过 32 个字符")
    private String city;

    @Schema(description = "区/县", example = "南山区")
    @Size(max = 32, message = "区/县不能超过 32 个字符")
    private String district;

    @Schema(description = "详细地址", example = "科技园南路 1 号")
    @Size(max = 255, message = "地址不能超过 255 个字符")
    private String address;

    /**
     * 经度，{@code decimal(10,6)}
     *
     * <p> 范围校验是有价值的：经纬度写反是一个经典错误，而
     * {@code (113.9, 22.5)} 反过来变成 {@code (22.5, 113.9)} 之后仍然是一个
     * 合法的坐标 —— 只是落在完全不同的地方。加了范围之后，
     * 被交换的一对里至少有一个必然越界（{@code 113.9} 超出纬度上限）。
     */
    @Schema(description = "经度", example = "113.943000")
    @DecimalMin(value = "-180", message = "经度不能小于 -180")
    @DecimalMax(value = "180", message = "经度不能大于 180")
    private BigDecimal longitude;

    @Schema(description = "纬度", example = "22.528000")
    @DecimalMin(value = "-90", message = "纬度不能小于 -90")
    @DecimalMax(value = "90", message = "纬度不能大于 90")
    private BigDecimal latitude;

    @Schema(description = "服务电话", example = "0755-88886666")
    @Size(max = 20, message = "服务电话不能超过 20 个字符")
    private String servicePhone;

    /**  默认 {@code "00:00:00"}，即全天营业 */
    @Schema(description = "开始营业时间，HH:mm 或 HH:mm:ss", example = "00:00:00",
            defaultValue = "00:00:00")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$",
            message = "开始营业时间格式应为 HH:mm 或 HH:mm:ss")
    private String openTime = DEFAULT_OPEN_TIME;

    /**
     * 结束营业时间（不含）
     *
     * <p> 允许 {@code "24:00:00"} —— 这是唯一一个 {@code LocalTime}
     * 装不下的值，也正是这两个字段用 {@code String} 的原因。默认就是它（全天营业），
     * 不是 {@code "00:00:00"}（那会让新站点默认「零点就关门」）。
     *
     * <p> 必须晚于 {@code openTime}，因此不支持跨夜营业，见类注释第二节。
     */
    @Schema(description = "结束营业时间（不含），24:00:00 表示到当天结束",
            example = "24:00:00", defaultValue = "24:00:00")
    @Pattern(regexp = "^(([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?|24:00:00)$",
            message = "结束营业时间格式应为 HH:mm、HH:mm:ss，或 24:00:00")
    private String closeTime = DEFAULT_CLOSE_TIME;

    @Schema(description = "停车费，元/小时", example = "5.00")
    @DecimalMin(value = "0", message = "停车费不能为负数")
    private BigDecimal parkingFee;

    /**
     * {@code 0停用 1启用 2维护中} —— 三个值，
     * 所以用不了只在 0/1 之间二分的 {@code StatusConstant}。见 {@code StationStatus}。
     */
    @Schema(description = "状态：0停用 1启用 2维护中", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用、2维护中")
    @Max(value = 2, message = "状态只能是 0停用、1启用、2维护中")
    private Integer status = 1;
}
