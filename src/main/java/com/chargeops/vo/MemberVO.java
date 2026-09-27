package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 车主信息（不含密码）
 *
 * <p>{@code GET /api/member/me} 和 {@code GET /api/admin/member/{id}} 都返回它。
 *
 * <h2>★ 一、为什么车主端和管理端共用同一个 VO</h2>
 *
 * <p>因为<b>管理员能看到的东西，车主自己都能看到</b>，两边需要暴露的字段集合完全相同。
 * 为管理端单独做一个 {@code MemberAdminVO} 只会多一份需要同步维护的清单，
 * 而它和管理端的差异<b>今天是零</b>；将来最可能出现的差异是「管理员能多看点东西」
 * （比如钱包余额），那时候再拆。现在共用还有一个直接好处：{@code /me} 和
 * {@code /api/admin/member/{id}} 返回的结构一致，前端的管理端页面可以直接复用
 * 车主端的渲染逻辑。
 *
 * <h2>★ 二、{@code status} 是给<b>车主端</b>用的，不只是管理端</h2>
 *
 * <p>背景：<b>禁用车主不会撤销已签发的 token</b>（拦截器只验签名、从不查库，
 * member token 的 TTL 是 7 天），所以一个刚被禁用的车主手上的 token 还能用最长 7 天。
 *
 * <p>★ 而 {@code GET /api/member/me} 是前端的<b>启动探针</b> —— 小程序打开时先拿本地
 * token 调一次它。只要它在响应里带上 {@code status}，一个<b>行为正常的</b>客户端就能在
 * 启动时看到 {@code status = 0} 并主动强制登出。这不能替代服务端强制（恶意客户端
 * 完全可以忽略这个字段），但它把「我们要接受 7 天窗口」变成了「<b>我们要接受 7 天窗口，
 * 只有那些从不调 /me 的客户端例外</b>」。这就是 {@code status} 值得放进车主自己的 VO 的原因。
 *
 * <h2>★ 三、没有 {@code createUser} / {@code updateUser}</h2>
 *
 * <p>这两个审计字段是<b>给运营排查用的</b>，对车主和管理界面都没有展示价值。
 * 而且它们在这里有一个额外的麻烦：{@code member.update_user} 是<b>多态的</b>
 * （车主自己改资料存 member.id，管理员禁用他存 staff.id），直接返回一个裸 id
 * 会让前端以为那是车主 id 而去查车主列表。完整论证见 {@code MemberMapper.updateStatus}。
 *
 * <p>★ 管理端真要展示「最后操作人」，正确做法是在 Service 里把它<b>解析成一个名字</b>
 * 再返回（照 {@code StationTariffVO.templateName} 那种补名字的做法），
 * 而不是甩一个 id 出去。
 */
@Data
@Schema(description = "车主信息（不含密码）")
public class MemberVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "车主 id")
    private Long id;

    @Schema(description = "手机号，登录账号")
    private String phone;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "性别：0未知 1男 2女")
    private Integer gender;

    /**
     * 性别的中文标签
     *
     * <p>★ 为什么既返回原始值又返回标签：原始值给前端做逻辑判断（比如「性别未填就提示
     * 完善资料」），标签给前端直接渲染。让前端自己维护一份 {@code 0→未知} 的映射，
     * 等于把枚举定义复制到了另一个代码库里。
     *
     * <p>★ 派生字段的降级策略：由 {@code Gender.of()} 派生，但<b>查不到标签时返回 null
     * 而不是抛异常</b>（照 {@code StationServiceImpl.labelOf} 的做法）——
     * 只读路径上，一行脏数据不该让整个接口 500。<b>写路径严格，读路径降级</b>。
     */
    @Schema(description = "性别标签：未知/男/女")
    private String genderLabel;

    /** ★ 对车主端而言它是<b>启动探针的判断依据</b>，见类注释第二节：{@code /me} 里看到 {@code 0} 就应该强制登出 */
    @Schema(description = "状态：1正常 0禁用")
    private Integer status;

    @Schema(description = "状态标签：正常/禁用")
    private String statusLabel;

    /** 最后一次登录成功的时间。★ 只在签发 token 时更新，不代表「最近活跃」 */
    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginTime;

    /**
     * ★ 格式 {@code yyyy-MM-dd HH:mm:ss} 由 {@code JacksonConfiguration} 统一配置，
     * 不要在字段上加 {@code @JsonFormat} —— 那会让同一个响应里出现两种时间格式。
     */
    @Schema(description = "注册时间")
    private LocalDateTime createTime;
}
