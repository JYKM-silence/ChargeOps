package com.chargeops.controller.admin;

import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.StationService;
import com.chargeops.vo.StationVO;
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
 * 管理端 - 充电站管理
 *
 * <p>路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。
 *
 * <h2>★ 一、五个接口的形状，全项目统一</h2>
 *
 * <pre>
 *   POST   /api/admin/station        @Valid StationDTO        → Result&lt;Long&gt;  新 id
 *   PUT    /api/admin/station/{id}   @Valid StationUpdateDTO  → Result&lt;Void&gt;
 *   DELETE /api/admin/station/{id}                            → Result&lt;Void&gt;
 *   GET    /api/admin/station/{id}                            → Result&lt;StationVO&gt;
 *   GET    /api/admin/station/page   StationPageQueryDTO      → Result&lt;PageResult&gt;
 * </pre>
 *
 * <p>后面五个域（charger / connector / package / tariff-template / station-tariff）
 * 都照这个形状写，差异只在域名词和 DTO 类型上。本类的注释是那一套约定的
 * <b>正本</b>，其余 Controller 只写自己域特有的部分。
 *
 * <h2>★ 二、每个 {@code @RequestBody} 都必须带 {@code @Valid}</h2>
 *
 * <p><b>少了它就是本项目最容易犯、且代价最高的一个遗漏。</b>
 *
 * <p>没有 {@code @Valid} 时，DTO 上那些 {@code @NotBlank} / {@code @Min} /
 * {@code @Pattern} 注解<b>全部静默失效</b> —— 注解还在、编译通过、
 * Swagger 文档里也照常显示，只是永远不会被执行。
 * 于是本该是 400 的请求一路走到 Service，最后撞在数据库的
 * {@code NOT NULL} 约束上，变成 500 +「未知错误」。
 *
 * <p>更糟的是那些<b>不会撞到任何约束</b>的字段：{@code ratedPowerKw} 传了
 * 一个负数、{@code stationId} 传了一个不存在的 id —— 它们会被原样写进库，
 * 直到某天有人发现「怎么有个桩的功率是 -7」。
 *
 * <h2>★ 三、{@code GET /{id}} 和 {@code GET /page} 为什么能共存</h2>
 *
 * <p>两个都是 {@code GET}，第二个路径段一个是 {@code {id}} 一个是字面量
 * {@code page}，看起来会冲突。实际上 Spring 6 的 {@code PathPatternParser}
 * <b>优先匹配字面量段</b>，所以 {@code /api/admin/station/page} 稳定地进
 * 分页方法，不会去试着把它绑成 {@code Long id}。
 *
 * <p><b>★ 这个性质有前提，写在这里免得以后被无意识地破坏：</b>
 * 它依赖 {@code page} 是一个<b>字面量</b>段，而 {@code {id}} 是一个
 * 类型不匹配的值。哪天有人把路径变量改成 {@code {code}}（String），
 * 或者加一个 {@code GET /{id}/chargers} 之类的映射，这个优先规则就会
 * 开始产生意料之外的结果 —— 而失败时的报错是
 * {@code MethodArgumentTypeMismatchException} → 兜底分支 →「未知错误」，
 * <b>指向不了任何地方</b>。所以加新路径前先在这里确认一遍。
 *
 * <h2>★★ 四、已知缺口：行级权限（本轮明确不做）</h2>
 *
 * <p>{@code staff} 表里有 {@code role}（1平台管理员 2站点运维）和
 * {@code station_id}（运维负责的站点），但 {@code BaseContext} 里
 * <b>只带了 {@code currentId}，没有站点作用域</b>。
 *
 * <p>后果是：<b>一个站点运维登录后，可以修改任意一个站点</b>，
 * 包括删除别的站点的充电桩。接口层面没有任何东西拦得住它。
 *
 * <p><b>为什么把这件事写成注释而不是悄悄留着：</b>
 * 安全的缺口应该是<b>可见的</b>。它现在是一个「已知的、被记录的、
 * 有排期的」缺口；如果没人写下来，半年后它就会变成
 * 「没人知道有没有做过权限隔离」—— 而那时候要做的第一件事
 * 是把所有接口重新审一遍，成本远大于现在写这三十行注释。
 *
 * <p>修法也不复杂：拦截器解 token 时顺带把 {@code role} 和
 * {@code stationId} 放进 {@code BaseContext}，然后在这些方法里
 * 校验「目标站点 ∈ 当前运维的站点范围」。缺的是<b>决定要这么做</b>，
 * 不是技术方案。<b>未排期。</b>
 */
@RestController
@RequestMapping("/api/admin/station")
@Tag(name = "管理端-充电站")
@Slf4j
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    /**
     * 新增充电站
     *
     * <p>★ 返回 {@code Result<Long>} 而不是 {@code Result<Void>} ——
     * {@code useGeneratedKeys} 本来就把新 id 回填到实体上了，
     * 顺手返回它，前端拿到就能直接插入新行，不用再 {@code GET} 一次。
     * 对「新建后跳到详情页」这个最常见的交互，省掉一次往返。
     */
    @PostMapping
    @Operation(summary = "新增充电站")
    public Result<Long> create(@Valid @RequestBody StationDTO stationDTO) {
        return Result.success(stationService.create(stationDTO));
    }

    /**
     * 修改充电站
     *
     * <p>★ <b>id 走路径，不走请求体</b> —— 这样客户端就无法通过改 payload
     * 来改「改的是哪一行」。这不是多余的谨慎：如果 id 在 body 里，
     * 前端某处把上一个请求的 body 复用了，改的就是另一条记录了，
     * 而且服务端没有任何办法察觉。
     *
     * <p>★ UpdateDTO 里的字段<b>全部可选</b>（没有 {@code @NotNull}），
     * 不传的字段表示「不修改」。代价见 {@code StationService.update}：
     * 局部更新<b>无法把一个字段清空</b>。
     */
    @PutMapping("/{id}")
    @Operation(summary = "修改充电站")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody StationUpdateDTO stationUpdateDTO) {
        stationService.update(id, stationUpdateDTO);
        return Result.success();
    }

    /**
     * 删除充电站
     *
     * <p>★ 守卫式硬删：站点下还有充电桩时抛 {@code DataConflictException}，
     * 消息里带上具体数量。常规的「停业」手段是 {@code status = 2 维护中}，
     * 但硬删必须存在 —— {@code uk_station_code} 不看 {@code status}，
     * 站点编码建错一次就永久占用。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电站")
    public Result<Void> delete(@PathVariable Long id) {
        stationService.delete(id);
        return Result.success();
    }

    /** 按 id 查详情 */
    @GetMapping("/{id}")
    @Operation(summary = "查询充电站详情")
    public Result<StationVO> getById(@PathVariable Long id) {
        return Result.success(stationService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ <b>这里的 {@code @Valid} 是承重的</b>，别以为它只对请求体有用：
     * 约束写在 {@code StationPageQueryDTO} 的 {@code pageNum} / {@code pageSize} 上，
     * 而 {@code pageSize} 上的 {@code @Max(100)} 是<b>唯一</b>挡住
     * {@code ?pageSize=1000000} 的东西。少了它，一行 URL 就是一次 DoS。
     *
     * <p>★ 分页参数缺失时用的是 DTO 的<b>字段初始值</b>
     * （{@code pageNum = 1} / {@code pageSize = 10}），不是 DDL 默认值 ——
     * Spring 的参数绑定在参数缺失时不会覆盖初始值。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电站")
    public Result<PageResult> page(@Valid StationPageQueryDTO query) {
        return Result.success(stationService.page(query));
    }
}
