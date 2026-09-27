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
 * 路径用 {@code /api/admin/package}：{@code package} 是 Java 关键字，但那只管类名
 * （本类是 {@code ChargePackageController}），不影响 URL。
 *
 * <h2>★ 一、{@code packageType} 决定哪个字段必填，另外三个必须为 null</h2>
 *
 * <pre>
 *   type=1 电量包 → energyKwh        type=2 时长包 → durationMinutes
 *   type=3 折扣包 → serviceDiscount  type=4 次卡   → times
 * </pre>
 *
 * <p>「其余三个必须为 null」这一半比「必填的那个要有」更要紧：漏了必填最多是算不出钱
 * （报错，看得见），而残留字段是<b>静默的</b> —— 时长包里残留的 {@code energyKwh = 150}
 * 会被套餐扣减逻辑读走，凭空送出 150 度电，每一步都不报错，只有对账时那笔钱对不上。
 *
 * <p>这条校验落在 Service，因为接口层做不到两点：它要<b>跨字段</b>看
 * （{@code @NotNull} 表达不了「条件必填」）；更新时还要看<b>合并后的行</b> ——
 * 只改价格的管理员不会传 {@code energyKwh}，那个值只能从库里读，
 * 拿 DTO 去校验会把「改个价格」误判成「电量包缺电量」。
 *
 * <h2>★ 二、{@code packageType} 和 {@code stationId} 被排除在 UpdateDTO 之外</h2>
 *
 * <p>{@code packageType} 改了就「已经是另一个套餐」了，且改完类型后旧类型的专属字段需要清空，
 * 而局部更新的 {@code <if>} 写法<b>清不掉</b>任何字段（见 {@code ChargePackageMapper.update}）——
 * 两个限制叠起来，允许改类型会造出一堆无法修复的坏行。
 * {@code stationId} 参与唯一键 {@code uk_pkg_name_station (name, station_id)}，
 * 允许改它等于允许把一个<b>已售出的</b>套餐挪到别的站点作用域下。
 *
 * <h2>★ 三、{@code stationId = 0} 表示全平台，不是 {@code null}</h2>
 *
 * <p>因为 MySQL 的唯一索引<b>不约束 NULL</b>：用 null 表示全平台，「套餐名不重复」
 * 在全平台这一档上会<b>静默失效</b>，同名套餐可以被无限重复创建。
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
     * ★ {@code stationId} 不传时默认 {@code 0}（全平台），不是「没填」—— 该字段永不为 null，
     * 理由见类注释第三节。
     *
     * <p>★ {@code price} 允许为 0（{@code @DecimalMin("0")}，「0 元体验包」是真实运营手段）；
     * 但 {@code serviceDiscount} 必须<b>大于 0 且不超过 1</b>：0 意味着服务费全免，
     * 该建免费套餐而不是折扣包；大于 1 是涨价，更不该出现在折扣包里。
     */
    @PostMapping
    @Operation(summary = "新增充电套餐")
    public Result<Long> create(@Valid @RequestBody ChargePackageDTO chargePackageDTO) {
        return Result.success(chargePackageService.create(chargePackageDTO));
    }

    /**
     * ★ <b>校验的是「库里那行 + 本次改动」合并后的结果</b>，不是请求体：只传 {@code price}
     * 的请求，如果库里那行本来就有类型专属字段的问题，照样会被打回 —— 这不是误伤，
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
     * ★ 常规的「下架」手段是 {@code status = 0}（保留历史订单的引用），但硬删必须存在：
     * {@code uk_pkg_name_station} 不看 {@code status}，名字建错一次就<b>永久占用</b> ——
     * 「150度电包」打成「150度点包」后想再用正确的名字，只能先把错的删掉。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电套餐")
    public Result<Void> delete(@PathVariable Long id) {
        chargePackageService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电套餐详情")
    public Result<ChargePackageVO> getById(@PathVariable Long id) {
        return Result.success(chargePackageService.getById(id));
    }

    /**
     * ★ {@code stationId} 过滤要<b>把 {@code 0} 当成一个正常的值</b>，不是「不过滤」——
     * 查「全平台套餐」就是 {@code ?stationId=0}。容易写错，因为多数系统里 0 是个哨兵值。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电套餐")
    public Result<PageResult> page(@Valid ChargePackagePageQueryDTO query) {
        return Result.success(chargePackageService.page(query));
    }
}
