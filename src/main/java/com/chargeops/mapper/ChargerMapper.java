package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充电桩数据访问
 *
 * <p>注解 / XML 的分工规则见 {@link StationMapper} 的类注释。
 *
 * <h2>★ 关于 {@code listByIds}</h2>
 *
 * <p>这里<b>曾经</b>写着「本接口没有 {@code listByIds}，因为没有任何地方需要
 * 批量按 id 查桩」—— 那句话在写下的时候是对的（需要批量补站名的是
 * {@code ChargerService}，它拿的是 {@code StationMapper.listByIds}）。
 *
 * <p>★ 车主端的「这个站有哪些枪」把它变成了一个真实需求：枪列表要显示
 * 「A区-01 的 2 号枪」，而 {@code connector} 表里只有 {@code charger_id}。
 * 这一次的调用方是 {@code MemberStationServiceImpl}。
 *
 * <p>★★ <b>这条注释的变迁本身值得留意</b>：那句「没人调用的方法是纯负担」
 * 是对的，但它针对的是<b>当时</b>的方法集合。真实需求出现时，
 * 正确做法是<b>改掉这句话并说明为什么变了</b>，
 * 而不是为了不动注释去写一个 join 绕开它 ——
 * 后者会让「这个 Mapper 只负责 charger 一张表」这条职责边界失守，
 * 而且失守得无声无息。
 */
@Mapper
public interface ChargerMapper {

    Charger getById(@Param("id") Long id);

    /**
     * 批量按主键查
     *
     * <p>消除 N+1：车主端枪列表要补「所属桩的编号/名称/类型」，
     * 一个站下十几根桩逐个 {@code getById} 就是十几次查询，这里只要 1 次。
     *
     * <p><b>★ {@code ids} 不能是空集合。</b>{@code <foreach>} 展开成
     * {@code IN ()} 是 SQL 语法错误（不是返回空列表），而空集合在业务上很常见
     * —— 一个站下恰好一把枪都没有的时候。调用方必须先判空，
     * 先例见 {@code ChargerServiceImpl} 补站名的那段。
     */
    List<Charger> listByIds(@Param("ids") List<Long> ids);

    /**
     * 某个站点下的桩数量
     *
     * <p>用于站点的删除守卫：站点下还有桩时不允许删。
     *
     * <p>★ 守卫只问「有没有桩」，还没问「这些桩上有没有在充的订单」——
     * 一个站点在有人充电时被删掉，那笔订单会失去它的站点归属。
     * 要加检查的位置是 {@code StationServiceImpl.assertDeletable}。
     */
    @Select("SELECT COUNT(*) FROM charger WHERE station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    /**
     * 新增充电桩
     *
     * <p>{@link AutoFill} 填四个审计字段（本表四列齐全）。
     *
     * <p>★ {@code gunCount} 必须<b>显式给 0</b>，不能指望 DDL 的默认值 1 ——
     * 全列插入下 {@code DEFAULT} 不生效，传 null 会撞 errno 1048。
     * 而且 0 才是对的：刚建好的桩一把枪都没有。
     *
     * <p>★ {@code ratedPowerKw} 是 {@code NOT NULL} 且<b>连默认值都没有</b>，
     * 所以它必须在 DTO 层用 {@code @NotNull} 拦住 ——
     * 否则用户看到的是一个只提到列名的 500。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Charger charger);

    /**
     * 按主键更新非空字段
     *
     * <p>★★ <b>{@code gunCount} 和 {@code stationId} 都不在 {@code <set>} 里</b>，
     * 因为它们都<b>不在 {@code ChargerUpdateDTO} 里</b> —— 让「不能改」
     * 成为编译期事实，而不是运行期的一句检查。两个字段的原因不同，
     * 见 {@code ChargerMapper.xml} 的 update 注释。
     *
     * <p>★ 局部更新的「没法清空字段」限制见 {@link StationMapper#update}。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Charger charger);

    /**
     * 分页查询
     *
     * <p>★ {@code PageHelper.startPage} 的调用约定见
     * {@link StationMapper#page}。
     */
    List<Charger> page(ChargerPageQueryDTO query);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除，不做守卫。调用方必须先查 {@code countByChargerId} ——
     * 桩下还有枪时删掉它，那些枪会带着失效的 {@code charger_id} 留下。
     */
    @Delete("DELETE FROM charger WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
