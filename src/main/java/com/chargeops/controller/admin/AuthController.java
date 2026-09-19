package com.chargeops.controller.admin;

import com.chargeops.dto.StaffLoginDTO;
import com.chargeops.result.Result;
import com.chargeops.service.StaffService;
import com.chargeops.vo.StaffLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端认证（登录）
 *
 * <h2>★ 为什么这个类不在 {@code /api/admin/} 下面</h2>
 *
 * <p>它的路径是 {@code /api/auth/staff/login}，<b>故意落在 JWT 拦截器的
 * 拦截范围之外</b>（拦截器只盯 {@code /api/admin/**}）。
 *
 * <p>另一种常见做法是把登录放在 {@code /api/admin/staff/login}，
 * 然后在拦截器上用 {@code excludePathPatterns} 排除它。也能跑通，
 * 但那是一条<b>黑名单规则</b>，代价在于：
 *
 * <ul>
 *   <li>每新增一个「不需要登录」的接口（短信验证码、忘记密码、
 *       站点公开查询、健康检查），都要记得往排除列表里加一条</li>
 *   <li>忘加的表现是「这个接口莫名返回 401」。而排查时，
 *       你多半会先去看 Controller、看请求头、看 token 是不是过期了 ——
 *       最后才想到是拦截器配置。这个顺序很反直觉，实际会浪费不少时间</li>
 *   <li>排除列表写错一个字符（比如 {@code /api/admin/staff/login/**} 多写了个
 *       {@code /**}）不会报错，只是不生效</li>
 * </ul>
 *
 * <p>改成「登录接口放在 {@code /api/auth/} 前缀下」之后，
 * 「要不要鉴权」变成了<b>由路径前缀一眼可见</b>的事实：
 * <pre>
 *   /api/auth/**    免鉴权
 *   /api/admin/**   管理端，需 staff token
 *   /api/member/**  车主端，需 member token
 * </pre>
 * 不需要维护任何名单，也不存在「忘了加」这件事。
 * <b>能让规则变成结构的，就不要让它变成配置。</b>
 *
 * <h2>★ 一个 Controller 只放认证相关的方法</h2>
 *
 * <p>登录、登出、刷新 token、找回密码都该在这个类里，
 * 而「查运维人员列表」「改运维人员资料」属于 {@link StaffController}。
 * 按 URL 前缀切分 Controller，是和拦截器规则对齐的 ——
 * 一个类里的接口如果鉴权要求不同，那个类就很难解释清楚。
 */
@RestController
@RequestMapping("/api/auth/staff")
@Tag(name = "管理端-认证")
@Slf4j
public class AuthController {

    private final StaffService staffService;

    public AuthController(StaffService staffService) {
        this.staffService = staffService;
    }

    /**
     * 管理端登录
     *
     * <p><b>{@code @Valid} 不能少。</b>少了它，DTO 上的
     * {@code @NotBlank} 全部形同虚设 —— 请求体传 {@code {}}（两个字段都是
     * 空字符串或 null）时照样会进 Service，走到
     * {@code staffMapper.getByUsername(null)} 查一次库，返回 null，
     * 然后抛「账号不存在」。
     *
     * <p>结果是<b>能用但是错的消息</b>：用户明明什么都没填，
     * 却被告知「账号不存在」。这类「校验注解写了但没生效」的问题
     * 不会报错，只会让提示语变得莫名其妙。
     * 记住这一对是配套的：<b>DTO 上写约束，Controller 上加 {@code @Valid}</b>。
     *
     * <p>校验失败抛的是 {@code MethodArgumentNotValidException}，
     * 由 {@code GlobalExceptionHandler} 转成友好提示 ——
     * 这就是为什么那个 handler 里要专门为它写一个分支。
     */
    @PostMapping("/login")
    @Operation(summary = "管理端登录", description = "返回 JWT 令牌，后续请求放在请求头 token 中")
    public Result<StaffLoginVO> login(@RequestBody @Valid StaffLoginDTO staffLoginDTO) {
        return Result.success(staffService.login(staffLoginDTO));
    }
}

