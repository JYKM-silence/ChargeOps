package com.chargeops.service;

import com.chargeops.dto.StationTariffDTO;
import com.chargeops.dto.StationTariffUpdateDTO;
import com.chargeops.vo.StationTariffVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点计费绑定服务
 *
 * <p>这是 P1a 里唯一一个<b>为 P1c 而存在</b>的服务 ——
 * 它的核心方法 {@link #resolveTemplateIdAt} 在本轮没有任何生产调用方，
 * 但它是订单链路的入口，所以先写好、先单测。
 *
 * <h2>★★ 一、这张表只负责「此刻该用哪个模板」</h2>
 *
 * <p>{@code charging_order} 里冻结了 {@code tariff_template_id}，
 * 结算时按<b>那个</b>模板计价。本表回答的是另一个问题：
 * <b>下单那一刻，该把哪个模板 id 冻结进去。</b>
 *
 * <p>这条铁律（电价调整不得影响历史/在途订单）是这张表存在的全部意义。
 * 电价是电网按地区发布的，会调整；如果充电中的订单在<b>结算时</b>
 * 才去查「现在这个站点用哪个价」，那么电价一调，
 * 已经在充的订单会被按新价结算 —— 用户看到的金额和启动时预估的不一样，
 * 而且没法解释。
 *
 * <h2>★★ 二、{@link #resolveTemplateIdAt} 用抛异常代替返回 null</h2>
 *
 * <p>底层的 {@code StationTariffMapper.resolveTemplateAt} 在
 * 「这个站点此刻没有生效的绑定时」返回 {@code null}。本方法把它
 * <b>翻成一次响亮的失败</b>（{@code TariffNotCoveredException}）。
 *
 * <p><b>为什么不让调用方自己判 null：</b>因为「这个站点现在不能充电」
 * 和「这个站点用默认价」在调用方写错时长得一模一样 ——
 * 而后者是一个<b>静默的、真实的资金错误</b>：
 * 用户被按一个没人批准过的价格收费，而且订单会正常完成、正常结算。
 * 没有任何东西会报错，只有对账时那笔钱对不上。
 *
 * <p>把失败模式从「返回 null，调用方要记得处理」变成
 * 「抛异常，不处理就 500」，是<b>让错误不可能被忽略</b>。
 * 方法签名里根本没有 null 这个可能，所以 P1c 也就无法忘记检查它。
 *
 * <p>★ 抛的是 {@code TariffNotCoveredException} —— 和计费引擎在
 * 「某个时刻没有任何规则覆盖」时抛的是同一个类型。两者在语义上是同一件事：
 * <b>这个时刻没有价格。</b>复用类型让上层的处理逻辑不用分情况。
 */
public interface StationTariffService {

    /**
     * 为站点绑定一个计费模板
     *
     * @return 新绑定的 id
     * @throws com.chargeops.exception.NotFoundException     站点或模板不存在
     * @throws com.chargeops.exception.ParamErrorException   失效时刻不晚于生效时刻
     * @throws com.chargeops.exception.DataConflictException 模板已停用（不能再被新绑定）
     * @throws org.springframework.dao.DuplicateKeyException 该站点在同一生效时刻已有绑定
     */
    Long bind(StationTariffDTO stationTariffDTO);

    /**
     * 修改绑定
     *
     * <p>★ <b>只能改三样</b>：{@code effectiveTo}、{@code priority}、{@code status}。
     * 站点、生效时刻、模板 id 都不在 DTO 里 —— 它们合起来是这个绑定的<b>身份</b>，
     * 改了其中任何一个，改的都不是这条绑定的属性，而是换了一条绑定。
     * 见 {@code StationTariffUpdateDTO} 类注释。
     *
     * @throws com.chargeops.exception.NotFoundException     绑定不存在
     * @throws com.chargeops.exception.ParamErrorException   失效时刻不晚于库里那个生效时刻
     */
    void update(Long id, StationTariffUpdateDTO stationTariffUpdateDTO);

    /**
     * 删除绑定（硬删）
     *
     * <p>★ 常规的退役手段是 {@code status = 0 停用}，而不是删除 ——
     * 停用保留了「这个站点曾经用过这套价」这条历史，
     * 而删掉之后再看 {@code station_tariff}，那段时期就变成空白。
     * 事后追查一笔老订单为什么用了这个价时会找不到依据。
     *
     * @throws com.chargeops.exception.NotFoundException 绑定不存在
     */
    void delete(Long id);

    /**
     * 按 id 查详情（含模板名与编码）
     *
     * @throws com.chargeops.exception.NotFoundException 绑定不存在
     */
    StationTariffVO getById(Long id);

    /**
     * 某个站点的全部绑定，按生效时刻倒序
     *
     * <p>★ 不分页：一个站点的绑定数量以「个」计（每次调价加一行），
     * 而调用方需要看的是完整的调价历史。
     *
     * @throws com.chargeops.exception.NotFoundException 站点不存在
     */
    List<StationTariffVO> listByStationId(Long stationId);

    /**
     * ★★ 解析「某个站点在某个时刻应该用哪个模板」—— <b>P1c 下单时调的就是这个</b>
     *
     * <p>它回答的是「下单那一刻该把哪个 template id 冻结进
     * {@code charging_order}」，而不是「结算时该用哪个价」。
     * 这个区别是整张表存在的理由，见类注释第一节。
     *
     * <p>★ <b>调用时机必须是 P1c 创建订单的同一个事务内</b>，
     * 而且要保证「解析出来的 id 就是冻结进去的 id」——
     * 中间不能有第二次解析。
     *
     * <p>★ <b>这条查询永久不缓存。</b>缓存一致性可以后面再优化，
     * 但「电价解析要可审计」这件事不能妥协：这张表整套设计的意义就是让
     * 「这笔订单为什么用了这个价」有唯一、可追溯、不依赖任何缓存状态的答案。
     *
     * <p>★ <b>这个方法必须在任何 {@code PageHelper.startPage} 之前调用</b>
     * （或者在一个没有分页的线程上）。{@code startPage} 的参数会被
     * 该线程上的<b>下一条</b> MyBatis 查询消费掉，而这里是
     * {@code LIMIT 1} 的查询 —— 它会把分页参数吃掉，
     * 于是真正该分页的查询变成全表扫描。
     * 详见 {@code ChargerServiceImpl.page} 里那段完整说明。
     *
     * @param stationId 站点 id
     * @param at        要解析的时刻；传 {@code LocalDateTime.now()} 表示「此刻」
     * @return 该时刻生效的模板 id，<b>永不为 null</b>
     * @throws com.chargeops.exception.TariffNotCoveredException 该站点在此刻没有生效的计费方案
     */
    Long resolveTemplateIdAt(Long stationId, LocalDateTime at);
}
