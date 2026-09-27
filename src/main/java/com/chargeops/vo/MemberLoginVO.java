package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车主端登录响应
 *
 * <p>注册接口也返回它（注册即登录）—— 见 {@code MemberServiceImpl.register}。
 *
 * 一、{@code token} 是裸 token，不带 {@code Bearer } 前缀
 *
 * <p>车主端取 token 的请求头是 {@code chargeops.jwt.member-token-name}，
 * 默认 {@code authentication} —— 注意和管理端的 {@code token} 不是同一个头名。
 * 这个值是 {@code JwtProperties.memberTokenName} 注释里点名过的坑：
 * 它和前端必须一致，不一致的表现是「登录成功了，但下一个请求 401」。
 * 前端联调时先看这个头名，不要先怀疑 token 过期。
 *
 * 二、响应里的 {@code id} 是展示用的，不是鉴权用的
 *
 * <p>服务端判断「你是谁」永远只从 token 里读（拦截器解出 claim 再放进
 * {@code BaseContext}），绝不信任请求体或路径里传的 memberId。
 * 这条约定拦下的是一类很常见的越权漏洞：接口设计成
 * {@code GET /api/member/vehicle?memberId=3}，攻击者改成别人的就看到了别人的车。
 * 本项目的正确做法是「查自己」的接口一个 id 参数都没有，
 * 一律用 {@code BaseContext.getCurrentId()} —— 见 {@code VehicleController}。
 *
 * 三、不返回 {@code password}，这是结构上的保证
 *
 * <p>不是「记得别加」，而是这个类里根本没有这个字段。VO 是白名单，
 * 只能返回自己声明过的属性。完整论证见 {@link StaffVO} 的类注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "车主端登录响应")
public class MemberLoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "车主 id")
    private Long id;

    /**
     * 手机号
     *
     * <p> 这里返回完整手机号而不是脱敏的 {@code 138****0001}：它就是登录账号，
     * 是「我自己」的信息，脱敏没有意义。需要脱敏的是别人看到你手机号的场景，
     * 那是订单详情之类的地方。
     */
    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "昵称")
    private String nickname;

    /**
     * JWT 令牌
     *
     * <p> 有效期由 {@code chargeops.jwt.member-ttl} 决定，默认 7 天 ——
     * 比管理端长得多，因为车主端是小程序，频繁重新登录的体验很差。
     *
     * <p> 这个 7 天有一个已知的副作用：禁用车主不会撤销已签发的 token，
     * 所以禁用之后最长 7 天内旧 token 仍然可用。修法见
     * {@code MemberAdminController.updateStatus}。
     */
    @Schema(description = "JWT 令牌，后续请求放在请求头 authentication 里")
    private String token;
}
