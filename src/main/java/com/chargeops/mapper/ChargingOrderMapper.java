package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电订单数据访问
 *
 * <h2>★★ 一、全部状态流转都是「带 from 的条件更新」，返回值必须检查 ★★</h2>
 *
 * <pre>
 *   UPDATE charging_order SET status = #{to} ... WHERE id = #{id} AND status = #{from}
 * </pre>
 *
 * <p><b>返回 0 不是异常，是一个正常的业务结果：「这一步已经有人做过了，
 * 或者现在轮不到你做」。</b>调用方必须据此拒绝本次操作
 * （抛 {@code DataConflictException} 或当作幂等成功）——
 * 不检查的话，「重复上报的结束事件」会一路走到结算，把同一笔订单结算两次。
 *
 * <p>★ <b>绝不能写成「先 SELECT 查 status，判一下，再 UPDATE」。</b>
 * 两个并发的启动请求会同时读到 1，然后都更新成功，<b>冻结两次钱</b>。
 * 这和 {@code WalletMapper} 的余额判断是同一个道理，
 * 区别只是判据从「余额够不够」换成了「状态对不对」。
 *
 * <h2>★★ 二、状态流转的 SQL 一律<b>不标</b> {@code @AutoFill} ★★</h2>
 *
 * <p>只有 {@link #insert} 标。理由不是「插件会报错」，而是<b>填出来的值是错的</b>：
 * {@code update_user} 会被填成 {@code BaseContext} 里的当前登录人，
 * 但一笔订单的流转来源有三种 —— 用户点的、<b>设备上报的</b>、定时任务扫的。
 * 设备上报时 {@code BaseContext} 是空的，插件会填成 NULL，而 NULL 在
 * {@code update_user} 上的含义是「谁改的？不知道」—— 真正的答案在
 * {@code charging_order_event.source} 里。所以这些语句显式
 * {@code SET update_time}，{@code update_user} 一动不动，
 * 保持它「最后一次<b>人工</b>编辑这一行」的含义。
 *
 * <h2>★ 三、查询全部带 {@code member_id}，没有「按 id 裸查」的车主端入口</h2>
 *
 * <p>作用域做进 SQL 之后，忘传 memberId 是查不到数据，而不是「忘了比对」这种
 * 可以写出来的 bug。车主端唯一的详情入口是 {@link #getByIdAndMember}。
 */
@Mapper
public interface ChargingOrderMapper {

    /**
     * 建单。★ 唯一一个可以标 {@code @AutoFill(INSERT)} 的语句：订单一定是某个登录
     * 用户创建的，不存在「设备创建订单」这条路，所以 {@code create_user} 填成当前
     * 登录人是对的（状态流转相反，见类注释第二节）。
     */
    @AutoFill(OperationType.INSERT)
    int insert(ChargingOrder order);

    /**
     * 按 id 查（无归属校验）
     *
     * <p>★ 只给<b>订单流程内部</b>用（结算、退款、设备上报都要先读到订单）。
     * 车主端必须用 {@link #getByIdAndMember}。
     */
    ChargingOrder getById(@Param("id") Long id);

    /**
     * 按 id + 车主查 —— 车主端唯一的详情入口，同时完成归属校验
     *
     * <p>★ 查不到和「不是你的」返回<b>同一个结果</b>（{@code null}）。
     * 区分它们会让接口变成「这个订单号存在吗」的探测器 ——
     * 用别人的订单号试探，能问出订单是否存在。
     */
    ChargingOrder getByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * 我的订单分页
     *
     * <p>★ 走 {@code idx_order_member_create (member_id, create_time)}，
     * 时间倒序直接由索引给出，不需要 filesort。
     *
     * <p>★ <b>不写 LIMIT</b>：分页由 {@code PageHelper.startPage} 在拦截器层加，
     * 手写 LIMIT 会让它算出错误的 total。
     *
     * @param status 可空。非空时走 {@code idx_order_member_status}
     */
    List<ChargingOrder> pageByMember(@Param("memberId") Long memberId,
                                     @Param("status") Integer status);

    /**
     * 管理端订单分页 —— <b>四个流程接口的取 id 入口</b>
     *
     * <p>★ 补它之前，{@code /api/admin/**} 下没有任何订单查询，
     * 而 {@code start} / {@code end} / {@code settle} / {@code refund} 全都要一个
     * {@code {id}} —— 四个按钮都在，却没有任何页面能拿到 id。完整说明见
     * {@code AdminOrderPageQueryDTO} 的类注释。
     *
     * <h3>★★ 需要单独的 {@code memberIds} 参数，而不是从 DTO 里读 ★★</h3>
     *
     * <p>DTO 里只有 {@code memberPhone}（前缀），订单表里只有 {@code member_id}。
     * 中间那一步「前缀 → id 列表」由 Service 走 {@code uk_member_phone} 完成，
     * 结果作为<b>独立参数</b>传进来。
     *
     * <p>★ <b>为什么不把这个字段加进 DTO</b>：DTO 是 GET 的查询串绑定目标，
     * 加一个 {@code List<Long> memberIds} 字段就等于开放了
     * {@code ?memberIds=1&memberIds=2} 这个入口，绕开了手机号前缀这条路径。
     * Service 每次都覆写它可以让它失效，但那是「靠人记得」，
     * 而独立参数是结构上的不存在。
     *
     * <h3>★★ {@code memberIds} 为空集合时返回 0 行，<b>而不是不过滤</b> ★★</h3>
     *
     * <p>「这个前缀一个车主都没匹配到」的正确答案是<b>没有订单</b>。
     * XML 里对此做了显式的 {@code AND 1 = 0} 分支 ——
     * 如果写成 {@code <if test="size > 0">} 然后让空集合落进「无条件」那一支，
     * 那么<b>按一个不存在的手机号搜索会列出全平台的订单</b>。
     * 这是本方法最容易写出的静默错误，XML 里有对应的注释。
     *
     * <p>★ <b>不写 LIMIT</b>：分页由 {@code PageHelper.startPage} 在拦截器层加。
     *
     * <p>★ 排序 {@code create_time DESC, id DESC} 的索引依赖，以及
     * 「不带任何条件时是 filesort」这个取舍，见 {@code AdminOrderPageQueryDTO} 第二节。
     *
     * @param memberIds 车主 id 收窄项。<b>{@code null} = 不按车主过滤</b>，
     *                  <b>空列表 = 没有任何车主匹配，返回 0 行</b>。
     *                  两者语义相反，不要合并
     */
    List<ChargingOrder> pageForAdmin(@Param("query") AdminOrderPageQueryDTO query,
                                     @Param("memberIds") List<Long> memberIds);

    /**
     * ★ 某把枪上<b>还占着它</b>的订单
     *
     * <p>走 {@code idx_order_connector_status (connector_id, status)}。
     * 设备重复上报「启动」或「结束」时，第一件事就是问这个。
     *
     * <p>★ 为什么是 {@code List} 而不是「查一条」：正常情况下最多一条，
     * 但真要是有两条（历史脏数据、并发建单），<b>必须让调用方看见这个事实</b>，
     * 而不是用 {@code LIMIT 1} 随便挑一条然后按它操作 ——
     * 被挑中的那条决定了后续所有金额，而另一条会永远停在充电中。
     */
    List<ChargingOrder> listHoldingByConnector(@Param("connectorId") Long connectorId,
                                               @Param("statuses") List<Integer> statuses);

    /**
     * ★ 某个车主的某辆车<b>还占着它</b>的订单
     *
     * <p>同一时刻一辆车只能有一笔进行中的订单。拦不住的话，
     * 用户会拿到两笔都算满额的订单 —— 而设备那边只充了一次电。
     *
     * <p>★ 带 {@code member_id} 不只是为了走索引：这个查询的语义是
     * 「<b>你的</b>车现在有没有在充」，带上它之后，就算将来车辆支持过户
     * （旧车主的进行中订单还在），这个查询回答的仍然是对的那个人。
     *
     * <p>★ <b>{@code charging_order} 上没有 vehicle_id 索引</b>，这是刻意的 ——
     * 进行中的订单在每个车主那里都只有个位数，先按
     * {@code idx_order_member_status} 收窄再过滤车辆，比为一个只用于
     * 「有没有」的列单独建索引便宜。车辆维度要统计时再加。
     */
    List<ChargingOrder> listHoldingByVehicle(@Param("memberId") Long memberId,
                                             @Param("vehicleId") Long vehicleId,
                                             @Param("statuses") List<Integer> statuses);

    /**
     * ★ 某站点下还有多少笔<b>未完结</b>的订单 —— 站点的删除守卫
     *
     * <p>★★ <b>SQL 里 {@code status} 必须写在 {@code station_id} 前面</b>，
     * 两个参数的顺序不是随手排的：
     * <ul>
     *   <li>走在途订单的量级：{@code status IN (1,2,3,4,6,9)} 按构造就是
     *       {@code charging_order} 里极小的一部分，<b>选择性来自 {@code status}</b>，
     *       正好是 {@code idx_order_status_create (status, create_time)} 的第一列；</li>
     *   <li>反过来写（{@code station_id} 在前）会被
     *       {@code idx_order_station_create (station_id, create_time)} 服务 ——
     *       那要扫完<b>这个站的全部历史订单</b>再逐行过滤状态，
     *       而一个运营了两年的站点有几十万行。</li>
     * </ul>
     *
     * <p>★ <b>不为此新建索引。</b>这和 {@link #listHoldingByVehicle} 不建
     * {@code vehicle_id} 索引是同一条论证：在途订单永远是少数，
     * 先按状态收窄再过滤一个列，比为一个「只用来判有没有」的维度建索引便宜。
     * 反过来说，{@code station_id} 单独建索引是<b>错的</b> ——
     * 它看起来更「对口」，实际会让优化器选到扫全站历史的那条路。
     *
     * <p>★ <b>为什么不复用 {@code listHoldingByConnector} 然后数长度</b>：
     * 那要先把整行捞回来才能得到「有几笔」，而这里只需要一个数字。
     *
     * @param statuses 传 {@code OrderStatus.blockingCodes()}，不要写字面量
     */
    int countByStation(@Param("stationId") Long stationId,
                       @Param("statuses") List<Integer> statuses);

    /**
     * ★ 某充电桩下还有多少笔<b>未完结</b>的订单 —— 桩的删除守卫
     *
     * <p>★ {@code charging_order} 上<b>没有 {@code charger_id} 索引</b>，
     * 而这不影响这个查询 —— 理由同上：{@code charger_id} 是<b>残余过滤</b>，
     * 不是前导列。这是「不加索引」这个决定成立的前提，改 SQL 时别把顺序换掉。
     */
    int countByCharger(@Param("chargerId") Long chargerId,
                       @Param("statuses") List<Integer> statuses);

    /**
     * ★ 某份权益被多少笔订单<b>用过</b> —— 套餐退款口径的权威判据
     *
     * <pre>
     *   SELECT COUNT(*) FROM charging_order
     *    WHERE member_package_id = #{packageId} AND status &lt;&gt; 8
     * </pre>
     *
     * <h3>★★ 为什么排除的<b>只有</b> {@code 8 已取消} ★★</h3>
     *
     * <p>退款口径是「只退完全没用过的，全额退」（见 {@code WalletService#refundPackage}），
     * 「用过」的判据因此必须是<b>排除法</b>而不是枚举法。逐个状态看：
     * <table border="1">
     *   <caption>订单状态 → 那份权益算不算被用了</caption>
     *   <tr><th>状态</th><th>那份权益被用了吗</th></tr>
     *   <tr><td>{@code 1/2/3 待启动/充电中/待结算}</td>
     *       <td><b>正在被用</b> —— 锁还占着（{@code locked_order_id} 就是它）</td></tr>
     *   <tr><td>{@code 4/6 待支付/支付失败}</td>
     *       <td><b>已经用掉了</b> —— 结算时 {@code settleEnergy} 等已经把余量扣了。
     *           钱可能还没收到，但那几度电用户已经用过了。★ 这是最容易漏的一格：
     *           只查 {@code status = 5} 的话，一辆车充完电一直不付钱，
     *           用户还能再拿回整份套餐的钱 —— <b>白充一次</b></td></tr>
     *   <tr><td>{@code 5 已支付}、{@code 7 已退款}、{@code 9 异常挂账}</td>
     *       <td><b>用掉了</b>。9 的来源是 {1,2,3,4,6}，其中 4/6 那两支已经扣过量了</td></tr>
     *   <tr><td>{@code 8 已取消}</td>
     *       <td><b>没有</b> —— ★ 唯一的一格。取消走 {@code releaseOrderResources}，
     *           它调 {@code releaseLock} 且<b>余量不动</b>，所以这份权益
     *           从来没被消耗过。把它算成「用过」会让「买错了立刻退」
     *           这个最正当的退款场景退不了</td></tr>
     * </table>
     *
     * <p>★★ <b>为什么不用 {@code remaining_* < total_*} 做判据</b>（它读起来更直接）：
     * 折扣包（{@code packageType = 3}）<b>没有 remaining 列</b> ——
     * 它不消耗，只打折。用余量判断的话，折扣包会被<b>恒定地</b>判成
     * 「没用过」，于是每次退款都退全款，而它可能已经为用户省了几十次服务费。
     * 要修就得在 Java 侧按 {@code packageType} 分四种情况写一遍，
     * 而那正是「加第五种类型时静默漏掉一支」的形状。
     * 按订单状态判则天然对所有类型一视同仁。</p>
     *
     * <p>★★ <b>而这里不能用 {@code OrderStatus} 的具名集合。</b>★★
     * 已有的三个集合（{@code holdingConnectorCodes} / {@code holdingMoneyCodes} /
     * {@code blockingCodes}）都是<b>正向枚举</b>「哪些状态算未完结」，
     * 而本方法的语义是<b>补集</b>：{@code IN (1,2,3,4,5,6,7,9)} 写成枚举意味着
     * <b>将来加一个新状态时，这一格默认落到哪一边</b> —— 写成 {@code IN} 就默认
     * 「新状态不算被用过」（会退错钱），写成 {@code <> 8} 就默认「算被用过」
     * （会拒绝一次正当的退款）。<b>资金路径上两害相权，选拒绝。</b>
     * 加新状态的人会被这一个 {@code <> 8} 逼着去重新回答那个问题，
     * 而一个 {@code IN} 列表只会安静地把他漏掉。</p>
     *
     * <p>★ <b>不建索引。</b>这个查询只在管理端手工点「作废」时跑一次，
     * 而 {@code member_package_id} 的取值分布极散（一笔订单一个），
     * 为它建索引的写入成本高于它省下的一次查询。它走的是
     * {@code charging_order} 的主键/状态索引加残余过滤，量级可接受。
     *
     * <p>★ <b>不校验订单是不是「这笔订单占了这份权益又还回去了」</b> ——
     * 那是 {@code locked_order_id} 要回答的问题，在
     * {@code MemberPackageMapper.voidPackage} 的 {@code WHERE} 里，不在这里。
     * 两者查的是同一件事的两个阶段：本方法查「有没有发生过」，那条查「此刻有没有」。
     */
    int countByPackage(@Param("packageId") Long packageId);

    /**
     * ★ 卡在 {@code 4 待支付} 太久、该判为支付失败的订单 —— 兜底扫描的取件口
     *
     * <h3>★★ 账龄看 {@code settle_time}，<b>不能</b>看 {@code create_time} ★★</h3>
     *
     * <p>这是本方法唯一容易写错的地方，而且写错了<b>不会报错</b>：
     * 一笔充了 8 小时、1 分钟前才结束的订单，它的 {@code create_time} 是 8 小时前 ——
     * 按 {@code create_time} 扫，它会被<b>立刻</b>判成支付失败，
     * 而用户此时可能正在付款页面上输密码。{@code settle_time} 才是
     * 「他有多少时间可以慢慢付」的起点，它是 {@code markSettled} 写的结算完成时间。
     *
     * <p>★ <b>副作用（可接受，且失败方向是安全的）：</b>{@code settle_time IS NULL}
     * 的行永远不会被扫到 —— MySQL 里 {@code NULL < ?} 求值为 {@code NULL}，
     * 即 false。正常情况下到不了 {@code 4}（能用 {@code markSettled} 就只有一条路），
     * 手工改库能造出来。这类行扫不到的表现是<b>不被处理</b>，不是被误判，
     * 所以宁可这样，也不要为了「兜住」而写成 {@code (settle_time IS NULL OR settle_time < ?)}
     * —— 那等于把「时间不明」的订单当场打成失败。
     *
     * <h3>★ 为什么带 {@code limit} 而不是一次扫完</h3>
     *
     * <p>调用方是定时任务。一次把几千笔待支付订单全捞进内存、逐笔开事务去写，
     * 会在数据库上形成一个长尾，而扫描本身是固定间隔重复的 ——
     * 这一轮没扫完的下一轮会处理掉。<b>上限是扫描类任务的必备参数，
     * 不是优化</b>：没有它，第一个把它跑在积压数据上的人会得到一个 OOM。
     *
     * <p>★ 走 {@code idx_order_status_create (status, create_time)}：{@code status = 4}
     * 等值先定位到「所有待支付订单」这个小集合，{@code settle_time} 是<b>残余过滤</b>
     * （该索引的第二列是 {@code create_time}，帮不上这个排序）。
     * <b>不为 {@code settle_time} 单独建索引</b>：待支付订单按构造就是极少数，
     * 加一个只服务兜底扫描的索引，代价是每次订单写入都要多维护一棵 B+ 树。
     *
     * <p>★ <b>排序末尾带 {@code id}</b>：{@code settle_time} 会重复（同一秒结算的一片订单），
     * 没有它的话「这一轮取哪 200 笔」是不确定的，而扫描是反复跑的 ——
     * 不确定的取件顺序会让「某笔订单被反复取到、另一笔永远轮不上」变得可能。
     *
     * @param before 早于这个时刻的算超时。由调用方用配置的分钟数算出，不在这写死
     * @param limit  单轮上限
     */
    List<ChargingOrder> listStalePendingPay(@Param("before") LocalDateTime before,
                                            @Param("limit") int limit);

    // ==================== 状态流转：全部返回 int，调用方必须检查 ====================

    /**
     * 补写冻结的权益 id
     *
     * <p>★ 为什么需要一条单独的语句：{@code member_package.locked_order_id}
     * 要写的是<b>订单 id</b>，而订单 id 是 insert 之后才有的 ——
     * 顺序只能是「先建单 → 拿 id 去锁权益 → 回头把权益 id 补到订单上」。
     *
     * <p>★ 条件里带着 {@code status = 1} 和 {@code member_package_id IS NULL}：
     * 少了 {@code status = 1} 的话，一个延迟到达的补写请求会给一笔已经在
     * 充电的订单换掉权益 —— 而钱已经按旧权益估过价了。
     *
     * <p>★ 影响行数 0 <b>不是</b>致命错误（意味着锁权益那一瞬间订单状态变了），
     * 调用方按「权益没绑上」处理即可。
     */
    int bindMemberPackage(@Param("id") Long id,
                          @Param("memberPackageId") Long memberPackageId,
                          @Param("updateTime") LocalDateTime updateTime);

    /**
     * 启动充电：{@code 1 待启动 → 2 充电中}
     *
     * <p>★ {@code start_meter_kwh} 由设备上报，电表是<b>累计读数</b>、
     * 不是从 0 开始 —— 订单的总电量靠 {@code end - start} 算，不是读绝对值。
     * 读表失败时调用方传 NULL 而不是 0：填 0 会让 total = 整个电表读数。
     */
    int markStarted(@Param("id") Long id,
                    @Param("startTime") LocalDateTime startTime,
                    @Param("startMeterKwh") BigDecimal startMeterKwh,
                    @Param("startSoc") Integer startSoc,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结束充电：{@code 2 充电中 → 3 待结算}
     *
     * <p>★ 只写「充电结束」这个事实，<b>不写金额</b>。
     * 两步分开是因为结算可能失败（电价规则有缺口、电量数据缺失），
     * 而失败不应该让「充电已经结束了」这个事实回滚掉 —— 那是物理世界发生过的事。
     */
    int markEnded(@Param("id") Long id,
                  @Param("endTime") LocalDateTime endTime,
                  @Param("durationSeconds") Integer durationSeconds,
                  @Param("endMeterKwh") BigDecimal endMeterKwh,
                  @Param("totalEnergyKwh") BigDecimal totalEnergyKwh,
                  @Param("endSoc") Integer endSoc,
                  @Param("stopReason") Integer stopReason,
                  @Param("updateTime") LocalDateTime updateTime);

    /**
     * 结算完成：{@code 3 待结算 → 4 待支付}
     *
     * <p>★ 金额三兄弟和 {@code settle_time} 在这一步一次性写定，之后<b>再也不改</b>；
     * {@code total_amount} 必须是分段金额之和（{@code BillingEngine} 的
     * {@code totalAmount()}），<b>不能写「总电量 × 综合单价」</b> ——
     * 那个值会偶尔和分段之和对不上，而差异出现在财务报表上时，
     * 已经没人能还原它当初是怎么算的。分段明细由
     * {@code ChargingSegmentMapper.batchInsert} 在同一个事务里写。
     *
     * <p>★ {@code settleTime} 只是「结算发生在何时」的事实记录，
     * <b>幂等判据是 {@code status = 3}</b>：只判 {@code settle_time IS NULL}
     * 的话，结算和另一条流转会双双通过。
     */
    int markSettled(@Param("id") Long id,
                    @Param("energyFee") BigDecimal energyFee,
                    @Param("serviceFee") BigDecimal serviceFee,
                    @Param("totalAmount") BigDecimal totalAmount,
                    @Param("discountAmount") BigDecimal discountAmount,
                    @Param("payAmount") BigDecimal payAmount,
                    @Param("settleTime") LocalDateTime settleTime,
                    @Param("updateTime") LocalDateTime updateTime);

    /**
     * 支付成功：{@code 4 待支付 / 6 支付失败 → 5 已支付}
     *
     * <p>★ <b>必须在 {@code walletService.settle} 之前执行</b>，而且必须同事务。
     * 钱包侧的 {@code uk(order_id, type)} 是最后一道防线，而它被触发时
     * 整个事务已经被标记回滚了（catch 住也没用）—— 所以订单侧必须先有自己的判断。
     *
     * <p>★ 来源是<b>两个</b>状态，{@code 6 支付失败} 也在内：
     * 用户充值回来点第二次支付时，订单正停在 6 上 ——
     * 只认 4 的话那个按钮永远返回「状态不对」，而钱还冻着。
     */
    int markPaid(@Param("id") Long id,
                 @Param("payTime") LocalDateTime payTime,
                 @Param("updateTime") LocalDateTime updateTime);

    /**
     * 支付失败：{@code 4 待支付 → 6 支付失败}
     *
     * <p>★ <b>钱还冻着</b> —— 既没扣掉也没退回。用户充值之后
     * 再点一次支付，走的是 {@link #markPaid} 里 {@code 6 → 5} 那条边。
     */
    int markPayFailed(@Param("id") Long id,
                      @Param("remark") String remark,
                      @Param("updateTime") LocalDateTime updateTime);

    /**
     * 退款完成：{@code 5 已支付 → 7 已退款}
     *
     * <p>★ 只有 {@code 5 已支付} 能退款。{@code 4 待支付} 不能退 ——
     * 那时候钱还没扣（冻结的钱由取消路径解冻）。
     */
    int markRefunded(@Param("id") Long id,
                     @Param("refundAmount") BigDecimal refundAmount,
                     @Param("refundTime") LocalDateTime refundTime,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 取消订单：{@code 1 待启动 → 8 已取消}
     *
     * <p>★ <b>只有 1 待启动能取消。</b>让 2 充电中也走这条路，等于给了一条
     * 「充完电把订单作废」的路径 —— 车已经被充过、电已经用掉，而订单被抹掉。
     * 已经开始的充电只能「结束」（走 {@link #markEnded}），然后按实际电量结算。
     *
     * <p>★ 取消时<b>必须同时解冻</b>钱包里的钱，由调用方在同一个事务里完成 ——
     * 两张表的一致性由事务保证，不由某一方负责。
     */
    int markCancelled(@Param("id") Long id,
                      @Param("updateTime") LocalDateTime updateTime);

    /**
     * 转入异常挂账：{@code 任意「钱还冻着」的状态 → 9}
     *
     * <p>★ 用「钱还冻着的状态集」而不是一个固定的 from：挂账可能发生在待启动
     * （时间戳异常）、充电中（设备通信断了）、待结算（电价有缺口）、
     * 待支付（余额不够）、支付失败（重试还是不够）。
     *
     * <p>★★ 集合必须传 {@code OrderStatus.holdingMoneyCodes()}，<b>不是</b>
     * {@link #listHoldingByConnector} 用的那个 {@code holdingConnectorCodes()} ——
     * 后者只有 {1,2,3}，漏掉 4 和 6，而挂账恰恰是冲着
     * 「钱冻着、账结不掉」的订单去的。
     */
    int markAbnormal(@Param("id") Long id,
                     @Param("statuses") List<Integer> statuses,
                     @Param("remark") String remark,
                     @Param("updateTime") LocalDateTime updateTime);
}
