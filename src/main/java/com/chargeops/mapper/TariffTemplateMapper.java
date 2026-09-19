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
 * <p>时段规则（{@code tariff_rule}）归 {@link TariffRuleMapper}。
 * 「模板 + 规则全集」的组装在 Service 里做，因为
 * {@link com.chargeops.billing.TariffValidator#validate} 必须一次看到
 * <b>全部 8 行</b>才能判断 24 小时覆盖有没有缺口 ——
 * 那是一个跨行的性质，任何「一次一行」的接口都表达不了。
 *
 * <p>所以实体 {@link TariffTemplate} 里<b>没有</b>
 * {@code List<TariffRule> rules} 字段：一个只在某个查询里被拼出来的字段，
 * 会让「这个对象是不是完整的」变成一个需要看调用点才能回答的问题。
 *
 * <h2>★ 「模板是否已被站点绑定」的查询不在这个 Mapper 里</h2>
 *
 * <p>它在 {@link StationTariffMapper#countByTemplateId} ——
 * 被查的是 {@code station_tariff} 表，那个 Mapper 才是它的归属。
 * {@code TariffTemplateServiceImpl.saveRules} 会把两者都注入进来，
 * 在同一个事务里先判再写。
 */
@Mapper
public interface TariffTemplateMapper {

    TariffTemplate getById(@Param("id") Long id);

    /**
     * 新增模板头
     *
     * <p>★ 只写头，不写规则。调用方
     * （{@code TariffTemplateServiceImpl.create}）必须在<b>同一个事务里</b>
     * 紧接着调 {@link TariffRuleMapper#batchInsert} 写入规则，
     * 并且顺序是「先校验规则、后写头」——
     * 反过来写的话，一个规则有缺口的模板会先落库拿到 id，
     * 校验失败回滚虽然能撤销，但自增 id 已经消耗掉了。
     */
    @AutoFill(OperationType.INSERT)
    void insert(TariffTemplate tariffTemplate);

    /**
     * 按主键更新非空字段
     *
     * <p>★ <b>这里能改的只有头信息</b>（{@code code} / {@code name} /
     * {@code region} / {@code description} / {@code status}），
     * 规则不在这条 SQL 里，也不该在 —— 见类注释。
     *
     * <p>改 {@code name} 是安全的：订单冻结的是 {@code id}，不是名字。
     * 改 {@code code} 等于把这个模板换了一个身份，但它参与的是
     * {@code uk_tariff_template_code}（唯一索引），撞了会抛
     * {@code DuplicateKeyException}，不会静默产生两个同码模板。
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
     * <p>★ 不分页，且<b>有意为之</b>：模板是配置数据，一个地区通常只有个位数个，
     * 而调用方（{@code station_tariff} 的绑定表单）需要的是完整选项列表 ——
     * 分页的下拉框是没法用的。
     *
     * <p>★ 只返回 {@code status = 1 启用}的。停用的模板不应该出现在
     * 「新建绑定」的选项里，但<b>已存在的绑定不受影响</b> ——
     * 那些绑定已经有自己的行，解析时不会经过这个方法。
     * 这是 {@code status} 语义的一部分，见 {@link TariffTemplate} 的类注释。
     *
     * <p>★ 如果模板数量某天涨到几百个，这个方法会变成一次全表扫描。
     * 到那时应该按 {@code region} 过滤而不是加分页。
     *
     * <p>★ SQL 在 XML 里：它要用 {@code Base_Column_List}，
     * 而注解里<b>没法</b> {@code <include>} 一个 XML 片段 ——
     * 写在注解里就得把 10 个列名再抄一遍，那正是这条分工规则要避免的。
     */
    List<TariffTemplate> listAllEnabled();

    /**
     * 批量按主键查，用于给绑定列表补模板名
     *
     * <p>存在的意义和 {@link StationMapper#listByIds} 完全一样：消除 N+1。
     * {@code StationTariffServiceImpl} 拿到一页绑定之后要补
     * {@code templateName} / {@code templateCode}，逐个 {@code getById}
     * 就是 N 次查询，而这里只要 1 次。
     *
     * <p>★ <b>{@code ids} 不能是空集合。</b>{@code <foreach>} 展开成
     * {@code IN ()} 是 SQL 语法错误（不是返回空列表）。调用方必须先判空 ——
     * 而空集合在业务上很常见：一个站点的第一个绑定还没建的时候。
     * 所以 Service 里那个「先判空再查」不是防御性代码，是<b>必经</b>的一步。
     *
     * <p>★ 这个方法<b>不过滤 {@code status}</b>，和 {@link #listAllEnabled}
     * 正好相反。原因很清楚：{@code listAllEnabled} 是给「新建绑定」
     * 的下拉框用的（停用的模板不该出现在选项里），
     * 而这里是给<b>已经存在的绑定</b>补名字 —— 那些绑定指向哪个模板就是哪个，
     * 模板后来停用了不改变这个事实，过滤掉只会让列表里多出一个空白的名字。
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
     *       <b>这一条 P1a 查不了</b>，订单表还没有代码。
     *       所以本轮只守第一条，第二条留给 P1c</li>
     * </ol>
     *
     * <p>★ 和站点一样，常规的「下线」手段是 {@code status = 0}，
     * 硬删只用于「建错了、一次都没用过」的模板。
     * 但硬删必须存在：{@code uk_tariff_template_code} 不看 {@code status}，
     * 编码建错一次就永久占用。
     *
     * <p>★ 删除<b>不级联</b> {@code tariff_rule}。规则行会成为孤儿
     * （{@code template_id} 指向不存在的模板），这是<b>有意</b>的：
     * 级联删除会让「删掉模板」这个动作在后台顺手抹掉 8 行计价数据，
     * 而孤儿规则至少是可查、可人工清理的。真正的清理交给
     * {@link TariffRuleMapper#deleteByTemplateId}，由调用方显式调用。
     */
    @Delete("DELETE FROM tariff_template WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
