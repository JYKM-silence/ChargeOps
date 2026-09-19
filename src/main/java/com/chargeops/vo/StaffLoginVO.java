package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 管理端登录响应
 *
 * <p>前端拿到 token 之后，后续每个请求都要把它放进请求头
 * （头名由 {@code chargeops.jwt.admin-token-name} 配置，默认 {@code token}）。
 *
 * <p><b>★ 顺带把 id / name / role 也返回去，不只是为了前端方便。</b>
 * 更重要的是它建立了一条约定：<b>登录响应里的 id 是「展示用」的，
 * 不是「鉴权用」的</b>。服务端判断「你是谁」永远只从 token 里读
 * （拦截器解出 claim 再放进 BaseContext），<b>绝不信任请求体或路径里传的 id</b>。
 *
 * <p>这条约定拦下的是一类很常见的越权漏洞：接口设计成
 * {@code GET /api/admin/staff/{id}} —— 攻击者把 id 改成别人的就能看别人的资料。
 * 正确做法是「只有「查自己」的接口不带 id，一律用 BaseContext.getCurrentId()」；
 * 真的要按 id 查别人，必须在 Service 里校验操作者的角色和所属站点。
 *
 * <p><b>为什么不返回 {@code password}：</b>这不是「记得别加」的问题，
 * 而是这个类里<b>根本没有这个字段</b>。VO 是白名单，
 * 它只能返回自己声明过的属性 —— 这就是 VO 和「把实体减几个字段」的区别，
 * 后者的默认状态是「全都能返回」，前者是「只返回我承认的」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理端登录响应")
public class StaffLoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "运维人员 id")
    private Long id;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "姓名")
    private String name;

    /** 1 平台管理员 2 站点运维 —— 前端用它决定左侧菜单显示哪些项 */
    @Schema(description = "角色：1 平台管理员 2 站点运维")
    private Integer role;

    /** 0 表示平台级。站点运维登录后前端会默认切到自己的站点 */
    @Schema(description = "负责站点 id，0 为平台级")
    private Long stationId;

    /**
     * JWT 令牌
     *
     * <p>注意这里是<b>不带 {@code Bearer } 前缀</b>的裸 token。
     * 加不加前缀本身无所谓，重要的是前端和后端必须一致 ——
     * 不一致的表现是「登录成功了，但下一个请求 401」，
     * 而两边都觉得自己是对的。本项目取请求头时是原样比对，
     * 所以前端直接放裸 token。
     */
    @Schema(description = "JWT 令牌，后续请求放在请求头里")
    private String token;
}
