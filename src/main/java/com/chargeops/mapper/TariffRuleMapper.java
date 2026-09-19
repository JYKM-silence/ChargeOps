package com.chargeops.mapper;

import com.chargeops.entity.TariffRule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计费时段规则数据访问
 *
 * <h2>★★ 一、写方法<b>绝不能</b>标 {@code @AutoFill}</h2>
 *
 * <p>{@code tariff_rule} 表<b>没有 {@code create_user} / {@code update_user}</b>，
 * 只有两个时间列。{@code AutoFillInterceptor} 反射找不到
 * {@code setCreateUser(Long)} 会<b>直接抛 {@code IllegalStateException}</b> ——
 * 标错了不是「填不上就不填」，是写操作当场全部炸掉。
 *
 * <p>两个时间列由 {@code TariffTemplateServiceImpl.saveRules} 显式
 * {@code set} 后传进来。理由和 {@link ConnectorMapper} 一样：
 * 让时间的来源只有一个（应用服务器），不要一半来自 Java、一半来自
 * 数据库的 {@code DEFAULT CURRENT_TIMESTAMP(3)}。
 *
 * <h2>★★ 二、规则是<b>整体替换</b>的，没有单行增删改</h2>
 *
 * <pre>
 *   deleteByTemplateId(templateId);          // 清掉旧的
 *   batchInsert(newRules);                   // 写入新的一整套
 * </pre>
 *
 * <p>这个形状不是省事，是<b>唯一正确的形状</b>：
 * 「24 小时完整覆盖、无缺口、无重叠」是一个<b>跨行</b>的性质，
 * 逐行增删改的中间状态必然违反它（删掉 08:00-10:00 那一行的瞬间，
 * 库里就有一个 2 小时的缺口）。整体替换 + 事务 + 保存前校验，
 * 让「库里存着的规则集」永远处于某个合法状态。
 *
 * <p>所以这里<b>没有</b> {@code insert}/{@code update}/{@code deleteById}。
 *
 * <h2>★ 三、没有 {@code station_tariff} 的计数查询</h2>
 *
 * <p>「这个模板有没有被站点绑定」是 {@code station_tariff} 表的问题，
 * 在 {@link StationTariffMapper#countByTemplateId}。
 * 本 Mapper 只碰 {@code tariff_rule} 一张表。
 *
 * <h2>★ 四、{@code batchInsert} 不回填主键，也<b>不需要</b>回填</h2>
 *
 * <p>{@code tariff_rule.id} 不被任何表引用 ——
 * 订单冻结的是模板 id，{@code charging_segment} 存的是
 * {@code period_type} 和两个单价，不是规则 id。
 *
 * <p>这不是巧合，是刻意的：如果结算记录引用的是 {@code rule_id}，
 * 那么「电价调整不影响历史订单」这条保证就要依赖
 * <b>那张规则行永远不被删</b>。而规则恰恰是整体替换的 ——
 * 每保存一次模板就删一次、插一次。用不引用换来的自由度，
 * 让「整体替换」这个最安全的写入形状成为可能。
 */
@Mapper
public interface TariffRuleMapper {

    /**
     * 某个模板的全部时段规则
     *
     * <p>★ <b>不分页，永远返回全集。</b>调用方需要全部 8 行才能校验
     * 24 小时覆盖，一个分页的接口在语义上就没法满足这个要求。
     *
     * <p>★ 按 {@code start_time} 排序。{@code "HH:mm:ss"} 是零填充的，
     * 所以<b>字典序恰好等于时间序</b> —— {@code "08:00:00" < "10:00:00" < "24:00:00"}。
     * 这是选择 {@code CHAR/VARCHAR} 之外的 {@code String} 表示时
     * 顺带得到的一个性质，也正是 {@code '24:00:00'} 能排在最后的原因
     * （写成 {@code '00:00:00'} 的话它会排到最前面，末段就不是末段了）。
     *
     * <p>★ {@code seq} 只管展示顺序，不参与这里的排序：
     * 时段规则的时间顺序是客观的，让一个可编辑的字段去决定它，
     * 只会出现「顺序被人调乱了，但校验依然通过」这种局面。
     */
    List<TariffRule> listByTemplateId(@Param("templateId") Long templateId);

    /**
     * 批量写入时段规则
     *
     * <p>★ <b>不标 {@code @AutoFill}</b> —— 见类注释第一节。
     * 每条规则的 {@code createTime} / {@code updateTime} 由调用方
     * 在构造列表时统一 {@code set} 成同一个时刻
     * （它们本来就是同一次操作写进去的）。
     *
     * <p>★ 用一条多值 {@code INSERT} 而不是 {@code <foreach>} 循环调用：
     * 8 条规则要么一起进去、要么一条都不进，不需要靠事务边界兜底，
     * 而且是一次往返而不是八次。
     *
     * <p>★ <b>列表不能为空。</b>{@code <foreach>} 展开成
     * {@code INSERT INTO ... VALUES} 后面没有值，是 SQL 语法错误。
     * 调用方必须先判空 —— 而「一个没有任何规则的模板」在业务上
     * 本来就不该存在（它无法覆盖 24 小时），所以这里拒绝得理直气壮。
     */
    void batchInsert(@Param("rules") List<TariffRule> rules);

    /**
     * 清掉某个模板的全部规则
     *
     * <p>★ 只在「整体替换」的事务里被调用，紧跟一次 {@link #batchInsert}。
     * 单独调用它会让那个模板在事务提交后<b>一条规则都没有</b>，
     * 所有用它的订单都算不出钱。
     *
     * <p>★ 调用方还要先过 {@code station_tariff} 的守卫：
     * 模板已被站点绑定时，连「替换」都不允许
     * （{@link StationTariffMapper#countByTemplateId}）。
     * 已冻结了这个模板的订单按它计价，改了规则那些订单的金额就变了 ——
     * 这正是 {@code station_tariff} 整张表存在的意义。
     *
     * <p>★ 反过来，模板<b>没有</b>被任何站点绑定时可以随便改：
     * 一个没有任何订单引用过的模板，怎么改都不会改变任何一笔已经算出来的钱。
     */
    @Delete("DELETE FROM tariff_rule WHERE template_id = #{templateId}")
    int deleteByTemplateId(@Param("templateId") Long templateId);
}
