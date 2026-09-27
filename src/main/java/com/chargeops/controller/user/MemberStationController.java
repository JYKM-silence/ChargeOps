package com.chargeops.controller.user;

import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.MemberStationService;
import com.chargeops.vo.MemberConnectorVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车主端 - 站点与充电枪
 *
 * <p>路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。两个端点都<b>没有 memberId 参数</b>，
 * 也都用不到 {@code BaseContext} —— 理由见 {@code MemberStationService} 类注释第二节。
 *
 * <h2>★★ 一、这两个端点补的是「三个 id 没有合法来源」那个缺口 ★★</h2>
 *
 * <p>完整论证见 {@link MemberStationPageQueryDTO} 的类注释：{@code POST /api/member/orders}
 * 要 {@code stationId} / {@code chargerId} / {@code connectorId}，而在此之前<b>车主端
 * 没有任何接口能让人拿到其中任何一个</b>，车主只能靠扫二维码。没有二维码的场景
 * （运维临时指一把枪、先在手机上看看哪把枪空着）完全走不通。
 *
 * <pre>
 *   GET /api/member/station/page                        → 有哪些站（★ 只返回启用中的）
 *   GET /api/member/connector/by-station/{stationId}    → 这个站有哪些枪
 * </pre>
 *
 * <p>★ 第二个端点的路径刻意照抄管理端的
 * {@code GET /api/admin/station-tariff/by-station/{stationId}}：「按站点取一批子行」
 * 这件事在两侧是同一个形状，写成两个样子只会让读过一侧的人认不出另一侧。
 *
 * <h2>★★ 二、为什么两个端点共用<b>一个</b> Controller ★★</h2>
 *
 * <p>{@code /api/member/station/**} 和 {@code /api/member/connector/**} 是两个路径段，
 * 按「一个域一个 Controller」的惯例本该是两个类。这里合成一个，理由是它们
 * <b>是同一个动作的两步</b>：车主先选站、再选枪（{@code stationId} 正是第二个端点的入参），
 * 两个域各自又只有这一个端点 —— 拆开就是两个单方法类，而它们共享同一套契约约定
 * （见第四节），那份约定写两遍迟早会分叉。
 *
 * <p>★ 所以类上的 {@code @RequestMapping} 只到 {@code /api/member}，
 * 两个路径段写在各自方法上。<b>不是为了少写一个类，是因为「选站 → 选枪」
 * 在这里确实是一次浏览</b>；哪天真长出「站点详情」「枪的实时状态」这类
 * 与选枪无关的端点，就应当把它拆成两个类。
 *
 * <h2>★★ 三、两个端点都必须传 token，但它们<b>不因此变成「我的」接口 ★★</b>
 *
 * <p>拦截器按路径前缀放行，{@code /api/member/**} 下的任何东西都需要 member token ——
 * 这一条是路径约定，不是业务语义。<b>站点和枪是全平台共享的数据</b>，
 * 换一个车主登录看到的是同一批站，所以 Service 层既不读 {@code BaseContext}、
 * 也不调 {@code MemberService.assertActive}（判据见那个方法的 javadoc：
 * 按「有没有资金影响」判断，而看站点不产生任何后果）。
 *
 * <h2>★★ 四、两个契约，前端<b>必须</b>照做，否则会在真机上出错 ★★</h2>
 *
 * <ol>
 *   <li>★ {@code MemberConnectorVO.available} 是<b>快照，不是预订</b>。
 *       它为 true 之后仍可能建单失败（{@code ConnectorOccupiedException}），
 *       那是正常的并发竞争，不是这个字段算错了。
 *       完整说明见 {@link MemberConnectorVO} 类注释第二节；</li>
 *   <li>★★ <b>枪列表的顺序不该被前端当作「已排好」</b>：后端刻意不排序
 *       （{@code gun_no} 是字符串，按它排出来是 {@code 1, 10, 2}），
 *       重排的职责在前端 —— 先按可用排、再按桩分组。
 *       理由见 {@code ConnectorMapper.listByStation} 的 javadoc。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/member")
@Tag(name = "车主端-站点与充电枪")
@Slf4j
@RequiredArgsConstructor
public class MemberStationController {

    private final MemberStationService memberStationService;

    /**
     * 分页查询充电站 —— <b>这是车主端唯一能看到站点的入口</b>
     *
     * <p>★ 响应里的站点<b>必然</b>是启用中的：{@code status = 1} 写死在
     * {@code StationMapper.pageForMember} 的 {@code WHERE} 里，维护中（2）的
     * 站点同样不返回 —— 对车主，「维护中」就是「现在别来」，展示出来只会让人白跑一趟。
     * 所以 {@code MemberStationVO} 里<b>没有</b> {@code status} 字段，
     * 前端也不需要再过滤一遍。
     *
     * <p>★ <b>这里的 {@code @Valid} 是承重的</b>（同 {@code StationController.page}）：
     * {@code pageSize} 上的 {@code @Max(100)} 是<b>唯一</b>挡住
     * {@code ?pageSize=1000000} 的东西，少了它一行 URL 就是一次 DoS。
     *
     * <p>★ 两个枪数（{@code connectorCount} / {@code idleConnectorCount}）是
     * <b>列表页价值最高的两个字段</b>：没有它们，车主只能一个个点进去看哪个站有空枪。
     * 站下一把枪都没有时它们都是 <b>0</b>（不是 null、也不报错）。
     */
    @GetMapping("/station/page")
    @Operation(summary = "分页查询充电站",
            description = "只返回「启用」的站点，维护中/停用的不出现；含该站的枪总数与空闲数")
    public Result<PageResult> page(@Valid MemberStationPageQueryDTO query) {
        return Result.success(memberStationService.page(query));
    }

    /**
     * 某个站点下的全部充电枪 —— 「选枪」这一步
     *
     * <p>★ <b>不过滤枪的状态</b>：离线 / 故障 / 维护（0/4/5）的枪也会返回。
     * 把它们筛掉会让一个正在维护的站显示成<b>空列表</b> —— 车主看到的是
     * 「这个站一把枪都没有」，而事实是「这个站有 8 把枪，现在都不能用」，
     * <b>两句完全不同的话，而后者才是能让人做出决定的那个</b>。
     * 所以状态原样返回（{@code status} + {@code statusLabel} + {@code available}），
     * 由前端决定怎么呈现（灰掉 + 写「维护中」）。
     *
     * <p>★ <b>顺序不排</b>，见类注释第四节第 2 点。
     *
     * <p>★ <b>站点不存在时返回空列表，不是 404。</b>判据是「这个接口回答的是什么问题」：
     * 它回答「这个站有哪些枪」，而一个不存在的站点、一个还没有枪的站点、
     * 一个枪全被删光的站点，对车主是同一件事 —— <b>现在没有一把能用的枪</b>。
     * 管理端的 {@code /api/admin/station-tariff/by-station/{stationId}} 在这一点上
     * 形状相同（同样返回空列表）。真需要「这个 id 存不存在」的答案，
     * 那是站点详情接口的职责，不是这个列表接口的。
     */
    @GetMapping("/connector/by-station/{stationId}")
    @Operation(summary = "查询某站点下的全部充电枪",
            description = "含离线/故障/维护中的枪；available 是快照（不保证下单成功），顺序由前端重排")
    public Result<List<MemberConnectorVO>> listByStation(@PathVariable Long stationId) {
        return Result.success(memberStationService.listByStation(stationId));
    }
}
