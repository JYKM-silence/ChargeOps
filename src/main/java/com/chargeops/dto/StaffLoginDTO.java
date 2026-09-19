package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端登录请求
 *
 * <p><b>★ 为什么不直接用 {@code Staff} 实体接收登录参数：</b>
 * 用实体接收的话，请求体里可以塞进 {@code id}、{@code role}、
 * {@code stationId}、{@code status} 这些字段。Spring 会把它们一起绑上去，
 * 然后在某个你没注意的地方被写进数据库 —— 比如「登录时顺便把我的 role 改成 1」。
 *
 * <p>这类问题叫<b>批量赋值（mass assignment）</b>。
 * 防御方式不是「在 Service 里记得别用那些字段」，而是<b>让请求体在类型层面
 * 就不存在那些字段</b>。DTO 就是这个「类型层面的白名单」。
 *
 * <p>这条规则对所有接收客户端输入的接口都成立：新增/修改站点的 DTO 里
 * 不该有 {@code createTime}，充值接口的 DTO 里不该有 {@code balance}。
 * 凡是「客户端说了不算」的字段，都不该出现在 DTO 里。
 *
 * <h2>校验注解为什么写在这里而不是 Service 里</h2>
 *
 * <p>{@code @NotBlank} 这类注解由 {@code @Valid} 触发，在<b>进入 Controller
 * 方法之前</b>就被 Spring 校验掉。好处有三：
 * <ol>
 *   <li>Service 里不用写 {@code if (username == null || username.trim().isEmpty())}
 *       这种重复代码</li>
 *   <li>校验失败由 {@code GlobalExceptionHandler} 的
 *       {@code MethodArgumentNotValidException} 分支统一转成友好提示</li>
 *   <li>接口文档里会自动带上「必填」标记（springdoc 读得到这些注解）</li>
 * </ol>
 *
 * <p><b>注意 {@code message} 是给终端用户看的</b>，所以要写成人话。
 * 不写 {@code message} 时 Spring 会填一句英文默认提示，
 * 前端就会把 "must not be blank" 弹给用户看。
 */
@Data
@Schema(description = "管理端登录请求")
public class StaffLoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "登录账号", example = "admin")
    @NotBlank(message = "账号不能为空")
    private String username;

    /**
     * 明文密码
     *
     * <p><b>★ 这个字段会出现在日志里，是个真实的风险点。</b>
     * 很多人调试时习惯 {@code log.info("登录参数：{}", dto)} —— 因为
     * {@code @Data} 生成了 {@code toString()}，这一行就把明文密码写进了日志文件。
     *
     * <p>本项目的约定：<b>登录链路的日志只记录 username，不记录整个 DTO。</b>
     * 更彻底的做法是在这个字段上加 {@code @ToString.Exclude}（Lombok），
     * 让对方就算打了整个对象也不会泄露。P0 没有加，因为
     * 「日志里不出现密码」这条规则应该在 Service 里一眼可见；
     * 等你写过几个接口、真的体会到「随手 log 一下」的诱惑之后，
     * 可以自己决定要不要把它变成编译期的保证。
     */
    @Schema(description = "明文密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
