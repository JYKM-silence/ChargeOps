package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.entity.Member;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 车主数据访问
 *
 * <h2>★ 一、方法按「是否返回 {@code password}」切开，照抄 {@link StaffMapper}</h2>
 *
 * <table border="1">
 *   <caption>同一个实体，三条粒度不同的读路径</caption>
 *   <tr><th>方法</th><th>查 password 吗</th><th>谁在用</th></tr>
 *   <tr><td>{@link #getByPhone}</td><td><b>查</b></td><td>只有登录校验</td></tr>
 *   <tr><td>{@link #getByIdWithPassword}</td><td><b>查</b></td><td>只有改密码</td></tr>
 *   <tr><td>{@link #getById}</td><td>不查</td><td>其它所有场景</td></tr>
 * </table>
 *
 * <p>两个「查密码」的方法<b>各有各的调用方</b>，不是重复：登录需要它来比对，
 * 改密码需要它来验证旧密码。合成一个的话，那个方法的调用点就再也说不清
 * 「这次为什么要密码」了。
 *
 * <p>★ 还有第二层保险：{@code MemberMapper.xml} 的 {@code Base_Column_List}
 * 里<b>没有 {@code password}</b>，所以走 XML 那条路返回的 {@code Member}
 * 对象 password 恒为 null —— <b>就算哪天有人写错了把一个实体直接返回给前端，
 * 泄露出去的也只是一个 null 字段</b>。两层独立生效：
 * 一层靠命名（用到才拿），一层靠 SQL（XML 那条根本取不到）。
 *
 * <p>理由和 {@link StaffMapper} 完全一样：<b>安全性最好由默认行为保证，
 * 而不是靠每一个调用方都记得。</b>BCrypt 哈希虽然不可逆，
 * 但它是一个可以离线无限次尝试爆破的目标。
 *
 * <h2>★ 二、没有 {@code deleteById} —— 这个方法的缺席是刻意的</h2>
 *
 * <p>车主被 {@code wallet}（钱包）、{@code charging_order}（订单）、
 * {@code vehicle}（车辆）三张表引用，物理删除会让它们全变成孤儿：
 * 订单还在、金额还在，但那笔钱属于谁查不到了。
 *
 * <p>所以「停用」的手段是 {@code status = 0}，不是删除。
 * 这和管理端 {@code MemberAdminController} 只有「分页 / 详情 / 启禁用」
 * 三个接口，是同一件事的两种表述。
 *
 * <p>★ 注意这和 {@code station} / {@code charger} 的结论<b>相反</b>，
 * 而两边都是对的：那两处<b>必须</b>有硬删，因为 {@code uk_station_code}
 * 不看 {@code status}，编码建错一次就永久占用。
 * 差别在于 —— 站点编码是人为编出来的，会编错；
 * 手机号是<b>真实世界的标识</b>，不存在「建错了要重来」的场景，
 * 而硬删的代价（订单 / 钱包 / 车辆变孤儿）却真实存在。
 * 两个相反决定各自的理由都写在代码里，比统一成一条规则更有价值。
 *
 * <p>★ 测试要清理数据怎么办：用 {@code JdbcTemplate} 直接删，
 * 不要为了测试方便往这里加方法 —— 那是让测试污染设计
 * （{@code StaffMapperTest} 的 {@code jdbcTemplate} 字段就是这个先例）。
 *
 * <h2>★ 三、{@code updateLastLoginTime} 是本类唯一<b>不</b>标 {@code @AutoFill} 的写方法</h2>
 *
 * <p>理由见那个方法自己的注释。这里只强调一句：
 * <b>标上去不会报错，会静默地把 {@code update_user} 写成 NULL</b>，
 * 而且每个车主每次登录都写一遍。
 */
@Mapper
public interface MemberMapper {

    /**
     * 按主键查（<b>不含密码列</b>）
     *
     * <p>SQL 在 XML 里，因为要和 {@link #insert} / {@link #update} / {@link #page}
     * 共用一份列清单 —— 四处写同样的列名，迟早有一处漏改，
     * 而漏改的那一处如果正好是「少排除了 password」，后果就是这个类
     * 花了整个类注释在防的事。
     *
     * <p>返回 {@code null} 表示记录不存在。
     * <b>调用方必须处理这个分支：token 有效但用户可能已经不在了</b> ——
     * 这两件事是独立的，token 只证明「签发时这个 id 存在」。
     */
    Member getById(@Param("id") Long id);

    /**
     * 按手机号查（<b>含密码列</b>），登录入口
     *
     * <p>★ 只给登录校验用。返回对象的 {@code password} 是 BCrypt 密文，
     * <b>绝不能进任何返回给前端的对象</b>。
     *
     * <p>手机号上有 {@code uk_member_phone}，所以最多返回一行，
     * 方法直接返回 {@code Member} 而不是 {@code List}。
     *
     * <p>★ 走的是唯一索引，一次等值查询 —— 这正是 DDL 那句话说的
     * 「唯一索引一份成本同时满足『查询』与『约束』两件事」。
     */
    @Select("SELECT id, phone, password, nickname, avatar, gender, status, last_login_time, "
            + "create_time, update_time, create_user, update_user "
            + "FROM member WHERE phone = #{phone}")
    Member getByPhone(@Param("phone") String phone);

    /**
     * 按主键查（<b>含密码列</b>），改密码专用
     *
     * <p>★ 和 {@link #getByPhone} 的区别不是「查得不一样」，而是
     * <b>调用方的意图不一样</b>：那个是「验证你是谁」，这个是「验证旧密码对不对」。
     * 分成两个方法之后，code review 时看到这个方法名就能问一句
     * 「这里为什么需要密码」，而不是在一个万能的 {@code getById} 上猜。
     *
     * <p>★ 不要图省事让改密码去调 {@link #getByPhone}（车主知道自己手机号，
     * Service 本来也有它）—— 那会让「改密码」这条路径依赖一个
     * 名为「按手机号查」的方法，语义上说不通，还多传一个参数。
     */
    @Select("SELECT id, phone, password, nickname, avatar, gender, status, last_login_time, "
            + "create_time, update_time, create_user, update_user "
            + "FROM member WHERE id = #{id}")
    Member getByIdWithPassword(@Param("id") Long id);

    /**
     * 新增车主（自助注册）
     *
     * <p>{@link AutoFill} 填四个审计字段。★ 注册时 {@code BaseContext} 是空的
     * （{@code /api/auth/**} 不经过拦截器），所以 {@code create_user} 会是 NULL ——
     * <b>这是对的</b>，完整论证见 {@link Member} 的类注释第二节。
     *
     * <p>{@code useGeneratedKeys} 回填自增主键。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Member member);

    /**
     * 按主键更新<b>资料字段</b>（昵称 / 头像 / 性别）
     *
     * <p>★ 这条 SQL <b>不碰 {@code phone}、不碰 {@code status}、不碰 {@code password}</b>。
     * 这不是「记得排除」，而是三层叠加的结果：
     * <ol>
     *   <li>{@code MemberUpdateDTO} 里根本没有 {@code phone} / {@code status} 字段</li>
     *   <li>{@code MemberServiceImpl.updateProfile} 只把这三个字段拷进实体，
     *       没有 {@code BeanUtils.copyProperties(dto, member)} 那种整体复制</li>
     *   <li>下面 XML 的 {@code <set>} 里没有这三列</li>
     * </ol>
     * 任何一层单独失效，另外两层都还拦得住。
     *
     * <p>★ 局部更新的已知限制：<b>没法把字段清空回 NULL</b>。
     * {@code <set>} + {@code <if>} 下传 null 的字段直接不出现在 SQL 里，
     * 所以「清掉昵称」办不到。这是全项目统一的取舍，理由见
     * {@code StationMapper.update}。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Member member);

    /**
     * 改密码
     *
     * <p>★ 为什么是独立方法而不是复用 {@link #update}：改密码需要
     * <b>验旧密码</b>和<b>单独审计</b>，混进「修改资料」会变成一个可以绕过的入口。
     * {@code StaffMapper.update} 的注释里已经立过这条规矩。
     *
     * <p>★ 为什么参数是实体而不是 {@code (@Param("id") Long, @Param("password") String)}：
     * {@code AutoFillInterceptor} 要求第一个参数是实体（见 {@link AutoFill} 的约定），
     * 收 {@code Long} 的话插件会拿 {@code Long} 去反射找
     * {@code setUpdateTime(LocalDateTime)}，找不到就抛 {@code IllegalStateException}。
     * <b>这是运行时错误，不是启动错误</b> —— 只要没人调用就永远发现不了。
     *
     * <p>★ 所以这里收 {@code Member}，调用方只需要 {@code setId} + {@code setPassword}，
     * 两个审计字段由插件填。语义也是对的：改密码是车主自己的操作，
     * {@code update_user} = 他自己的 id。
     *
     * <p>★ 传进来的 {@code password} 必须是<b>已经 BCrypt 加密过</b>的密文。
     * 这个方法不做加密 —— Mapper 层的职责是「一条 SQL」，不是「一个业务规则」。
     */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE member SET password = #{password}, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id}")
    int updatePassword(Member member);

    /**
     * 管理端启用 / 禁用车主
     *
     * <p>★ 又是一个「只写一列」的窄语句，同样收实体 —— 理由和
     * {@link #updatePassword} 一样（{@code @AutoFill} 要求首参是实体）。
     *
     * <p>★ <b>为什么不做成「给 {@code update} 传个 status 就完了」：</b>
     * 因为管理端对车主的权限面<b>恰好是这 1 bit</b>。
     * 「管理端可以改车主的昵称」是一件没人批准过的事，
     * 而一个通用的 {@code update} 会让它变成顺手就能做的事。
     * 接口形状锁死权限面，见 {@code MemberAdminController}。
     *
     * <p>★★ <b>{@code update_user} 这里存的是 staff id，不是 member id。</b>
     * {@code member.update_user} 因此是<b>多态的</b>：
     * <ul>
     *   <li>车主改自己资料 → 存 member.id</li>
     *   <li>管理员禁用他 → 存 staff.id</li>
     * </ul>
     * ★ <b>两个 id 空间不同，而这一列没有任何东西标明是哪一个。</b>
     * 排查「这个车主的昵称怎么变了」时，如果 {@code update_user} 是 3，
     * 它可能是车主 3，也可能是运维 3 —— <b>必须两张表都查一次才能确定</b>。
     *
     * <p>这个代价是<b>已知并接受的</b>：DDL 只有一列，没有
     * {@code update_user_type}。要修就得加列。
     * 在真需要区分之前，至少让读它的人知道要查两次。
     */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE member SET status = #{status}, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id}")
    int updateStatus(Member member);

    /**
     * 记录一次成功登录的时间
     *
     * <h2>★★ 这个方法是本类唯一不标 {@code @AutoFill} 的写方法，两个理由都不是洁癖</h2>
     *
     * <p><b>1. 登录是「鉴权事件」，不是「数据编辑」。</b>
     * 让它走通用 {@code update} 会推进 {@code update_time}，
     * 于是「这条资料最后一次被编辑是什么时候」这个问题的答案就永久失真了 ——
     * 而审计列存在的<b>全部意义</b>就是回答那个问题。
     *
     * <p><b>2. 登录成功那一刻还没有 token，{@code BaseContext} 是空的。</b>
     * 登录接口在 {@code /api/auth/**} 下，压根不经过 JWT 拦截器。
     * 标上 {@code @AutoFill(UPDATE)} 会让插件把
     * {@code update_user = BaseContext.getCurrentId() = null} 写进去 ——
     * 一个「谁改的？不知道」的审计列，而且是<b>每次登录都写一遍</b>。
     *
     * <p>★★ <b>特别注意那个 bug 的症状：HTTP 200 + {@code code:1}，一个错都不报。</b>
     * 只有几个月后有人查「这行是谁改的」，才会发现那一列莫名其妙变成了 NULL。
     * 这不是「理论上的风险」，是这个方法<b>不写成这样就会立刻发生</b>的事。
     *
     * <p>★ <b>{@code update_time} 还是会前进</b>，因为 DDL 上有
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)}。不要试图压住它 ——
     * 见 {@code Member.lastLoginTime} 的注释。
     *
     * <p>★ 时间用 {@code LocalDateTime.now()} 在 Java 侧取，不用
     * {@code NOW()}：全项目统一由 Java 侧生成业务时间。
     */
    @Update("UPDATE member SET last_login_time = #{lastLoginTime} WHERE id = #{id}")
    int updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);

    /**
     * 管理端分页查询
     *
     * <p><b>★ 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，
     * 中间不能插入任何别的查询。</b>完整的机制解释见 {@code StationMapper.page} ——
     * 简言之 {@code startPage} 把分页参数放进 ThreadLocal，
     * 会被该线程上的<b>下一条</b> MyBatis 查询消费掉。
     *
     * <p>★ 本轮这个分页<b>不做任何富化</b>（不补「车辆数」「钱包余额」）。
     * 将来要补的话，那条补充查询必须放在<b>分页查询之后</b>，
     * 而且要用批量的 {@code IN}，不能循环单查。
     */
    List<Member> page(MemberPageQueryDTO query);
}
