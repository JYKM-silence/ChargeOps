package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 修改充电套餐的请求体
 *
 * 一、{@code packageType} 不可改 —— 而「局部更新不能清空字段」正是它不可改的原因
 *
 * <p>表面理由很好说：类型决定哪些列必填，改类型是一个跨字段的语义变更。
 * 但真正让这件事在技术上做不到的是本项目的更新语义：
 * 把一个「150 度电包」改成「30 天时长包」，需要两步同时发生 ——
 * 写入 {@code duration_minutes = 43200}，并且清空 {@code energy_kwh}。
 * 而 {@code <set> + <if test="x != null">} 的语义是「不传 = 不动」，
 * 它表达不了「清空」。
 *
 * <p>于是改完类型的结果是「时长包但 {@code energyKwh} 还是 150」，
 * 而应用套餐时读到那个残留值，会在一张时长包上白白送出 150 度电。
 * 金额对不上，但每一步都没报错。
 *
 * <p> 这和「{@code originPrice} 想去掉划线价只能改成一个数」是同一个限制，
 * 但后果严重程度差一个量级：前者损失真金白银，后者只是界面不好看。
 * 所以策略也不同 —— 这个字段直接从 DTO 里去掉（编译期事实），
 * 而 {@code originPrice} 保留但记下限制。
 *
 * 二、{@code stationId} 不可改
 *
 * <p>它参与唯一键 {@code uk_pkg_name_station (name, station_id)}。
 * 允许改它等于允许把已经售出的套餐挪到另一个站点作用域下 ——
 * 那些在别的站点买了这个套餐的用户，会突然发现它「不属于」自己的站点。
 *
 * <p>而且改了之后唯一键要重新检查：从科技园站挪到全平台时，
 * 如果全平台已有一个同名的，就变成一次必然失败的更新，
 * 而错误消息是「已存在」，指向的是那个早就存在的套餐，不是这次操作。
 *
 * 三、四个类型专属字段都在，校验的是【合并后的行】
 *
 * <p>{@code energyKwh} / {@code durationMinutes} / {@code times} /
 * {@code serviceDiscount} 四个都在这里，因为类型不变的情况下，
 * 改的是「那个类型的值」—— 比如把 150 度改成 200 度。
 *
 * <p> 但 {@code ChargePackageServiceImpl.assertTypeFields} 校验的
 * 不是这个 DTO，而是用 DTO 的非空值覆盖到「从库里读出的行」上之后的结果：
 * 只改价格的管理员不会传 {@code energyKwh}（他没打算动它，
 * 甚至可能没看到它），那个值只能从库里来，拿 DTO 去校验会误判成「电量包缺电量」。
 *
 * <p>反过来，往一个电量包上写 {@code times = 10} 时，合并后的行会同时有
 * {@code energyKwh} 和 {@code times} 两个非空值，而「其余三个必须为 null」
 * 那一半校验会拒绝它 —— 一个条件同时守住了两个方向。
 */
@Data
@Schema(description = "修改充电套餐")
public class ChargePackageUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "套餐名称，同一作用域内唯一；不传表示不修改", example = "150度电包")
    @Size(max = 64, message = "套餐名称不能超过 64 个字符")
    private String name;

    @Schema(description = "分类 id；不传表示不修改", example = "1")
    private Long categoryId;

    @Schema(description = "售价，元；不传表示不修改", example = "100.00")
    @DecimalMin(value = "0", message = "售价不能为负数")
    private BigDecimal price;

    /**
     * 划线价（原价）
     *
     * <p> 设了就抹不掉：想把「划线价」去掉，改成什么值都还是有一个划线价。
     * 这是「不传 = 不动」的直接推论，和 {@code StationUpdateDTO} 里
     * 说的同一个限制（修法也一样，已在那个类的注释里记下）。
     *
     * <p> 本轮想让划线价不再展示，唯一的手段是把它设成和 {@code price} 相等 ——
     * 前端渲染出来是「<s>¥100.00</s> ¥100.00」。难看，
     * 但它诚实地反映了当前的存储状态，比一个假的「已清空」要好。
     */
    @Schema(description = "划线价（原价），元；注意：设了之后无法清空", example = "150.00")
    @DecimalMin(value = "0", message = "划线价不能为负数")
    private BigDecimal originPrice;

    @Schema(description = "电量，kWh；仅当套餐是电量包时有意义", example = "150.00")
    @DecimalMin(value = "0", inclusive = false, message = "电量必须大于 0")
    private BigDecimal energyKwh;

    @Schema(description = "时长，分钟；仅当套餐是时长包时有意义", example = "43200")
    @Min(value = 1, message = "时长必须大于 0 分钟")
    private Integer durationMinutes;

    @Schema(description = "次数；仅当套餐是次卡时有意义", example = "10")
    @Min(value = 1, message = "次数必须大于 0")
    private Integer times;

    /**  仅当套餐是服务费折扣包时有意义；取值范围同 {@code ChargePackageDTO} */
    @Schema(description = "服务费折扣，0.8 表示八折；仅当套餐是服务费折扣包时有意义", example = "0.8000")
    @DecimalMin(value = "0", inclusive = false, message = "服务费折扣必须大于 0")
    @DecimalMax(value = "1", message = "服务费折扣不能大于 1（大于 1 是加价，不是折扣）")
    private BigDecimal serviceDiscount;

    /**
     * 这个字段可以改成 0：{@code 0} 是有意义的值（永久有效），
     * 而「不传」用 null 表达，两者在 Java 对象上是不同的状态 ——
     * 所以 {@code ChargePackageMapper.xml} 里它的 {@code <if>} 只能是
     * {@code validDays != null}。
     *
     * <p> 规律（本项目反复出现：{@code station_id=0} 表示全平台、
     * {@code parkingFee=0} 表示免费停车）：先问这个字段的 0 有没有业务含义，
     * 再决定 {@code <if>} 怎么写。有含义就只判 null，没含义才判 {@code != 0}。
     */
    @Schema(description = "有效期天数；0 表示永久有效；不传表示不修改", example = "0")
    @Min(value = 0, message = "有效期天数不能为负数")
    private Integer validDays;

    @Schema(description = "套餐说明；不传表示不修改", example = "150 度电，全国通用")
    @Size(max = 512, message = "套餐说明不能超过 512 个字符")
    private String description;

    @Schema(description = "图片地址；不传表示不修改", example = "https://cdn.example.com/pkg/150kwh.png")
    @Size(max = 255, message = "图片地址不能超过 255 个字符")
    private String image;

    /**  下架是套餐的常规退役手段，不是删除：历史订单还引用着它 */
    @Schema(description = "状态：1上架 0下架；不传表示不修改", example = "1")
    @Min(value = 0, message = "状态只能是 0下架、1上架")
    @Max(value = 1, message = "状态只能是 0下架、1上架")
    private Integer status;

    @Schema(description = "排序值，小的在前；不传表示不修改", example = "0")
    private Integer sort;
}
