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
 * 充电桩数据访问。注解 / XML 的分工规则见 {@link StationMapper} 的类注释。
 */
@Mapper
public interface ChargerMapper {

    Charger getById(@Param("id") Long id);

    /** 消除 N+1。{@code ids} 不能是空集合：{@code <foreach>} 展开成 {@code IN ()} 是语法错误，调用方必须先判空 */
    List<Charger> listByIds(@Param("ids") List<Long> ids);

    /** 站点删除守卫用。它只问「有没有桩」，没问「桩上有没有在充的订单」 */
    @Select("SELECT COUNT(*) FROM charger WHERE station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    /** 全列插入：{@code gunCount} 必须显式给 0（DDL 默认值不生效，传 null 报 errno 1048），{@link AutoFill} 填四个审计字段 */
    @AutoFill(OperationType.INSERT)
    void insert(Charger charger);

    /** {@code gunCount} / {@code stationId} 不在 {@code <set>} 里，因为 DTO 里就没有 —— 「不能改」是编译期事实 */
    @AutoFill(OperationType.UPDATE)
    void update(Charger charger);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<Charger> page(ChargerPageQueryDTO query);

    /** 裸删除，不做守卫。调用方必须先查 {@link #countByChargerId} */
    @Delete("DELETE FROM charger WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
