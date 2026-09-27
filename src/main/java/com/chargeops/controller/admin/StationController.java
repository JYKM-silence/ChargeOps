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
 * <h2>★ 一、五个接口的形状，全项目统一（本类的注释是这套约定的<b>正本</b>）</h2>
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
 * 都照这个形状写，差异只在域名词和 DTO 类型上。
 *
 * <h2>★ 二、每个 {@code @RequestBody} 都必须带 {@code @Valid}</h2>
 *
 * <p><b>少了它是最容易犯、代价最高的遗漏：</b>DTO 上的 {@code @NotBlank} /
 * {@code @Min} / {@code @Pattern} <b>全部静默失效</b>（注解还在、编译通过、
 * Swagger 里照常显示，只是永远不执行），本该 400 的请求一路走到 Service，
 * 最后撞在 {@code NOT NULL} 上变成 500 +「未知错误」。更糟的是那些<b>不会撞到
 * 任何约束</b>的字段（{@code ratedPowerKw} 传负数、{@code stationId} 传不存在的 id）
 * 会被原样写进库，直到某天有人发现「怎么有个桩的功率是 -7」。
 *
 * <h2>★ 三、{@code GET /{id}} 和 {@code GET /page} 为什么能共存</h2>
 *
 * <p>Spring 6 的 {@code PathPatternParser} <b>优先匹配字面量段</b>，
 * 所以 {@code /station/page} 稳定地进分页方法，不会去试着绑成 {@code Long id}。
 *
 * <p><b>★ 这个性质有前提：</b>它依赖 {@code page} 是字面量段而 {@code {id}} 类型不匹配。
 * 哪天有人把路径变量改成 {@code {code}}（String），或加一个 {@code GET /{id}/chargers}
 * 之类的映射，优先规则就会产生意料之外的结果 —— 失败时报的是
 * {@code MethodArgumentTypeMismatchException} → 兜底分支 →「未知错误」，<b>指向不了任何地方</b>。
 * 加新路径前先在这里确认一遍。
 *
 * <h2>★ 四、已知缺口：行级权限</h2>
 *
 * <p>{@code staff} 表里有 {@code role}（1平台管理员 2站点运维）和 {@code station_id}，
 * 但 {@code BaseContext} <b>只带了 {@code currentId}，没有站点作用域</b> ——
 * 后果是<b>一个站点运维登录后可以修改任意站点</b>，包括删除别的站点的充电桩，
 * 接口层面没有任何东西拦得住。
 *
 * <p>安全缺口应该是<b>可见的</b>：写下来它才是「已知的缺口」，不写半年后就会变成
 * 「没人知道有没有做过权限隔离」。修法不复杂 —— 拦截器解 token 时把 {@code role}
 * 和 {@code stationId} 放进 {@code BaseContext}，再校验「目标站点 ∈ 当前运维的范围」。
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
     * <p>★ 返回 {@code Result<Long>} 而不是 {@code Result<Void>}：
     * {@code useGeneratedKeys} 本来就把新 id 回填到实体上了，顺手返回能省掉
     * 「新建后跳详情」的一次往返。
     */
    @PostMapping
    @Operation(summary = "新增充电站")
    public Result<Long> create(@Valid @RequestBody StationDTO stationDTO) {
        return Result.success(stationService.create(stationDTO));
    }

    /**
     * 修改充电站
     *
     * <p>★ <b>id 走路径，不走请求体</b>：这样客户端就无法通过改 payload 来改
     * 「改的是哪一行」。不是多余的谨慎 —— id 在 body 里的话，前端某处复用了
     * 上一个请求的 body 就会改错记录，而服务端没有任何办法察觉。
     *
     * <p>★ UpdateDTO 的字段<b>全部可选</b>，不传表示「不修改」。
     * 代价见 {@code StationService.update}：局部更新<b>无法把一个字段清空</b>。
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
     * <p>★ 守卫式硬删：站点下还有充电桩时抛 {@code DataConflictException}（带具体数量）。
     * 常规「停业」手段是 {@code status = 2 维护中}，但硬删必须存在 ——
     * {@code uk_station_code} 不看 {@code status}，站点编码建错一次就永久占用。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除充电站")
    public Result<Void> delete(@PathVariable Long id) {
        stationService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询充电站详情")
    public Result<StationVO> getById(@PathVariable Long id) {
        return Result.success(stationService.getById(id));
    }

    /**
     * 分页查询
     *
     * <p>★ <b>这里的 {@code @Valid} 是承重的</b>，别以为它只对请求体有用：
     * {@code pageSize} 上的 {@code @Max(100)} 是<b>唯一</b>挡住 {@code ?pageSize=1000000}
     * 的东西，少了它一行 URL 就是一次 DoS。
     *
     * <p>★ 分页参数缺失时用的是 DTO 的<b>字段初始值</b>（{@code pageNum = 1} /
     * {@code pageSize = 10}）而不是 DDL 默认值：Spring 的参数绑定在参数缺失时
     * 不会覆盖初始值。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询充电站")
    public Result<PageResult> page(@Valid StationPageQueryDTO query) {
        return Result.success(stationService.page(query));
    }
}
