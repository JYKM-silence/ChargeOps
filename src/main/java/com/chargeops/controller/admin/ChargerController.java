package com.chargeops.controller.admin;

import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargerService;
import com.chargeops.vo.ChargerVO;
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
 * 管理端 - 充电桩管理
 *
 * <p>五个接口的形状、{@code @Valid} 的必要性、{@code GET /{id}} 与
 * {@code GET /page} 为什么能共存、以及行级权限的已知缺口，
 * 都在 {@link StationController} 的类注释里 —— 那是这一套约定的<b>正本</b>，
 * 本类不重复。下面只写充电桩特有的部分。
 *
 * <h2>★ 一、创建时不能指定 {@code gunCount} 和 {@code status}</h2>
 *
 * <p>{@code ChargerDTO} 里没有这两个字段，Service 强制写成
 * {@code gunCount = 0}（<b>不是</b> DDL 默认的 1）和
 * {@code status = 0 离线}。
 *
 * <p>{@code gunCount = 0} 而不是 1：新建的桩一把枪都没有，
 * 写成 1 是在<b>描述一个不存在的事实</b> —— 而 {@code gun_count}
 * 是冗余列，它的唯一职责就是等于 {@code COUNT(connector)}。
 * 让它在出生那一刻就不等于，等于从第一天开始漂移。
 * 第一次加枪时由 {@code refreshGunCount} 写进正确的值。
 *
 * <h2>★ 二、列表会补 {@code stationName}</h2>
 *
 * <p>桩列表是<b>跨站点</b>查的，一行显示成「DEV-2026-0001 / A区-01」
 * 而不带站名是没有意义的 —— 管理员根本认不出这是哪个站的桩。
 * 所以 {@code ChargerVO} 比实体多一个 {@code stationName}，
 * 由 Service 用 {@code StationMapper.listByIds} 批量补齐。
 *
 * <p>★ 批量的理由不是性能洁癖，而是 <b>PageHelper 的 ThreadLocal 陷阱</b>：
 * 补名字的查询必须在分页查询<b>之后</b>，而且不能插在
 * {@code PageHelper.startPage} 和分页查询<b>之间</b>——
 * 那会让补名字的 SQL 拿到 {@code LIMIT 10}。
 * 详见 {@code ChargerServiceImpl.page} 里那段完整说明。
 */
@RestController
@RequestMapping("/api/admin/charger")
@Tag(name = "管理端-充电桩")
@Slf4j
public class ChargerController {

    private final ChargerService chargerService;

    public ChargerController(ChargerService chargerService) {
        this.chargerService = chargerService;
    }

    /**
     * 新增充电桩
     *
     * <p>★ {@code stationId} 必填且会被校验存在性 —— 桩必须挂在某个站点下。
     * 但要注意 DDL 里 {@code charger.station_id} <b>没有外键约束</b>
     * （整个项目都没有外键，见 DDL 的说明），所以这层校验完全靠 Service，
     * 直接写 SQL 是绕得过去的。
     */
    @PostMapping
    @Operation(summary = "新增充电桩")
    public Result<Long> create(@Valid @RequestBody ChargerDTO chargerDTO) {
        return Result.success(chargerService.create(chargerDTO));
    }

    /**
     * 修改充电桩
     *
     * <p>★ <b>{@code stationId} 不在 UpdateDTO 里</b>：把一个桩挪到别的站点，
     * 会让它下面所有枪的冗余列 {@code station_id} 全部失真，
     * 而历史订单指向的站点也变成错的。要挪就删了重建。
     *
     * <p>★ <b>{@code gunCount} 也不在</b>：它是冗余列，
     * 手工改等于让它和 {@code COUNT(connector)} 脱钩。
     *
     * <p>★ <b>{@code status} 在，且没有白名单</b>（{@code @Min(0) @Max(4)}）——
     * 这一点和充电枪<b>不同</b>：桩的五个状态（离线/在线空闲/充电中/故障/维护中）
     * 管理员都可以设。桩的「充电中」只是一个展示用的汇总状态，
     * 不像枪的「充电中」那样直接对应 {@code uk_connector_current_order}
     * 那把锁的语义。这个区别见 {@code ConnectorUpdateDTO.status} 的注释。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电桩")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ChargerUpdateDTO chargerUpdateDTO) {
        chargerService.update(id, chargerUpdateDTO);
        return Result.success();
    }

    /**
     * 删除充电桩
     *
     * <p>★ 守卫式硬删：桩下还有充电枪时抛 {@code DataConflictException}。
     * 常规手段是 {@code status = 4 维护中}。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电桩")
    public Result<Void> delete(@PathVariable Long id) {
        chargerService.delete(id);
        return Result.success();
    }

    /** 按 id 查详情（含 {@code stationName}） */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电桩详情")
    public Result<ChargerVO> getById(@PathVariable Long id) {
        return Result.success(chargerService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ 最常用的过滤条件是 {@code stationId} —— 「先选站点、再看这个站的桩」
     * 是做运维的入口路径。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电桩")
    public Result<PageResult> page(@Valid ChargerPageQueryDTO query) {
        return Result.success(chargerService.page(query));
    }
}
