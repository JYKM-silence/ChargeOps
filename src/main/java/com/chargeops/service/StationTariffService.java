package com.chargeops.service;

import com.chargeops.dto.StationTariffDTO;
import com.chargeops.dto.StationTariffUpdateDTO;
import com.chargeops.vo.StationTariffVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站点计费绑定服务
 *
 * <p>这张表只回答一个问题：下单那一刻，该把哪个模板 id 冻结进
 * {@code charging_order}。{@code charging_order} 冻结了 {@code tariff_template_id}，
 * 结算时按那个模板计价，所以电价调整不影响在途订单 —— 如果充电中的订单在结算时
 * 才去查「现在这个站点用哪个价」，电价一调，已经在充的订单会被按新价结算，
 * 用户看到的金额和启动时预估的不一样，而且没法解释。
 *
 * <p>{@link #resolveTemplateIdAt} 用抛异常代替返回 null：底层的
 * {@code StationTariffMapper.resolveTemplateAt} 在「这个站点此刻没有生效的绑定时」
 * 返回 {@code null}，本方法把它翻成 {@code TariffNotCoveredException}。
 * 「这个站点现在不能充电」和「这个站点用默认价」在调用方写错时长得一模一样，
 * 而后者是一个静默的资金错误：用户被按一个没人批准过的价格收费，订单会正常完成、
 * 正常结算，只有对账时那笔钱对不上。
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
     * <p> 只能改三样：{@code effectiveTo}、{@code priority}、{@code status}。
     * 站点、生效时刻、模板 id 合起来是这个绑定的身份，改了其中任何一个，
     * 改的都不是这条绑定的属性，而是换了一条绑定。
     *
     * @throws com.chargeops.exception.ParamErrorException 失效时刻不晚于库里那个生效时刻
     */
    void update(Long id, StationTariffUpdateDTO stationTariffUpdateDTO);

    /**
     * 删除绑定（硬删）
     *
     * <p> 常规的退役手段是 {@code status = 0 停用}：停用保留了「这个站点曾经用过
     * 这套价」这条历史，删掉之后那段时期就变成空白，事后追查一笔老订单为什么
     * 用了这个价时会找不到依据。
     */
    void delete(Long id);

    /** 按 id 查详情（含模板名与编码） */
    StationTariffVO getById(Long id);

    /** 某个站点的全部绑定，按生效时刻倒序。不分页：一个站点每次调价加一行，调用方要看完整的调价历史 */
    List<StationTariffVO> listByStationId(Long stationId);

    /**
     * 解析「某个站点在某个时刻应该用哪个模板」—— 下单时调的就是这个
     *
     * <p> 调用时机必须是创建订单的同一个事务内，而且要保证「解析出来的 id 就是
     * 冻结进去的 id」，中间不能有第二次解析。
     *
     * <p> 这条查询永久不缓存：「电价解析要可审计」不能妥协，这张表整套设计的意义
     * 就是让「这笔订单为什么用了这个价」有唯一、可追溯、不依赖任何缓存状态的答案。
     *
     * <p> 必须在任何 {@code PageHelper.startPage} 之前调用（或在一个没有分页的线程上）：
     * {@code startPage} 的参数会被该线程上的下一条 MyBatis 查询消费掉，而这里是
     * {@code LIMIT 1} 的查询 —— 它会把分页参数吃掉，于是真正该分页的查询变成全表扫描。
     * 详见 {@code ChargerServiceImpl.page}。
     *
     * @param at 要解析的时刻；传 {@code LocalDateTime.now()} 表示「此刻」
     * @return 该时刻生效的模板 id，永不为 null
     * @throws com.chargeops.exception.TariffNotCoveredException 该站点在此刻没有生效的计费方案
     */
    Long resolveTemplateIdAt(Long stationId, LocalDateTime at);
}
