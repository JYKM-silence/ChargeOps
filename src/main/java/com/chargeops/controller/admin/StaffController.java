package com.chargeops.controller.admin;

import com.chargeops.result.Result;
import com.chargeops.service.StaffService;
import com.chargeops.vo.StaffVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 运维人员管理。路径 {@code /api/admin/**}，需要携带 staff token。
 *
 * <p>{@code /me} 是前端启动时的探针：成功就进主界面，401 就跳登录页。
 * 没有它只能在某个业务接口失败时才发现登录态失效，表现是「进到首页了，但列表一直加载不出来」。
 */
@RestController
@RequestMapping("/api/admin/staff")
@Tag(name = "管理端-运维人员")
@Slf4j
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    /** 不收 id：登录者是谁只从 {@code BaseContext} 取，越权查别人的资料在形状上无法表达 */
    @GetMapping("/me")
    @Operation(summary = "查询当前登录的运维人员信息")
    public Result<StaffVO> me() {
        return Result.success(staffService.getCurrentStaff());
    }
}
