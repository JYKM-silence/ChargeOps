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
 * 管理端 - 运维人员管理
 *
 * <p>路径 {@code /api/admin/**}，<b>需要携带 staff token</b>。
 *
 * <p>P0 阶段这里只有 {@code /me} 一个接口 —— 它存在的意义是
 * <b>把「登录 → 拿 token → 带 token 访问受保护接口」这条链路完整跑通</b>，
 * 也是验收标准第 3 条。后面运维人员的增删改查会加在这个类里。
 *
 * <h2>★ {@code /me} 这个接口为什么重要</h2>
 *
 * <p>它是前端启动时的「探针」：小程序或后台管理页面打开时，
 * 先拿本地存的 token 调一次 {@code /me}。
 * 返回成功 → 说明 token 还有效，直接进主界面；
 * 返回 401 → 说明过期了，跳登录页。
 *
 * <p>没有这个接口的话，前端只能在某个具体业务接口失败时才发现登录态失效，
 * 表现是「进到首页了，但列表一直加载不出来」——
 * 用户看到的是一个坏掉的页面，而不是一个登录页。
 *
 * <p>也正因为它是探针，<b>它的实现必须足够轻</b>（一次主键查询），
 * 不能塞进权限计算、菜单组装之类的逻辑。
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

    /**
     * 查询当前登录的运维人员信息
     *
     * <p><b>★ 注意方法上没有任何参数</b> —— 不收 id。
     * 「当前登录者是谁」由拦截器从 token 解出来放进
     * {@code BaseContext}，Service 从那里读。
     *
     * <p>这不只是为了少传一个参数，而是为了让「越权查别人的资料」
     * 在接口形状上就<b>无法表达</b>。
     */
    @GetMapping("/me")
    @Operation(summary = "查询当前登录的运维人员信息")
    public Result<StaffVO> me() {
        return Result.success(staffService.getCurrentStaff());
    }
}
