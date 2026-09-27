package com.chargeops.controller.admin;

import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.dto.MemberStatusDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 车主管理。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * BaseContext 里是员工 id。类名带 {@code Admin} 是因为车主端已占用 {@code MemberController}。
 *
 * <p>只有 page / {id} / {id}/status：不做新建（账号来源已定为自助注册），
 * 不做硬删（车主有订单、钱包、车辆，硬删会让它们全变孤儿）。
 *
 * <p>禁用不会撤销已签发的 token，那个车主最长还能用 7 天。所以有资金影响的端点不能依赖拦截器：
 * {@code MemberService.assertActive} 有 7 处调用，{@code pay} 是刻意不查的那一个。
 */
@RestController
@RequestMapping("/api/admin/member")
@Tag(name = "管理端-车主管理")
@Slf4j
public class MemberAdminController {

    private final MemberService memberService;

    public MemberAdminController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** {@code phone} 前缀匹配（两侧 {@code %} 会让 {@code uk_member_phone} 用不上），{@code nickname} 模糊匹配 */
    @GetMapping("/page")
    @Operation(summary = "分页查询车主")
    public Result<PageResult> page(@Valid MemberPageQueryDTO query) {
        return Result.success(memberService.page(query));
    }

    /** 和车主端 {@code GET /api/member/me} 是同一个 VO，结构上就没有密码字段 */
    @GetMapping("/{id}")
    @Operation(summary = "查询车主详情")
    public Result<MemberVO> getById(@PathVariable Long id) {
        return Result.success(memberService.getById(id));
    }

    /** 禁用不会撤销已签发的 token，不是「立刻踢下线」；这也是管理端唯一改车主行为的接口 */
    @PutMapping("/{id}/status")
    @Operation(summary = "启用/禁用车主",
            description = "注意：禁用不会撤销已签发的 token，最长 7 天内旧 token 仍可用")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody MemberStatusDTO memberStatusDTO) {
        memberService.updateStatus(id, memberStatusDTO.getStatus());
        return Result.success();
    }
}
