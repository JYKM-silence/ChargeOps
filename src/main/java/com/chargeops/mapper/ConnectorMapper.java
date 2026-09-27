package com.chargeops.mapper;

import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.entity.Connector;
import com.chargeops.vo.StationConnectorCountVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电枪数据访问
 *
 * <h2>★★ 一、这个接口的写方法<b>绝不能</b>标 {@code @AutoFill}</h2>
 *
 * <p>{@code connector} 表<b>没有 {@code create_user} / {@code update_user} 两列</b>，
 * 而 {@code AutoFillInterceptor} 是<b>反射</b>找 {@code setCreateUser(Long)} 的 ——
 * 找不到会<b>直接抛 {@code IllegalStateException}</b>，不是静默跳过。
 * 所以标错了不是「填不上就不填」，而是 {@code create} / {@code update}
 * <b>当场全部炸掉</b>。（对照 {@link StationMapper}：那张表四列齐全，所以标了。）
 *
 * <p>两个时间由 {@code ConnectorServiceImpl} 显式 {@code set}。
 * <b>为什么不让 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 来填：</b>
 * 那样这两个时间来自<b>数据库服务器</b>的时钟，而全库其它表的时间来自
 * <b>应用服务器</b>的时钟，跨表的时间比较（「这笔订单创建时那把枪是什么状态」）
 * 会出现看似不可能的次序。让时间的来源只有一个。
 *
 * <h2>★★ 二、更新走乐观锁，且 version 由<b>客户端</b>回传</h2>
 *
 * <p>影响行数为 0 就是有人先改了。<b>服务端自己读一遍 version 再写的话，
 * 检查永远通过 —— 那是装饰性的乐观锁</b>，一个看起来有并发保护、
 * 实际上只多了一次查询的实现。所以 {@code ConnectorVO} 里必须带
 * {@code version}，前端改完回传。
 *
 * <h2>★ 三、{@code current_order_id} 归订单状态机，走另外四个方法</h2>
 *
 * <p>它不在管理端的 {@code insert} / {@code update} 里：创建时取 DDL 默认值
 * {@code NULL}，更新时也不在 {@code <set>} 里。这让「创建不出一把已被占用的枪」
 * 变成<b>结构上的事实</b>，而不是「Service 里记得别设它」。
 *
 * <p>写这一列的是 {@link #occupy} / {@link #markCharging} / {@link #markOccupied}
 * / {@link #release} 四个方法 —— <b>刻意不和管理端更新共用一条 SQL</b>，
 * 因为判据不同：管理端走 {@code WHERE id = ? AND version = ?}（version 由客户端
 * 回传），订单流程走 {@code WHERE id = ? AND current_order_id = ? orderId}
 * （「当前占用者是不是我这一单」）。
 *
 * <p>★ 这四个方法改 {@code status} 时<b>都推 {@code version}</b>，而
 * {@link #incrementChargeCount} <b>不推</b>。区别在于 {@code status} 是管理员
 * 也能改的列，而管理端的写是 {@code WHERE id = ? AND version = ?}（version 由客户端回传）。
 *
 * <p>★★ <b>推 version 挡住的只是其中一个方向，别记反。</b>★★
 * <ul>
 *   <li><b>挡得住</b>：CAS 先改、管理员后改。管理员手上那个 version 已经过期，
 *       他的 {@code UPDATE} 影响 0 行 →「刷新后重试」，而不是把一次他从未看到的
 *       状态流转悄悄写掉；</li>
 *   <li><b>挡不住</b>：管理员先改、CAS 后改。CAS 的 {@code WHERE} 里<b>没有
 *       version</b>，所以它会无条件覆盖 —— 管理员在充电期间设的「4 故障」，
 *       会被随后的一次 {@code markCharging} 或 {@code release} 改掉。
 *       设备上报路径上这是<b>有意接受</b>的（设备是事实来源），
 *       理由和后果写在 {@link #markCharging} 上。</li>
 * </ul>
 *
 * <p>★ {@code total_charge_count} 为什么反过来不推：它不在管理端 {@code update}
 * 的 {@code <set>} 子句里，管理员的写碰不到它，两个方向都不会丢失更新 ——
 * 推了只会让每个管理员在每次有车充完电时收一次假冲突。
 */
@Mapper
public interface ConnectorMapper {

    Connector getById(@Param("id") Long id);

    /**
     * 某根桩下的枪数量
     *
     * <p>用于桩的删除守卫：桩下还有枪时不允许删。
     *
     * <p>★ 这个值同时是 {@code charger.gun_count} 的事实来源 ——
     * {@link #refreshGunCount} 算的就是它。两者用的是同一个 COUNT，
     * 所以「守卫说还有 3 把枪」和「列表显示 3 把枪」永远不会互相矛盾。
     */
    @Select("SELECT COUNT(*) FROM connector WHERE charger_id = #{chargerId}")
    int countByChargerId(@Param("chargerId") Long chargerId);

    /**
     * 把 {@code charger.gun_count} 刷新成该桩下真实的枪数
     *
     * <p>★ <b>必须在枪的新增/删除的同一个事务里调用。</b>
     * {@code gun_count} 是 DDL 明写的冗余列，不维持就会漂移 ——
     * 而且漂移是无声的：页面上显示「A区-01（2 枪）」，点进去只有 1 把，
     * 一直漂到有人手工数枪才会被发现。
     *
     * <p>★ 为什么是「重新 COUNT 一遍」而不是 {@code gun_count = gun_count + 1}：
     * 增量更新在<b>并发</b>下会丢更新（两个请求各读到 1，各写 2，实际有 3 把枪），
     * 而重新 COUNT 是幂等的 —— 无论调多少次、谁先谁后，结果都等于真实值。
     */
    @Update("UPDATE charger SET gun_count = "
            + "(SELECT COUNT(*) FROM connector WHERE charger_id = #{chargerId}) "
            + "WHERE id = #{chargerId}")
    int refreshGunCount(@Param("chargerId") Long chargerId);

    /**
     * 新增充电枪
     *
     * <p>★ <b>刻意不标 {@code @AutoFill}</b> —— 见类注释第一节。
     * SQL 在 XML 里，因为要和 {@link #update} 共用列清单。
     */
    void insert(Connector connector);

    /**
     * 按主键更新，带乐观锁
     *
     * <p>★ <b>执行成功时返回 1，版本冲突或行不存在时返回 0。</b>
     * 调用方必须检查返回值 —— 丢掉它的话，乐观锁就完全失效了：
     * 更新悄悄没生效，而接口返回成功。
     *
     * <p>★ 同样的 0 行有两种含义，调用方要能区分：
     * 「这行被删了」（{@code getById} 返回 null → {@code NotFoundException}）
     * 和「别人先改了」（{@code getById} 还在 → {@code DataConflictException}）。
     *
     * @return 影响行数，成功恒为 1
     */
    int update(Connector connector);

    /**
     * <b>建单时抢占这把枪</b> —— 「一把枪同时只能有一个订单」的真正实现
     *
     * <p>★★ 这是 {@code current_order_id} 与唯一索引 {@code uk_connector_current_order}
     * 唯一的写入入口。在此之前它一直是 {@code NULL}，因为没有任何代码写过它 ——
     * 而 {@code RedisConstant.LOCK_CONNECTOR_START} / {@link
     * com.chargeops.exception.ConnectorOccupiedException} / {@code ConnectorStatus}
     * 三处的注释都曾经把一个不存在的写入当成「正确性的保证」。
     *
     * <p>★ <b>它替代的是「先查后插」，不是补充它。</b>建单流程里那次
     * {@code listHoldingByConnector} 查询<b>仍然保留</b>，但它的职责降级为
     * 「给出好看的错误文案」；<b>裁决权在这一条 UPDATE 的影响行数上</b> ——
     * 两个并发请求会同时查到「没有进行中的订单」，然后由数据库决定谁赢。
     *
     * <p>★★ <b>但它有第二种失败方式，而且不是 0 行</b>：如果<b>同一笔订单</b>去占用
     * <b>第二把</b>空闲的枪，这一行完全满足 {@code status = 1 AND current_order_id IS NULL}，
     * UPDATE 会真的执行，然后被<b>唯一索引</b> {@code uk_connector_current_order} 拒绝 ——
     * 调用方拿到的是 {@code DuplicateKeyException}，不是 0。
     *
     * <p>★ 所以「调用方只判影响行数是不是 0」这句话<b>只对「枪被别人占了」这一半成立</b>。
     * 今天这条路径不可达（订单状态机在走到 {@code start()} 之前就会拦住第二次启动），
     * 但契约要写全：<b>0 行 = 枪不空闲；蹦异常 = 一笔订单想占两把枪</b>，
     * 后者是数据错乱而不是并发抢枪，不该被翻译成「请换一把枪」。
     *
     * @return 影响行数。<b>0 表示枪已被占用、或状态不再是「1 空闲」</b>，
     *         调用方据此抛 {@code ConnectorOccupiedException}
     * @throws org.springframework.dao.DuplicateKeyException 同一笔订单试图占用第二把枪
     */
    int occupy(@Param("connectorId") Long connectorId,
               @Param("orderId") Long orderId,
               @Param("updateTime") LocalDateTime updateTime);

    /**
     * 设备上报「启动成功」
     *
     * <p>★ 谓词是 {@code current_order_id = orderId}，所以它也顺带确认了
     * 「这把枪现在是我这一单占着的」。一笔迟到的启动上报不会改到别人的枪。
     *
     * <p>★★ 谓词里<b>没有来源状态</b>，所以上面那个「2 已占用 → 3 充电中」
     * <b>是意图，不是约束</b>。两个后果必须知道：
     * <ul>
     *   <li><b>它不幂等、也不可用来去重</b>：设备把同一条「启动成功」重报一次，
     *       影响行数仍然是 1（并再推一次 version），不是 0。想靠返回值判断
     *       「这条上报是不是重复的」，在这里得不到答案 —— 和
     *       {@code ChargingOrderMapper.markStarted} 那类带来源状态的条件更新
     *       <b>语义不同</b>，别照着那边推；</li>
     *   <li>★★ <b>它会覆盖管理员设的「4 故障」</b>：管理员在充电期间把枪改成故障，
     *       下一条设备上报会把它改回 3 充电中。推 version 只保护了<b>管理员的写</b>
     *       （他手上那个陈旧 version 会冲突），而设备上报不带 version，
     *       所以这一侧的覆盖是无条件的。{@link #release} 的注释里对同一类问题
     *       是<b>明确接受</b>的（理由：设备是事实来源，管理员的一个状态位
     *       不该让一笔真实发生的充电停在中途）—— 这里沿用同一个取舍，
     *       但只写下来，因为它此前没被写下来。</li>
     * </ul>
     *
     * @return 0 表示这把枪不属于该订单（或已被释放），调用方应视为冲突
     */
    int markCharging(@Param("connectorId") Long connectorId,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * 设备上报「结束」：车已经拔枪，但订单还要结算，枪仍归这一单 ——
     * 所以回到「2 已占用」而不是直接放空闲，否则结算期间别人可以下第二笔单
     *
     * <p>★ 谓词与 {@link #markCharging} 完全相同（只有 {@code id} 和
     * {@code current_order_id}，<b>没有来源状态</b>），所以那个「3 充电中 → 2 已占用」
     * 同样只是意图：重报一次仍影响 1 行，也会覆盖管理员的「4 故障」。
     * 理由和取舍见 {@link #markCharging} 的注释。
     *
     * @return 0 表示这把枪不属于该订单（或已被释放）
     */
    int markOccupied(@Param("connectorId") Long connectorId,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /**
     * <b>释放这把枪</b>：订单进入终态、或不再占着它时调用
     *
     * <p>★★ 谓词只有 {@code current_order_id = orderId}，<b>因此它幂等且自守卫</b>：
     * 重复调用、对一把从未被本单占用的枪调用，都是影响 0 行、无副作用。
     * 正因为有这条性质，{@code cancel} / {@code settle} / {@code markAbnormal}
     * 三处可以各自直接调它，不必先判断「我这笔订单还占着枪吗」。
     *
     * <p>★ <b>不要把它和「解冻钱包」合成一个方法</b>：取消订单两样都要做，
     * 而结算<b>只释放枪、绝不解冻</b>（钱要留给待支付去扣）。合成之后必然有人
     * 在结算路径上误调一次，把那笔钱解冻 —— 而那笔钱马上就要被扣款。
     *
     * @return 影响行数，通常不关心；0 表示这把枪本来就不被该订单占用
     */
    int release(@Param("orderId") Long orderId,
                @Param("updateTime") LocalDateTime updateTime);

    /**
     * 累计充电次数 {@code +1}，由<b>结算</b>在同一事务里调用
     *
     * <p>★ <b>不推 {@code version}</b>，与上面四条相反：{@code total_charge_count}
     * 不在管理端 {@code update} 的 {@code <set>} 里，两个方向都不会丢失更新，
     * 而推版本号会让管理员在每次有车充完电时收到一次假冲突。
     *
     * <p>★ <b>它数的是「结算成功的次数」，不是「车充过电的次数」。</b>
     * 经 {@code end()} 电表异常路径转入 {@code 9 异常挂账} 的订单永远走不到结算，
     * 因此不计入。这是已知的取舍：挂账订单的电量本身就算不出来，
     * 没有依据说它「充过一次」。
     */
    int incrementChargeCount(@Param("connectorId") Long connectorId);

    /**
     * 分页查询
     *
     * <p>★ {@code PageHelper.startPage} 的调用约定见 {@link StationMapper#page}。
     */
    List<Connector> page(ConnectorPageQueryDTO query);

    /**
     * 某个站点下的<b>全部</b>充电枪 —— 车主端「选枪」用
     *
     * <p>★ 走 {@code idx_connector_station_status (station_id, status)} 的第一列。
     * 这正是「{@code connector} 冗余一份 {@code station_id}」这个决定的回报 ——
     * 否则「这个站有哪些枪」每次都要 join {@code charger}。
     *
     * <h3>★★ 为什么不加 {@code status} 过滤，只返回空闲的 ★★</h3>
     *
     * <p>把「0 离线 / 4 故障 / 5 维护」的枪直接筛掉，会让一个正在维护的站点
     * 显示成<b>空列表</b> —— 车主看到的是「这个站一把枪都没有」，
     * 而事实是「这个站有 8 把枪，现在都不能用」。<b>两句完全不同的话，
     * 而后者才是能让人做出决定的那个。</b>
     *
     * <p>★ 所以这里把状态原样返回，由前端决定怎么呈现（灰掉 + 写「维护中」），
     * 而不是在 SQL 里替用户做那个判断。**能看见「为什么不能用」
     * 比看不见它更有用。**
     *
     * <p>★ <b>不排序</b>：一把枪的 {@code gun_no} 是字符串（{@code "1"} / {@code "10"}），
     * 按它排会得到 {@code 1, 10, 2}；按 id 排则取决于建枪顺序，同样对不上现场。
     * 真正的顺序应当由前端按「先可用、再按桩分组」重排 —— 那是展示逻辑，
     * 不该固化在 SQL 里。★ 但这里还是给了 {@code ORDER BY id}：
     * 不加的话行序未定义，两次请求可能给出不同顺序，前端做 diff 时会莫名抖动。
     */
    List<Connector> listByStation(@Param("stationId") Long stationId);

    /**
     * 一批站点各自的枪数（总数 / 空闲数）—— 车主端站点列表的统计列
     *
     * <h3>★★ 这是本接口<b>唯一</b>返回投影而非实体的方法 ★★</h3>
     *
     * <p>返回类型 {@code StationConnectorCountVO} 背后<b>没有一张表</b>，
     * 它是一个 {@code GROUP BY station_id} 的聚合结果。为它单开一个类
     * 而不是复用 {@code MemberStationVO}，理由写在那两个类的注释里：
     * <b>「类型说它是什么、实际只装了三个字段」的偏差不会报错，只会静默给出空值。</b>
     *
     * <h3>★ 一次查询覆盖整页站点，而不是逐个 COUNT</h3>
     *
     * <p>一页 10 个站点逐个统计就是 10 次查询。这是
     * {@code ChargerServiceImpl.toVos} 那条「批量而不是逐个」在聚合上的同一条论证。
     *
     * <h3>★★ {@code COALESCE} 不是装饰 ★★</h3>
     *
     * <p>{@code SUM(status = 1)} 在<b>没有任何一行满足</b>时返回 {@code NULL}
     * 而不是 {@code 0}（对照 {@code COUNT}，它恒返回 0）。所以一个站下有 3 把枪
     * 但全是故障时，不写 {@code COALESCE} 会得到 {@code idleCount = null}，
     * 界面上显示成空白而不是 {@code 0}。这类 null 会和「这个站没有枪」
     * 混成同一个展示，而两者是不同的意思。
     *
     * <h3>★ 站下没有枪时<b>整行都不出现</b></h3>
     *
     * <p>{@code GROUP BY} 只给有匹配行的组，所以 {@code LEFT JOIN} 那套
     * 「给个 0 行」的保证在这里没有。调用方必须用「取不到就是 0」的默认值，
     * 不能 {@code map.get(stationId)} 之后直接拆箱 —— 那是 NPE。
     *
     * <p><b>★ {@code ids} 不能是空集合</b>，同 {@code ChargerMapper#listByIds}。
     */
    List<StationConnectorCountVO> countByStationIds(@Param("ids") List<Long> ids);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除。调用方必须先守两道（都在 Service 里）：
     * <ol>
     *   <li>{@code charger} 还存在（否则这是在给一根已删的桩加枪的历史收尾）</li>
     *   <li>{@code current_order_id IS NULL} 且 {@code status} 不在
     *       {@code {2已占用, 3充电中}} —— 正在用的枪被删掉，
     *       那笔在充订单会失去它的枪，而设备还在往这个 id 上报数据</li>
     * </ol>
     */
    @Delete("DELETE FROM connector WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
