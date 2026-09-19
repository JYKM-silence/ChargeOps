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
 * 车主持有权益数据访问
 *
 * <p>注解 / XML 的分工规则见 {@link StationMapper} 的类注释。
 * 本类全部放 XML —— 每条语句都要共用 {@code Base_Column_List}，
 * 而这个列表有 23 列，写在注解里没法复用。
 *
 * <h2>★★ 一、条件更新是这个 Mapper 的主线 ★★</h2>
 *
 * <p>{@link #lockForOrder} / {@link #releaseLock} / {@code settle*} 全部是
 * <b>把判断写进 WHERE</b> 的条件更新，没有一个是「先查再改」：
 *
 * <pre>
 *   影响行数 = 1  →  这次操作赢了
 *   影响行数 = 0  →  别人先做了（或状态不对），调用方据此决定重试还是放弃
 * </pre>
 *
 * <p>★ <b>调用方必须检查返回值</b>。这个项目里「影响行数 0」不是异常，
 * 而是正常的竞争结果 —— 但不检查的话，它会静默地变成
 * 「以为自己锁上了，实际没锁」，然后两笔订单同时充电、最后重复扣款。
 *
 * <h2>★★ 二、结算为什么能幂等：{@code lockedOrderId} 就是幂等标记 ★★</h2>
 *
 * <p>设备会重复上报「充电结束」，结算因此可能被触发多次。防重复扣减
 * <b>不需要</b>额外的幂等表 —— 锁本身就在 UPDATE 的 WHERE 里：
 *
 * <pre>
 *   WHERE id = ? AND locked_order_id = ?     ← 第二列就是幂等键
 * </pre>
 *
 * <p>第一次执行后 {@code locked_order_id} 被置为 NULL，第二次必然匹配不上。
 * <b>已经在真库上验证过</b>：第一次影响 1 行，重复执行影响 0 行
 * （见本类对应的测试）。
 *
 * <h2>★ 三、和 {@link ConnectorMapper} 的边界完全对应 ★★</h2>
 *
 * <p>{@code ConnectorMapper} 的类注释里写着「{@code current_order_id}
 * 不在这两个写方法里」—— 因为枪的抢锁/释放属于<b>订单流程</b>，
 * 而不是「管理端编辑充电枪」。
 *
 * <p>本类把那条线又往前挪了一格：{@link #lockForOrder} 和结算方法
 * <b>写在这里</b>，因为持有权益没有「管理端编辑」这回事 ——
 * 它的一生只有三个动作：被买下、被订单锁定、被结算扣减。
 * 三个动作全部由订单流程驱动，所以放在这个 Mapper 里是完整的，
 * 而调用它们的是 P1c-3 的订单 Service。
 *
 * <h2>★ 四、这几个写方法为什么不标 {@code @AutoFill}</h2>
 *
 * <p>{@code @AutoFill} 要求<b>实体是第一个参数</b>（见 {@link AutoFill} 的约定），
 * 而这几个方法收的是散参数，拿不到实体。
 *
 * <p>它们也<b>不应该</b>用自动填充，理由有两条：
 * <ol>
 *   <li>这是<b>状态迁移</b>，不是「有人编辑了这条记录」。
 *       行为人是订单流程里的某个来源（用户端 / 设备上报 / 定时任务），
 *       那个信息记录在 {@code charging_order_event.source} 和
 *       {@code operator_id}，而不是这里。所以这几个 UPDATE
 *       <b>不碰 {@code update_user}</b>，保持它的原值</li>
 *   <li>{@code update_time} 由调用方显式传入，而不是靠 DDL 的
 *       {@code ON UPDATE CURRENT_TIMESTAMP(3)} —— 项目约定是
 *       「时间一律 Java 侧生成」，数据库默认值只是兜底。
 *       传进来的那个 {@code now} 和订单、流水用的是同一个时刻，
 *       对账时才能对上</li>
 * </ol>
 */
@Mapper
public interface MemberPackageMapper {

    MemberPackage getById(@Param("id") Long id);

    /**
     * 买下一份权益
     *
     * <p>★ 全列插入，DDL 的 {@code DEFAULT} <b>不会生效</b> ——
     * 下面这些 {@code NOT NULL} 列必须由调用方给出，漏一个就是 errno 1048：
     *
     * <table border="1">
     *   <caption>必须提供值的列</caption>
     *   <tr><th>列</th><th>来源</th></tr>
     *   <tr><td>{@code memberId / packageId / packageType / packageName}</td>
     *       <td>购买请求 + SKU 快照</td></tr>
     *   <tr><td>{@code effectiveFrom / expireTime}</td>
     *       <td>购买时刻 + SKU 的 {@code validDays} 算出来；
     *           永久有效用 {@link MemberPackage#PERMANENT_EXPIRE_TIME}</td></tr>
     *   <tr><td>{@code status}</td><td>{@code 1}</td></tr>
     *   <tr><td>{@code version}</td><td>{@code 0}</td></tr>
     * </table>
     *
     * <p>★ <b>各类型的专属列必须严格只留一个非空</b>，和
     * {@code charge_package} 的规则完全一样，理由也一样：
     * 残留字段是<b>静默的</b> —— 一份「30 天时长包」如果
     * {@code remaining_kwh} 还留着 150，结算时读到它就会白白送出 150 度电。
     * 校验落在 Service，见 {@code PackageType} 的类注释。
     *
     * <p>★ {@code lockedOrderId} 必须为 {@code null} —— 新买的权益没有被任何订单锁定。
     * 带上一个非 null 值会立即占用唯一索引，让别的订单锁不上它。
     */
    @AutoFill(OperationType.INSERT)
    void insert(MemberPackage memberPackage);

    /**
     * 车主端「我的套餐」
     *
     * <p>不过滤状态 —— 已用尽 / 已过期 / 已作废的也要显示成灰态，
     * 用户才知道「我买过、但用完了」，而不是「我的套餐凭空消失了」。
     *
     * <p>排序 {@code status, expire_time, id} 正好对上
     * {@code idx_mp_member_status_expire (member_id, status, expire_time)}：
     * 等值列在最左、排序列在后面，不产生 filesort。
     * 末尾补 {@code id} 是为了<b>全序</b> —— 两份同一毫秒过期的权益
     * 在两次请求里的相对顺序必须一致，否则前端翻页/刷新会看到顺序跳动。
     */
    List<MemberPackage> listByMember(@Param("memberId") Long memberId);

    /**
     * 结算用：找出这个车主在某类型下<b>此刻可用</b>的权益
     *
     * <p>四个条件缺一不可，而且**不能只查 {@code status = 1}**：
     * <pre>
     *   status = 1                ← 状态列是缓存，不能单独作为判据
     *   locked_order_id IS NULL   ← 没被别的订单占着
     *   expire_time &gt; now        ← 没过期；永久权益是 9999 哨兵，会被选中
     *   remaining_* &gt; 0           ← 还有余量（按类型选对应的那一列）
     * </pre>
     *
     * <p>★ 后两个条件就是 DDL 注释里那句「status 是缓存，
     * expire_time 和剩余量才是真相」的落地处。少了 {@code expire_time} 这一条，
     * 一份过期但没人刷新过状态的权益会被发出去；少了余量那一条，
     * 一份用尽的权益会被再次选中，结算时扣成负数。
     *
     * <p>★ 排序按 {@code expire_time} 升序：<b>先到期的先用</b>。
     * 反过来的话，一份这个月过期的权益会被压在明年过期的后面，
     * 直到彻底作废都没被用过 —— 用户会认为「我的套餐白买了」。
     *
     * <h2>★★ 为什么返回 List 而不是单个对象 ★★</h2>
     *
     * <p>因为「有多份可用时选哪一份」是<b>业务决策</b>，不是查询的事。
     * P1c-3 决定用哪一份（当前实现下就是第一份），然后拿它的 id 去
     * {@link #lockForOrder}。锁成功才算数 ——
     * 查询和锁定之间别的线程可能已经抢先锁走了第一份，
     * 那时正确的动作是<b>回到这个列表取下一份重试</b>，
     * 而不是直接失败。返回 List 让这条路走得通。
     *
     * <p>★ 折扣包（{@code packageType = 3}）也走这个方法，
     * 但它的可用性<b>不含余量条件</b>（它不消耗），见 XML 里的分支。
     *
     * @param now 当前时间。★ 由调用方传入而不是用数据库的 {@code NOW()} ——
     *            这样「过期判断」和「结算金额」用的是同一个时刻，
     *            否则一笔在边界上（权益恰好在这一毫秒过期）的订单
     *            可能判定和计价对不上
     */
    List<MemberPackage> listUsable(@Param("memberId") Long memberId,
                                   @Param("packageType") Integer packageType,
                                   @Param("now") LocalDateTime now);

    /**
     * 锁定一份权益给某笔订单（条件更新）
     *
     * <pre>
     *   UPDATE member_package SET locked_order_id = #{orderId}, update_time = #{updateTime}
     *    WHERE id = #{id} AND locked_order_id IS NULL AND status = 1
     * </pre>
     *
     * <p>影响行数为 1 = 抢到了；为 0 = 被别人抢走了，调用方应回到
     * {@link #listUsable} 取下一份。
     *
     * <p>★ {@code locked_order_id IS NULL} 这个条件看起来是多余的
     * （有唯一索引兜底），但它有用：唯一索引拦下冲突时抛的是
     * {@code DuplicateKeyException}，调用方要额外处理一种异常；
     * 带上这个条件，冲突就退化成一次普通的「影响行数 0」——
     * 和别的条件更新走同一条分支。唯一索引仍然是最后一道防线，
     * 只是正常情况下它不会被触发。
     *
     * <p>★ 不锁折扣包。折扣包恒为 NULL，见
     * {@link MemberPackage#getLockedOrderId()} 的说明。
     */
    int lockForOrder(@Param("id") Long id,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 释放锁定（取消订单 / 超时未启动）
     *
     * <pre>
     *   UPDATE member_package SET locked_order_id = NULL, update_time = #{updateTime}
     *    WHERE id = #{id} AND locked_order_id = #{orderId}
     * </pre>
     *
     * <p>★ <b>余量不动</b> —— 这是它和 {@code settle*} 的唯一区别。
     * 取消订单不该消耗任何权益。
     *
     * <p>★ {@code locked_order_id = #{orderId}} 这个条件同样让它<b>幂等</b>：
     * 重复释放第二次影响 0 行，而不是把「别人的锁」清掉。
     * 少了它的话，一个延迟到达的释放请求会把已经重新锁定这份权益的
     * <b>另一笔订单</b>的锁清掉，那笔订单结算时就会扣不到量 ——
     * 钱收了，量没扣，而且全程没有报错。
     */
    int releaseLock(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结算：电量包（{@code packageType = 1}）
     *
     * <pre>
     *   UPDATE member_package
     *      SET status = CASE WHEN remaining_kwh &lt;= #{usedKwh} THEN 2 ELSE status END,
     *          remaining_kwh = remaining_kwh - #{usedKwh},
     *          locked_order_id = NULL,
     *          update_time = #{updateTime}
     *    WHERE id = #{id} AND locked_order_id = #{orderId}
     * </pre>
     *
     * <p>★ 三件事在<b>同一条语句</b>里完成：扣量、释放锁、用尽则改状态。
     * 拆成三条 UPDATE 的话，中间任何一步失败或重试都会留下不一致
     * （最典型的是「锁释放了但量没扣」，表现为用户白充一次）。
     *
     * <h2>★★ 为什么 {@code status} 排在扣量【前面】、而且比的是原值 ★★</h2>
     *
     * <p><b>MySQL 的 {@code SET} 子句从左到右求值，后面的子句看到的是前面刚改完的值</b>
     * （不是标准 SQL 的「所有 SET 读原值」）。所以写成
     * {@code SET remaining_kwh = remaining_kwh - #{usedKwh}, status = CASE WHEN remaining_kwh - #{usedKwh} <= 0 ...}
     * 的话，{@code CASE} 读到的 {@code remaining_kwh} 已经减过一次，
     * <b>等于又减了一次</b>，条件提前一步成立。
     *
     * <p>这不是理论推演 —— 它被本类的测试抓到过，也在真库上复现过：
     * 剩余 2 次、扣 1 次之后，剩余确实是 1，但 {@code status} 被置成了
     * 2（已用尽）。<b>用户还剩 1 次没用，下一次充电会被静默拒绝</b>，
     * 而且前一次就已经显示「已用尽」了。
     *
     * <p>所以比较的是<b>原值</b>：「我有的 &lt;= 你要扣的」⇔ 扣完 &lt;= 0 ⇔ 用尽。
     * <b>改这三个 settle 语句时不要把 {@code status} 挪到扣量后面</b>，
     * 哪怕是为了读起来更顺。
     *
     * <p>★ 用 {@code <=} 而不是 {@code =}：理论上不该扣成负数，
     * 但真出现了（比如重复结算没被拦住），用 {@code <=} 至少能把状态
     * 标成已用尽，而不是留下一个负余额却仍然显示「生效中」。
     *
     * <p>★ 调用方必须校验 {@code usedKwh} 不为负。扣一个负数等于加量。
     */
    int settleEnergy(@Param("id") Long id,
                     @Param("orderId") Long orderId,
                     @Param("usedKwh") BigDecimal usedKwh,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结算：时长包（{@code packageType = 2}）
     *
     * <p>同 {@link #settleEnergy}，只是扣的是分钟数。
     *
     * <p>★ 用 {@code int} 而不是 {@code Integer}：调用方算出来的时长
     * 不该有「不知道」这个可能，用包装类型会引入一个多余的空值分支。
     */
    int settleDuration(@Param("id") Long id,
                       @Param("orderId") Long orderId,
                       @Param("usedMinutes") int usedMinutes,
                       @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结算：次卡（{@code packageType = 4}）
     *
     * <p>★ <b>没有「用了几次」这个参数</b>：一次充电消耗一次，
     * 这是次卡的定义。留一个参数出来会让人以为可以传 2，
     * 而那个语义（一次充电算两次）在业务上不存在。
     */
    int settleTimes(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);
}
