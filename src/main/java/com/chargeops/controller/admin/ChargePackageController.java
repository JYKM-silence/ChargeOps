package com.chargeops.controller.admin;

import com.chargeops.dto.ChargePackageDTO;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.dto.ChargePackageUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.ChargePackageService;
import com.chargeops.vo.ChargePackageVO;
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
 * 管理端 - 充电套餐管理
 *
 * <p>五个接口的形状等公共约定见 {@link StationController} 的类注释（正本）。
 *
 * <p>★ 路径是 {@code /api/admin/package} 而不是 {@code /charge-package}：
 * 这个域没有同名冲突，{@code package} 是 Java 关键字但<b>不是</b> URL 的问题 ——
 * 它只影响 Java 里能不能用这个名字做类名，而类名是 {@code ChargePackageController}。
 *
 * <h2>★★ 一、{@code packageType} 决定了哪些字段必填，而那三个字段必须留空</h2>
 *
 * <pre>
 *   type=1 电量包        → energyKwh       必填，其余三个必须为 null
 *   type=2 时长包        → durationMinutes 必填，其余三个必须为 null
 *   type=3 服务费折扣包  → serviceDiscount 必填，其余三个必须为 null
 *   type=4 次卡          → times           必填，其余三个必须为 null
 * </pre>
 *
 * <p><b>「其余三个必须为 null」这一半比「必填的那个要有」更要紧</b>，
 * 因为漏了必填最多是算不出钱（报错，看得见），而残留字段是<b>静默的</b>：
 * 一个时长包里残留的 {@code energyKwh = 150} 会被 P1c 的套餐逻辑读走，
 * 凭空送出 150 度电。每一步都不报错，只有对账时那笔钱对不上。
 *
 * <p>这条校验有两个接口层做不到的地方，所以它落在 Service：
 * <ol>
 *   <li>它需要<b>跨字段</b>看（哪些必填取决于另一个字段的值），
 *       {@code @NotNull} 表达不了「条件必填」</li>
 *   <li>更新时它需要看<b>合并后的行</b>：只改价格的管理员不会传
 *       {@code energyKwh}，那个字段的值只能从库里读。
 *       拿 DTO 去校验会把「改个价格」误判成「电量包缺电量」，
 *       把一个完全合法的请求打回</li>
 * </ol>
 *
 * <h2>★ 二、两个字段被刻意排除在 UpdateDTO 之外</h2>
 *
 * <ul>
 *   <li><b>{@code packageType}</b> —— 它决定上面那张表，改它意味着
 *       「这已经是另一个套餐了」。而且改完类型后，旧类型的专属字段
 *       需要一个「清空」操作，而局部更新的 {@code <if>} 写法<b>清不掉</b>
 *       任何字段（见 {@code ChargePackageMapper.update} 的说明）。
 *       两个限制叠起来，允许改类型会造出一堆无法修复的坏行</li>
 *   <li><b>{@code stationId}</b> —— 它参与唯一键
 *       {@code uk_pkg_name_station (name, station_id)}，
 *       允许改它等于允许把一个<b>已售出的</b>套餐挪到别的站点作用域下</li>
 * </ul>
 *
 * <h2>★ 三、{@code stationId = 0} 表示全平台</h2>
 *
 * <p>不是 {@code null}。因为 MySQL 的唯一索引<b>不约束 NULL</b> ——
 * 用 null 表示全平台，「套餐名不重复」这条约束在全平台这一档上
 * 会<b>静默失效</b>，同名套餐可以被无限重复创建。
 * 详见 {@code ChargePackage} 的类注释第二节。
 */
@RestController
@RequestMapping("/api/admin/package")
@Tag(name = "管理端-充电套餐")
@Slf4j
public class ChargePackageController {

    private final ChargePackageService chargePackageService;

    public ChargePackageController(ChargePackageService chargePackageService) {
        this.chargePackageService = chargePackageService;
    }

    /**
     * 新增套餐
     *
     * <p>★ {@code stationId} 不传时默认 {@code 0}（全平台），
     * 不是「没填」—— 这个字段永不为 null，理由见类注释第三节。
     *
     * <p>★ {@code price} 允许为 {@code 0}（{@code @DecimalMin("0")}），
     * 因为「0 元体验包」是真实存在的运营手段；但 {@code serviceDiscount}
     * 是<b>严格大于 0 且不超过 1</b> 的，因为折扣率 0 意味着服务费全免，
     * 那不是折扣包该表达的东西（该建一个免费套餐），而大于 1 是涨价，
     * 更不该出现在「折扣包」里。
     */
    @PostMapping
    @Operation(summary = "新增充电套餐")
    public Result<Long> create(@Valid @RequestBody ChargePackageDTO chargePackageDTO) {
        return Result.success(chargePackageService.create(chargePackageDTO));
    }

    /**
     * 修改套餐
     *
     * <p>★ <b>校验的是「库里那行 + 本次改动」合并之后的结果</b>，
     * 不是请求体。所以一个只传 {@code price} 的请求，如果库里那行
     * 本来就有类型专属字段的问题，照样会被打回 —— 这不是误伤，
     * 那行数据确实不能继续留在库里。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电套餐")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ChargePackageUpdateDTO chargePackageUpdateDTO) {
        chargePackageService.update(id, chargePackageUpdateDTO);
        return Result.success();
    }

    /**
     * 删除套餐
     *
     * <p>★ 常规的「下架」手段是 {@code status = 0}（保留历史订单的引用），
     * 但硬删必须存在：{@code uk_pkg_name_station} 不看 {@code status}，
     * 所以名字建错一次就<b>永久占用</b> ——「150度电包」打成「150度点包」
     * 之后再想用正确的名字，只能先把错的删掉。
     *
     * <p>★ <b>P1c 要在这里加上「有没有已售出的套餐」的检查</b>：
     * 本轮只做存在性判断，还没有「谁买了这个套餐」那张表。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电套餐")
    public Result<Void> delete(@PathVariable Long id) {
        chargePackageService.delete(id);
        return Result.success();
    }

    
    /** 按 id 查详情 */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电套餐详情")
    public Result<ChargePackageVO> getById(@PathVariable Long id) {
        return Result.success(chargePackageService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ {@code stationId} 过滤要<b>把 {@code 0} 当成一个正常的值</b>，
     * 不是「不过滤」—— 查「全平台套餐」就是 {@code ?stationId=0}。
     * 这个区别容易写错，因为大多数系统里 {@code 0} 是个哨兵值。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电套餐")
    public Result<PageResult> page(@Valid ChargePackagePageQueryDTO query) {
        return Result.success(chargePackageService.page(query));
    }
}
