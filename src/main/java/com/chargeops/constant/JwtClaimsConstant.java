package com.chargeops.constant;

/**
 * JWT 载荷里的 key 名。签发侧和校验侧共用，两边字面量不一致不会报错、只会拿到 null。
 */
public class JwtClaimsConstant {

    /** 运维人员 id（staff.id）。不叫 EMP_ID：staff 表同时含平台管理员和站点运维，靠 role 区分 */
    public static final String STAFF_ID = "staffId";

    /** 车主 id（member.id） */
    public static final String MEMBER_ID = "memberId";
}
