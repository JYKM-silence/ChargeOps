package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 车主端登录的请求体
 *
 * <h2>★★ 一、字段和 {@link MemberRegisterDTO} 几乎一样，但<b>故意是两个类</b></h2>
 *
 * <p>字段几乎一样，但约束完全不同，而且这个不同是<b>刻意的</b>：
 * 注册那边 {@code phone} 有严格的 {@code @Pattern}、{@code password} 有
 * {@code @Size(6,32)}；这里两个都<b>只有 {@code @NotBlank}</b>。
 *
 * <p><b>★ 为什么登录一个复杂度规则都不写 —— 这不是偷懒：</b>
 * 注册的职责是<b>设定</b>密码，所以要管它够不够强；登录的职责是<b>比较</b>密码，
 * 管的只是对不对。一旦在登录上加了复杂度规则，任何一条<b>不满足当前规则</b>
 * 的既有密码（老的弱密码、别的渠道导入的、将来规则调整前注册的）都会
 * <b>永久无法登录</b>，而用户自己毫无办法，只能走「忘记密码」。
 * 而且这个错误的表现很坏：用户输入的密码<b>是对的</b>，
 * 却收到一句「密码长度必须在 6-32 位之间」，他会以为自己记错了。
 *
 * <p>同理手机号在这里也不做格式校验：校验失败的提示对登录<b>没有意义</b>
 * （格式不对的结果本来就是「查不到这个人」），却会挡住将来可能出现的、
 * 不符合当前正则的存量号码。
 *
 * <h2>★ 二、登录 DTO 和注册 DTO 必须分开</h2>
 *
 * <p>合并成一个 {@code MemberCredentialDTO} 是很自然的想法，
 * 但那样两边就必须共用一套约束，而上面说明它们<b>必须不同</b> ——
 * 合了之后唯一的走向是「按注册的强度校验登录」，
 * 也就是那个让存量用户登不进去的结果。
 */
@Data
@Schema(description = "车主登录")
public class MemberLoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ★ 只校验「非空 + 别超长」、<b>不校验格式</b>，理由见类注释第一节。
     * 长度上限和 {@code member.phone} 的列宽（{@code varchar(20)}）对齐 ——
     * 让它在这里被挡下，而不是进到 SQL 里被 MySQL 截断。
     */
    @Schema(description = "手机号", example = "13900000001")
    @NotBlank(message = "手机号不能为空")
    @Size(max = 20, message = "手机号长度不合法")
    private String phone;

    /**
     * ★ <b>刻意不加任何格式或长度约束</b>，理由见类注释第一节。
     * {@code @NotBlank} 还是要有 —— 空密码不应该消耗一次 BCrypt 校验。
     */
    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
