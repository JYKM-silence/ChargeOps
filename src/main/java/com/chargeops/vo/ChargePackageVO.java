package com.chargeops.vo;

import com.chargeops.constant.StatusConstant;
import com.chargeops.entity.ChargePackage;
import com.chargeops.enumeration.PackageType;
import com.chargeops.exception.ParamErrorException;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电套餐信息
 *
 * <h2>★ 一、四个类型专属字段<b>全部</b>返回，其中三个必然是 null</h2>
 *
 * <p>这不是冗余。前端要根据 {@code packageType} 决定显示什么，而「其余三个是 null」
 * 本身就是一条<b>需要被看到</b>的信息。更严格的设计是只返回本类型的那个字段，
 * 但那样在 JSON 里「没有」和「后端根本没返回这个字段」长得一样（都是键不存在）。
 * 把 null 显式返回，至少让「这个字段确实为空」和「这个字段不适用」在调试时可区分。
 *
 * <h2>★ 二、{@code stationId=0} 是「全平台」，前端要渲染成文字</h2>
 *
 * <p>{@code 0} 的含义是「全平台通用」，<b>不能</b>被渲染成「站点 0」——
 * 「0 号站点」这个东西不存在，界面上出现它就是一个 bug。
 *
 * <p>★ 这里<b>没有</b> {@code stationName}（要补的话得放在正确的时机，
 * 见 {@code ChargerVO} 类注释里的 {@code PageHelper} 坑）：桩列表不带站名是
 * <b>不可读</b>的（「A区-01」每个站都有），所以那里必须补；套餐列表不带站名是可读的，
 * 而「这个套餐属于谁」由 {@code stationId=0} 这个约定表达得足够清楚。
 *
 * <h2>★ 三、{@code serviceDiscount} 是四位小数</h2>
 *
 * <p>不要格式化掉末尾的 0。<b>这个字段的精度和 {@code decimal(5,4)} 是一致的，
 * 中间任何一步四舍五入都会造成金额误差。</b>
 */
@Data
@Slf4j
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

    @Schema(description = "类型文案，如「电量包」")
    private String packageTypeLabel;

    @Schema(description = "限定站点 id；0 表示全平台通用")
    private Long stationId;

    @Schema(description = "售价，元")
    private BigDecimal price;

    @Schema(description = "划线价（原价），元；null 表示不展示划线")
    private BigDecimal originPrice;

    @Schema(description = "电量，kWh；仅电量包有值")
    private BigDecimal energyKwh;

    @Schema(description = "时长，分钟；仅时长包有值")
    private Integer durationMinutes;

    @Schema(description = "次数；仅次卡有值")
    private Integer times;

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

    /** ★ 值域对应 {@code StatusConstant} */
    @Schema(description = "状态文案，如「上架」")
    private String statusLabel;

    @Schema(description = "排序值，小的在前")
    private Integer sort;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 实体 → VO，顺带算上两个派生标签
     *
     * <p>★★ <b>它放在 VO 上、而不是留在某个 Service 的私有方法里，是因为它有
     * 不止一个调用方</b>：管理端的套餐列表（{@code ChargePackageServiceImpl}）
     * 和车主端的货架（{@code MemberPackageServiceImpl.listAvailable}）都要用。
     * 留两份的话，「1 显示成什么」就有两个答案，而其中一份迟早会漏掉新类型。
     *
     * <p>★ <b>{@code BeanUtils.copyProperties} 在这里是安全的</b>，判据和
     * {@code StationServiceImpl.toVo} 那条一样：<b>目标类型 {@link ChargePackageVO}
     * 是一个白名单</b>，它没有超出展示需要的字段。危险的方向是「复制进实体」。
     *
     * <p>★ <b>两个标签的失败都降级成 {@code null}，不抛异常。</b>这是本项目对
     * 只读路径的统一取舍，规则写在 {@code StationServiceImpl.labelOf} 里：
     * <b>只读路径上的派生字段失败要降级，写路径上的校验失败要抛。</b>
     * 一个数据库里存在、代码不认识的值（有人手工 UPDATE 了一个 {@code package_type = 9}）
     * 不该让整个列表接口 500 —— <b>「这一行的类型显示不出来」和「整个页面打不开」
     * 差得很远</b>。
     *
     * <p>★★ <b>注意 {@code MemberPackageVO.from} 和这里是同一条规则，
     * 而 {@code WalletTransactionVO.from} 不是</b>（它直接调
     * {@code WalletTransactionType.of(...)}，未知值会抛）。
     * 那个是本项目里的一处不一致，这里明确选择跟随前者 ——
     * 要改的话应该改它，而不是在新代码里再分一个叉。
     */
    public static ChargePackageVO from(ChargePackage pkg) {
        ChargePackageVO vo = new ChargePackageVO();
        BeanUtils.copyProperties(pkg, vo);
        vo.setPackageTypeLabel(labelOfType(pkg.getPackageType()));
        vo.setStatusLabel(labelOfStatus(pkg.getStatus()));
        return vo;
    }

    private static String labelOfType(Integer packageType) {
        if (packageType == null) {
            return null;
        }
        try {
            return PackageType.of(packageType).getLabel();
        } catch (ParamErrorException e) {
            log.warn("套餐类型无法识别，packageType={}（数据是手工改过还是枚举漏了一个值？）", packageType);
            return null;
        }
    }

    /**
     * 套餐状态是真的二值（{@code 1上架 0下架}），所以能直接映射，不需要枚举。
     */
    private static String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        // ★ 常量写在左边，status 为 null 时也不会 NPE。
        //   `status == StatusConstant.ENABLE` 比较的是 Integer 引用，
        //   只因 Integer 缓存了 -128~127 才恰好正确 —— 一个「碰巧能跑」的隐患。
        if (StatusConstant.ENABLE.equals(status)) {
            return "上架";
        }
        if (StatusConstant.DISABLE.equals(status)) {
            return "下架";
        }
        log.warn("套餐状态无法识别，status={}", status);
        return null;
    }
}
