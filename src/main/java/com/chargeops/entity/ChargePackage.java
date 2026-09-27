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
 * <h2>★ 一、四个「类型专属列」，每行只该有一个非空</h2>
 *
 * <pre>
 *   package_type = 1 电量包        → energy_kwh       必填，其余三个为 NULL
 *   package_type = 2 时长包        → duration_minutes 必填，其余三个为 NULL
 *   package_type = 3 服务费折扣包  → service_discount 必填，其余三个为 NULL
 *   package_type = 4 次卡          → times            必填，其余三个为 NULL
 * </pre>
 *
 * <p>MySQL 没有「条件必填」这种约束，这条规则<b>只能靠应用层守</b>，
 * 校验落在 {@code ChargePackageServiceImpl.assertTypeFields}。
 * 「必填的要有」只是一半，更要紧的是「其余三个必须为 NULL」——
 * 残留字段是<b>静默的</b>：一个从「150 度电包」改成「30 天时长包」的套餐，
 * 如果 {@code energyKwh} 还留着 150，应用套餐时就会在一个时长包上白白送出 150 度电，
 * 金额对不上但每一步都没报错。
 * 更新时校验的是<b>合并后的行</b>而不是 DTO —— 只改价格的管理员不会传
 * {@code energyKwh}，那个值只能从库里来。
 *
 * <p>不拆成老库那种 {@code setmeal + setmeal_dish} 嵌套结构：
 * 充电套餐是「花 100 元买 150 度电」这种<b>单一权益</b>，没有子项，
 * 硬套只会造出一张永远只有一行子记录的关系表。
 *
 * <h2>★ 二、{@code stationId} 用 {@code 0} 表示全平台，不用 {@code NULL}</h2>
 *
 * <p>唯一索引 {@code uk_pkg_name_station (name, station_id)} 建在它上面，而
 * <b>MySQL 的唯一索引不约束 NULL</b> —— 全平台套餐若用 {@code NULL}，
 * 这个作用域就完全不受唯一键保护，同名套餐可以被无限重复创建，约束等于废掉。
 * 用 {@code 0} 之后，「150度电包」在 {@code station_id=0}（全平台）和
 * {@code station_id=1}（科技园站）可以各存在一份，第二份全平台的会撞唯一键。
 *
 * <p>代价：这个字段<b>永不为 null</b>，「不传站点」的语义是「全平台」而不是「没填」——
 * DTO 里必须有明确的默认值 {@code 0}。
 *
 * <h2>★ 三、{@code status} 是真正的二值字段</h2>
 *
 * <p>{@code 1上架 0下架}，对应 {@link com.chargeops.constant.StatusConstant}。
 * 「下架」是常规退役手段（保留历史订单的引用）；硬删也在，
 * 因为 {@code uk_pkg_name_station} 不看 status：名字建错一次就永久占用，
 * 只能先把错的删掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargePackage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 分类 id，指向 {@code category} 表 */
    private Long categoryId;

    /** {@code 1电量包 2时长包 3服务费折扣包 4次卡} —— 决定哪些专属列该有值 */
    private Integer packageType;

    /**
     * 限定站点；<b>{@code 0} 表示全平台通用</b>，★ 永不为 {@code null}（见类注释第二节）
     *
     * <p>★ <b>不出现在 UpdateDTO 里</b>：它参与唯一键 {@code uk_pkg_name_station}，
     * 允许改它等于允许把一个已售出的套餐挪到别的站点作用域下。
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

    /**
     * {@code package_type=3} 时的服务费折扣，{@code decimal(5,4)}，{@code 0.8000} 表示八折
     *
     * <p>四位小数是因为它要和 {@code decimal(10,4)} 的服务费单价相乘，
     * 折扣精度不够会直接体现为金额误差。业务上应在 {@code (0, 1]} 之间，
     * 那是 Service 的校验，不是列宽的事。
     */
    private BigDecimal serviceDiscount;

    /** 有效期天数；{@code 0} 表示永久 */
    private Integer validDays;

    private String description;

    private String image;

    /** {@code 1上架 0下架} —— 对应 {@link com.chargeops.constant.StatusConstant} */
    private Integer status;

    private Integer sort;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充；本表四列齐全，Mapper 的写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
