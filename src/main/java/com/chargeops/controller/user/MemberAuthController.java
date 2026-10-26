package com.chargeops.controller.user;

import com.chargeops.dto.MemberLoginDTO;
import com.chargeops.dto.MemberRegisterDTO;
import com.chargeops.exception.AccountNotFoundException;
import com.chargeops.exception.LoginFailedException;
import com.chargeops.exception.PasswordErrorException;
import com.chargeops.result.Result;
import com.chargeops.service.LoginRateLimitService;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车主端认证（注册 / 登录）。路径 {@code /api/auth/member/**}，不经过 JWT 拦截器。
 *
 * <p>{@code controller.user} 包对应 URL 段 {@code /api/member/**}，包名和 URL 段不同名是这个包的
 * 统一约定（这里因为要和 {@code /api/auth/admin} 对称才用了 {@code member}），别顺手统一。
 *
 * <p>类名不能改成 {@code AuthController}：Spring 的默认 bean 名是简单名首字母小写、不看包名，
 * 和 {@code controller/admin/AuthController} 撞名后容器启动时抛 {@code ConflictingBeanDefinitionException}，
 * 而编译期完全正常。同一个坑见 {@code MemberAdminController}。
 *
 * <p>注册即登录，和登录共用 {@code MemberLoginVO} 和同一个 {@code issueToken}。
 */
@RestController
@RequestMapping("/api/auth/member")
@Tag(name = "车主端-认证")
@Slf4j
public class MemberAuthController {

    private final MemberService memberService;
    private final LoginRateLimitService loginRateLimitService;

    public MemberAuthController(MemberService memberService, LoginRateLimitService loginRateLimitService) {
        this.memberService = memberService;
        this.loginRateLimitService = loginRateLimitService;
    }

    /** 手机号重复不预检，由 {@code uk_member_phone} 挡；{@code @Valid} 上的手机号正则是承重的，少了它打错的号码会永久占用 */
    @PostMapping("/register")
    @Operation(summary = "车主注册", description = "注册成功即登录，直接返回 JWT 令牌")
    public Result<MemberLoginVO> register(@Valid @RequestBody MemberRegisterDTO memberRegisterDTO) {
        return Result.success(memberService.register(memberRegisterDTO));
    }

    /** 「手机号没注册」和「密码错误」返回同一句，但「该账号未设置密码」单独一句 */
    @PostMapping("/login")
    @Operation(summary = "车主登录", description = "返回 JWT 令牌，后续请求放在请求头 authentication 中")
    public Result<MemberLoginVO> login(@Valid @RequestBody MemberLoginDTO memberLoginDTO,
                                       HttpServletRequest request) {
        return Result.success(loginRateLimitService.execute(
                request.getRemoteAddr(), memberLoginDTO.getPhone(),
                () -> memberService.login(memberLoginDTO),
                exception -> exception instanceof AccountNotFoundException
                        || exception instanceof PasswordErrorException
                        || exception instanceof LoginFailedException));
    }
}
