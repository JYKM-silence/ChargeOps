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
 * <h2>★ 一、为什么是扁平的 {@code /api/admin/connector}，不嵌套在充电桩下</h2>
 *
 * <p>直觉上枪属于桩，路径似乎该是 {@code /charger/{chargerId}/connector}，这里<b>刻意没那么做</b>：
 * 嵌套会让「这把枪属于哪个桩」有<b>两个事实来源</b>（路径里一个、请求体里可能还有一个），
 * 不一致时以谁为准；而且后台需要一个跨桩、跨站点的枪列表（「科技园站所有故障的枪」），
 * 嵌套形状根本表达不了 —— 它强制你先选一个桩，而实际上按 {@code stationId} 过滤比按
 * {@code chargerId} 过滤常用得多。这和「枪必须属于某个桩」不矛盾，那是数据约束，
 * 由 {@code ConnectorDTO.chargerId} 上的 {@code @NotNull} 保证。
 *
 * <h2>★ 二、修改接口回传的 {@code version} 是承重的</h2>
 *
 * <p>{@code ConnectorUpdateDTO} 里有 {@code @NotNull Integer version}，客户端<b>必须</b>把从
 * 详情/列表接口拿到的 {@code version} 原样回传。服务端执行条件更新
 * {@code UPDATE connector SET ... WHERE id = ? AND version = ?}，影响行数为 0 时抛
 * 「数据已被他人修改，请刷新后重试」。
 *
 * <p><b>★ 为什么不让服务端自己先读一次 version 再写：</b>那样「读到的 version」和
 * 「用来更新的 version」之间有一个窗口，检查永远会通过 —— 那是一个<b>装饰性</b>的并发控制，
 * 唯一作用是让代码看起来有锁。只有让客户端持有 version，「两个管理员同时打开编辑框」
 * 这个真实场景才会被挡住。这是本项目<b>唯一</b>有乐观锁的地方：只有枪有「两个人同时改
 * 同一把枪」的真实场景。
 *
 * <h2>★ 三、状态 {@code status} 有白名单</h2>
 *
 * <p>管理员只能设 {@code 0离线 / 1空闲 / 4故障 / 5维护}，设成 {@code 2已占用} 或
 * {@code 3充电中} 会被拒绝：这两个状态由<b>订单状态机</b>写入，手工设 3 会凭空造出一把
 * 「正在充电」却没有对应订单的枪，直接绕过 {@code uk_connector_current_order}
 * 「有订单的枪才可能是占用/充电中」这个不变量。
 *
 * <p>白名单在 Service 里用 {@code ConnectorStatus.isAdminSettable()} 判断，不在 DTO 上用
 * {@code @Min/@Max} —— 值域 0..5 是连续的，注解表达不了「里面只有四个合法」。
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
     * <p>★ <b>{@code stationId} 不在 DTO 里，由服务端从 {@code chargerId} 派生</b>：
     * 它是 {@code connector} 上的冗余列，只为让「某站点所有枪」不必 join 充电桩表；
     * 让调用方传就等于给它一个和 {@code charger.station_id} 不一致的机会。
     *
     * <p>★ {@code refreshGunCount} 更新所属桩的 {@code gun_count} 必须和插入同事务 ——
     * 否则并发加枪会丢更新，而丢了的计数没有任何机制能自愈。
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
     * <p>★ {@code chargerId} 和 {@code stationId} 不在 UpdateDTO 里：挪枪会让原桩和新桩的
     * {@code gun_count} 同时失真，而历史订单记的是「哪个桩的哪号枪」，挪动后它们成孤儿。
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
     * <p>★ 两道守卫<b>都查</b>：{@code current_order_id} 非空，或 {@code status} 是
     * {@code 2已占用 / 3充电中}，命中任一即拒绝。二者理论上不该同时为真
     * （有订单 ⟺ 状态 2/3），但那是订单状态机维护的不变量，这里面对的可能是
     * 一行<b>已被改坏的数据</b>；只查一个，另一类坏行就会被放行 ——
     * 而删掉一把有订单的枪，那个订单结算时再也找不到自己的枪。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电枪")
    public Result<Void> delete(@PathVariable Long id) {
        connectorService.delete(id);
        return Result.success();
    }

    /**
     * ★ 返回里带 {@code version} —— 编辑页回传的那个值，也是 {@code ConnectorVO}
     * 唯一比别的 VO 多出来的字段。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电枪详情")
    public Result<ConnectorVO> getById(@PathVariable Long id) {
        return Result.success(connectorService.getById(id));
    }

    /**
     * ★ {@code stationId} / {@code chargerId} 分别给「看某个站 / 某个桩的所有枪」用。
     * 按 {@code chargerId} 查出的条数应恰好等于该桩的 {@code gun_count}，
     * 对不上就说明 {@code refreshGunCount} 漏掉过一次调用。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电枪")
    public Result<PageResult> page(@Valid ConnectorPageQueryDTO query) {
        return Result.success(connectorService.page(query));
    }
}
