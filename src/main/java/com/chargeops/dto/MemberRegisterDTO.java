package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 车主自助注册的请求体
 *
 * <h2>★ 一、手机号在这里是<b>严格</b>校验的 —— 和 {@code staff.phone} 正相反</h2>
 *
 * <p>两端用的是同一个字段名，但约束完全不同：{@code member.phone} 是<b>登录账号</b>
 * （格式错了这个账号就<b>永远登不进去</b>），而 {@code staff.phone} 只是联系方式
 * （格式错了联系不上人，但系统照常工作），所以只有前者做严格校验。
 *
 * <p>★ 关键在于：{@code phone} 上有 {@code uk_member_phone}，所以一个打错的手机号
 * 会<b>永久占用</b>那个（错的）号码，而真正的机主来注册时会撞「已存在」。
 * 校验挡住的是这个。
 *
 * <p>★ 正则 {@code ^1[3-9]\d{9}$} 只认中国大陆号段，是一个<b>有意识的收窄</b>：
 * 平台当前只做国内业务，而收紧的代价（境外号码注册不了）在业务上是零。
 * 将来要支持境外号，改这里<b>同时</b>要改 {@code member.phone} 的列宽。
 *
 * <h2>★ 二、密码的复杂度校验<b>只在这里</b>，登录那边一个都不写</h2>
 *
 * <p>注册是「<b>设定</b>一个密码」，所以要管它够不够强；登录是「<b>比较</b>一个
 * 密码」，管的是对不对。在登录的 DTO 上加复杂度规则会让任何一条不符合当前规则的
 * 历史密码<b>永久无法登录</b>，而那些人自己毫无办法。这个不对称是刻意的。
 *
 * <p>★ 上界 {@code 32} 不是安全考虑，是<b>性能</b>考虑：BCrypt 对很长的输入仍然
 * 要完整跑一遍，而它设计得就是慢的，允许任意长度等于给了一个
 * 「用超长密码消耗 CPU」的入口。（BCrypt 本身会截断到 72 字节，
 * 所以超长输入并不会更安全 —— 只是更慢。）
 *
 * <h2>★ 三、{@code nickname} 可空，不填就是没有昵称</h2>
 *
 * <p>不给默认值（比如「车主138****0001」）—— 那是<b>展示层</b>该做的降级，
 * 不是存储层该做的事。存一个假的昵称会让「用户到底填没填」这个信息丢失。
 */
@Data
@Schema(description = "车主注册")
public class MemberRegisterDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ {@code @NotBlank} 不能省：null 不会被 {@code @Pattern} 拦下
     * （Bean Validation 的约定是<b>null 一律跳过格式校验</b>），于是它会一路走到
     * insert，撞 {@code NOT NULL} 变成 500 +「未知错误」。
     */
    @Schema(description = "手机号，登录账号", example = "13900000001")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * ★ <b>这个字段从请求体进来，出去的是哈希。</b>
     * Service 里第一件事就是 {@code passwordEncoder.encode(...)}，
     * 之后再也没有地方见过明文。
     */
    @Schema(description = "密码，6-32 位", example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度必须在 6-32 位之间")
    private String password;

    /** ★ 不填就是 null，不给编造的默认值 */
    @Schema(description = "昵称，可选", example = "老王")
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;
}
