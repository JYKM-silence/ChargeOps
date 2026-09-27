package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.StationTariff;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点-计费模板绑定数据访问。{@link #resolveTemplateAt} 是下单入口，回答「启动那一刻该冻结哪个模板 id」。
 * 这条查询永久不缓存：理由不是性能，是「这笔订单为什么用了这个价」不能依赖缓存状态。
 */
@Mapper
public interface StationTariffMapper {

    StationTariff getById(@Param("id") Long id);

    /** 含已失效、已停用的绑定，不过滤 {@code status}：「这个站点以前用过什么模板」只有这张表能回答 */
    List<StationTariff> listByStationId(@Param("stationId") Long stationId);

    /**
     * 解析某站点在某时刻该用哪个模板。区间是半开的 {@code [effective_from, effective_to)}：
     * 写成 {@code effective_to >= at} 会让恰好衔接的两条同时命中，而它们的 priority 默认都是 0。
     * 走 {@code idx_station_tariff_lookup}，列序和 WHERE 一致。
     * 返回 {@code null} = 此刻没有任何生效的绑定，调用方必须拒绝启动充电，不能回退到默认价。
     */
    StationTariff resolveTemplateAt(@Param("stationId") Long stationId,
                                    @Param("at") LocalDateTime at);

    /** 撞 {@code uk_station_tariff_start} 会抛 {@code DuplicateKeyException}，这是有意的，不要预检 */
    @AutoFill(OperationType.INSERT)
    void insert(StationTariff stationTariff);

    /** 不能改 {@code stationId} 和 {@code effectiveFrom}（参与 {@code uk_station_tariff_start}）；改 {@code templateId} 只影响之后启动的订单 */
    @AutoFill(OperationType.UPDATE)
    void update(StationTariff stationTariff);

    /** 「模板规则不可变」的执行者：{@code saveRules} 改动规则前先调它，返回 &gt; 0 就拒绝 */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE template_id = #{templateId}")
    int countByTemplateId(@Param("templateId") Long templateId);

    /** 目前没有调用方 —— {@code assertDeletable} 只查了充电桩和订单两张表，这条缺口已记入 README */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    /** 硬删，不需要额外守卫：在途订单冻结的是模板 id，不是绑定 id */
    @Delete("DELETE FROM station_tariff WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
