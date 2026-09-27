package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 车主修改自己资料的请求体
 *
 * <h2>★★ 一、这里<b>没有</b> {@code phone}、<b>没有</b> {@code status}</h2>
 *
 * <p>这不是「还没加」，是<b>刻意的</b>，而且是这个类最重要的设计。
 * 字段不存在 ⇒ 客户端传了也会被 Jackson 直接丢掉 ⇒「改手机号」和「自助解封」
 * 这两个操作<b>在接口形状上无法表达</b>。这比在 Service 里写一句
 * {@code if (dto.getStatus() != null) throw} 强得多：那种写法依赖每个人都记得加，
 * 而这里<b>根本无从写起</b>。
 *
 * <h3>为什么不能改 {@code status}</h3>
 *
 * <p>{@code status} 是管理端的权限面。让车主能改它，管理端的「禁用」就是
 * <b>一个请求就能绕过</b>的功能。
 *
 * <h3>为什么不能改 {@code phone} —— 这个理由更重要</h3>
 *
 * <p>{@code phone} 不只是资料，它是<b>登录账号</b>（{@code uk_member_phone}）。
 * 改它不叫「编辑资料」，叫<b>账号迁移</b>，而账号迁移有一个本轮无法满足的
 * 安全前提：<b>证明对新号码的控制权</b>（短信验证码）。
 *
 * <p>★ 没有那一步会怎样：任何拿到 token 的人（Web 端一次 XSS、偷到手机、
 * token 被误打进日志）都能把账号静默换绑到一个自己控制的号码上。而因为手机号
 * <b>就是登录账号</b>，这是<b>彻底的、不可逆的账号接管</b>：改密码救不回来
 * （攻击者已经换掉登录凭据了）、token 过期也救不回来（他用新号码重新登录就行）、
 * 原机主<b>自己的号码登不进去了</b>。项目里没有任何短信服务商，
 * 所以这个前提<b>没法假装满足</b>，也没法用一个「先写死 123456」的桩糊过去
 * —— 那比不做更危险。
 *
 * <p>★ <b>将来的形状</b>（写给以后加这个功能的人）：它应该是一个<b>独立接口</b>
 * {@code POST /api/member/me/phone}，请求体 {@code {newPhone, code}}，
 * 依赖短信链路。<b>不要给这个 DTO 加一个字段就完事</b> —— 因为它的校验是
 * <b>异步的</b>（要等短信回来），而这里每个字段的校验都是同步的，
 * 混在一起会让这个类的语义变得说不清。
 *
 * <h2>★ 二、局部更新的已知限制：<b>没法把字段清空</b></h2>
 *
 * <p>{@code MemberMapper.xml} 的 {@code <set>} + {@code <if>} 下，传 null 的字段
 * 直接不出现在 SQL 里，所以「把昵称删掉」办不到 —— 传 {@code null} 是「不改」，
 * 不是「改成空」。这是全项目统一的取舍（{@code StationMapper.update} 有完整论证），
 * 修法是 {@code BeanMergeUtils.copyNonNullProperties} + 去掉 {@code <if>}
 * 的全行更新，等真有需求时统一改。
 *
 * <h2>★ 三、空串和 null 的区别</h2>
 *
 * <p>{@code nickname} / {@code avatar} 在 XML 里判的是 {@code != null and != ''} ——
 * 两者都表示「不改」。★ 这是有意的：如果空串能清空字段，前端一次「表单没填」
 * （很多表单库会把空输入框序列化成 {@code ""} 而不是省略）就会把昵称抹掉。
 *
 * <p>{@code gender} 只判 {@code != null}，因为它的合法值里有 {@code 0}（未知），
 * 「没传」和「传了 0」必须区分开。
 */
@Data
@Schema(description = "车主修改资料")
public class MemberUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 昵称。★ 传空串或 null 都表示「不改」 */
    @Schema(description = "昵称", example = "老王")
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /**
     * ★ <b>这一项只收 URL，本接口不做上传</b> —— 和 {@code ChargePackageDTO.image}
     * 同一个约定。上传走 {@code POST /api/member/upload}，把返回的 URL 填到这里。
     *
     * <p>★ 服务端不校验可达性，也不校验归属：客户端传什么就存什么。
     * <b>上传接口的存在不等于这一项被约束了</b> ——
     * 传一个任意的外链、或者别人上传出来的 URL，同样会被原样存下来。
     */

    @Schema(description = "头像地址", example = "https://cdn.example.com/avatar/1.png")
    @Size(max = 255, message = "头像地址不能超过 255 个字符")
    private String avatar;

    /**
     * ★ 值域用 {@code @Min/@Max} 卡死在 0-2，而不是留给 Service 去
     * {@code Gender.of()} 抛异常：这样错误提示是「性别只能是 0未知、1男、2女」，
     * 而不是「未知的性别：7」。两处都校验是有意的 —— 这里是给调用方的提示，
     * 那里是兜底。
     */
    @Schema(description = "性别：0未知 1男 2女", example = "1")
    @Min(value = 0, message = "性别只能是 0未知、1男、2女")
    @Max(value = 2, message = "性别只能是 0未知、1男、2女")
    private Integer gender;
}
