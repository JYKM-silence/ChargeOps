package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 车主修改密码的请求体
 *
 * <h2>★ 一、为什么是独立 DTO + 独立接口，而不是给「修改资料」加个字段</h2>
 *
 * <p>{@code StaffMapper.update} 的注释里已经立过这条规矩：改密码是一个独立接口
 * （需要校验旧密码、需要单独审计），混在「修改资料」里会变成一个可以绕过的入口。
 *
 * <p>★ 具体是<b>怎么被绕过</b>的：资料更新是<b>局部更新</b>（{@code <set>} +
 * {@code <if>}），于是「这次请求里有没有改密码」要靠 {@code password != null}
 * 判断 —— 只要将来某处补默认值、或者某个前端把上次的密码回填进表单，
 * 密码就会被<b>在车主不知情的情况下</b>改掉；而且那条路径上
 * <b>没有地方可以插进「验旧密码」这一步</b>。
 *
 * <p>拆开之后，「改密码必须验旧密码」变成了一个<b>结构上的事实</b>：这个 DTO 里有
 * {@code oldPassword}，而 {@code MemberMapper.updatePassword} 是唯一会写
 * {@code password} 列的语句。
 *
 * <h2>★★ 二、为什么必须验旧密码</h2>
 *
 * <p>不验的话，一个被偷走的 token 就等于<b>永久账号接管</b>：攻击者改掉密码，
 * 原机主再也登不进来，而 token 还有 7 天才过期，他有 7 天时间从容操作。
 * 验了之后，攻击者至少还需要知道旧密码。
 *
 * <p>★ 这不是「理论上的攻击」：token 泄露的路径很多（Web 端 XSS、共用设备、
 * 被误打进日志或监控平台），而它们的共同点是
 * <b>攻击者拿到了一个已经登录的身份，但没有密码</b>。验旧密码正是针对这个差别的。
 *
 * <h2>★ 三、不做的校验：「新密码不能与旧密码相同」</h2>
 *
 * <p>这是一条看起来很合理、实际有害的规则：它挡住的是「我改错了想改回来」
 * 这种<b>完全合法</b>的操作，而真正的价值很低（它假设用户会「改密码」
 * 来应付强制改密，但强制改密的场景本来就该在别处做）；
 * 更坏的是副作用 —— <b>把「密码没变」这个事实变成一个可探测的信号</b>。
 * 这是一次有意识的取舍，不是遗漏。
 *
 * <h2>★★ 四、未设密码的账号<b>改不了</b>密码，而且这是对的</h2>
 *
 * <p>{@code member.password} 可以是 NULL（验证码登录用户）。对这类账号，
 * {@code passwordEncoder.matches(任意值, null)} 恒为 false，所以「验旧密码」
 * 这一步永远失败 —— 他们会收到「原密码错误」。
 *
 * <p>★ 有一个很自然的「修法」是：{@code password == null} 时跳过旧密码校验、
 * 直接允许设置新密码。<b>必须拒绝它</b>，理由是具体的：能走到这个接口的唯一凭证
 * 是一个 token，而这个账号从来没设过密码，也就是说
 * <b>没有任何东西能证明来的人是机主本人</b>。跳过校验等于允许
 * 「持有一个 token」升级成「设置一个永久密码」—— 那正是第二节在防的事，
 * 只是换了个入口，<b>它会把一个 7 天的窗口变成永久接管。</b>
 *
 * <p>★ <b>正确的修法</b>：等验证码登录链路落地之后，在<b>那条已经验证过手机号的
 * 链路</b>上加「设置密码」—— 那里有「机主本人」的证据（收到的验证码），这里没有。
 * 现在没有验证码接口，所以这个缺口是<b>潜伏的</b>，不会真的伤到人。
 */
@Data
@Schema(description = "车主修改密码")
public class MemberPasswordDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ 服务端拿它和库里的 BCrypt 密文比对。它<b>不会</b>被存下来，
     * 也不该出现在任何日志里 —— {@code MemberServiceImpl.changePassword}
     * 的日志只记 memberId。
     */
    @Schema(description = "原密码", example = "123456")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * ★ 长度约束和 {@code MemberRegisterDTO.password} <b>保持一致</b>。
     * 两处不一致会造成一个具体的麻烦：注册时能设的密码，改密码时设不了
     * （或者反过来）—— 用户在两个页面看到不同的规则。
     *
     * <p>★ 这是<b>唯一</b>一处需要和注册 DTO 同步的约束，之所以没抽成共享常量：
     * {@code @Size} 的参数必须是编译期常量，抽出来只能变成一个
     * {@code public static final int}，而那<b>并不会</b>阻止两边漂移
     * （照样可以改一处不改另一处），只是让改动看起来更「正式」。
     * 写在两处 + 两处互相引用，更诚实。
     */
    @Schema(description = "新密码，6-32 位", example = "abcdef")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度必须在 6-32 位之间")
    private String newPassword;
}
