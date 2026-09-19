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
 * <h2>★★ 一、{@code password} 可空，而且 {@code NULL} 是一个<b>合法的设计状态</b></h2>
 *
 * <p>DDL 的列注释：「BCrypt 密文；<b>验证码登录的用户为 NULL</b>」。
 * 也就是说 {@code password IS NULL} 不是「数据坏了」，
 * 而是「这个人从来没用密码登录过，他走的是短信验证码」。
 *
 * <p><b>所有读路径都必须处理这个分支</b>，尤其是两处：
 * <ul>
 *   <li>登录：拿密码登录一个没设密码的账号，永远失败。
 *       必须给出<b>专门的提示</b>，不能混进「手机号或密码错误」——
 *       后者会让用户得到一个永远无法行动的提示，
 *       他会一直试密码而不是去用验证码。
 *       见 {@code MemberServiceImpl.login} 的第三道判断</li>
 *   <li>改密码：{@code PUT /api/member/me/password} 要求验旧密码，
 *       而 {@code matches(任意值, null)} 恒为 false，
 *       所以这类账号<b>改不了密码</b>。这是刻意的 ——
 *       见 {@code MemberPasswordDTO} 的类注释里有完整论证</li>
 * </ul>
 *
 * <p>★ 特别注意<b>不要</b>依赖 {@code passwordEncoder.matches(raw, null)} 的返回值。
 * 它现在确实返回 false（Spring 会打一行 "Empty encoded password" 然后返回 false），
 * 但那是实现细节而不是契约 —— 换成别的 {@code PasswordEncoder} 实现
 * 或者 Spring 改掉这个降级行为，判断就会静默失效。
 * <b>要判就显式 {@code == null} 判。</b>
 *
 * <h2>★★ 二、自注册的车主 {@code create_user} 是 NULL，而且这是对的</h2>
 *
 * <p>{@code AutoFillInterceptor} 执行时 {@code BaseContext} 是空的 ——
 * 注册接口在 {@code /api/auth/**} 下，拦截器根本不经过它。
 * 那种时候写 NULL 是<b>符合预期</b>的，引用那个类里的原话：
 * 「谁创建的本来就该是 NULL，而不是 0 或某个假的 id」。
 *
 * <p>还有一层更硬的机制原因：{@code AutoFillInterceptor} 在
 * {@code INSERT 执行之前}填充审计字段，而车主的 {@code id}
 * 是 INSERT 之后才回填的（{@code useGeneratedKeys}）——
 * 填充那一刻<b>没有 id 可写</b>。想让 {@code create_user} 等于自己，
 * 只能插入后再补一条 UPDATE，而那会让
 * 「{@code create_*} 只被一个机制写一次」这条不变量破掉，
 * 比留一个 NULL 糟糕得多。
 *
 * <p>★ 这个 NULL 还带来一个<b>有用</b>的信号：
 * 在 {@code member} 表上，{@code create_user IS NULL ⇔ 自注册}。
 * 将来管理端如果加了建号功能，那些行的 {@code create_user} 会是运维人员 id，
 * 两种来源一眼可分。反过来如果写成 {@code create_user = id}，
 * 这一列就永远等于主键、信息量为零。
 *
 * <h2>★ 三、{@code gender} 和 {@code staff.sex} 不是一套约定</h2>
 *
 * <p>{@code member.gender} 是 {@code tinyint} 的 {@code 0未知 1男 2女}，
 * 而 {@code staff.sex} 是 {@code char(1)} 的 {@code '0'女 '1'男}。
 * 两边的「女」分别是 2 和 '0'，<b>照抄会把性别存反</b>。
 * 详见 {@link com.chargeops.enumeration.Gender} 的对照表。
 *
 * <h2>★ 四、审计四列齐全 → 可标 {@code @AutoFill}</h2>
 *
 * <p>对照 {@code Connector} / {@code TariffRule} —— 那两张表
 * <b>没有</b> {@code create_user} / {@code update_user} 列，
 * 它们的 Mapper 绝不能标 {@code @AutoFill}，因为插件会反射调用
 * {@code setCreateUser} 并在方法不存在时抛 {@code IllegalStateException}。
 * 本表四列都在，所以 {@code insert} / {@code update} 都可以标。
 *
 * <p>★ <b>唯一的例外是改密码和记登录时间这两条</b>，理由分别见
 * {@code MemberMapper.updatePassword} 和 {@code MemberMapper.updateLastLoginTime}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Member implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 手机号，<b>唯一的登录账号</b>，唯一索引 {@code uk_member_phone}
     *
     * <p>★ 它同时是「登录凭据」和「联系方式」，这个双重身份决定了
     * <b>它不能被当作普通资料字段来改</b> —— 改它等于换登录凭据，
     * 需要先证明对新号码的控制权（短信验证）。
     * 所以 {@code MemberUpdateDTO} 里没有这个字段，详见那个类的注释。
     */
    private String phone;

    /**
     * BCrypt 密文，形如 {@code $2a$10$...}，固定 60 字符。
     * <b>可空</b>，{@code NULL} = 验证码登录用户，见类注释第一节
     *
     * <p>★ 和 {@link Staff#password} 一样，这个字段永远不能出现在
     * 任何返回给前端的对象里。落地方式是 {@code MemberMapper.xml}
     * 的 {@code Base_Column_List} 里没有 {@code password}，
     * 只有登录和改密码两条路径走单独的 SQL 去取它。
     * 于是「走 XML 返回的 Member 对象 password 恒为 null」，
     * 安全性由<b>默认行为</b>保证，而不是靠调用方记得。
     */
    private String password;

    private String nickname;

    /**
     * 头像地址
     *
     * <p>★ <b>只存 URL，不做上传</b> —— 和 {@code ChargePackageDTO.image} 同一个约定。
     * 项目里 {@code FileStorage} / {@code LocalFileStorage} 都在，
     * {@code /img/**} 的静态映射也注册了，但<b>没有任何上传接口</b>调用它们。
     * 所以这里客户端传什么就存什么，服务端不校验可达性。
     * 「文件上传接口」是已记录的缺口，见 README 的已知缺口表。
     */
    private String avatar;

    /** {@code 0未知 1男 2女} —— 见 {@link com.chargeops.enumeration.Gender} */
    private Integer gender;

    /**
     * {@code 1正常 0禁用} —— 对应 {@code StatusConstant}
     *
     * <p>★ <b>禁用不会撤销已签发的 token</b>，这是本轮已记录的缺口。
     * 拦截器只验签名、从不查库，member token 的 TTL 是 7 天，
     * 所以禁用之后最长 7 天内旧 token 仍然可用。
     * 完整的论证与修法见 {@code MemberAdminController.updateStatus} 的类注释。
     */
    private Integer status;

    /**
     * 最后一次登录成功的时间
     *
     * <p>★ <b>不代表「最后一次用这个账号」</b> —— 它只在
     * <b>签发 token 成功</b>那一刻更新。持有 7 天有效 token 的车主
     * 天天用小程序充电，这个值也可能停在 7 天前。
     * 要判断「最近活跃」不能看它，得看订单或钱包流水。
     *
     * <p>★ 更新走 {@code MemberMapper.updateLastLoginTime} 这条
     * <b>只写这一列</b>的窄语句，而不是通用的 {@code update}。理由两条：
     * <ol>
     *   <li>登录是<b>鉴权事件</b>不是数据编辑。让它推进 {@code update_time}，
     *       「这条资料最后一次被编辑是什么时候」就永久失真了 ——
     *       而审计列存在的全部意义就是回答那个问题</li>
     *   <li>登录成功那一刻<b>还没有 token</b>，{@code BaseContext} 是空的。
     *       走 {@code @AutoFill(UPDATE)} 会把 {@code update_user}
     *       写成 NULL，制造一个「谁改的？不知道」的审计列</li>
     * </ol>
     *
     * <p>★ 顺带一个要接受的后果：{@code update_time} 会<b>照常前进</b>，
     * 因为 DDL 上有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}。
     * 不要试图用 {@code update_time = update_time} 去压住它 ——
     * 那是一行看起来就像 bug 的代码，而且依赖「显式赋值时不触发
     * 自动更新」这个没人会记得的 MySQL 语义。
     * 结论：<b>{@code member.update_time} 的含义是「这一行最后一次变化」，
     * 不是「资料最后一次被编辑」。</b>将来真需要后者，加一列，别改语义。
     */
    private LocalDateTime lastLoginTime;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，命名必须和 AutoFillConstant 里的常量一致：
    // setCreateTime / setUpdateTime / setCreateUser / setUpdateUser
    //
    // ★ 自注册的行 create_user 为 NULL，理由见类注释第二节

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
