package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 车主（对应 {@code member} 表）
 *
 * <p>字段类型的两条约定（时间用 {@link LocalDateTime}、参与动态 SQL 的字段用包装类型）
 * 见 {@link Staff} 的类注释，不在这里重复。
 *
 * <p>{@code phone} 是唯一的登录账号（唯一索引 {@code uk_member_phone}）；{@code password} 存
 * BCrypt 密文，{@code NULL} = 验证码登录用户；{@code status} 的 {@code 1正常 0禁用} 见
 * {@code StatusConstant}，禁用不撤销已签发的 token（拦截器不查库，token TTL 7 天）。
 *
 * <p>{@code member.gender} 和 {@code staff.sex} 的取值不是一套约定，照抄会把性别存反。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String phone;

    private String password;

    private String nickname;

    /** 头像地址，只存 URL（上传走 {@code POST /api/member/upload}）；服务端不校验它，任意外链都会被原样存下来 */
    private String avatar;

    /** {@code 0未知 1男 2女} —— 见 {@link com.chargeops.enumeration.Gender} */
    private Integer gender;

    private Integer status;

    /** 最后一次登录成功的时间（不是「最近活跃」），由 {@code MemberMapper.updateLastLoginTime} 窄更新 */
    private LocalDateTime lastLoginTime;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充，setter 命名必须和 AutoFillConstant 一致
    // 自注册的行 create_user 为 NULL（create_user IS NULL ⇔ 自注册）；四列齐全，写方法可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
