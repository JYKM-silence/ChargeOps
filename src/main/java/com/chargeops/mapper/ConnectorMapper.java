package com.chargeops.mapper;

import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.entity.Connector;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 充电枪数据访问
 *
 * <h2>★★ 一、这个接口的写方法<b>绝不能</b>标 {@code @AutoFill}</h2>
 *
 * <p>{@code connector} 表<b>没有 {@code create_user} / {@code update_user} 两列</b>
 * （见 DDL），而 {@code AutoFillInterceptor} 是<b>反射</b>找
 * {@code setCreateUser(Long)} 的 —— 找不到会<b>直接抛 {@code IllegalStateException}</b>，
 * 不是静默跳过。
 *
 * <p>所以标错了不是「填不上就不填」，而是 {@code create} / {@code update}
 * <b>当场全部炸掉</b>。这是 P1a 最容易犯的错，因为「所有写操作都该标
 * {@code @AutoFill}」是一个非常自然的直觉。
 *
 * <p>取而代之：{@code createTime} / {@code updateTime} 由
 * {@code ConnectorServiceImpl} 显式 {@code set}。
 * <b>为什么不让 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 来填：</b>
 * 那样这两个时间就来自<b>数据库服务器</b>的时钟，而全库其它表的时间来自
 * <b>应用服务器</b>的时钟。两台机器的时钟总会差几毫秒到几秒，
 * 于是跨表的时间比较（「这笔订单创建时那把枪是什么状态」）会出现
 * 看似不可能的次序。让时间的来源只有一个。
 *
 * <p>对照 {@link StationMapper}：那张表四列齐全，所以标了 {@code @AutoFill}。
 *
 * <h2>★★ 二、更新走乐观锁，且 version 由<b>客户端</b>回传</h2>
 *
 * <pre>
 *   UPDATE connector SET ..., version = version + 1
 *   WHERE id = #{id} AND version = #{version}
 * </pre>
 *
 * <p>影响行数为 0 就是有人先改了。<b>服务端自己读一遍 version 再写的话，
 * 检查永远通过 —— 那是装饰性的乐观锁</b>，一个看起来有并发保护、
 * 实际上只多了一次查询的实现。所以 {@code ConnectorVO} 里必须带
 * {@code version}，前端改完回传。
 *
 * <p>版本号在 SQL 里 {@code version + 1} 自增，不在 Java 侧算 ——
 * Java 侧算的话「读出来的 V」和「写进去的 V+1」之间存在窗口。
 *
 * <h2>★ 三、{@code current_order_id} 不在这两个写方法里</h2>
 *
 * <p>它归 P1c 的订单状态机所有。P1a 创建枪时它取 DDL 默认值 {@code NULL}，
 * 更新时也不出现在 {@code <set>} 里。
 *
 * <p>这样做的价值是让「P1a 不可能创建出一把已被占用的枪」变成
 * <b>结构上的事实</b>，而不是「Service 里记得别设它」。
 * P1c 会用自己的一条条件更新来写这一列 —— 那条 SQL 和这里的
 * 管理端更新是两件事，不该共用一个方法。
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
     * <p>为什么用「重新 COUNT 一遍」而不是 {@code gun_count = gun_count + 1}：
     * 增量更新在<b>并发</b>下会丢更新（两个请求各读到 1，各写 2，实际有 3 把枪），
     * 而重新 COUNT 是幂等的 —— 无论调多少次、谁先谁后，结果都等于真实值。
     * 冗余列的维护要选「重算」而不是「增量」，除非有性能上的硬理由。
     */
    @Update("UPDATE charger SET gun_count = "
            + "(SELECT COUNT(*) FROM connector WHERE charger_id = #{chargerId}) "
            + "WHERE id = #{chargerId}")
    int refreshGunCount(@Param("chargerId") Long chargerId);

    /**
     * 新增充电枪
     *
     * <p>★ <b>刻意不标 {@code @AutoFill}</b> —— 见类注释第一节。
     * {@code createTime} / {@code updateTime} 由 Service 显式设置。
     *
     * <p>SQL 在 XML 里（要和 {@link #update} 共用列清单）。
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
     * 直接报「数据已被他人修改」会在记录真的被删掉时给出误导性的提示。
     *
     * @return 影响行数，成功恒为 1
     */
    int update(Connector connector);

    /**
     * 分页查询
     *
     * <p>★ {@code PageHelper.startPage} 的调用约定见 {@link StationMapper#page}。
     */
    List<Connector> page(ConnectorPageQueryDTO query);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除。调用方必须先守两道：
     * <ol>
     *   <li>{@code charger} 还存在（否则这是在给一根已删的桩加枪的历史收尾）</li>
     *   <li>{@code current_order_id IS NULL} 且 {@code status} 不在
     *       {@code {2已占用, 3充电中}} —— 正在用的枪被删掉，
     *       那笔在充订单会失去它的枪，而设备还在往这个 id 上报数据</li>
     * </ol>
     * 两道都在 Service 里，因为都需要读别的表或判断业务状态。
     */
    @Delete("DELETE FROM connector WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
