package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.Vehicle;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 车辆数据访问
 *
 * <h2>★★ 一、这个接口的每一条 SQL 都带 {@code member_id} —— 这是它的设计原则</h2>
 *
 * <p>读是 {@code WHERE id = ? AND member_id = ?}，写也是。
 * <b>没有任何一条只按 id 操作的语句</b>，包括 {@code update} 和 {@code delete}。
 *
 * <p>为什么把这件事做进 SQL 而不是靠 Service 里先查后判：作用域是文件名级的
 * 性质，只要这个接口里不存在「按 id 查车」的方法，<b>写不出水平越权的代码</b>
 * —— 想越权得先改 Mapper，而改 Mapper 是一个会被 review 看见的动作。
 * 而「先 SELECT 判断归属、再 UPDATE」既是 TOCTOU，又把安全检查放在了
 * <b>未来的调用方可能忘记加</b>的位置。
 *
 * <p>★ 名字里带上作用域（{@code getByIdAndMember} 而不是 {@code getById}），
 * 让调用点一眼可见它是安全的 —— 危险的操作应该有一个更扎眼的名字。
 *
 * <h2>★★ 二、{@code is_default} 的不变量：由两条条件更新维护</h2>
 *
 * <p>业务规则「每个车主最多一辆默认车」<b>无法用唯一索引表达</b>
 * （见 {@link Vehicle} 的类注释第一节：那个索引会禁止
 * 「一个车主有两辆非默认车」），所以它落在
 * {@code clearDefaultExcept} / {@code markDefault} 这两个方法上。
 * <b>三条承重的细节，每一条都不是优化：</b>
 *
 * <p><b>1. {@code clearDefaultExcept} 的 {@code id <> #{id}} 是正确性要求。</b>
 * 新建第二辆车时，Service 是先 insert（拿到 id）再清默认的，那一刻新行在
 * <b>同一个事务里已经可见</b>且 {@code is_default} 已经是 1 ——
 * 没有这个排除条件，创建操作会把自己刚设的默认标记立刻清掉。
 * ★ <b>而且它是静默的</b>：接口返回成功，用户的第二辆车就是没成为默认车。
 *
 * <p><b>2. {@code markDefault} 的 {@code member_id} 在 WHERE 里，
 * 所以「是不是你的车」就是写入条件本身。</b>影响行数为 0 ⇒ 不存在或不是你的，
 * Service 抛 {@code NotFoundException}。
 *
 * <p><b>3. {@code clearDefaultExcept} 返回 0 不是错误</b>（其它车本来就不是
 * 默认车）。<b>只有 {@code markDefault} 的行数携带含义</b> ——
 * 不要让这两个返回值被对称地处理。
 *
 * <p><b>★ 并发：这两条是「条件更新」，不是「先读再写」。</b>
 * {@code WHERE member_id = ?} 会走 {@code uk_vehicle_member_plate} 的最左前缀，
 * 把该车主的车辆行全部锁住，两个并发的「设为默认」在第一条语句上就串行化了，
 * 最终恰好一辆是默认车。
 *
 * <p>★ <b>残留的口子只有一个，而且不是代码能防的</b>：手工 SQL 可以直接把
 * 两辆车都改成 {@code is_default = 1}，修法就是再调一次「设为默认」。
 * 这也是为什么读侧不写 {@code WHERE is_default = 1} 期望一行，
 * 而是按 {@code is_default DESC, id DESC} 取第一条：数据真坏了时，
 * 页面只是多显示一个标记，而不是抛 {@code TooManyResultsException}。
 *
 * <h2>★ 三、这两个方法为什么收实体参数，而不是收两个 id</h2>
 *
 * <p>机械原因：{@code AutoFillInterceptor.findEntity} 会把<b>第一个参数</b>
 * 当成实体交给反射填充器，收 {@code Long} 的话插件会拿它去找
 * {@code setUpdateTime(LocalDateTime)}，找不到就抛
 * {@code IllegalStateException} —— ★ <b>运行时错误，第一次调用才炸。</b>
 *
 * <p>语义原因（更重要）：这两个语句<b>确实在改行</b>，所以它们<b>应该</b>写审计列。
 * 传进来的就是真对象（create 路径上是刚插入的那辆，setDefault 路径上是目标那辆），
 * 不是为满足反射而造的假对象。
 *
 * <h2>★ 四、没有「有订单就不能删」的守卫，也不要加</h2>
 *
 * <p>{@code charging_order.plate_no} 的列注释写着：「车牌快照：
 * <b>车辆可被删除/改牌</b>，订单必须保留当时值」，{@code vehicle_id}
 * 也允许 NULL —— 悬挂引用是<b>被设计得出来的</b>。所以「车被删了」
 * 这件事的检查属于<b>下单路径</b>，不属于删除路径；加在这里会让
 * 「卖车之后想把车从列表里去掉」这个正当操作变得不可能
 * （{@code vehicle} 没有 status 列，硬删是唯一手段）。
 *
 * <p>★ 第二个交代：<b>订单查询不许 JOIN vehicle 取车牌</b>，
 * 必须读 {@code charging_order.plate_no} 快照列 —— 否则删车之后
 * 历史订单的车牌就消失了，而那张表存在的意义就是留住当时的值。
 */
@Mapper
public interface VehicleMapper {

    /**
     * 按主键查，<b>限定归属车主</b>（不含归属校验的版本刻意不存在）
     *
     * <p>返回 {@code null} 表示「不存在 <b>或</b> 不是这个车主的」——
     * 两种情况 Service 返回同一句话，见 {@code VehicleServiceImpl}。
     * 分开提示会变成「这个 id 存在，只是不属于你」，
     * 那等于免费告诉攻击者哪些 id 是有效的。
     */
    Vehicle getByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * 查某个车主的全部车辆
     *
     * <p>★ 不分页，因为一个车主的车辆数在个位 —— 这是刻意的，
     * 也是车主端没有 {@code /page} 接口的原因。
     *
     * <p>★ 排序 {@code is_default DESC, id DESC}：默认车在最前，其余按添加顺序
     * 倒序；{@code id} 唯一 → 全序 → 排序稳定，翻页/刷新不会跳。
     *
     * <p>★ 走 {@code uk_vehicle_member_plate} 的最左前缀（{@code member_id}），
     * 所以这个查询是走索引的 —— DDL 里那句「一次解决两件事」说的就是这个：
     * 唯一键顺便当了「查我的车辆」的索引。
     */
    List<Vehicle> listByMemberId(@Param("memberId") Long memberId);

    /**
     * 数某个车主有几辆车
     *
     * <p>用途只有一个：判断「这是不是他的第一辆车」，
     * 是的话强制 {@code is_default = 1}（见 {@code VehicleServiceImpl.create}）。
     */
    @Select("SELECT COUNT(*) FROM vehicle WHERE member_id = #{memberId}")
    int countByMemberId(@Param("memberId") Long memberId);

    /**
     * 新增车辆
     *
     * <p>{@link AutoFill} 填四个审计字段（本表四列齐全），
     * {@code useGeneratedKeys} 回填自增主键。
     *
     * <p>★ {@code is_default} 是 {@code NOT NULL} 且走全列插入，
     * 传 null 会直接 errno 1048 —— 默认值 {@code 0} 由
     * {@code VehicleDTO.DEFAULT_IS_DEFAULT} 提供，Service 再兜一次。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Vehicle vehicle);

    /**
     * 按主键更新非空字段，<b>限定归属车主</b>
     *
     * <p>★ {@code is_default} <b>不在这条 update 的范围里</b>。
     * 改默认车只有一个入口：{@code PUT /api/member/vehicle/{id}/default}。
     * 两条写入路径通向同一个不变量，迟早会有一条忘了清旧的 ——
     * 所以 {@code VehicleUpdateDTO} 里根本没有这个字段。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Vehicle vehicle);

    /**
     * 按主键删除，<b>限定归属车主</b>
     *
     * <p>★ 裸删除，<b>不做任何守卫</b>，而且刻意如此 —— 完整理由见类注释第四节。
     *
     * <p>★ 返回值是影响行数：0 表示「不存在或不是你的」。
     */
    @Delete("DELETE FROM vehicle WHERE id = #{id} AND member_id = #{memberId}")
    int deleteByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * 把该车主其它车辆的 {@code is_default} 清零
     *
     * <p>★ {@code id <> #{id}} 是<b>正确性要求</b>，不是优化 —— 见类注释第二节第 1 条。
     * 去掉它，新建第二辆默认车时会把自己刚设的标记立刻清掉，而且不报错。
     *
     * <p>★ 返回 0 行是正常情况（其它车本来就不是默认车），<b>不是错误</b>。
     *
     * <p>★ 审计两列显式写：不写的话 {@code update_user} 会停在旧值上，
     * 而 {@code update_time} 却因为 DDL 的 {@code ON UPDATE} 前进了 ——
     * 一个自相矛盾的行。
     */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE vehicle SET is_default = 0, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE member_id = #{memberId} AND is_default = 1 AND id <> #{id}")
    int clearDefaultExcept(Vehicle defaultVehicle);

    /**
     * 把这一辆置为默认车
     *
     * <p>★ {@code member_id} 在 WHERE 里，所以<b>归属校验就是写入条件本身</b> ——
     * 见类注释第二节第 2 条。
     *
     * <p>★ 幂等：已经是默认车时再调一次也成功（匹配到自己，值不变）。
     * 这一点让「设为默认」这个操作可以被安全地重复触发。
     */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE vehicle SET is_default = 1, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id} AND member_id = #{memberId}")
    int markDefault(Vehicle vehicle);
}
