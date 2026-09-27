package com.chargeops.service;

import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.MemberConnectorVO;

import java.util.List;

/**
 * 车主端「找站 → 选枪」：看得到哪些充电站、某个站下有哪些充电枪
 *
 * <h2>★★ 一、它补上的是一个真实缺口：车主端此前看不到任何站点 ★★</h2>
 *
 * <p>权威论证在 {@link MemberStationPageQueryDTO} 的类注释第一节里：在它之前
 * {@code /api/member/**} 下唯一和设备有关的东西是 {@code POST /api/member/orders}，
 * 而那个接口要三个 id（{@code stationId} / {@code chargerId} / {@code connectorId}）——
 * <b>三个 id 在车主端没有任何合法来源</b>，车主只能靠扫二维码。没有二维码的场景
 * （运维临时指一把枪、先在手机上看看哪把枪空着）完全走不通。
 *
 * <p>{@link #page} 给出「有哪些站」，{@link #listByStation} 给出「这个站有哪些枪」，
 * 两者合起来正好是那三个 id 的来源。
 *
 * <h2>★★ 二、两个方法都<b>不读 {@code BaseContext}</b>，也<b>不调 {@code assertActive}</b> ★★</h2>
 *
 * <p>站点和枪是<b>全平台共享的设备数据</b>，不是「我的」东西 ——
 * 「当前登录的是谁」和这两个方法要回答的问题无关，所以它们既没有 id 参数，
 * 也不从 {@code BaseContext} 取。<b>身份在这里唯一的用途是让拦截器放行</b>
 * （{@code /api/member/**} 需要 member token），Service 层用不到它。
 *
 * <p>★ 对照 {@code MemberController} / {@code VehicleController}：
 * 那边「改自己的」是<b>唯一</b>语义，所以路径段是字面量 {@code me}，Service 必须读身份；
 * 这边反过来 —— <b>这批数据没有「属于谁」这个维度</b>。
 *
 * <p>★★ <b>不调 {@code MemberService.assertActive} 是按规则得出的结论，不是漏了。</b>★★
 * 那条规则写在它的 javadoc 里：<b>「按这个接口有没有资金影响判断，不是按读写判断」</b>。
 * 而它自己给出的<b>反例</b>（{@code GET /api/member/me}、「我的车辆」）与这两个方法同类：
 * 一个被禁用的车主看看有哪些站、哪些枪，<b>不产生任何后果</b>，调它只是多一次查库。
 * 对照 {@code ChargingOrderService.pageMine} —— 那个是只读的但仍然查，因为订单就是钱。
 */
public interface MemberStationService {

    /**
     * 分页查询充电站（车主端）—— <b>只返回「1 启用」的站点</b>
     *
     * <p>★ 状态过滤<b>不在这个方法里</b>，它写死在 {@code StationMapper.pageForMember}
     * 的 {@code WHERE} 里（连维护中的站也一并排除）。理由见
     * {@link MemberStationPageQueryDTO} 类注释第二节：这条规则只可能有一个值，
     * 所以它不该是 Service 里的一行赋值 —— 那行赋值漏掉一次，
     * <b>停用中的站点就会出现在车主端，而且不会报错</b>。
     *
     * <p>★ 每个站点还带上两个枪数（总数 / 空闲数），它们是<b>批量查出来的</b>：
     * 一页 10 个站点逐个 COUNT 就是 10 次查询，而
     * {@code ConnectorMapper.countByStationIds} 一次覆盖整页。
     * <b>站下一把枪都没有时两个数都是 0</b>（不是 null）。
     *
     * @param query 分页与筛选条件。{@code name} 模糊、{@code city} 精确，
     *              理由见 {@link MemberStationPageQueryDTO} 类注释第三节
     */
    PageResult page(MemberStationPageQueryDTO query);

    /**
     * 某个站点下的<b>全部</b>充电枪 —— 车主端「选枪」用
     *
     * <p>★ <b>不过滤枪的状态</b>：离线 / 故障 / 维护中的枪也要返回，
     * 否则一个正在维护的站点会显示成空列表，而「这个站一把枪都没有」和
     * 「这个站有 8 把枪、现在都不能用」是完全不同的两句话。
     * 完整论证见 {@code ConnectorMapper.listByStation}。
     *
     * <p>★★ <b>返回顺序 = {@code listByStation} 的顺序（按 id），这里不重排。</b>★★
     * {@code gun_no} 是字符串，按它排会得到 {@code 1, 10, 2}；真正的顺序应当由前端
     * 按「先可用、再按桩分组」重排 —— 那是展示逻辑，不该固化在后端。
     *
     * <p>★ 每条枪都补上所属桩的编号 / 名称 / 类型文案（一次批量查询，不是逐个查）：
     * 同一个站下的枪混在一张列表里，只给 {@code chargerId}，
     * 用户站在桩前面不知道哪把是自己选的。
     *
     * @param stationId 站点 id。★ <b>站点不存在时返回空列表而不是抛 404</b>，
     *                  理由见 {@code MemberStationController.listByStation}
     */
    List<MemberConnectorVO> listByStation(Long stationId);
}
