package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 新增充电套餐的请求体
 *
 * <h2>★★ 一、四个字段的【字段初始值】不是风格问题，是必需的</h2>
 *
 * <p>{@code ChargePackageMapper.xml} 的 insert 是<b>全列插入</b>，
 * 而 SQL 的 {@code DEFAULT} 只在列<b>没出现在语句里</b>时生效 ——
 * 传 null 就是往 {@code NOT NULL} 列里显式写 NULL，MySQL 直接报 errno 1048，
 * <b>不会</b>用上 DDL 写的默认值。
 *
 * <table border="1">
 *   <caption>必须由 DTO 提供的默认值</caption>
 *   <tr><th>字段</th><th>DDL 默认值</th><th>这里的初始值</th><th>含义</th></tr>
 *   <tr><td>{@code stationId}</td><td>0</td><td>{@code 0L}</td><td>0 = 全平台通用</td></tr>
 *   <tr><td>{@code validDays}</td><td>0</td><td>{@code 0}</td><td>0 = 永久有效</td></tr>
 *   <tr><td>{@code status}</td><td>1</td><td>{@code 1}</td><td>1 = 上架</td></tr>
 *   <tr><td>{@code sort}</td><td>0</td><td>{@code 0}</td><td>0 = 默认排序位</td></tr>
 * </table>
 *
 * <p>这个坑在本次集成测试里<b>连踩三次</b>（{@code station.open_time}、
 * {@code charger.rated_power_kw}、{@code charger.gun_count}），
 * 所以它不是巧合，是「全列插入」这个约定和「DDL 写 DEFAULT」之间的系统性摩擦。
 * 结论是：<b>别把 DDL 的 DEFAULT 当成能被依赖的保证</b>，
 * 「不填会怎样」由这里的字段初始值决定并写进 Swagger，
 * DDL 的 DEFAULT 只留给手工 SQL 兜底。
 *
 * <h2>★★ 二、{@code stationId} 用 {@code 0} 而不是 {@code null} 表示全平台</h2>
 *
 * <p>唯一索引是 {@code uk_pkg_name_station (name, station_id)}，
 * 而 <b>MySQL 的唯一索引不约束 NULL</b>。
 *
 * <p>如果全平台套餐用 {@code NULL}，「全平台」这个作用域就完全不受唯一键保护，
 * 同名套餐可以被无限重复创建 —— <b>约束等于废掉，而且是静默废掉</b>：
 * 你查 {@code SHOW INDEX} 还能看到那个唯一索引，只是它管不到你想要的那一行。
 *
 * <p>所以这个字段<b>永不为 null</b>，默认 {@code 0} 表示全平台。
 * 见 {@code ChargePackage} 类注释第二节。
 *
 * <h2>★★ 三、{@code packageType} 决定哪些字段必填 —— 但校验不在这里</h2>
 *
 * <pre>
 *   1 电量包        → energyKwh       必填，其余三个必须为 NULL
 *   2 时长包        → durationMinutes 必填，其余三个必须为 NULL
 *   3 服务费折扣包  → serviceDiscount  必填，其余三个必须为 NULL
 *   4 次卡          → times           必填，其余三个必须为 NULL
 * </pre>
 *
 * <p>「条件必填」在 Jakarta Validation 里写不出来 ——
 * 注解是<b>逐字段</b>的，而这个规则是<b>字段之间</b>的
 * （{@code energyKwh} 该不该必填，取决于 {@code packageType} 的值）。
 * 硬写要自定义类级约束，而那种约束的错误消息指向不了具体字段。
 *
 * <p>所以校验在 {@code ChargePackageServiceImpl.assertTypeFields}，
 * 它<b>两个方向都要查</b>：
 *
 * <ul>
 *   <li>类型要求的字段必须有（缺了报错）</li>
 *   <li>★ <b>其余三个必须为 NULL</b> —— 这一半更要紧，因为它是静默的。
 *       一个从「150 度电包」改成「30 天时长包」的套餐，
 *       如果 {@code energyKwh} 还留着 150，P1c 应用套餐时读到它，
 *       就会在一个时长包上白白送出 150 度电。
 *       金额对不上，但<b>每一步都没报错</b></li>
 * </ul>
 *
 * <p>★ 这里给每个字段留了 {@code @DecimalMin/@Min} 的下界，
 * 是为了让「-100 度电」这类值在进 Service 之前就被挡掉。
 * 上界不加：那是业务定价，不是格式。
 */
@Data
@Schema(description = "新增充电套餐")
public class ChargePackageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * {@code stationId} 的默认值：{@code 0} = 全平台通用
     *
     * <p>★ 四个常量都是<b>公开的</b>，不是内联的字面量。原因见
     * {@code StationDTO.DEFAULT_OPEN_TIME} 的注释：字段初始值挡不住
     * 显式的 JSON {@code null}，所以 {@code ChargePackageServiceImpl}
     * 必须再兜一次底，而那一次兜底要用的值必须和这里的初始值是同一个。
     */
    public static final Long DEFAULT_STATION_ID = 0L;

    /** {@code validDays} 的默认值：{@code 0} = 永久有效 */
    public static final Integer DEFAULT_VALID_DAYS = 0;

    /** {@code status} 的默认值：{@code 1} = 上架 */
    public static final Integer DEFAULT_STATUS = 1;

    /** {@code sort} 的默认值：{@code 0} = 默认排序位 */
    public static final Integer DEFAULT_SORT = 0;

    /**
     * 套餐名称
     *
     * <p>★ 唯一性受 {@code uk_pkg_name_station} 约束，<b>范围是「作用域内」</b>：
     * 「150度电包」在 {@code stationId=0}（全平台）和 {@code stationId=1}
     * （科技园站）可以各存在一份，这是想要的行为；
     * 而同作用域内的第二份会撞唯一键。
     *
     * <p>★ 代价：唯一键<b>不看 {@code status}</b>，
     * 所以「150度点包」这种错别字下架之后仍然永久占用那个名字，
     * 想用正确的名字只能先把错的硬删掉。
     * 这正是硬删接口必须存在的原因之一。
     */
    @Schema(description = "套餐名称，同一作用域内唯一", example = "150度电包")
    @NotBlank(message = "套餐名称不能为空")
    @Size(max = 64, message = "套餐名称不能超过 64 个字符")
    private String name;

    /**
     * 分类 id，指向 {@code category} 表
     *
     * <p>★★ <b>这个 {@code @NotNull} 是承重的，不是装饰。</b>
     * {@code charge_package.category_id} 是 {@code bigint NOT NULL}
     * 而且<b>没有 DDL 默认值</b> —— 和 {@code charger.rated_power_kw}
     * 属于同一类列。少了这个注解的实测后果（冒烟测试里真的发生了）：
     * 不传分类时请求一路走到 Service、穿过类型专属字段校验、
     * 最后撞在 insert 上，返回的是
     * <b>HTTP 500 + {@code "未知错误"}</b>。
     *
     * <p>「未知错误」是 {@code GlobalExceptionHandler} 对
     * 非 {@code BaseException} 的兜底文案，它的存在意义是<b>不泄漏</b>
     * 数据库细节 —— 但也因此，调用方拿到的信息量是零：
     * 既不知道是哪个字段，也不知道是参数问题还是服务坏了。
     * 一个 400 +「套餐分类不能为空」才是这条请求应得的回答。
     *
     * <p>★ <b>本字段只校验「给了」，不校验「给的值存在」</b> ——
     * 全项目没有外键（见 DDL 说明），所以一个不存在的
     * {@code categoryId=999999} 会被原样写进库。这和
     * {@code ChargerDTO.stationId} 不同：那个在 Service 里有
     * {@code requireStation} 拦着。差别的来源很实际 ——
     * 本轮没有 {@code CategoryMapper}（{@code category} 不在
     * P1a 的六个域里），拿不到那张表。
     * <b>已知缺口，记在这里</b>；补法是加一个 {@code CategoryMapper}
     * 并在 Service 里查一次存在性，和 {@code requireStation} 同形。
     */
    @Schema(description = "分类 id", example = "1")
    @NotNull(message = "套餐分类不能为空")
    private Long categoryId;

    /** {@code 1电量包 2时长包 3服务费折扣包 4次卡} —— 见 {@code PackageType} */
    @Schema(description = "类型：1电量包 2时长包 3服务费折扣包 4次卡", example = "1")
    @NotNull(message = "套餐类型不能为空")
    @Min(value = 1, message = "套餐类型只能是 1电量包、2时长包、3服务费折扣包、4次卡")
    @Max(value = 4, message = "套餐类型只能是 1电量包、2时长包、3服务费折扣包、4次卡")
    private Integer packageType;

    /**
     * 限定站点，{@code 0} 表示全平台通用
     *
     * <p>★ 字段初始值 {@code 0L} 是必须的，见类注释第一、二节。
     * 不传 = 全平台，而不是「没填」。
     */
    @Schema(description = "限定站点 id；0 表示全平台通用", example = "0", defaultValue = "0")
    private Long stationId = DEFAULT_STATION_ID;

    /** 售价，{@code decimal(12,2)}。★ 可以填 0（赠送套餐），所以不加 {@code inclusive=false} */
    @Schema(description = "售价，元", example = "100.00")
    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0", message = "售价不能为负数")
    private BigDecimal price;
 
    /**
     * 划线价（原价），展示用
     *
     * <p>★ 可空，且<b>为 null 就是「不展示划线」</b>——
     * 和这个字段里 {@code 0} 的区别是真实的：{@code 0} 会渲染成
     * 「<s>¥0.00</s> ¥100.00」，一个看起来很蠢的界面。
     *
     * <p>不做「必须大于 {@code price}」的校验：划线价是否合规
     * （比如《价格法》对虚构原价的限制）是运营规则，
     * 不是一个能在 DTO 层静态表达的约束。
     */
    @Schema(description = "划线价（原价），元；不传则不展示划线", example = "150.00")
    @DecimalMin(value = "0", message = "划线价不能为负数")
    private BigDecimal originPrice;

    /** {@code package_type=1} 时的电量。★ 另外三个类型必须为 null，见类注释第三节 */
    @Schema(description = "电量，kWh；仅 packageType=1 时填写", example = "150.00")
    @DecimalMin(value = "0", inclusive = false, message = "电量必须大于 0")
    private BigDecimal energyKwh;

    /** {@code package_type=2} 时的时长（分钟）。★ 另外三个类型必须为 null */
    @Schema(description = "时长，分钟；仅 packageType=2 时填写", example = "43200")
    @Min(value = 1, message = "时长必须大于 0 分钟")
    private Integer durationMinutes;

    /** {@code package_type=4} 时的次数。★ 另外三个类型必须为 null */
    @Schema(description = "次数；仅 packageType=4 时填写", example = "10")
    @Min(value = 1, message = "次数必须大于 0")
    private Integer times;

    /**
     * {@code package_type=3} 时的服务费折扣
     *
     * <p>★ 取值范围 {@code (0, 1]}：{@code 0.8} 是八折，
     * {@code 1} 是「不打折」（一个允许存在的、等价于原价的值），
     * 而 {@code 0} 表示<b>服务费全免</b>。
     *
     * <p>{@code 0} 被 {@code inclusive = false} 排掉，是一个<b>刻意的取舍</b>：
     * 「服务费折扣包打 0 折」在业务上就是「服务费免费」，
     * 那应该有一个独立的、名字说清了这件事的套餐类型，
     * 而不是让一个叫「折扣」的字段去表达「免费」。
     * 折扣为 0 的套餐一旦创建，列表页会显示「服务费 0 折」，
     * 用户不会知道那是免费还是配错了。
     *
     * <p>★ 上界 {@code 1}（而不是 {@code 9.9999}，列宽允许的上限）：
     * 折扣大于 1 意味着<b>加价</b>，那是调价不是折扣。
     * 列宽是物理上限，这里写的是业务上限 —— 两者不该混为一谈。
     *
     * <p>★ 精度 {@code decimal(5,4)} 是四位小数，不是两位。
     * 它要和 {@code decimal(10,4)} 的服务费单价相乘，
     * 折扣精度不够会直接体现为金额误差。见
     * {@code ChargePackageMapperTest} 里那条断言 {@code scale() == 4} 的测试。
     */
    @Schema(description = "服务费折扣，0.8 表示八折；仅 packageType=3 时填写", example = "0.8000")
    @DecimalMin(value = "0", inclusive = false, message = "服务费折扣必须大于 0")
    @DecimalMax(value = "1", message = "服务费折扣不能大于 1（大于 1 是加价，不是折扣）")
    private BigDecimal serviceDiscount;

    /**
     * 有效期天数
     *
     * <p>★ 字段初始值 {@code 0} 是必须的（见类注释第一节），
     * 含义是<b>永久有效</b>。
     *
     * <p>这个字段和 {@code durationMinutes} 的区别值得说一次，
     * 因为它们的名字都像「时间」：
     * {@code durationMinutes} 是<b>买到的时长</b>（「30 天随便充」），
     * {@code validDays} 是<b>这单生意的有效期</b>（「买了之后 90 天内要用完」）。
     * 一个时长包可以同时有 {@code durationMinutes=43200} 和 {@code validDays=90} ——
     * 买的是 30 天的使用时长，但这张卡 90 天内要激活。
     */
    @Schema(description = "有效期天数；0 表示永久有效", example = "0", defaultValue = "0")
    @Min(value = 0, message = "有效期天数不能为负数")
    private Integer validDays = DEFAULT_VALID_DAYS;

    @Schema(description = "套餐说明", example = "150 度电，全国通用，有效期永久")
    @Size(max = 512, message = "套餐说明不能超过 512 个字符")
    private String description;

    /** 图片地址。★ 只存 URL，不做上传 —— 文件存储是独立的一件事 */
    @Schema(description = "图片地址", example = "https://cdn.example.com/pkg/150kwh.png")
    @Size(max = 255, message = "图片地址不能超过 255 个字符")
    private String image;

    /** {@code 1上架 0下架}。★ 默认上架，不传就是可售状态 */
    @Schema(description = "状态：1上架 0下架", example = "1", defaultValue = "1")
    @Min(value = 0, message = "状态只能是 0下架、1上架")
    @Max(value = 1, message = "状态只能是 0下架、1上架")
    private Integer status = DEFAULT_STATUS;

    /** 展示排序。★ {@code uk_pkg_name_station} 才是约束，这个字段只管排序 */
    @Schema(description = "排序值，小的在前", example = "0", defaultValue = "0")
    private Integer sort = DEFAULT_SORT;
}
