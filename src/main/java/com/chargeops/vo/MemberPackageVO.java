package com.chargeops.vo;

import com.chargeops.entity.MemberPackage;
import com.chargeops.enumeration.MemberPackageStatus;
import com.chargeops.enumeration.PackageType;
import com.chargeops.exception.ParamErrorException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车主端权益视图（「我的套餐」、以及购买成功后的返回体）
 *
 * <h2>★ 一、为什么把七个「最多只有一个有值」的字段原样暴露出去</h2>
 *
 * <p>四种套餐类型各自只用一组字段，所以任何一行里<b>七列中有五列是 null</b>
 * （服务费折扣包更极端：它连「总量/剩余」都没有，<b>七列里六列是 null</b>）：
 *
 * <pre>
 *   packageType=1 电量包        → totalKwh / remainingKwh
 *   packageType=2 时长包        → totalMinutes / remainingMinutes
 *   packageType=3 服务费折扣包  → serviceDiscount（它没有「总量/剩余」）
 *   packageType=4 次卡          → totalTimes / remainingTimes
 * </pre>
 *
 * <p>★ <b>本可以在这里把它们压成一组 {@code total / remaining / unit}</b>，
 * 那对客户端更友好。没有那样做的理由是：<b>压缩会在服务端引入一次「按类型取数」的
 * 分支，而那个分支一旦和 {@code packageType} 漂移，客户端就会拿到
 * 「30 分钟」显示成「30 度」</b> —— 一个看起来正常、但把单位搞错了的界面。
 * 原样暴露的话，客户端本来就要按 {@code packageType} 分支（它要决定显示什么图标、
 * 用什么量词），<b>那一次分支是它无论如何都要写的</b>，
 * 而不是服务端再替它写一遍、多一处会漂移的地方。
 *
 * <p>★ 代价说清楚：客户端<b>不能</b>把 {@code null} 当成 0 ——
 * 「这个字段不适用于这个类型」和「还剩 0」是两件事。
 * 判据永远是 {@code packageType}，不是字段是不是 null。
 *
 * <h2>★ 二、{@code packageType} 和 {@code status} 都给出数字 + 中文标签</h2>
 *
 * <p>理由同 {@code WalletTransactionVO}：前端不该自己维护一份「1=电量包」的映射，
 * 那份映射一旦和枚举漂移，界面上会出现用户看不懂的数字，而且<b>不报错</b>。
 *
 * <p>★★ 但标签<b>只是显示用的</b>，前端的所有分支都必须按数字走。★★
 * 拿标签做判断的话，改一次文案（「服务费折扣包」→「服务费折扣」）就会
 * 静默地改掉一个业务分支的行为。
 *
 * <h2>★ 三、{@code expireTime} 是一个真实的绝对时间，不是天数</h2>
 *
 * <p>永久有效的那一份，这个字段是 {@code 9999-12-31T23:59:59}
 * （{@link MemberPackage#PERMANENT_EXPIRE_TIME}），<b>不是 null</b>。
 * 所以前端<b>不要</b>用 {@code expireTime == null} 判断永久 ——
 * 那个条件永远不成立，表现是「永久有效」被显示成一个 9999 年的日期。
 * 正确做法是比较它是否大于一个足够远的阈值，或者干脆直接展示它
 * （「有效期至 9999-12-31」在界面上是可以接受的）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class MemberPackageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** SKU 的 id。★ 想展示最新商品图/描述时用它去查 SKU，不要去查这份权益上的快照 */
    private Long packageId;

    /** {@code 1电量包 2时长包 3服务费折扣包 4次卡} —— ★ 前端分支按这个值走，不按标签 */
    private Integer packageType;

    private String packageTypeLabel;

    /** 购买时的 SKU 名称快照。★ SKU 改名不影响这里已售出的那一份 */
    private String packageName;

    /**
     * 购买时实付的金额快照
     *
     * <p>★ 它会出现在车主端，但<b>主要消费方是管理端</b>：作废接口要用它决定退多少钱，
     * 而运营在点「作废」之前必须先看到这个数 —— 一个只显示「作废」按钮、
     * 不显示金额的界面，等于让人闭着眼睛点一次真实的资金操作。
     *
     * <p>★★ <b>{@code null} 不是 0</b>：它是「这一行建于 {@code V3} 之前、
     * 当时花了多少已经查不到了」。前端<b>不要</b>把 null 显示成「¥0.00」——
     * 那会让运营以为这是一份赠送套餐，而正确的动作是<b>转人工</b>。
     * 判据是 {@code payAmount == null}（见 {@code MemberPackage#getPayAmount}）。
     */
    private BigDecimal payAmount;

    /** {@code packageType = 1} 时才有值 */
    private BigDecimal totalKwh;

    private BigDecimal remainingKwh;

    /** {@code packageType = 2} 时才有值 */
    private Integer totalMinutes;

    private Integer remainingMinutes;

    /** {@code packageType = 4} 时才有值 */
    private Integer totalTimes;

    private Integer remainingTimes;

    /** {@code packageType = 3} 时才有值。{@code 0.8000} 表示八折 */
    private BigDecimal serviceDiscount;

    /** 生效时间（含）。通常等于购买时间 */
    private LocalDateTime effectiveFrom;

    /** 过期时间（不含）。★ 永久有效是 {@code 9999-12-31}，不是 null —— 见类注释第三节 */
    private LocalDateTime expireTime;

    /** {@code 1生效中 2已用尽 3已过期 4已作废}。★ 它是缓存，不是真相 —— 见 {@code MemberPackageStatus} */
    private Integer status;

    private String statusLabel;

    private String remark;

    private LocalDateTime createTime;

    /**
     * ★ 不包含 {@code lockedOrderId}：它是<b>内部并发控制的状态</b>，
     * 对车主没有任何意义（「你这份权益正被 1001 号订单占着」不是一个能给出建议的信息）。
     * 暴露它只会让客户端有机会基于一个瞬时值做判断，而那个值随时会变。
     *
     * <p>★ 也不包含 {@code buyOrderId}：它在直购模型下恒为 null，
     * 一个永远是 null 的字段只会让人以为「这里应该有值，是后端没填」。
     */
    public static MemberPackageVO from(MemberPackage mp) {
        return MemberPackageVO.builder()
                .id(mp.getId())
                .packageId(mp.getPackageId())
                .packageType(mp.getPackageType())
                .packageTypeLabel(labelOfType(mp.getPackageType()))
                .packageName(mp.getPackageName())
                .payAmount(mp.getPayAmount())
                .totalKwh(mp.getTotalKwh())
                .remainingKwh(mp.getRemainingKwh())
                .totalMinutes(mp.getTotalMinutes())
                .remainingMinutes(mp.getRemainingMinutes())
                .totalTimes(mp.getTotalTimes())
                .remainingTimes(mp.getRemainingTimes())
                .serviceDiscount(mp.getServiceDiscount())
                .effectiveFrom(mp.getEffectiveFrom())
                .expireTime(mp.getExpireTime())
                .status(mp.getStatus())
                .statusLabel(labelOfStatus(mp.getStatus()))
                .remark(mp.getRemark())
                .createTime(mp.getCreateTime())
                .build();
    }

    /**
     * ★ 两个标签都降级成 {@code null}，不抛 —— 这是本项目对只读路径的统一取舍，
     * 规则和完整论证写在 {@link ChargePackageVO#from} 上（以及它引用的
     * {@code StationServiceImpl.labelOf}）。
     *
     * <p>★ <b>两个方法都写在这里而不是复用 {@code ChargePackageVO} 的</b>：
     * 一个是套餐 SKU 的枚举（{@code PackageType}），一个是持有权益的状态
     * （{@code MemberPackageStatus}），<b>它们只是碰巧各自需要一次「把未知值降级」</b>，
     * 不是同一个映射。把它们合成一个「通用 label 工具」会让两个域的枚举
     * 从此刻起必须一起演进。
     */
    private static String labelOfType(Integer packageType) {
        if (packageType == null) {
            return null;
        }
        try {
            return PackageType.of(packageType).getLabel();
        } catch (ParamErrorException e) {
            log.warn("权益的套餐类型无法识别，packageType={}（数据是手工改过还是枚举漏了一个值？）", packageType);
            return null;
        }
    }

    private static String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        try {
            return MemberPackageStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            log.warn("权益状态无法识别，status={}", status);
            return null;
        }
    }
}
