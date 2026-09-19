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
 * <p>MySQL 没有「条件必填」这种约束能力，所以这条规则<b>只能靠应用层守</b>。
 * 校验落在 {@code ChargePackageServiceImpl.assertTypeFields}，而且：
 *
 * <ul>
 *   <li><b>两个方向都要校验。</b>「必填的要有」只是其中一半 —— 更要紧的是
 *       「其余三个必须为 NULL」。残留字段是<b>静默的</b>：一个从「150 度电包」
 *       改成「30 天时长包」的套餐，如果 {@code energyKwh} 还留着 150，
 *       P1c 应用套餐时读到它，就会在一个时长包上白白送出 150 度电。
 *       金额对不上，但每一步都没报错</li>
 *   <li><b>更新时校验的是「合并后的行」</b>，不是 DTO。
 *       只改价格的管理员不会传 {@code energyKwh}，那个字段的值只能从库里来 ——
 *       所以用 DTO 的非空值覆盖到从库里读出的行上，再整体校验</li>
 * </ul>
 *
 * <p>为什么不像老库那样拆成 {@code setmeal + setmeal_dish} 嵌套结构：
 * 那套是「套餐由多个菜品按份数组成」的语义，所以必须有关系表。
 * 而充电套餐是「花 100 元买 150 度电」这种<b>单一权益</b>，没有子项 ——
 * 硬套会造出一张永远只有一行子记录的关系表。
 *
 * <h2>★ 二、{@code stationId} 用 {@code 0} 表示全平台，不用 {@code NULL}</h2>
 *
 * <p>唯一索引是 {@code uk_pkg_name_station (name, station_id)}。
 * 而 <b>MySQL 的唯一索引不约束 NULL</b> —— 如果全平台套餐用 {@code NULL}，
 * 「全平台」这个作用域就完全不受唯一键保护，
 * 同名套餐可以被无限重复创建，约束等于废掉。
 *
 * <p>用 {@code 0} 之后，「150度电包」在 {@code station_id=0}（全平台）
 * 和 {@code station_id=1}（科技园站）可以各存在一份，这是想要的行为；
 * 而 {@code station_id=0} 的第二份会撞唯一键，这也是想要的。
 *
 * <p>代价：这个字段<b>永不为 null</b>，所以「不传站点」的语义是「全平台」
 * 而不是「没填」—— DTO 里必须有明确的默认值 {@code 0}，不能留 null。
 *
 * <h2>★ 三、{@code status} 是真正的二值字段</h2>
 *
 * <p>{@code 1上架 0下架}，对应 {@link com.chargeops.constant.StatusConstant} ——
 * 全项目只有账号和套餐能复用它，因为只有它们真的是开关。
 *
 * <p>「下架」是套餐的常规退役手段（保留历史订单的引用），
 * 而硬删也在（见 {@code ChargePackageService.delete}），
 * 因为 {@code uk_pkg_name_station} 不看 status：
 * 名字建错一次就永久占用，「150度电包」打成「150度点包」之后再想用正确的名字，
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
     * 限定站点；<b>{@code 0} 表示全平台通用</b>
     *
     * <p>★ 永不为 {@code null} —— 原因见类注释第二节。
     * 也<b>不出现在 UpdateDTO 里</b>：它参与唯一键 {@code uk_pkg_name_station}，
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
     * {@code package_type=3} 时的服务费折扣，{@code decimal(5,4)}
     *
     * <p>{@code 0.8000} 表示八折。四位小数是因为它要和
     * {@code decimal(10,4)} 的服务费单价相乘，折扣精度不够会直接体现为金额误差。
     *
     * <p>上限由列宽 {@code decimal(5,4)} 决定：最大 {@code 9.9999}。
     * 业务上应该在 {@code (0, 1]} 之间，但那是 Service 的校验，不是列宽的事。
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
