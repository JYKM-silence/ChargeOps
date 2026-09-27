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
 * 站点-计费模板绑定数据访问（对应 {@code station_tariff} 表）
 *
 * <h2>★★ 一、{@link #resolveTemplateAt} 是下单时要调的那个方法</h2>
 *
 * <p>整张表的设计意图见 {@link StationTariff} 的类注释。一句话版本：
 * 订单在<b>启动那一刻</b>把模板 id 冻结进 {@code charging_order}，
 * 之后无论电价怎么调，那笔订单都按冻结的模板结算。
 * {@code resolveTemplateAt} 回答的就是「启动那一刻该冻结哪个 id」。
 *
 * <h2>★ 二、这条查询永久不缓存</h2>
 *
 * <p>全项目唯一一条被明令禁止加缓存的数据访问，理由不是性能，是<b>可审计性</b>：
 * 这张表存在的意义就是让「这笔订单为什么用了这个价」有一个不依赖任何
 * 缓存状态的答案。缓存一旦引入，「解析出来的模板和库里的绑定对不上」
 * 就变成一个需要排查缓存的问题。而后台 QPS 很低，
 * 这条查询走 {@code idx_station_tariff_lookup}，成本可以忽略。
 *
 * <p>★ 另外本表四列审计字段齐全，所以写方法可以标 {@code @AutoFill} ——
 * 和 {@code connector} / {@code tariff_rule} 相反。
 */
@Mapper
public interface StationTariffMapper {

    StationTariff getById(@Param("id") Long id);

    /**
     * 某个站点的全部绑定（含已失效、已停用的），按生效时间倒序
     *
     * <p>★ <b>不过滤 {@code status}</b>：这是给后台看的列表，停用的绑定必须可见 ——
     * 否则「这个站点以前用过什么模板」这个只有这张表能回答的问题就没法回答了。
     * 也不分页：「看清这个站点的电价沿革」需要看全。
     */
    List<StationTariff> listByStationId(@Param("stationId") Long stationId);

    /**
     * ★★ <b>解析某站点在某时刻应该使用哪个模板 —— 下单时调用的就是它</b>
     *
     * <pre>
     *   WHERE station_id = ?
     *     AND status = 1
     *     AND effective_from &lt;= ?
     *     AND (effective_to IS NULL OR effective_to &gt; ?)
     *   ORDER BY priority DESC, effective_from DESC, id DESC
     *   LIMIT 1
     * </pre>
     *
     * <p>走 {@code idx_station_tariff_lookup (station_id, status, effective_from, effective_to)}，
     * 列序和 WHERE 里的顺序完全一致。
     *
     * <h3>★ 半开区间 {@code [effective_from, effective_to)} 让「恰好衔接」只命中一条</h3>
     *
     * <p>老绑定到 {@code 2026-07-01 00:00:00} 为止、新绑定从同一时刻开始，
     * 那么在正好这个时刻老的不满足 {@code effective_to > at}、
     * 新的满足 {@code effective_from <= at} —— <b>恰好一条命中</b>。
     * 如果写成 {@code effective_to >= at}，两条会同时命中，结果就由
     * {@code priority} 决定，而 {@code priority} 默认都是 0，
     * 于是这一天的电价变成<b>不可预测</b>的东西。
     *
     * <h3>★ 三个排序键，后两个是「让结果确定」而不是「让结果正确」</h3>
     *
     * <p>{@code uk_station_tariff_start} 只保证<b>起点不重复</b>，不保证区间
     * 不重叠，所以同站同刻可能有多条同时命中（一条长期有效的 + 一条促销期的）。
     * {@code priority DESC} 是业务裁决：<b>大者胜</b>。但两条绑定的
     * {@code priority} <b>可能相同</b>，这时如果没有后两个键，
     * {@code LIMIT 1} 取哪一行是<b>未定义</b>的 —— 同一个请求发起两次
     * 可能结算出两个不同的价格。{@code effective_from DESC, id DESC}
     * 把「未定义」变成「确定」（后建立的绑定赢）。
     *
     * <p>★ 但这只是让结果<b>可复现</b>，不是让结果<b>正确</b>：
     * 同优先级的两条重叠绑定本身就是数据质量问题，
     * 运维做临时调价时应该给促销那条一个更高的 {@code priority}。
     *
     * <h3>★ 返回 {@code null} 的含义：这个站点此刻<b>不能充电</b></h3>
     *
     * <p>{@code null} 表示该站点在 {@code at} 时刻没有任何生效的绑定。
     * <b>调用方必须把它当作一个响亮的失败来处理</b>（拒绝启动充电，
     * 明确提示「该站点未配置计费模板」），<b>绝不能回退到一个默认价格</b> ——
     * 一个「找不到就用默认价」的兜底看起来更健壮，实际是把「配置缺失」
     * 从立刻可见的失败变成了一个<b>静默的错误价格</b>，而错误的价格
     * 会变成真实的账单。
     *
     * @param at 解析时刻。传 {@link LocalDateTime#now()} 表示「现在」。
     *           下单传的是<b>开始充电的时刻</b>，不是结算时刻 ——
     *           两者的差别正是这张表要防的那件事
     * @return 命中的绑定行（调用方取 {@code getTemplateId()} 冻结进订单）；
     *         没有任何绑定时返回 {@code null}
     */
    StationTariff resolveTemplateAt(@Param("stationId") Long stationId,
                                    @Param("at") LocalDateTime at);

    /**
     * 新增绑定
     *
     * <p>★ 撞 {@code uk_station_tariff_start} 时抛
     * {@code DuplicateKeyException} ——<b>这是有意的，不要预检</b>。
     * 调价用的是<b>另一条</b>不同 {@code effectiveFrom} 的绑定：
     * 同一起点开两条，它们的先后关系没有任何字段能表达。
     */
    @AutoFill(OperationType.INSERT)
    void insert(StationTariff stationTariff);

    /**
     * 按主键更新非空字段
     *
     * <p>★ <b>不能改 {@code stationId} 和 {@code effectiveFrom}</b>：
     * 它们参与 {@code uk_station_tariff_start}，改了等于把一条绑定的身份换掉
     * （改起点会让它静默地覆盖另一个时间段，还可能撞上另一条已存在的绑定）。
     *
     * <p>★ <b>改 {@code templateId} 是安全的</b> —— 在途订单已经冻结了
     * 自己的模板，改绑定只影响<b>之后</b>启动的订单，这正是整张表要买的保险。
     * 但<b>常规的调价路径不是改这一行</b>，是新建一条绑定行、用
     * {@code effectiveFrom} 划定生效期：改 {@code templateId} 会把
     * 「这个站点什么时候用过哪个模板」的历史覆盖掉。
     *
     * <p>★ 局部更新的「没法清空字段」限制见 {@link StationMapper#update}。
     * 这里的具体后果是 <b>没法把 {@code effectiveTo} 改回 NULL</b>
     * （即「改成长期有效」做不到），要撤销只能删掉重建。
     */
    @AutoFill(OperationType.UPDATE)
    void update(StationTariff stationTariff);

    /**
     * 某个模板被多少条绑定引用
     *
     * <p>★★ <b>它就是「模板规则不可变」这条保证的执行者。</b>
     * {@code TariffTemplateServiceImpl.saveRules} 在改动规则前先调它，
     * 返回值 &gt; 0 就抛 {@code DataConflictException(TEMPLATE_IN_USE)}。
     * 没有这个守卫，{@code station_tariff} 存在的意义就是假的：
     * 订单冻结了模板 id，但那个 id 指向的规则已经被改过了 ——
     * 「电价调整不影响历史订单」变成一句空话，而且是<b>静默失效</b>。
     *
     * <p>★ 不区分 {@code status}：一条<b>已停用</b>的绑定同样说明这个模板
     * 被用过、可能还有在途订单引用着它 —— 停用只是让它不再被新的订单解析到。
     */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE template_id = #{templateId}")
    int countByTemplateId(@Param("templateId") Long templateId);

    /**
     * 某个站点有多少条绑定（不过滤 {@code status}）
     *
     * <p>删掉站点会留下一堆指向不存在站点的绑定行，而
     * {@code resolveTemplateAt} 是<b>按 station_id 查</b>的 ——
     * 那些行永远不会再被查到，也不会报错，只会静静地占着数据。
     *
     * <p>★★ <b>本方法目前没有调用方 —— 站点删除守卫没有接它。</b>★★
     * 这句话是<b>刻意留在这里</b>的，不是一句待办：它把「一个看起来该有的守卫
     * 其实不存在」这件事写在读者脸上，而不是让人从「有这个查询」推断出
     * 「所以删除时一定查过了」。{@code StationServiceImpl.assertDeletable}
     * 查的是<b>充电桩</b>和<b>订单</b>两张表，没有查绑定。
     *
     * <p>★ 为什么没接：绑定的生命周期本来就不跟着站点走 ——
     * {@code StationTariffService} 的换绑 / 解绑路径会自己维护这张表，
     * 而一条<b>已停用</b>的绑定留着反而是对的（它是一段历史：
     * 这个站曾经用过什么价）。所以「有绑定就不许删站点」并不是一条
     * 显然正确的规则，它需要产品决定；在决定之前，不接比接错好。
     * 这条已记入 README 的已知缺口表。
     *
     * <p>★ <b>所以：如果你正打算删掉这个方法，先确认上面那条缺口是不是已经
     * 决定成「不接」了 —— 是的话删掉它，并且顺手删掉这段注释。</b>
     */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除，而且和模板不同，这里<b>不需要额外的守卫</b>：
     * 在途订单冻结的是模板 id，不是绑定 id，所以「解绑」可以放心地删。
     * 唯一要注意的是 {@code uk_station_tariff_start} 会因此重新空出来 ——
     * 删掉一条 {@code 2026-01-01} 起效的绑定之后，
     * 又可以新建一条同日生效的了，这符合预期。
     *
     * <p>★ 删错了的后果是「这个站点的订单解析不到模板」
     * （{@link #resolveTemplateAt} 返回 {@code null}），
     * 而调用方会拒绝启动充电 —— 一个响亮的失败，不是错误的价格。
     */
    @Delete("DELETE FROM station_tariff WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
