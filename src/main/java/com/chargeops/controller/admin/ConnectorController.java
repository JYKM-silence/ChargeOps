package com.chargeops.controller.admin;

import com.chargeops.dto.ConnectorDTO;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.dto.ConnectorUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ConnectorService;
import com.chargeops.vo.ConnectorVO;
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

/**
 * 管理端 - 充电枪管理
 *
 * <p>五个接口的形状等公共约定见 {@link StationController} 的类注释（正本）。
 *
 * <h2>★ 一、为什么是扁平的 {@code /api/admin/connector}，而不是嵌套在充电桩下</h2>
 *
 * <p>直觉上枪属于桩，路径似乎该是 {@code /api/admin/charger/{chargerId}/connector}。
 * 这里<b>刻意没那么做</b>，两个理由：
 *
 * <ol>
 *   <li><b>嵌套会让「这把枪属于哪个桩」有两个事实来源</b> ——
 *       路径里一个、请求体里可能还有一个。两者不一致时以谁为准？
 *       扁平的形状里 {@code chargerId} 只出现在请求体（创建时）
 *       或查询参数里，<b>只有一个来源</b>。</li>
 *   <li><b>后台需要一个跨桩、跨站点的枪列表</b>（「科技园站所有故障的枪」），
 *       嵌套形状根本表达不了这个查询 —— 它强制你先选一个桩。
 *       而实际上按 {@code stationId} 过滤比按 {@code chargerId} 过滤常用得多。</li>
 * </ol>
 *
 * <p>所以是扁平路径 + {@code chargerId} 作为<b>查询条件</b>（不是路径段）。
 * 注意这和「枪必须属于某个桩」不矛盾 —— 那是数据约束，由
 * {@code ConnectorDTO.chargerId} 上的 {@code @NotNull} 保证。
 *
 * <h2>★★ 二、修改接口的回传 {@code version} 是承重的</h2>
 *
 * <p>{@code ConnectorUpdateDTO} 里有 {@code @NotNull Integer version}，
 * 客户端<b>必须</b>把它从详情/列表接口拿到的 {@code version} 原样回传。
 *
 * <p>服务端执行的是条件更新
 * {@code UPDATE connector SET ... WHERE id = ? AND version = ?}，
 * 影响行数为 0 时抛「数据已被他人修改，请刷新后重试」。
 *
 * <p><b>★ 为什么不让服务端自己先读一次 version 再写：</b>
 * 那样「读到的 version」和「用来更新的 version」之间有一个窗口，
 * 而检查永远会通过 —— 它是一个<b>装饰性</b>的并发控制，
 * 唯一的作用是让代码看起来有锁。只有让客户端持有 version，
 * 「两个管理员同时打开编辑框」这个真实场景才会被挡住。
 *
 * <p>这是整个 P1a 里<b>唯一</b>有乐观锁的地方。别的域没有，
 * 因为只有枪有「两个人同时改同一把枪」的真实场景
 * （枪的状态是运维最常动的字段）。
 *
 * <h2>★ 三、状态 {@code status} 有白名单</h2>
 *
 * <p>管理员只能设 {@code 0离线 / 1空闲 / 4故障 / 5维护}，
 * 试图设成 {@code 2已占用} 或 {@code 3充电中} 会被拒绝。
 *
 * <p>理由：这两个状态由<b>订单状态机</b>写入，不是可编辑的字段。
 * 手工设成 3 会凭空造出一把「正在充电」但没有对应订单的枪 ——
 * 而 {@code uk_connector_current_order} 这把锁的语义是
 * 「有订单的枪才可能是占用/充电中」，手工设 3 直接绕过了那个不变量。
 *
 * <p>白名单在 Service 里用 {@code ConnectorStatus.isAdminSettable()} 判断，
 * 不在 DTO 上用 {@code @Min/@Max} —— 因为值域 0..5 是连续的，
 * 注解表达不了「这里面只有四个合法」。
 */
@RestController
@RequestMapping("/api/admin/connector")
@Tag(name = "管理端-充电枪")
@Slf4j
public class ConnectorController {

    private final ConnectorService connectorService;

    public ConnectorController(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    /**
     * 新增充电枪
     *
     * <p>★ <b>{@code stationId} 不在 DTO 里，由服务端从 {@code chargerId} 派生</b> ——
     * 它是 {@code connector} 表上的冗余列，存在只为让「某站点所有枪」
     * 这个查询不必 join 充电桩表。让调用方传它就等于给了它一个
     * 和 {@code charger.station_id} 不一致的机会。
     *
     * <p>★ 同一次事务里会调 {@code refreshGunCount} 更新所属桩的
     * {@code gun_count} 冗余列。这两步必须原子 —— 否则并发加枪时
     * 计数会丢更新，而丢了的计数没有任何机制能自愈。
     */
    @PostMapping
    @Operation(summary = "新增充电枪")
    public Result<Long> create(@Valid @RequestBody ConnectorDTO connectorDTO) {
        return Result.success(connectorService.create(connectorDTO));
    }

    /**
     * 修改充电枪
     *
     * <p>★ <b>必须回传 {@code version}</b>，见类注释第二节。
     *
     * <p>★ {@code chargerId} 和 {@code stationId} 都不在 UpdateDTO 里 ——
     * 把一把枪挪到别的桩，会让原桩和新桩的 {@code gun_count} 同时失真，
     * 而历史订单记录的是「在哪个桩的哪号枪上充的」，挪动会让那些记录成为孤儿。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电枪")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ConnectorUpdateDTO connectorUpdateDTO) {
        connectorService.update(id, connectorUpdateDTO);
        return Result.success();
    }

    /**
     * 删除充电枪
     *
     * <p>★ 两道守卫：{@code current_order_id} 非空，<b>或</b>
     * {@code status} 是 {@code 2已占用 / 3充电中}，都拒绝。
     *
     * <p>两个条件要<b>都查</b>，因为它们理论上不该同时为真
     * （有订单 ⟺ 状态是 2/3），但那是订单状态机维护的不变量，
     * 而这里面对的可能是一行<b>已经被改坏的数据</b>。
     * 只查其中一个的话，另一类坏行会被放行删掉 ——
     * 而删掉一把有订单的枪，那个订单的结算就再也找不到自己的枪了。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电枪")
    public Result<Void> delete(@PathVariable Long id) {
        connectorService.delete(id);
        return Result.success();
    }

    /**
     * 按 id 查详情
     *
     * <p>★ 返回里带 {@code version} —— 它是编辑页拿来做乐观锁回传的那个值。
     * 这也是 {@code ConnectorVO} 唯一比别的 VO 多出来的字段。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电枪详情")
    public Result<ConnectorVO> getById(@PathVariable Long id) {
        return Result.success(connectorService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ 三个过滤条件里 {@code stationId} 是给「看某个站的所有枪」用的，
     * {@code chargerId} 是给「看某个桩的所有枪」用的 —— 后者查出来的
     * 数量应该恰好等于那个桩的 {@code gun_count}，这两个数对不上
     * 就说明 {@code refreshGunCount} 漏掉了一次调用。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电枪")
    public Result<PageResult> page(@Valid ConnectorPageQueryDTO query) {
        return Result.success(connectorService.page(query));
    }
}
