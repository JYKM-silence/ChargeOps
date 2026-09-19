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
 * <p><b>★ 与 StationMapper 的一处不同：这里没有 {@code listByIds}。</b>
 * 需要批量补站名的是 {@code ChargerService}（它拿 {@code StationMapper.listByIds}），
 * 而没有任何地方需要「批量按 id 查充电桩」——
 * 桩的列表永远是从 {@code page} 或 {@code getById} 来的。
 * 写一个没人调用的方法是纯负担：半年后没人敢删它，因为不知道当初为什么加的。
 */
@Mapper
public interface ChargerMapper {

    Charger getById(@Param("id") Long id);

    /**
     * 某个站点下的桩数量
     *
     * <p>用于站点的删除守卫：站点下还有桩时不允许删。
     *
     * <p>走索引 {@code idx_charger_station_status} 的第一列。
     *
     * <p>★ <b>P1c 要在这里加上订单维度的检查。</b>
     * 本轮的守卫只问「有没有桩」，P1c 之后还应该问
     * 「这些桩上有没有在充的订单」—— 一个站点在有人充电时被删掉，
     * 那笔订单会失去它的站点归属。加检查的位置就在
     * {@code StationServiceImpl.assertDeletable}。
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
     * 见 {@code ChargerMapper.xml} 的 insert 注释。
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
     * 成为编译期事实，而不是运行期的一句检查。
     * 理由见 {@code ChargerMapper.xml} 的 update 注释（两个字段的原因不同）：
     * {@code gunCount} 是冗余列不能有第二条写入路径，
     * {@code stationId} 是改了会让每把枪的冗余 {@code station_id} 指向老站点。
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
     * 桩下还有枪时删掉它，那些枪会带着失效的 {@code charger_id} 留下，
     * 而且 {@code charger.gun_count} 的冗余对账也再没有依据。
     */
    @Delete("DELETE FROM charger WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
