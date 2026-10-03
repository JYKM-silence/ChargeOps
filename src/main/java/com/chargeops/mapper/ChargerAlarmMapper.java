package com.chargeops.mapper;

import com.chargeops.entity.ChargerAlarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 充电桩告警数据访问。只追加：告警恢复是改 {@code status}，不删行。
 *
 * <p> 没有 insert 是因为本项目不接设备直连，告警由设备侧写入 ——
 * 本模块只读它。种子数据见 {@code V5__ai_ops_seed.sql}。
 */
@Mapper
public interface ChargerAlarmMapper {

    /**
     * 查某台桩最近的告警，新的在前。
     *
     * <p> {@code limit} 由调用方给，不走 PageHelper：这里要的是「最近几条」，
     * 不是分页浏览，套分页插件反而要处理 count 查询。
     */
    List<ChargerAlarm> listRecentByChargerId(@Param("chargerId") Long chargerId, @Param("limit") int limit);

    /**
     * 按 id 查一条告警。
     *
     * <p> 存在的理由是 AI 建单那条路径：{@code alarmId} 是模型自己填的，
     * 建单前要核实它确实存在、而且属于同一台桩，否则工单会挂到别的桩的告警上。
     */
    ChargerAlarm getById(@Param("id") Long id);
}
