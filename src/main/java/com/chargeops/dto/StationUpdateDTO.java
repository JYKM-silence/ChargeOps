package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 修改充电站的请求体
 *
 * 一、局部更新：只有【非空】字段会被写进去
 *
 * <p>这里没有 {@code @NotNull} —— 一个字段传了就改、不传就不动。
 * 对应的 SQL 是 {@code <set> + <if test="x != null">}，
 * 见 {@code StationMapper.xml} 的 update。
 *
 * <p> 代价：本接口无法把字段清空。「把运营商和电话删掉后保存，
 * 刷新一遍它们还在」—— 这不是 bug，是「不传 = 不动」的直接推论：
 * JSON 里写 {@code "operator": null} 和「根本没写 operator」在 Java 对象上
 * 是同一个状态，后端分不出来调用方想要哪一个。本轮刻意接受这个限制，
 * 理由是 {@code StaffMapper.update} 已经是这个行为，
 * 同一轮里引入第二套更新约定比这个限制本身更糟。
 *
 * <p>真正的修法：用 {@code BeanMergeUtils.copyNonNullProperties} 把 DTO 合到
 * 从库读出的行上，然后走去掉所有 {@code <if>} 的全行更新 —— 那时「不传」
 * 的语义变成「保留库里的值」，而「想清空就传空字符串」在类型上也就可表达了。
 *
 * 二、{@code code} 可以改
 *
 * <p>它虽然是唯一索引 {@code uk_station_code} 的列，但唯一索引能拦住重复 ——
 * 「改成已存在的编码」会得到一条 {@code DuplicateKeyException} 翻译出来的
 * 「已存在」消息，是一个能解释自己的失败，不需要在这里禁掉这个字段。
 * 而 {@code station_tariff} 这类引用站点 id 的表不受影响（它们存的是
 * {@code station_id}）—— 这就是「代理键做主键」的好处：业务标识可以改，
 * 而引用不动。
 */
@Data
@Schema(description = "修改充电站")
public class StationUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 与创建时同一条正则，没有 {@code @NotBlank}：不传表示不改编码，
     * 而不是「把编码改成空」。传空字符串同样会被
     * {@code <if test="code != null and code != ''">} 跳过更新，
     * 这个行为和 {@code @Pattern} 的拒绝是一致的。
     */
    @Schema(description = "站点编码，唯一；不传表示不修改", example = "ST-440300-0001")
    @Size(max = 32, message = "站点编码不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "站点编码只能是字母、数字和连字符")
    private String code;

    @Schema(description = "站点名称；不传表示不修改", example = "科技园充电站")
    @Size(max = 64, message = "站点名称不能超过 64 个字符")
    private String name;

    @Schema(description = "运营商；不传表示不修改", example = "ChargeOps")
    @Size(max = 64, message = "运营商不能超过 64 个字符")
    private String operator;

    @Schema(description = "省；不传表示不修改", example = "广东省")
    @Size(max = 32, message = "省不能超过 32 个字符")
    private String province;

    @Schema(description = "市；不传表示不修改", example = "深圳市")
    @Size(max = 32, message = "市不能超过 32 个字符")
    private String city;

    @Schema(description = "区/县；不传表示不修改", example = "南山区")
    @Size(max = 32, message = "区/县不能超过 32 个字符")
    private String district;

    @Schema(description = "详细地址；不传表示不修改", example = "科技园南路 1 号")
    @Size(max = 255, message = "地址不能超过 255 个字符")
    private String address;

    @Schema(description = "经度；不传表示不修改", example = "113.943000")
    @DecimalMin(value = "-180", message = "经度不能小于 -180")
    @DecimalMax(value = "180", message = "经度不能大于 180")
    private BigDecimal longitude;

    @Schema(description = "纬度；不传表示不修改", example = "22.528000")
    @DecimalMin(value = "-90", message = "纬度不能小于 -90")
    @DecimalMax(value = "90", message = "纬度不能大于 90")
    private BigDecimal latitude;

    @Schema(description = "服务电话；不传表示不修改", example = "0755-88886666")
    @Size(max = 20, message = "服务电话不能超过 20 个字符")
    private String servicePhone;

    /**
     * 这里没有字段初始值（创建 DTO 里有 {@code "00:00:00"}），差别是故意的：
     * 创建时「不传」要被解释成一个合理的默认值，而修改时「不传」必须保持「不动」——
     * 否则每次改个电话都会把营业时间重置成全天。
     */
    @Schema(description = "开始营业时间，HH:mm 或 HH:mm:ss；不传表示不修改", example = "00:00:00")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$",
            message = "开始营业时间格式应为 HH:mm 或 HH:mm:ss")
    private String openTime;

    @Schema(description = "结束营业时间（不含），24:00:00 表示到当天结束；不传表示不修改",
            example = "24:00:00")
    @Pattern(regexp = "^(([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?|24:00:00)$",
            message = "结束营业时间格式应为 HH:mm、HH:mm:ss，或 24:00:00")
    private String closeTime;

    /**
     * {@code 0} 是一个有意义的值（「这里停车不要钱」），和 null（不修改）不同 ——
     * 所以 {@code <if>} 判断只能是 {@code parkingFee != null}，
     * 不能写成 {@code parkingFee != null and parkingFee != 0}。
     *
     * <p> 这类「0 是不是缺省」的判断在本项目里反复出现（还有
     * {@code charge_package.stationId=0} 表示全平台、{@code station_id=0}
     * 表示平台级账号），规律是：先问这个字段的 0 有没有业务含义，
     * 再决定 {@code <if>} 怎么写。
     */
    @Schema(description = "停车费，元/小时；0 表示免费；不传表示不修改", example = "5.00")
    @DecimalMin(value = "0", message = "停车费不能为负数")
    private BigDecimal parkingFee;

    /** {@code 0停用 1启用 2维护中}，不传表示不修改 */
    @Schema(description = "状态：0停用 1启用 2维护中；不传表示不修改", example = "1")
    @Min(value = 0, message = "状态只能是 0停用、1启用、2维护中")
    @Max(value = 2, message = "状态只能是 0停用、1启用、2维护中")
    private Integer status;
}
