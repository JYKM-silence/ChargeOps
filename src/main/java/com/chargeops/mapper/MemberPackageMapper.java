package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.MemberPackage;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 车主持有权益数据访问。全部放 XML —— 每条语句都要共用 {@code Base_Column_List}。
 *
 * <p>{@link #lockForOrder} / {@link #releaseLock} / {@code settle*} 全是条件更新：影响行数 1 = 这次赢了，
 * 0 = 别人先做了，调用方必须检查。结算幂等不需要额外的幂等表 —— {@code locked_order_id} 就在 WHERE 里。
 */
@Mapper
public interface MemberPackageMapper {

    MemberPackage getById(@Param("id") Long id);

    /** 全列插入：NOT NULL 的列漏一个就是 errno 1048；{@code lockedOrderId} 必须为 null，带上值会立即占用唯一索引 */
    @AutoFill(OperationType.INSERT)
    void insert(MemberPackage memberPackage);

    /** 不过滤状态：已用尽 / 已过期 / 已作废也要显示成灰态，否则用户会以为套餐凭空消失了 */
    List<MemberPackage> listByMember(@Param("memberId") Long memberId);

    /** 结算用：这个车主在某类型下此刻可用的权益，按 {@code expire_time} 升序（先到期的先用）。
     * 五个条件缺一不可，只查 {@code status = 1} 不够 —— 状态列是缓存，时间区间和余量才是真相。 */
    List<MemberPackage> listUsable(@Param("memberId") Long memberId,
                                   @Param("packageType") Integer packageType,
                                   @Param("now") LocalDateTime now);

    /** {@code locked_order_id IS NULL} 把冲突从「抛 DuplicateKeyException」退化成普通的 0 行，和别的条件更新走同一条分支 */
    int lockForOrder(@Param("id") Long id,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 余量不动 —— 这是它和 {@code settle*} 的唯一区别。{@code locked_order_id = #{orderId}} 让它幂等，也挡住迟到的释放请求 */
    int releaseLock(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);

    /** 按订单号释放它占着的任何一份权益。需要它是因为异常挂账会漏掉权益锁，漏放的后果是那份权益被 {@code listUsable} 永久跳过、也永远退不了款 */
    int releaseLockByOrder(@Param("orderId") Long orderId,
                           @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结算电量包：扣量、释放锁、用尽则改状态在同一条语句里完成。
     * {@code status} 必须排在扣量前面—— MySQL 的 {@code SET} 从左到右求值，后面的子句读到的是
     * 前面刚改完的值，挪到后面 {@code CASE} 会读到已减过一次的余量，「用尽」提前一步成立。
     */
    int settleEnergy(@Param("id") Long id,
                     @Param("orderId") Long orderId,
                     @Param("usedKwh") BigDecimal usedKwh,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 同 {@link #settleEnergy}，扣的是分钟数。调用方必须保证入参不为负 —— 扣一个负数等于加量 */
    int settleDuration(@Param("id") Long id,
                       @Param("orderId") Long orderId,
                       @Param("usedMinutes") int usedMinutes,
                       @Param("updateTime") LocalDateTime updateTime);

    int settleTimes(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * 作废一份权益（退款），唯一一处由人写入的权益状态迁移。调用方必须在退钱之前调它并检查返回值：
     * {@code PACKAGE_REFUND} 流水的 {@code order_id} 是 NULL，唯一索引不约束 NULL，
     * 数据库层没有第二道防线 —— 那一行返回值判断就是全部的防护。
     */
    int voidPackage(@Param("id") Long id,
                    @Param("remark") String remark,
                    @Param("updateTime") LocalDateTime updateTime);

    /** 把过期但状态还停在 1 的权益落库成 3，供定时任务调用；它不是正确性的一环（判据在 {@link #listUsable} 的时间条件里），{@code limit} 必须 &gt; 0 */
    int expireOverdue(@Param("now") LocalDateTime now,
                      @Param("limit") int limit,
                      @Param("updateTime") LocalDateTime updateTime);
}
