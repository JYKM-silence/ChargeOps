package com.chargeops.service;

import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.MemberConnectorVO;

import java.util.List;

/**
 * 车主端「找站 → 选枪」：看得到哪些充电站、某个站下有哪些充电枪
 *
 * <p>{@link #page} 给出「有哪些站」，{@link #listByStation} 给出「这个站有哪些枪」，
 * 两者合起来是建单那三个 id（{@code stationId} / {@code chargerId} /
 * {@code connectorId}）在车主端唯一的来源。
 *
 * <p> 两个方法都不读 {@code BaseContext}，也不调 {@code assertActive}：站点和枪是
 * 全平台共享的设备数据，没有「属于谁」这个维度，身份在这里唯一的用途是让拦截器放行。
 * 一个被禁用的车主看看有哪些站、哪些枪，不产生任何后果。
 */
public interface MemberStationService {

    /**
     * 分页查询充电站（车主端）—— 只返回「1 启用」的站点
     *
     * <p> 状态过滤不在这个方法里，它写死在 {@code StationMapper.pageForMember}
     * 的 {@code WHERE} 里：这条规则只可能有一个值，做成 Service 里的一行赋值的话，
     * 那行漏掉一次停用中的站点就会出现在车主端，而且不会报错。
     *
     * <p> 每个站点还带上两个枪数（总数 / 空闲数），由
     * {@code ConnectorMapper.countByStationIds} 一次覆盖整页 ——
     * 逐个 COUNT 的话一页 10 个站点就是 10 次查询。站下一把枪都没有时两个数都是 0。
     */
    PageResult page(MemberStationPageQueryDTO query);

    /**
     * 某个站点下的全部充电枪 —— 车主端「选枪」用
     *
     * <p> 不过滤枪的状态：否则一个正在维护的站点会显示成空列表，而「这个站一把枪都没有」
     * 和「这个站有 8 把枪、现在都不能用」是完全不同的两句话。
     *
     * <p> 返回顺序就是 {@code listByStation} 的顺序（按 id），这里不重排：
     * {@code gun_no} 是字符串，按它排会得到 {@code 1, 10, 2}。
     *
     * @param stationId 站点 id。 站点不存在时返回空列表而不是抛 404，
     * 理由见 {@code MemberStationController.listByStation}
     */
    List<MemberConnectorVO> listByStation(Long stationId);
}
