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
 * 本类全部放 XML —— 每条语句都要共用 {@code Base_Column_List}（24 列）。
 *
 * <h2>★★ 一、条件更新是这个 Mapper 的主线 ★★</h2>
 *
 * <p>{@link #lockForOrder} / {@link #releaseLock} / {@code settle*} 全部是
 * <b>把判断写进 WHERE</b> 的条件更新，没有一个是「先查再改」：
 * 影响行数 1 = 这次操作赢了，0 = 别人先做了（或状态不对），
 * 调用方据此决定重试还是放弃。
 *
 * <p>★ <b>调用方必须检查返回值</b>：不检查的话，它会静默地变成
 * 「以为自己锁上了，实际没锁」，然后两笔订单同时充电、最后重复扣款。
 *
 * <h2>★★ 二、结算为什么能幂等：{@code lockedOrderId} 就是幂等标记 ★★</h2>
 *
 * <p>设备会重复上报「充电结束」，结算因此可能被触发多次。防重复扣减
 * <b>不需要</b>额外的幂等表 —— 锁本身就在 UPDATE 的 WHERE 里：
 * 第一次执行后 {@code locked_order_id} 被置为 NULL，第二次必然匹配不上。
 * <b>已经在真库上验证过</b>：第一次影响 1 行，重复执行影响 0 行。
 *
 * <h2>★ 三、和 {@link ConnectorMapper} 的边界对应</h2>
 *
 * <p>枪的抢锁/释放属于<b>订单流程</b>而不是「管理端编辑充电枪」，
 * 所以那里没有；持有权益同样没有「管理端编辑剩余量」这回事 ——
 * 剩余的递减只发生在结算，<b>没有一条路径能让人手工改 {@code remaining_*}</b>。
 *
 * <p>★★ <b>它的状态迁移一共只有四个来源，全部在这个 Mapper 里：</b>★★
 * <pre>
 *   1 生效中   ← insert（被买下）
 *   2 已用尽   ← settle* 里的 CASE（结算时余量归零，顺手改）
 *   3 已过期   ← expireOverdue（定时任务，纯推导）
 *   4 已作废   ← voidPackage（★ 唯一一个由人写的，管理端退款）
 * </pre>
 * 落到 3 和 4 的两个是<b>后加的</b>：在 {@code V3} 之前，
 * {@code MemberPackageStatus} 的 {@code EXPIRED} / {@code VOIDED} 两个枚举值
 * <b>在代码里一次都没被写入过</b> —— 状态的迁移只到「被买下 / 被用尽」为止。
 * 这不是设计，是没写完，而它的症状是「一份过期三年的权益在库里仍然写着『生效中』」。
 *
 * <h2>★ 四、这几个写方法为什么不标 {@code @AutoFill}</h2>
 *
 * <p>机械原因：{@code @AutoFill} 要求<b>实体是第一个参数</b>
 * （见 {@link AutoFill} 的约定），而这几个方法收的是散参数。
 *
 * <p>更重要的是它们<b>不应该</b>用自动填充：这是<b>状态迁移</b>，不是
 * 「有人编辑了这条记录」—— 行为人是订单流程里的某个来源
 * （用户端 / 设备上报 / 定时任务），记在
 * {@code charging_order_event.source} / {@code operator_id}。
 * 所以这几个 UPDATE <b>不碰 {@code update_user}</b>；而 {@code update_time}
 * 由调用方显式传入，和订单、流水用的是同一个时刻（项目约定时间一律
 * Java 侧生成），对账时才能对上。
 *
 * <p>★ <b>唯一有点别扭的是 {@link #voidPackage}：它背后确实有一个人</b>
 * （点了「作废」的那个运营）。即便如此也不写 {@code update_user} ——
 * 一是不想为它破一次例（「哪些写方法填 update_user」变成一张要背的清单），
 * 二是 {@code BaseContext} 里那个 id 在<b>管理端</b>请求下是员工 id、
 * 在车主端请求下是车主 id，两个域共用一个 {@code update_user} 列
 * 会让它变成一个没有确定含义的列。作废是谁做的，看 Service 的日志和
 * {@code wallet_transaction} 那条 {@code ADMIN_ADJUST} 流水。
 */
@Mapper
public interface MemberPackageMapper {

    MemberPackage getById(@Param("id") Long id);

    /**
     * 买下一份权益
     *
     * <p>★ 全列插入，DDL 的 {@code DEFAULT} <b>不会生效</b> ——
     * 这些 {@code NOT NULL} 列必须由调用方给出，漏一个就是 errno 1048：
     * {@code memberId / packageId / packageType / packageName}（购买请求 +
     * SKU 快照）、{@code effectiveFrom / expireTime}（购买时刻 + SKU 的
     * {@code validDays}，永久有效用
     * {@link MemberPackage#PERMANENT_EXPIRE_TIME}）、
     * {@code status}（{@code 1}）、{@code version}（{@code 0}）。
     *
     * <p>★ <b>各类型的专属列必须严格只留一个非空</b>，和
     * {@code charge_package} 的规则一样，理由也一样：残留字段是<b>静默的</b> ——
     * 一份「30 天时长包」如果 {@code remaining_kwh} 还留着 150，
     * 结算时读到它就会白白送出 150 度电。校验落在 Service。
     *
     * <p>★ {@code lockedOrderId} 必须为 {@code null} —— 新买的权益没有被任何
     * 订单锁定，带上非 null 值会立即占用唯一索引，让别的订单锁不上它。
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
     * {@code idx_mp_member_status_expire}（等值列在最左、排序列在后面，
     * 不产生 filesort）。末尾补 {@code id} 是为了<b>全序</b> ——
     * 同一毫秒过期的两份权益在两次请求里的顺序必须一致，
     * 否则前端刷新时会看到顺序跳动。
     */
    List<MemberPackage> listByMember(@Param("memberId") Long memberId);

    /**
     * 结算用：找出这个车主在某类型下<b>此刻可用</b>的权益
     *
     * <p>五个条件缺一不可，而且**不能只查 {@code status = 1}**：
     * <pre>
     *   status = 1                ← 状态列是缓存，不能单独作为判据
     *   locked_order_id IS NULL   ← 没被别的订单占着
     *   effective_from &lt;= now    ← 已经生效
     *   expire_time &gt; now        ← 没过期；永久权益是 9999 哨兵，会被选中
     *   remaining_* &gt; 0           ← 还有余量（按类型选对应的那一列）
     * </pre>
     *
     * <p>★ 后面几条就是 DDL 那句「status 是缓存，effective_from /
     * expire_time / 剩余量才是真相」的落地处：少了 {@code effective_from}，
     * 一份「下月生效」的权益今天就能被抵扣；少了 {@code expire_time}，
     * 一份过期但没人刷新过状态的权益会被发出去；少了余量那一条，
     * 一份用尽的权益会被再次选中，结算时扣成负数。<b>三者都是静默的</b>。
     *
     * <p>★ 排序按 {@code expire_time} 升序：<b>先到期的先用</b>。
     * 反过来的话，一份这个月过期的权益会被压在明年过期的后面，
     * 直到彻底作废都没被用过 —— 用户会认为「我的套餐白买了」。
     *
     * <p>★★ <b>返回 List 而不是单个对象</b>：因为「有多份可用时选哪一份」
     * 是<b>业务决策</b>。调用方拿第一份的 id 去 {@link #lockForOrder}，
     * 锁成功才算数 —— 查询和锁定之间别的线程可能已经抢先锁走了它，
     * 那时正确的动作是<b>回到这个列表取下一份重试</b>。返回 List 让这条路走得通。
     *
     * <p>★ 折扣包（{@code packageType = 3}）也走这个方法，
     * 但它的可用性<b>不含余量条件</b>（它不消耗），见 XML 里的分支。
     *
     * @param now 当前时间。★ 由调用方传入而不是用数据库的 {@code NOW()} ——
     *            这样「过期判断」和「结算金额」用的是同一个时刻，
     *            否则一笔恰好卡在权益过期那一毫秒的订单可能判定和计价对不上
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
     * <p>★ {@code locked_order_id IS NULL} 看起来多余（有唯一索引兜底），
     * 但它把冲突从「抛 {@code DuplicateKeyException}，调用方要额外处理一种异常」
     * 退化成一次普通的「影响行数 0」，和别的条件更新走同一条分支。
     * 唯一索引仍然是最后一道防线，只是正常情况下不会被触发。
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
     * <p>★ <b>余量不动</b> —— 这是它和 {@code settle*} 的唯一区别。取消订单不消耗权益。
     *
     * <p>★ {@code locked_order_id = #{orderId}} 这个条件同样让它<b>幂等</b>：
     * 重复释放第二次影响 0 行。少了它的话，一个延迟到达的释放请求会把已经
     * 重新锁定这份权益的<b>另一笔订单</b>的锁清掉，那笔订单结算时就会扣不到量 ——
     * 钱收了，量没扣，而且全程没有报错。
     */
    int releaseLock(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * 按<b>订单号</b>释放它占着的任何一份权益，不需要知道是哪一份
     *
     * <pre>
     *   UPDATE member_package SET locked_order_id = NULL, update_time = #{updateTime}
     *    WHERE locked_order_id = #{orderId}
     * </pre>
     *
     * <p>★★ <b>它和 {@link #releaseLock} 的区别是「谁在回答定位问题」。</b>★★
     * {@code releaseLock} 要求调用方先知道 {@code memberPackageId} 才敢放 ——
     * 那个 id 记在 {@code charging_order.member_package_id} 上，
     * 所以调用方得先把订单整行读出来。而本方法的谓词<b>就在权益表这一侧</b>：
     * 「哪一行被这笔订单锁着」这个问题由 {@code locked_order_id} 自己回答
     * （它有唯一索引，最多命中一行）。
     *
     * <p>★ 于是它<b>天然幂等，而且自守卫</b>：重复调用、对一笔从未锁过权益的订单调用，
     * 都是影响 0 行、无副作用。正因如此，{@code ChargingOrderServiceImpl.markAbnormal}
     * 可以无条件地调它，不必先判断「这笔订单还占着权益吗」——
     * <b>和那里对枪口做的是同一件事，理由也一字不差</b>
     * （见 {@code releaseConnector} 的 javadoc）。
     *
     * <p>★★ <b>为什么需要它：异常挂账会漏掉权益锁。</b>★★
     * {@code markAbnormal} 的来源集合是 {@code holdingMoneyCodes() = {1,2,3,4,6}}，
     * 其中 {4,6} 的权益锁早在 {@code settle} 里就释放了（{@code settle*} 的 SET 里有
     * {@code locked_order_id = NULL}），但 <b>{1,2,3} 的没释放过</b> ——
     * 而 {@code end()} 的电表异常路径（{@code 3 → 9}）正是一条真实可达的路径。
     * 漏放的后果是那个 {@code locked_order_id} <b>永久留在那里</b>：
     * <ul>
     *   <li>{@code listUsable} 的 {@code locked_order_id IS NULL} 会把这份权益
     *       <b>永久跳过</b> —— 车主的套餐还在，但再也不会被任何订单选中，
     *       表现是「明明有 150 度，结算时按余额扣钱」；</li>
     *   <li>本轮的退款路径会被 {@code voidPackage} 的
     *       {@code locked_order_id IS NULL} 挡住 —— <b>这份权益永远退不了款</b>，
     *       而它其实一点都没被用过。</li>
     * </ul>
     * 两者都不报错，都只在数据里留一个悬空的外键值。
     *
     * <p>★ <b>不碰 {@code remaining_*} 和 {@code status}</b>：挂账的订单
     * <b>没有结算过</b>（结算失败才会挂账），所以这份权益一点没被消耗，
     * 释放锁之后它应该<b>原样可用</b>。这和 {@link #releaseLock} 的立场一致 ——
     * 「释放锁」和「扣量」是两件事，后者只在 {@code settle*} 里发生。
     * ★ 所以<b>不要</b>为了「代码复用」把本方法实现成「先查出 id 再调 releaseLock」：
     * 那会多一次查询，而且让「哪一种释放」变得可混淆。
     */
    int releaseLockByOrder(@Param("orderId") Long orderId,
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
     * <p>★★ <b>{@code status} 排在扣量【前面】、而且比的是原值</b>：
     * MySQL 的 {@code SET} 子句从左到右求值，后面的子句读到的是前面刚改完的值
     * （不是标准 SQL 的「所有 SET 读原值」）。所以
     * {@code status = CASE WHEN remaining_kwh - #{usedKwh} <= 0 ...} 这种写法里，
     * {@code CASE} 读到的 {@code remaining_kwh} 已经减过一次，<b>等于又减了一次</b>，
     * 「用尽」提前一步成立。真库上复现过：剩余 2 扣 1 之后剩余确实是 1，
     * 但 {@code status} 已被置成 2（已用尽）—— <b>用户还剩 1 次没用就被静默拒绝</b>。
     * <b>不要把 {@code status} 挪到扣量后面</b>，哪怕是为了读起来更顺。
     * 详细对照见 XML 里的对应语句。
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
     * <p>★ <b>没有「用了几次」这个参数</b>：一次充电消耗一次，这是次卡的定义。
     * 留一个参数出来会让人以为可以传 2，而那个语义在业务上不存在。
     */
    int settleTimes(@Param("id") Long id,
                    @Param("orderId") Long orderId,
                    @Param("updateTime") LocalDateTime updateTime);

    // ==================== 管理端 ====================

    /**
     * ★★ 作废一份权益（退款）。<b>全项目唯一一处由人写入的权益状态迁移。</b>★★
     *
     * <pre>
     *   UPDATE member_package
     *      SET status = 4, remark = #{remark}, update_time = #{updateTime}
     *    WHERE id = #{id} AND status = 1 AND locked_order_id IS NULL
     * </pre>
     *
     * <p>★★ <b>调用方必须检查返回值，而且必须在退钱之前调它。</b>★★
     * 影响 1 行 = 这次作废赢了，可以去退钱；影响 0 行 = <b>别人先做了</b>
     * （另一笔作废、一笔订单刚锁上它、或者一次结算把余量扣光了），
     * 这时<b>一分钱都不能退</b>。
     *
     * <p>★ 这条路径的资金流水（{@code PACKAGE_REFUND}）的 {@code order_id} 是 NULL，
     * 而 {@code uk_txn_order_type} <b>不约束 NULL</b> —— <b>数据库层没有第二道防线</b>。
     * 也就是说「不检查这个返回值、直接退钱」的症状是<b>同一份权益退两次钱</b>，
     * 而流水上会留下两条一模一样的记录，每条单独看都完全正常。
     * 这一行 {@code if} 就是全部的防护。
     *
     * <p>★★ <b>{@code locked_order_id IS NULL} 有两个身份，都不是多余的：</b>★★
     * <ul>
     *   <li><b>业务判据</b>：有一笔订单正拿着这份权益（在充电，或刚锁上还没启动），
     *       此刻作废会让那笔订单结算时读到一个已作废的权益；</li>
     *   <li><b>并发闸门</b>：它和 {@link #lockForOrder} 的谓词
     *       （{@code locked_order_id IS NULL AND status = 1}）形成<b>互斥</b> ——
     *       两条 UPDATE 打的是同一行，MySQL 的行锁把它们排成序，
     *       <b>必然有一方影响 0 行</b>。少了这个条件，「作废成功」和
     *       「订单锁上这份权益」可以同时成立，留下一个
     *       「钱已经退了、权益还在被一笔活着的订单消费」的状态。</li>
     * </ul>
     *
     * <p>★ {@code status} 硬编码 {@code 4} 而不是收参数：<b>这条语句就是「作废」这个
     * 迁移本身</b>。把目标状态做成参数只会多出一个「传错状态」的可能，
     * 而那个错误的症状是静默的（迁移仍然成功，只是迁到了一个别的状态）。
     *
     * <p>★ <b>不碰 {@code version}，也不碰 {@code update_user}。</b>
     * 前者的理由见类注释第四节末段（这一行上没有别的写入者做乐观锁比较，
     * 推它只会让别的路径莫名其妙地冲突）；后者是「状态迁移不是编辑记录」那条规则 ——
     * {@code voidPackage} 背后确实有一个人，但破例一次会让「哪些写方法填
     * {@code update_user}」变成一张要背的清单，而且那个 id 在两个域里含义不同。
     *
     * @param remark 作废原因。★ 它会覆盖这一行上原有的 {@code remark} ——
     *               购买路径从不写这一列（恒为 NULL），所以目前不是一次丢失；
     *               <b>哪天购买也开始写 remark，这里就要改成拼接</b>
     * @return 影响行数。<b>1 = 可以退钱；0 = 什么都不做</b>
     */
    int voidPackage(@Param("id") Long id,
                    @Param("remark") String remark,
                    @Param("updateTime") LocalDateTime updateTime);

    // ==================== 定时任务 ====================

    /**
     * 把过期但状态还停在 {@code 1 生效中} 的权益落库成 {@code 3 已过期}
     *
     * <pre>
     *   UPDATE member_package
     *      SET status = 3, update_time = #{updateTime}
     *    WHERE status = 1 AND locked_order_id IS NULL AND expire_time &lt;= #{now}
     *    LIMIT #{limit}
     * </pre>
     *
     * <p>★ 三个 {@code WHERE} 条件各自的理由、以及为什么必须有 {@code LIMIT}，
     * 都写在 XML 里对应语句的上方 —— <b>改这条 SQL 之前先读那一段</b>，
     * 尤其是 {@code locked_order_id IS NULL}（不打扰一笔正在充电的订单）
     * 和 {@code <=}（和 {@code listUsable} 的 {@code >} 严丝合缝，不能差一个等号）。
     *
     * <p>★★ <b>它<b>不是</b>正确性的一环。</b>★★
     * 判断「这份权益能不能用」的权威 SQL 是 {@link #listUsable}，它自己带着
     * {@code expire_time > #{now}}。本方法只负责让 {@code status} 这个
     * <b>给人看的缓存</b>跟上事实 —— 扫晚了、挂了，都不会让任何一笔钱算错。
     * 完整论证见 {@code MemberPackageExpireJob} 类注释第一节。
     * ★ 反过来说：<b>哪天有人把 {@code listUsable} 里的时间条件删掉、
     * 改成只看 {@code status = 1}，这个方法就会突然变成正确性的一环</b>，
     * 而它的保证强度（30 分钟一轮、无重试、无告警）完全不够 —— 那会是一次静默的降级。
     *
     * <p>★ 返回<b>影响行数</b>，调用方用它判断「扫满了没有」。
     * 注意它和 {@code LIMIT} 相等只说明「可能有积压」，<b>不</b>说明
     * 「刚好有 limit 份过期」—— 那批行里可能有几行在被选的瞬间还不满足条件。
     * 所以那个判断只能用来打日志，不能用来算业务数字。
     *
     * @param now        判定边界。<b>和 {@code updateTime} 传同一个值</b> ——
     *                   否则会出现「按 A 判定过期、落库时间写的是 B」
     * @param limit      单轮上限。★ 必须 > 0：{@code LIMIT 0} 影响 0 行，
     *                   任务会看起来「一直在跑但什么都不做」，而没有任何报错
     * @param updateTime 写进 {@code update_time}，见 {@code now}
     * @return 影响行数
     */
    int expireOverdue(@Param("now") LocalDateTime now,
                      @Param("limit") int limit,
                      @Param("updateTime") LocalDateTime updateTime);
}
