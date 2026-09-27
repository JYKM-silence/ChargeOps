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
 * 计费模板头数据访问
 *
 * <p>注解 / XML 的分工规则见 {@link StationMapper} 的类注释。
 *
 * <h2>★ 这个 Mapper 只碰 {@code tariff_template} 一张表，规则不在这里</h2>
 *
 * <p>时段规则归 {@link TariffRuleMapper}，「模板 + 规则全集」的组装在 Service
 * 里做 —— {@link com.chargeops.billing.TariffValidator#validate} 必须一次
 * 看到<b>全部 8 行</b>才能判断 24 小时覆盖有没有缺口。所以实体
 * {@link TariffTemplate} 里<b>没有</b> {@code List<TariffRule> rules} 字段：
 * 一个只在某个查询里被拼出来的字段，会让「这个对象是不是完整的」
 * 变成一个需要看调用点才能回答的问题。
 *
 * <p>★「模板是否已被站点绑定」也不在这里，它在
 * {@link StationTariffMapper#countByTemplateId} —— 被查的是
 * {@code station_tariff} 表，那个 Mapper 才是它的归属。
 */
@Mapper
public interface TariffTemplateMapper {

    TariffTemplate getById(@Param("id") Long id);

    /**
     * 新增模板头
     *
     * <p>★ 只写头，不写规则。调用方必须在<b>同一个事务里</b>紧接着调
     * {@link TariffRuleMapper#batchInsert}，而且顺序是「先校验规则、后写头」——
     * 反过来写的话，一个规则有缺口的模板会先落库拿到 id，
     * 回滚虽然能撤销，但自增 id 已经消耗掉了。
     */
    @AutoFill(OperationType.INSERT)
    void insert(TariffTemplate tariffTemplate);

    /**
     * 按主键更新非空字段
     *
     * <p>★ <b>这里能改的只有头信息</b>，规则不在这条 SQL 里，也不该在 —— 见类注释。
     * 改 {@code name} 是安全的：订单冻结的是 {@code id}，不是名字。
     * 改 {@code code} 等于把这个模板换了一个身份，但它参与
     * {@code uk_tariff_template_code}，撞了会抛 {@code DuplicateKeyException}，
     * 不会静默产生两个同码模板。
     *
     * <p>★ 局部更新的「没法清空字段」限制见 {@link StationMapper#update}。
     */
    @AutoFill(OperationType.UPDATE)
    void update(TariffTemplate tariffTemplate);

    /**
     * 分页查询
     *
     * <p>★ {@code PageHelper.startPage} 的调用约定见 {@link StationMapper#page}。
     */
    List<TariffTemplate> page(TariffTemplatePageQueryDTO query);

    /**
     * 全部启用中的模板，供绑定站点时的下拉框使用
     *
     * <p>★ 不分页，且<b>有意为之</b>：模板是配置数据，而调用方
     * （绑定站点的下拉框）需要的是完整选项列表。
     *
     * <p>★ 只返回 {@code status = 1} 的。停用的模板不应出现在「新建绑定」的选项里，
     * 但<b>已存在的绑定不受影响</b> —— 那些绑定有自己的行，解析时不会经过这个方法。
     *
     * <p>★ 如果模板数量某天涨到几百个，这个方法会变成一次全表扫描。
     * 到那时应该按 {@code region} 过滤而不是加分页。
     */
    List<TariffTemplate> listAllEnabled();

    /**
     * 批量按主键查，用于给绑定列表补模板名
     *
     * <p>存在的意义和 {@link StationMapper#listByIds} 完全一样：消除 N+1
     * （{@code StationTariffServiceImpl} 拿到一页绑定之后要补
     * {@code templateName} / {@code templateCode}）。
     *
     * <p>★ <b>{@code ids} 不能是空集合。</b>{@code <foreach>} 展开成
     * {@code IN ()} 是 SQL 语法错误（不是返回空列表）。调用方必须先判空 ——
     * 而空集合在业务上很常见：一个站点的第一个绑定还没建的时候。
     *
     * <p>★ 这个方法<b>不过滤 {@code status}</b>，和 {@link #listAllEnabled} 相反：
     * 这里是给<b>已经存在的绑定</b>补名字，模板后来停用了不改变
     * 「这条绑定指向它」这个事实，过滤掉只会让列表里多出一个空白的名字。
     */
    List<TariffTemplate> listByIds(@Param("ids") List<Long> ids);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除。调用方必须先守两道：
     * <ol>
     *   <li>没有任何 {@code station_tariff} 行引用它
     *       （{@link StationTariffMapper#countByTemplateId}）——
     *       删掉一个正在被站点使用的模板，那些站点的电价解析会返回
     *       空结果，表现为「这个站点的订单算不出钱」</li>
     *   <li>没有任何 {@code charging_order} 冻结了它 ——
     *       <b>这一条还没有代码守</b></li>
     * </ol>
     *
     * <p>★ 常规的「下线」手段是 {@code status = 0}，硬删只用于「建错了、
     * 一次都没用过」的模板。但硬删必须存在：
     * {@code uk_tariff_template_code} 不看 {@code status}，编码建错一次就永久占用。
     *
     * <p>★ 删除<b>不级联</b> {@code tariff_rule}，这是<b>有意</b>的：
     * 级联删除会让「删掉模板」这个动作顺手抹掉 8 行计价数据，
     * 而孤儿规则至少是可查、可人工清理的。真正的清理交给
     * {@link TariffRuleMapper#deleteByTemplateId}，由调用方显式调用。
     */
    @Delete("DELETE FROM tariff_template WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
