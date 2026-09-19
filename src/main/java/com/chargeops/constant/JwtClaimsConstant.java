package com.chargeops.constant;

/**
 * JWT 载荷（claims）里的 key 名
 *
 * <p>这些字符串会同时出现在「签发」和「校验」两侧：
 * 签发方 {@code claims.put(STAFF_ID, id)}，校验方 {@code claims.get(STAFF_ID)}。
 * 两边写的字面量不一致时不会报错，只会拿到 null，
 * 再往下就是 {@code Long.valueOf(null.toString())} 抛 NPE —— 而且是在拦截器里抛，
 * 表现成「所有请求 500」。所以这一份常量是签发与校验唯一的共同来源。
 *
 * <p><b>为什么不复用 token 的 subject 字段：</b>
 * 可以用，但 claim 的 key 更直观 —— 打开 jwt.io 粘贴一个 token，
 * 一眼就能看出这个 token 属于哪类身份，不用去猜 subject 里那个数字是什么。
 * 调试成本比省一个字符串高得多。
 */
public class JwtClaimsConstant {

    /**
     * 运维人员 id（staff.id）
     *
     * <p>叫 STAFF_ID 而不是 EMP_ID：本项目里 {@code staff} 表同时包含
     * 「平台管理员」和「站点运维」两种角色，用 role 字段区分。
     * 叫 emp（雇员）会让「雇员」和「运维人员」两个概念混在一起 ——
     * 而 role=1 的管理员其实并不做运维的活。
     */
    public static final String STAFF_ID = "staffId";

    /** 车主 id（member.id） */
    public static final String MEMBER_ID = "memberId";
}
