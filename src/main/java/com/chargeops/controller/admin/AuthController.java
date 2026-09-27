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
 * 管理端认证（登录）。路径 {@code /api/auth/staff}，故意落在 JWT 拦截范围之外：
 * 免登录靠路径前缀表达，不靠 {@code excludePathPatterns} 黑名单（写错一个字符不报错、只是不生效）。
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
     * {@code @Valid} 不能少：少了它 DTO 上的 {@code @NotBlank} 全部形同虚设，
     * 空请求体会一路走到 Service，最后回报「账号不存在」。
     */
    @PostMapping("/login")
    @Operation(summary = "管理端登录", description = "返回 JWT 令牌，后续请求放在请求头 token 中")
    public Result<StaffLoginVO> login(@RequestBody @Valid StaffLoginDTO staffLoginDTO) {
        return Result.success(staffService.login(staffLoginDTO));
    }
}

