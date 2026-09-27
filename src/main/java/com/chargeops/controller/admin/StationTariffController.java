package com.chargeops.controller.admin;

import com.chargeops.dto.StationTariffDTO;
import com.chargeops.dto.StationTariffUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.StationTariffService;
import com.chargeops.vo.StationTariffVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 站点计费绑定
 *
 * <p>公共约定见 {@link StationController} 的类注释（正本）。
 * 本域的形状<b>和别的五个域不一样</b>：它管的是一张关系表，不是一个有独立生命周期的实体。
 *
 * <h2>★ 一、为什么它和 {@code tariff-template} 是两个域</h2>
 *
 * <p>模板是「一套电价长什么样」（8 条时段规则），绑定是「哪个站点、从哪一刻起、用哪一套」：
 * 前者是<b>可复用的配置</b>，后者是<b>站点与配置之间的关系</b> —— 一个模板可以绑给一百个站点，
 * 一个站点在不同时期可以绑不同的模板。
 *
 * <p>所以没有 {@code page} 接口：绑定列表永远是<b>按站点查</b>的（「科技园站的电价历史」），
 * 一个站点的绑定数量以「个」计（每次调价加一行），不需要分页，跨站点的「所有绑定」列表
 * 也没有使用场景。取而代之的是 {@code GET /by-station/{stationId}}，按生效时刻倒序 ——
 * 它天然就是那个站点的<b>调价历史</b>。
 *
 * <h2>★ 二、生效区间是半开的 {@code [effectiveFrom, effectiveTo)}</h2>
 *
 * <p>{@code effectiveFrom} 含、{@code effectiveTo} <b>不含</b>：换价只需要两条相邻的绑定，
 * 旧的那条 {@code effectiveTo} 和新那条的 {@code effectiveFrom} 写成同一个时刻即可，
 * 那一瞬间不会出现两条同时生效（重叠）或者都不生效（空窗）。
 *
 * <p>★ 由此推出 {@code effectiveTo} 必须<b>严格晚于</b> {@code effectiveFrom}：
 * 两者相等是一个空区间，永远不会被解析命中，而它在列表页上和一条正常绑定<b>长得一模一样</b> ——
 * 运维会以为配好了，直到用户下单时系统说「该站点当前没有配置计费方案」。
 *
 * <h2>★ 三、{@code stationId} / {@code effectiveFrom} / {@code templateId} 不可修改</h2>
 *
 * <p>修改接口只接受 {@code effectiveTo} / {@code priority} / {@code status}。另外三个合起来
 * 是这条绑定的<b>身份</b> —— 改了其中任何一个，改的都不是这条绑定的属性，而是换了一条绑定。
 * 其中 {@code effectiveFrom} 尤其要紧：它是<b>本表里唯一能制造出「空窗期」的编辑</b> ——
 * 把它往后推，前面就空出一段没有任何模板生效的时间，那些时刻的订单在下单时会被拒绝
 * （这正是 {@code resolveTemplateIdAt} 抛异常而不是回退到默认价的原因）。
 *
 * <p>★ <b>本表没有「覆盖完整性」校验器</b>，不检查同一站点的绑定是否首尾相接、有没有空窗。
 * 这是有意的：那样的校验要求所有绑定一次性提交，而现实中调价是按次发生的（今天加一条、
 * 明年再加一条），强求完整性会让「明天起涨价」这个操作无法执行。代价由
 * {@code resolveTemplateIdAt} 的异常承担：空窗期表现为「这个站点现在充不了电」，一次响亮的失败。
 *
 * <h2>★ 四、{@code resolveTemplateIdAt} 没有对应的接口</h2>
 *
 * <p>{@code StationTariffService.resolveTemplateIdAt(stationId, at)} 是订单链路下单时
 * <b>冻结计价模板</b>要调的方法，有集成测试覆盖，但<b>刻意不暴露成 HTTP 接口</b>：
 * 它不是管理操作，而是订单链路内部的一步，挂出来只会多一个与业务无关的公开面；
 * 它要的参数（精确到毫秒的 {@code at}）也不是运维会填的东西。真需要排查「这个站点现在能不能充电」，
 * 那条 warn 日志（{@code "站点在指定时刻没有生效的计费方案"}）已经把 {@code stationId}
 * 和 {@code at} 都打出来了。
 */
@RestController
@RequestMapping("/api/admin/station-tariff")
@Tag(name = "管理端-站点计费绑定")
@Slf4j
public class StationTariffController {

    private final StationTariffService stationTariffService;

    public StationTariffController(StationTariffService stationTariffService) {
        this.stationTariffService = stationTariffService;
    }

    /**
     * ★ 三样东西都会被校验：站点存在、模板存在、<b>模板是启用状态</b>。停用的模板不能被<b>新</b>
     * 绑定，但已有绑定不受影响 —— 所以「停用模板」不改变任何站点的现有计费，两条路径两个判断。
     *
     * <p>★ {@code stationId} 必填（不像 {@code charge_package.stationId} 有「0 = 全平台」的语义）——
     * 电价是电网按地区发布的，「全平台统一的电价」在业务上不存在。
     *
     * <p>★ 同一站点在<b>同一个 {@code effectiveFrom}</b> 上重复绑定会撞
     * {@code uk_station_tariff_window}，被 {@code GlobalExceptionHandler} 翻成「xxx已存在」。
     * 这是对的：同一时刻有两条生效绑定，解析就只能靠 {@code priority} 分胜负，
     * 而那个细节没人记得住。
     */
    @PostMapping
    @Operation(summary = "为站点绑定计费模板")
    public Result<Long> bind(@Valid @RequestBody StationTariffDTO stationTariffDTO) {
        return Result.success(stationTariffService.bind(stationTariffDTO));
    }

    /**
     * ★ 只能改 {@code effectiveTo} / {@code priority} / {@code status}，见类注释第三节。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改站点计费绑定")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody StationTariffUpdateDTO stationTariffUpdateDTO) {
        stationTariffService.update(id, stationTariffUpdateDTO);
        return Result.success();
    }

    /**
     * ★ <b>常规的退役手段是 {@code status = 0} 停用，不是删除。</b>停用保留了「这个站点曾经
     * 用过这套价」这条历史，删掉则那段时期变成空白 —— 事后追查一笔老订单为什么用了这个价时
     * 会找不到依据。所以调用这个接口前应该先问一句「你是想退役它，还是想抹掉它」。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除站点计费绑定")
    public Result<Void> delete(@PathVariable Long id) {
        stationTariffService.delete(id);
        return Result.success();
    }

    /**
     * ★ 含模板名与编码：从别的域复制 {@code id} 过来时用得上 —— 列表里只有 {@code templateId}
     * 是没有信息量的，没人记得模板 7 是哪个价。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询站点计费绑定详情")
    public Result<StationTariffVO> getById(@PathVariable Long id) {
        return Result.success(stationTariffService.getById(id));
    }

    /**
     * ★ 不分页，见类注释第一节 —— 它就是这个站点的调价历史。
     *
     * <p>★ <b>没有分页也意味着没有 {@code PageHelper} 陷阱</b>：这个查询不会往 ThreadLocal
     * 里放任何东西，所以补 {@code templateName} 的那次批量查询可以放心地跟在后面。
     */
    @GetMapping("/by-station/{stationId}")
    @Operation(summary = "查询某站点的全部计费绑定（按生效时刻倒序）")
    public Result<List<StationTariffVO>> listByStationId(@PathVariable Long stationId) {
        return Result.success(stationTariffService.listByStationId(stationId));
    }
}
