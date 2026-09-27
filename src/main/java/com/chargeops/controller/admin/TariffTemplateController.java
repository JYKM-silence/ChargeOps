package com.chargeops.controller.admin;

import com.chargeops.dto.TariffTemplateDTO;
import com.chargeops.dto.TariffTemplatePageQueryDTO;
import com.chargeops.dto.TariffTemplateUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.TariffTemplateService;
import com.chargeops.vo.TariffTemplateVO;
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
 * 管理端 - 计费模板管理
 *
 * <p>五个接口的形状等公共约定见 {@link StationController} 的类注释（正本）。
 * 本域比别的域<b>多一个接口</b>（{@code /list-enabled}），也有一个别的域都没有的约束（规则不可变）。
 *
 * <h2>★ 一、模板和它的规则是一个整体，一次写完</h2>
 *
 * <p>{@code TariffTemplateDTO} 里<b>内嵌</b>了 {@code List<TariffRuleDTO> rules}，
 * 没有单独的「加一条规则」接口：{@code TariffValidator} 要检查「8 条规则合起来是否无缺口、
 * 无重叠地覆盖 24 小时」，<b>那是一个跨行的性质</b>，单看任何一条规则都判断不出来。
 * 规则若能逐条加，每加一条模板都处于「暂时不完整」的中间状态，而这个状态会泄漏到所有读路径上；
 * 整体替换把它从数据模型里彻底移除：一个模板要么有一套完整的规则，要么没有规则。
 *
 * <h2>★ 二、规则一旦被站点绑定就不可修改</h2>
 *
 * <p>{@code charging_order} 在下单那一刻把 {@code tariff_template_id} 冻结进去，这样电价调整
 * 不影响在途订单。但<b>这条保证只在规则不可变时才成立</b>：规则还能改的话，已冻结的 id 就指向
 * 被改过的规则，保证静默失效 —— 订单金额照样算出来，只是和用户启动时看到的不一样。
 * 所以带 {@code rules} 的 {@code PUT} 在 Service 里被拦下：只要该模板被任何一行
 * {@code station_tariff} 引用，就抛「该模板已被站点绑定，不能修改计费规则；请新建一个模板版本」。
 *
 * <p>★ <b>要调价就新建一个模板版本</b>，用 {@code station_tariff.effective_from} 绑定生效期 ——
 * 这不是绕路，这就是那张表的设计意图：模板是版本化的，站点通过绑定表选择「此刻用哪个版本」。
 * 改名称 / 地区 / 说明 / {@code status} <b>不受这条守卫约束</b>（不改变任何规则，
 * 也就不影响已冻结的订单）：<b>冻结的是规则，不是模板头</b>。
 *
 * <h2>★ 三、列表【不含】规则，详情【含】规则</h2>
 *
 * <p>一页 10 个模板就是 80 条规则，而列表页只显示模板名和地区。所以 {@code page} 返回的
 * {@code TariffTemplateVO.rules} 恒为 {@code null} —— <b>{@code null} 而不是空列表</b>，
 * 见那个类的注释：「字段存在但为 null」和「字段不存在」对调用方是两种不同的信号。
 *
 * <h2>★ 四、{@code GET /list-enabled} 是给下拉框用的</h2>
 *
 * <p>新建站点绑定时需要一个「当前可用模板」的下拉框，它必须<b>一次拿全、不分页</b> ——
 * 分页的下拉框没法用（用户没法翻到第 2 页去选）。模板是配置数据，一个地区通常只有个位数个，
 * 所以不分页没有风险；它和 {@code page} 的区别是只返回 {@code status = 1} 的，
 * 而且<b>永远不要给它加分页</b>。
 */
@RestController
@RequestMapping("/api/admin/tariff-template")
@Tag(name = "管理端-计费模板")
@Slf4j
public class TariffTemplateController {

    private final TariffTemplateService tariffTemplateService;

    public TariffTemplateController(TariffTemplateService tariffTemplateService) {
        this.tariffTemplateService = tariffTemplateService;
    }

    /**
     * ★ {@code rules} 上是 {@code @NotEmpty} + {@code @Valid}，<b>两个都要有</b>：前者保证
     * 「至少有一条规则」，后者保证每条规则自己的字段约束（时间格式、价格非负）被执行 ——
     * 只有 {@code @Valid} 的话，传一个空数组就能建出一个没有规则的模板，而它在列表页上完全正常。
     *
     * <p>★ 更深一层的校验（24 小时完整覆盖、无重叠）在 Service 里，且发生在<b>写库之前</b> ——
     * 规则有缺口的模板不该先落库拿到自增 id 再回滚，那会白白消耗 id，
     * 运维看到 id 不连续会以为有数据被删过。
     */
    @PostMapping
    @Operation(summary = "新增计费模板")
    public Result<Long> create(@Valid @RequestBody TariffTemplateDTO tariffTemplateDTO) {
        return Result.success(tariffTemplateService.create(tariffTemplateDTO));
    }

    /**
     * ★ {@code rules} 上<b>只有</b> {@code @Valid}，没有 {@code @NotEmpty} —— 不传（{@code null}）
     * 和传空数组都表示「不修改规则，只改头信息」。这是有意把「表单没勾任何时段」和「没动规则」
     * 当成等价：让空数组去<b>清空</b>一个模板的规则，代价是所有用它的站点在下单时算出空金额，
     * 那个后果和「用户手滑提交了一个空表单」完全不成比例。
     *
     * <p>★ 传了非空的 {@code rules} 就会走第二节那道不可变守卫。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改计费模板")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody TariffTemplateUpdateDTO tariffTemplateUpdateDTO) {
        tariffTemplateService.update(id, tariffTemplateUpdateDTO);
        return Result.success();
    }

    /**
     * ★ 被任何 {@code station_tariff} 行引用时拒绝 —— 删掉一个正在被站点使用的模板，
     * 那些站点的电价解析会返回空结果，表现成「这个站点的订单算不出钱」。
     *
     * <p>★ <b>规则不会被级联删除</b>，会留下孤儿行（{@code template_id} 指向不存在的模板）：
     * 级联删除会让「删掉模板」这个动作顺手抹掉 8 行计价数据，而孤儿行至少可查、可人工清理。
     * 常规运维里「删除模板」就不该发生 —— 用 {@code status = 0} 停用。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除计费模板")
    public Result<Void> delete(@PathVariable Long id) {
        tariffTemplateService.delete(id);
        return Result.success();
    }

    /**
     * ★ <b>含</b>规则，且按 {@code start_time} 排序返回 —— 靠的是「零填充的 {@code HH:mm:ss}
     * 字典序等于时间序」这条性质（{@code "24:00:00"} 自然排在最后），所以编辑页拿到的是
     * 一个可以直接渲染成时间轴的列表，不需要前端再排一次。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询计费模板详情（含计费规则）")
    public Result<TariffTemplateVO> getById(@PathVariable Long id) {
        return Result.success(tariffTemplateService.getById(id));
    }

    /**
     * ★ <b>不含</b>规则，和 {@code getById} 的区别是有意的，见类注释第三节。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询计费模板")
    public Result<PageResult> page(@Valid TariffTemplatePageQueryDTO query) {
        return Result.success(tariffTemplateService.page(query));
    }

    /**
     * ★ 供「新建站点计费绑定」的下拉框使用，见类注释第四节；返回的每一项 {@code rules}
     * 都是 {@code null}。<b>这个接口不能加分页参数</b> —— 一个只能选到前 10 个模板的下拉框
     * 会比没有下拉框更糟，因为它看起来是好的。
     */
    @GetMapping("/list-enabled")
    @Operation(summary = "查询全部启用中的计费模板（下拉框用）")
    public Result<List<TariffTemplateVO>> listEnabled() {
        return Result.success(tariffTemplateService.listAllEnabled());
    }
}
