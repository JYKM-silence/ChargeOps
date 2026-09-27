package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.entity.TariffTemplate;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计费模板头数据访问：只碰 {@code tariff_template} 一张表，时段规则归 {@link TariffRuleMapper}。
 */
@Mapper
public interface TariffTemplateMapper {

    TariffTemplate getById(@Param("id") Long id);

    /** 只写头。调用方必须在同一事务里紧接着调 {@link TariffRuleMapper#batchInsert}，顺序是先校验规则后写头 */
    @AutoFill(OperationType.INSERT)
    void insert(TariffTemplate tariffTemplate);

    /** 能改的只有头信息。改 {@code code} 会撞 {@code uk_tariff_template_code} 抛异常，不会静默产生两个同码模板 */
    @AutoFill(OperationType.UPDATE)
    void update(TariffTemplate tariffTemplate);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<TariffTemplate> page(TariffTemplatePageQueryDTO query);

    /** 供绑定站点的下拉框用。只返回 {@code status = 1}，不影响已存在的绑定 */
    List<TariffTemplate> listAllEnabled();

    /** 给绑定列表补模板名。{@code ids} 不能是空集合（{@code IN ()} 是语法错误）；不过滤 {@code status} */
    List<TariffTemplate> listByIds(@Param("ids") List<Long> ids);

    /**
     * 裸删除。调用方必须守住「没有 {@code station_tariff} 引用它」
     * （{@link StationTariffMapper#countByTemplateId}）；还差一条没有代码守：
     * 没有任何 {@code charging_order} 冻结了它。删除不级联规则。
     */
    @Delete("DELETE FROM tariff_template WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
