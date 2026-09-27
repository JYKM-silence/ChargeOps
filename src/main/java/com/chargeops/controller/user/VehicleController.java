package com.chargeops.controller.user;

import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.VehicleService;
import com.chargeops.vo.VehicleVO;
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
 * 车主端 - 我的车辆
 *
 * <p>路径 {@code /api/member/vehicle/**}，由 {@code JwtTokenMemberInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。六个端点：我的全部（不分页）、按 id 查、新增、修改、删除、
 * 设为默认车；{@code POST} 返回新 id（约定见 {@link StationController#create}）。
 *
 * <h2>★ 一、五个端点带 {@code {id}}（和 {@code MemberController} 形状相反），安全性来自别处</h2>
 *
 * <p>★ {@code MemberController} 那边刻意<b>没有</b> {@code {id}}（「改自己」不该有能力表达
 * 「改别人」），这里则相反 —— 车辆天然是「一堆东西里的一件」，必须有 id。所以安全性
 * <b>不可能</b>来自「没有 id 参数」，它来自另外两层，而且两层独立成立：
 *
 * <ol>
 *   <li>Service 的方法签名里<b>没有 memberId 参数</b> —— 身份只从 {@code BaseContext} 来，
 *       「把 A 的车当成 B 的车来操作」连写都写不出来</li>
 *   <li>每一条 SQL 的 {@code WHERE} 里都带 {@code member_id} —— 即使上面那层被绕过，
 *       {@code UPDATE} / {@code DELETE} 也改不到别人的车（影响行数为 0）</li>
 * </ol>
 *
 * <p>★ 完整论证在 {@code VehicleMapper} 的类注释第一节，一句话：
 * <b>作用域是文件级的性质，不是每个调用方的自觉。</b>
 *
 * <h2>★ 二、{@code GET /} 不分页；{@code {id}} 取不到时也<b>不</b>说「无权访问」</h2>
 *
 * <p>不分页的理由见 {@code VehicleService.listMine} —— 一个车主的车辆数在个位，
 * 而且那个查询走 {@code uk_vehicle_member_plate} 的最左前缀。
 *
 * <p>★ 第二条更要紧：{@code GET /{id}} 传一个别人的车辆 id 时，返回的是「车辆不存在」，
 * <b>不是</b>「无权访问」—— 后者会免费告诉攻击者「这个 id 存在，只是不属于你」，
 * 他拿一个 id 从 1 数到 10000 就能把系统里所有有效的车辆 id 标出来。而对<b>合法用户</b>来说，
 * 这两种情况是同一个事实：他要找的那辆车不在他的列表里。
 * 完整论证见 {@code VehicleService} 的类注释第一节。
 *
 * <h2>★ 三、{@code PUT /{id}/default} 是<b>唯一</b>能改默认车的入口</h2>
 *
 * <p>它必须独立成一个端点，而不是 {@code PUT /{id}} 上的一个字段 —— 完整论证见
 * {@code VehicleUpdateDTO} 的类注释第一节：两条写入路径通向同一个不变量，迟早有一条忘了清旧的，
 * 而「忘了清旧的」这个错误<b>不报错、不冲突</b>，只是列表页上出现两个「默认」标签。
 *
 * <p>★ 这个路径末段是字面量 {@code default}，和 {@code GET /{id}} 不冲突。但<b>将来若要加
 * {@code PUT /{id}/{something}} 这种形状，先回 {@link StationController} 类注释第三节
 * 看一遍那个优先规则的说明</b> —— 那里写清楚了它依赖什么、破坏它的表现是什么。
 */
@RestController
@RequestMapping("/api/member/vehicle")
@Tag(name = "车主端-我的车辆")
@Slf4j
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    /**
     * ★ <b>顺序是 {@code is_default DESC, id DESC}，默认车在最前</b>，前端需要「默认车」时
     * <b>取第一条</b> —— 不要写「找 {@code isDefault == 1} 的那条」：数据万一坏了（手工 SQL 造出
     * 两辆默认车），取第一条只是多显示一个标记，而「找那条」要么结果不确定、要么在没有默认车时
     * 拿到 null。完整论证见 {@code VehicleVO} 的类注释第一节。
     */
    @GetMapping
    @Operation(summary = "查询我的全部车辆", description = "不分页；默认车在第一条")
    public Result<List<VehicleVO>> listMine() {
        return Result.success(vehicleService.listMine());
    }

    /**
     * ★ 别人的车辆 id 返回「车辆不存在」而不是「无权访问」—— 见类注释第二节。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询我的某一辆车")
    public Result<VehicleVO> getMine(@PathVariable Long id) {
        return Result.success(vehicleService.getMine(id));
    }

    /**
     * ★ <b>这是车主的第一辆车时，{@code isDefault} 会被强制置 1</b>，不管请求里传了什么
     * （理由见 {@code VehicleDTO} 的类注释第三节）—— 这是「有车的车主一定有且只有一辆默认车」
     * 这条不变量的第一个落点。
     *
     * <p>★ 车牌入库会 trim + 转大写。这<b>不是</b>为了唯一性（排序规则
     * {@code utf8mb4_0900_ai_ci} 本来就大小写不敏感），而是为了展示统一，以及挡住
     * 「尾随空格绕过唯一索引」这个正确性问题（{@code utf8mb4_0900_ai_ci} 是 NO PAD，
     * 尾空格有意义）。完整论证见 {@code VehicleServiceImpl.normalizePlateNo}。
     *
     * <p>★ {@code memberId} <b>不在请求体里</b>（{@code VehicleDTO} 里没有这个字段），
     * 服务端从 token 取 —— 客户端无法把车登记到别人名下。
     */
    @PostMapping
    @Operation(summary = "新增车辆")
    public Result<Long> create(@Valid @RequestBody VehicleDTO vehicleDTO) {
        return Result.success(vehicleService.create(vehicleDTO));
    }

    /**
     * ★ <b>改不了 {@code isDefault}</b>（走 {@code PUT /{id}/default}），也改不了归属 ——
     * 见 {@code VehicleUpdateDTO} 的两个「没有」。
     *
     * <p>★ <b>车牌可以改</b>，而且这是 DDL 明确支持的：{@code charging_order.plate_no} 的注释
     * 写着「车牌快照：车辆可被删除/改牌，订单必须保留当时值」。改牌之后历史订单显示旧牌
     * （正确 —— 当时确实是那块牌），这辆车显示新牌（正确），两件事回答的是不同的问题。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改车辆")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody VehicleUpdateDTO vehicleUpdateDTO) {
        vehicleService.update(id, vehicleUpdateDTO);
        return Result.success();
    }

    /**
     * ★ <b>硬删，没有守卫</b>：「有订单就不能删」这种守卫加不了，而且<b>不该加</b> ——
     * {@code charging_order.plate_no} 那张快照列存在的理由就是允许车辆被删
     * （完整论证见 {@code VehicleMapper} 的类注释第四节）。{@code vehicle} 也没有状态列，
     * 所以「卖车之后把它从列表里去掉」只有硬删这一条路。
     *
     * <p>★ <b>删掉的如果是默认车，剩下的第一辆会自动成为默认车</b> —— 否则「有车的车主一定有且
     * 只有一辆默认车」这条不变量就断了，前端「取列表第一条当默认车」会拿到一个没有默认车的列表。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除车辆", description = "若删的是默认车，剩余第一辆会自动成为默认车")
    public Result<Void> delete(@PathVariable Long id) {
        vehicleService.delete(id);
        return Result.success();
    }

    /**
     * ★ 幂等 —— 已经是默认车时再调一次也成功。
     *
     * <p>★ 用 {@code PUT} 而不是 {@code POST}：它不创建资源，而是把「默认车」这个位置
     * <b>指派</b>给这一辆，同一个请求重复发多少次结果都一样。
     */
    @PutMapping("/{id}/default")
    @Operation(summary = "设为默认车")
    public Result<Void> setDefault(@PathVariable Long id) {
        vehicleService.setDefault(id);
        return Result.success();
    }
}
