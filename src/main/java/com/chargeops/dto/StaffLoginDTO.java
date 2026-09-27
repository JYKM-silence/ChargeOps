package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端登录请求
 *
 * <p> 为什么不直接用 {@code Staff} 实体接收登录参数：用实体接收的话，
 * 请求体里可以塞进 {@code id}、{@code role}、{@code stationId}、{@code status}
 * 这些字段，Spring 会把它们一起绑上去，然后在某个你没注意的地方被写进数据库 ——
 * 比如「登录时顺便把我的 role 改成 1」。这类问题叫批量赋值（mass assignment），
 * 防御方式不是「在 Service 里记得别用那些字段」，而是让请求体在类型层面就
 * 不存在那些字段。这条规则对所有接收客户端输入的接口都成立：
 * 新增/修改站点的 DTO 里不该有 {@code createTime}，充值接口的 DTO 里不该有
 * {@code balance} —— 凡是「客户端说了不算」的字段，都不该出现在 DTO 里。
 *
 * <p> {@code message} 是给终端用户看的，所以要写成人话。不写
 * {@code message} 时 Spring 会填一句英文默认提示（"must not be blank"），
 * 前端就会把它弹给用户看。
 */
@Data
@Schema(description = "管理端登录请求")
public class StaffLoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "登录账号", example = "admin")
    @NotBlank(message = "账号不能为空")
    private String username;

    /**
     * 这个字段会出现在日志里，是个真实的风险点。
     * 调试时习惯写的 {@code log.info("登录参数：{}", dto)} 会因为 {@code @Data}
     * 生成的 {@code toString()} 把明文密码写进日志文件。
     *
     * <p>本项目的约定：登录链路的日志只记录 username，不记录整个 DTO。
     * 更彻底的做法是在这个字段上加 {@code @ToString.Exclude}（Lombok），
     * 让对方就算打了整个对象也不会泄露 —— 但没加，因为「日志里不出现密码」
     * 这条规则应该在 Service 里一眼可见。
     */
    @Schema(description = "明文密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
