package com.chargeops.mapper;

import com.chargeops.entity.TariffRule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计费时段规则数据访问。写方法绝不能标 {@code @AutoFill}：本表没有
 * {@code create_user} / {@code update_user} 列，标了插件反射会直接抛异常。
 * 没有单行增删改：「24 小时覆盖、无缺口」是跨行的性质，只能整体替换。
 */
@Mapper
public interface TariffRuleMapper {

    /** 永远返回全集（调用方要看到全部 8 行才能校验覆盖）。按 {@code start_time} 排，{@code '24:00:00'} 会排在最后 */
    List<TariffRule> listByTemplateId(@Param("templateId") Long templateId);

    /** 列表不能为空：{@code <foreach>} 展开后是 SQL 语法错误。时间由调用方统一 set 成同一个时刻 */
    void batchInsert(@Param("rules") List<TariffRule> rules);

    /** 只在「整体替换」的事务里被调用，单独调会让那个模板在提交后一条规则都不剩 */
    @Delete("DELETE FROM tariff_rule WHERE template_id = #{templateId}")
    int deleteByTemplateId(@Param("templateId") Long templateId);
}
