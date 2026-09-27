package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电套餐（对应 {@code charge_package} 表）
 *
 * <p>四个「类型专属列」每行只该有一个非空：{@code package_type = 1} 电量包 → {@code energy_kwh}，
 * 2 时长包 → {@code duration_minutes}，3 服务费折扣包 → {@code service_discount}，4 次卡 → {@code times}，
 * 其余三个必须为 NULL —— 残留字段是静默的（时长包上白送 150 度电，金额对不上但每一步都不报错），
 * 校验落在 {@code ChargePackageServiceImpl.assertTypeFields}。
 *
 * <p>{@code stationId} 用 {@code 0} 而不是 {@code NULL} 表示全平台：{@code uk_pkg_name_station (name, station_id)}
 * 不约束 NULL，用 NULL 的全平台套餐完全不受唯一键保护，同名套餐可以被无限重复创建。
 *
 * <p>{@code status} 是 {@code 1上架 0下架}；{@code uk_pkg_name_station} 不看 status，名字建错只能硬删。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargePackage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Long categoryId;

    /** {@code 1电量包 2时长包 3服务费折扣包 4次卡} —— 决定哪些专属列该有值 */
    private Integer packageType;

    /**
     * 限定站点；{@code 0} 表示全平台通用，永不为 {@code null}（DTO 里必须有默认值 {@code 0}）
     *
     * <p> 不出现在 UpdateDTO 里：它参与唯一键 {@code uk_pkg_name_station}，
     * 改它等于把一个已售出的套餐挪到别的站点作用域下。
     */
    private Long stationId;

    /** 售价，{@code decimal(12,2)} */
    private BigDecimal price;

    /** 划线价（原价），展示用。{@code NULL} 表示不展示划线 */
    private BigDecimal originPrice;

    /** {@code package_type=1} 时的电量，{@code decimal(10,2)} */
    private BigDecimal energyKwh;

    /** {@code package_type=2} 时的时长（分钟） */
    private Integer durationMinutes;

    /** {@code package_type=4} 时的次数 */
    private Integer times;

    /** {@code package_type=3} 时的服务费折扣，{@code decimal(5,4)}，{@code 0.8000} 表示八折。
     * 四位小数是因为它要和 {@code decimal(10,4)} 的服务费单价相乘，精度不够会直接体现为金额误差 */
    private BigDecimal serviceDiscount;

    /** 有效期天数；{@code 0} 表示永久 */
    private Integer validDays;

    private String description;

    private String image;

    /** {@code 1上架 0下架} —— 对应 {@link com.chargeops.constant.StatusConstant} */
    private Integer status;

    private Integer sort;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
