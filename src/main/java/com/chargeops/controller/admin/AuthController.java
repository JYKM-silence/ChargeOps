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
 * <h2>★ 路径是 {@code /api/auth/staff/login}，故意落在 JWT 拦截范围之外</h2>
 *
 * <p>把登录放在 {@code /api/admin/staff/login} 再用 {@code excludePathPatterns} 排除
 * 也能跑通，但那是一条<b>黑名单规则</b>：每新增一个免登录接口都要记得加一条，
 * 忘加的表现是「莫名 401」且排查顺序很反直觉；排除列表写错一个字符也不会报错，
 * 只是不生效。放在 {@code /api/auth/} 下，「要不要鉴权」就变成路径前缀一眼可见的事实
 * （详细论证见 {@code WebMvcConfiguration#addInterceptors}）。
 *
 * <h2>★ 一个 Controller 只放认证相关的方法</h2>
 *
 * <p>登录、登出、刷新 token、找回密码都在这个类里，而「查运维人员列表」
 * 「改运维人员资料」属于 {@link StaffController}。按 URL 前缀切分 Controller
 * 是和拦截器规则对齐的 —— 一个类里的接口如果鉴权要求不同，那个类就很难解释清楚。
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
     * <p><b>★ {@code @Valid} 不能少。</b>少了它 DTO 上的 {@code @NotBlank} 全部形同虚设：
     * 传 {@code {}} 照样进 Service，查一次库返回 null，最后抛「账号不存在」——
     * 用户什么都没填却被告知账号不存在。<b>DTO 上写约束，Controller 上加 {@code @Valid}</b>，
     * 这一对是配套的。校验失败抛的 {@code MethodArgumentNotValidException} 由
     * {@code GlobalExceptionHandler} 转成友好提示。
     */
    @PostMapping("/login")
    @Operation(summary = "管理端登录", description = "返回 JWT 令牌，后续请求放在请求头 token 中")
    public Result<StaffLoginVO> login(@RequestBody @Valid StaffLoginDTO staffLoginDTO) {
        return Result.success(staffService.login(staffLoginDTO));
    }
}

