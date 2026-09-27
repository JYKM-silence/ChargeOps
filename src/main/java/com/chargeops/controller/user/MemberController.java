package com.chargeops.controller.user;

import com.chargeops.dto.MemberPasswordDTO;
import com.chargeops.dto.MemberUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车主端 - 我的信息。路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 保护，
 * BaseContext 里是车主 id。
 *
 * <p>三个端点的路径段都是字面量 {@code me}，没有 {@code {id}}：「修改任意一个车主」
 * 在这个 Controller 的接口形状里无法表达，服务端也就不用写 {@code if (id != currentId)} 那种判断。
 *
 * <p>{@code GET /me} 是前端的启动探针，响应里的 {@code status} 让行为正常的客户端
 * 看到「我被禁用了」并主动登出；这不替代服务端强制（禁用不撤销已签发的 token）。
 */
@RestController
@RequestMapping("/api/member")
@Tag(name = "车主端-我的信息")
@Slf4j
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** token 有效 ≠ 车主还在：这里会拿 {@code BaseContext} 里的 id 重新查一次库，查不到就抛「车主不存在」 */
    @GetMapping("/me")
    @Operation(summary = "查询当前登录车主")
    public Result<MemberVO> me() {
        return Result.success(memberService.getCurrentMember());
    }

    /** 改不了手机号和状态：{@code MemberUpdateDTO} 里没有那两个字段，客户端传了会被 Jackson 丢掉 */
    @PutMapping("/me")
    @Operation(summary = "修改当前登录车主的资料")
    public Result<Void> updateProfile(@Valid @RequestBody MemberUpdateDTO memberUpdateDTO) {
        memberService.updateProfile(memberUpdateDTO);
        return Result.success();
    }

    /** 必须验旧密码：不验的话，一个被偷走的 token 就等于永久账号接管 */
    @PutMapping("/me/password")
    @Operation(summary = "修改当前登录车主的密码")
    public Result<Void> changePassword(@Valid @RequestBody MemberPasswordDTO memberPasswordDTO) {
        memberService.changePassword(memberPasswordDTO);
        return Result.success();
    }
}
