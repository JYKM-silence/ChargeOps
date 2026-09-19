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
 * <h2>★★ 一、{@link #resolveTemplateAt} 是 P1c 下单时要调的那个方法</h2>
 *
 * <p>整张表的设计意图见 {@link StationTariff} 的类注释。一句话版本：
 * 订单在<b>启动那一刻</b>把模板 id 冻结进 {@code charging_order}，
 * 之后无论电价怎么调，那笔订单都按冻结的模板结算。
 *
 * <p>{@code resolveTemplateAt} 回答的就是「启动那一刻该冻结哪个 id」。
 * 它<b>只在这一处</b>被调用，调用时机是 P1c 创建订单的同一事务内。
 *
 * <h2>★ 二、这条查询永久不缓存</h2>
 *
 * <p>这是全项目唯一一条被明令禁止加缓存的数据访问。理由不是性能，
 * 是<b>可审计性</b>：这张表存在的意义就是让「这笔订单为什么用了这个价」
 * 有一个不依赖任何缓存状态的答案。缓存一旦引入，
 * 「解析出来的模板和库里的绑定对不上」就变成一个需要排查缓存的问题。
 *
 * <p>后台 QPS 很低，这条查询走 {@code idx_station_tariff_lookup}
 * （列序和 WHERE 条件完全一致），成本可以忽略。
 *
 * <h2>★ 三、本表四列审计字段齐全，写方法可以标 {@code @AutoFill}</h2>
 *
 * <p>和 {@code connector} / {@code tariff_rule} 不同，本表有
 * {@code create_user} / {@code update_user}，所以标了不会炸。
 */
@Mapper
public interface StationTariffMapper {

    StationTariff getById(@Param("id") Long id);

    /**
     * 某个站点的全部绑定（含已失效、已停用的），按生效时间倒序
     *
     * <p>★ <b>不过滤 {@code status}</b>：这是给后台看的列表，
     * 停用的绑定必须可见 —— 否则「这个站点以前用过什么模板」
     * 这个只有这张表能回答的问题就没法回答了。
     *
     * <p>★ 不分页：一个站点的绑定通常几条到十几条（每次调价加一条），
     * 而「看清这个站点的电价沿革」需要看全。
     */
    List<StationTariff> listByStationId(@Param("stationId") Long stationId);

    /**
     * ★★ <b>解析某站点在某时刻应该使用哪个模板 —— P1c 下单时调用的就是它</b>
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
     * <p>老绑定到 {@code 2026-07-01 00:00:00} 为止、新绑定从
     * {@code 2026-07-01 00:00:00} 开始，那么在正好这个时刻：
     * 老的被判为失效（{@code effective_to > at} 是 {@code 00:00 > 00:00}，假），
     * 新的被判为生效（{@code effective_from <= at} 是 {@code 00:00 <= 00:00}，真）。
     * <b>恰好一条命中，不需要靠优先级裁决。</b>
     *
     * <p>如果写成 {@code effective_to >= at}，两条会同时命中，
     * 结果就由 {@code priority} 决定 —— 而 {@code priority} 默认都是 0，
     * 于是这一天的电价变成一个<b>不可预测</b>的东西。
     * 这和 {@code tariff_rule} 的「相邻相接不算重叠」是同一条约定。
     *
     * <h3>★ 三个排序键，后两个是「让结果确定」而不是「让结果正确」</h3>
     *
     * <p>{@code uk_station_tariff_start (station_id, effective_from)}
     * 只保证<b>起点不重复</b>，不保证区间不重叠。所以同站同刻可能有多条
     * 同时命中（一条长期有效的 + 一条促销期的）。{@code priority DESC}
     * 是业务上的裁决规则：<b>大者胜</b>。
     *
     * <p>但两条绑定的 {@code priority} <b>可能相同</b>（默认都是 0）。
     * 这时如果没有后两个键，{@code LIMIT 1} 取哪一行是<b>未定义</b>的 ——
     * 意味着同一个请求发起两次可能结算出两个不同的价格。
     * {@code effective_from DESC, id DESC} 把「未定义」变成「确定」：
     * <b>后建立的绑定赢</b>，这也符合直觉（后加的覆盖先加的）。
     *
     * <p>★ 但必须说清楚：这只是让结果<b>可复现</b>，不是让结果<b>正确</b>。
     * 同优先级的两条重叠绑定本身就是数据质量问题，
     * 运维做临时调价时应该给促销那条一个更高的 {@code priority}。
     * 这个 tiebreak 的作用是让这种情况下的行为至少不是随机的。
     *
     * <h3>★ 返回 {@code null} 的含义：这个站点此刻<b>不能充电</b></h3>
     *
     * <p>{@code null} 表示该站点在 {@code at} 时刻没有任何生效的绑定。
     * <b>P1c 必须把它当作一个响亮的失败来处理</b>（拒绝启动充电，并明确
     * 提示「该站点未配置计费模板」），<b>绝不能回退到一个默认价格</b>。
     *
     * <p>为什么这一点值得写进注释：一个「找不到就用默认价」的兜底
     * 看起来更健壮，实际是把「配置缺失」从一个立刻可见的失败
     * 变成了一个<b>静默的错误价格</b> —— 而错误的价格会变成真实的账单。
     * 这个 schema 里也没有任何「平台默认模板」的概念，
     * 兜底值只能靠代码硬编码，那更糟。
     *
     * @param at 解析时刻。传 {@link LocalDateTime#now()} 表示「现在」。
     *           P1c 传的是<b>订单开始充电的时刻</b>，不是结算时刻 ——
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
     * 「同一站点同一生效时刻只能有一条绑定」在业务上是对的：
     * 同一起点开两条，它们的先后关系没有任何字段能表达。
     *
     * <p>调价用的是「<b>另一条</b>不同 {@code effectiveFrom} 的绑定」，
     * 不是同一个起点的两条。
     */
    @AutoFill(OperationType.INSERT)
    void insert(StationTariff stationTariff);

    /**
     * 按主键更新非空字段
     *
     * <p>★ <b>可改的是 {@code templateId / effectiveTo / priority / status}，
     * 不能改 {@code stationId} 和 {@code effectiveFrom}。</b>
     * 后两个字段参与 {@code uk_station_tariff_start}，改了等于把一条绑定的
     * 身份换掉：改起点会让它静默地覆盖另一个时间段，
     * 还可能撞上另一条已存在的绑定。要改就删掉重建。
     *
     * <p>★ <b>改 {@code templateId} 是安全的</b> —— 在途订单已经冻结了
     * 自己的模板，改绑定只影响<b>之后</b>启动的订单。
     * 这正是整张表要买的那份保险。
     * 它存在的场景是「管理员绑错了模板，5 分钟后发现」——
     * 不给改的话只能删掉重建，而删掉重建会丢掉这条绑定的 {@code id}。
     *
     * <p>★ 但<b>常规的调价路径不是改这一行</b>，是新建一条绑定行，
     * 用 {@code effectiveFrom} 划定生效期。改 {@code templateId} 会让
     * 「这个站点什么时候用过哪个模板」的历史被覆盖掉，
     * 而那条历史只有这张表能回答。
     *
     * <p>★ 局部更新的「没法清空字段」限制见 {@link StationMapper#update}。
     * 这里有一个具体的后果：<b>没法把 {@code effectiveTo} 改回 NULL</b>
     * （即「改成长期有效」做不到）。把一条长期绑定改成有期限可以，
     * 反方向不行。要撤销只能删掉重建。
     */
    @AutoFill(OperationType.UPDATE)
    void update(StationTariff stationTariff);

    /**
     * 某个模板被多少条绑定引用
     *
     * <p>★★ <b>它就是「模板规则不可变」这条保证的执行者。</b>
     * {@code TariffTemplateServiceImpl.saveRules} 在改动规则前先调它，
     * 返回值 &gt; 0 就抛 {@code DataConflictException(TEMPLATE_IN_USE)}。
     *
     * <p>没有这个守卫，{@code station_tariff} 存在的意义就是假的：
     * 订单冻结了模板 id，但那个 id 指向的规则已经被改过了 ——
     * 「电价调整不影响历史订单」变成一句空话，而且是<b>静默失效</b>
     * （金额会算出来，只是和用户启动时看到的不一样）。
     *
     * <p>★ 查询在 {@code station_tariff} 表上，所以它属于这个 Mapper，
     * 不属于 {@link TariffRuleMapper} —— 一个 Mapper 只碰一张表。
     *
     * <p>★ 不区分 {@code status}：一条<b>已停用</b>的绑定同样说明
     * 这个模板被用过，可能还有在途订单引用着它。停用绑定只是让它
     * 不再被新的订单解析到，不会让已经冻结了它的订单改变计价依据。
     */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE template_id = #{templateId}")
    int countByTemplateId(@Param("templateId") Long templateId);

    /**
     * 某个站点有多少条绑定（不过滤 {@code status}）
     *
     * <p>用于站点的删除守卫：还有绑定的站点不允许删。
     * 删掉站点会留下一堆指向不存在站点的绑定行，
     * 而 {@code resolveTemplateAt} 是<b>按 station_id 查</b>的 ——
     * 那些行永远不会再被查到，也不会报错，只会静静地占着数据。
     */
    @Select("SELECT COUNT(*) FROM station_tariff WHERE station_id = #{stationId}")
    int countByStationId(@Param("stationId") Long stationId);

    /**
     * 按主键删除（硬删）
     *
     * <p>★ 裸删除。和模板不同，这里<b>不需要额外的守卫</b>：
     * 绑定的存在与否不影响任何一笔已经算出来的钱 ——
     * 在途订单冻结的是模板 id，不是绑定 id。
     *
     * <p>所以「解绑」可以放心地删。唯一要注意的是
     * {@code uk_station_tariff_start} 会因此重新空出来：
     * 删掉一条 {@code 2026-01-01} 起效的绑定之后，
     * 又可以新建一条同日生效的了。这是符合预期的。
     *
     * <p>★ 删错了的后果是「这个站点的订单解析不到模板」
     * （{@link #resolveTemplateAt} 返回 {@code null}），
     * 而 P1c 会拒绝启动充电 —— 一个响亮的失败，不是错误的价格。
     * 这个方向是对的。
     */
    @Delete("DELETE FROM station_tariff WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
