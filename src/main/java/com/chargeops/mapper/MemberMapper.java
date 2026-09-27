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
 * 泄露出去的也只是一个 null 字段</b>。两层独立生效：一层靠命名，一层靠 SQL。
 * （理由和 {@link StaffMapper} 一样：安全性最好由默认行为保证。）
 *
 * <h2>★ 二、没有 {@code deleteById} —— 这个方法的缺席是刻意的</h2>
 *
 * <p>车主被 {@code wallet}、{@code charging_order}、{@code vehicle} 三张表引用，
 * 物理删除会让它们全变成孤儿：订单还在、金额还在，但那笔钱属于谁查不到了。
 * 所以「停用」的手段是 {@code status = 0}，不是删除。
 *
 * <p>★ 这和 {@code station} / {@code charger} 的结论<b>相反</b>，而两边都对：
 * 那两处必须有硬删，因为编码建错一次就永久占用；而手机号是<b>真实世界的标识</b>，
 * 不存在「建错了要重来」的场景。
 *
 * <p>★ 测试要清理数据：用 {@code JdbcTemplate} 直接删，不要为了测试方便
 * 往这里加方法 —— 那是让测试污染设计（{@code StaffMapperTest} 就是先例）。
 *
 * <h2>★ 三、{@code updateLastLoginTime} 是本类唯一<b>不</b>标 {@code @AutoFill} 的写方法</h2>
 *
 * <p>理由见那个方法自己的注释。这里只强调一句：
 * <b>标上去不会报错，会静默地把 {@code update_user} 写成 NULL</b>。
 */
@Mapper
public interface MemberMapper {

    /**
     * 按主键查（<b>不含密码列</b>）
     *
     * <p>SQL 在 XML 里，因为要和 {@link #insert} / {@link #update} / {@link #page}
     * 共用 {@code Base_Column_List}。
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
     *
     * <p>★ 不要图省事让改密码去调 {@link #getByPhone} —— 那会让「改密码」
     * 这条路径依赖一个名为「按手机号查」的方法，语义上说不通，还多传一个参数。
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
     */
    @AutoFill(OperationType.INSERT)
    void insert(Member member);

    /**
     * 按主键更新<b>资料字段</b>（昵称 / 头像 / 性别）
     *
     * <p>★ 这条 SQL <b>不碰 {@code phone}、不碰 {@code status}、不碰 {@code password}</b>，
     * 而且是三层叠加的结果：{@code MemberUpdateDTO} 里没有这些字段、
     * {@code updateProfile} 只逐个字段拷进实体（没有整体
     * {@code BeanUtils.copyProperties}）、XML 的 {@code <set>} 里也没有这三列。
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
     *
     * <p>★ 为什么参数是实体而不是两个散参数：{@code AutoFillInterceptor}
     * 要求第一个参数是实体（见 {@link AutoFill} 的约定），收 {@code Long}
     * 的话插件会拿它去反射找 {@code setUpdateTime(LocalDateTime)}，
     * 找不到就抛 {@code IllegalStateException} ——
     * <b>运行时错误，不是启动错误</b>，只要没人调用就永远发现不了。
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
     * <p>★ 又是一个「只写一列」的窄语句，同样收实体（理由同 {@link #updatePassword}）。
     * <b>不做成「给 {@code update} 传个 status 就完了」</b>：管理端对车主的
     * 权限面<b>恰好是这 1 bit</b>，「管理端可以改车主的昵称」是一件没人批准过的事，
     * 而一个通用的 {@code update} 会让它变成顺手就能做的事。
     *
     * <p>★★ <b>{@code update_user} 这里存的是 staff id，不是 member id。</b>
     * {@code member.update_user} 因此是<b>多态的</b>（车主改自己资料存
     * member.id，管理员禁用他存 staff.id），而这一列没有任何东西标明是哪一个 ——
     * 排查「这个车主的昵称怎么变了」时，{@code update_user = 3} 可能是车主 3
     * 也可能是运维 3，<b>必须两张表都查一次才能确定</b>。这个代价是已知并接受的
     * （DDL 只有一列，要修就得加 {@code update_user_type}）。
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
     * 于是「这条资料最后一次被编辑是什么时候」这个问题的答案就永久失真了。
     *
     * <p><b>2. 登录成功那一刻还没有 token，{@code BaseContext} 是空的。</b>
     * 标上 {@code @AutoFill(UPDATE)} 会让插件把
     * {@code update_user = null} 写进去 —— 一个「谁改的？不知道」的审计列，
     * 而且是<b>每次登录都写一遍</b>。
     *
     * <p>★★ <b>那个 bug 的症状：HTTP 200 + {@code code:1}，一个错都不报。</b>
     * 只有几个月后有人查「这行是谁改的」，才会发现那一列变成了 NULL。
     *
     * <p>★ <b>{@code update_time} 还是会前进</b>（DDL 上有
     * {@code ON UPDATE CURRENT_TIMESTAMP(3)}），不要试图压住它 ——
     * 见 {@code Member.lastLoginTime} 的注释。
     */
    @Update("UPDATE member SET last_login_time = #{lastLoginTime} WHERE id = #{id}")
    int updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);

    /**
     * 管理端分页查询
     *
     * <p><b>★ 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，
     * 中间不能插入任何别的查询。</b>机制解释见 {@code StationMapper.page}。
     *
     * <p>★ 这个分页<b>不做任何富化</b>（不补「车辆数」「钱包余额」）。
     * 将来要补的话，那条补充查询必须放在<b>分页查询之后</b>，
     * 而且要用批量的 {@code IN}，不能循环单查。
     */
    List<Member> page(MemberPageQueryDTO query);

    /**
     * 按 id 批量查 —— <b>给其它模块的列表页补齐「这是谁」</b>
     *
     * <p>典型调用方：管理端订单列表要把 {@code member_id} 显示成手机号/昵称。
     * 那是一个<b>分页之后</b>的补充查询，一页最多 10~100 行，
     * 用一次 {@code IN} 换掉 N 次单查。
     *
     * <h3>★★ 绝对不能传空集合 ★★</h3>
     *
     * <p>{@code IN ()} 是 <b>SQL 语法错误</b>，MyBatis 会把它拼成
     * {@code WHERE id IN} 然后让 MySQL 报 {@code You have an error in your SQL syntax}。
     * 这个错误<b>不是</b>业务异常，会被兜底 handler 接住变成
     * 「未知错误」—— 而它真正的触发条件（这一页一条订单都没有）看起来
     * 跟 SQL 毫无关系，排查成本很高。
     *
     * <p>★ 所以调用方必须先判空：<b>本方法没有任何办法在 SQL 层拦下这件事</b>。
     * 先例见 {@code ChargerServiceImpl} 里补站点名的那段。
     *
     * <p>★ 返回的 {@code Member} 走 XML 的 {@code Base_Column_List}，
     * <b>不含 password</b> —— 这一条是免费的，不用额外做什么。
     */
    List<Member> listByIds(@Param("ids") List<Long> ids);

    /**
     * 按手机号<b>前缀</b>查出一批 id —— 给「按手机号搜订单」当索引入口
     *
     * <p>★ 返回的是 {@code List<Long>} 而不是 {@code List<Member>}：
     * 调用方（{@code ChargingOrderServiceImpl.pageForAdmin}）要拿它去收窄
     * {@code charging_order}，用不上 member 的其它列，而 id 列表在内存里
     * <b>比整行小一个数量级</b> —— 前缀宽的时候这个差别是真实的。
     *
     * <p>★★ <b>为什么必须有 {@code limit}。</b>★★
     * 手机号前缀 {@code 138} 在一个运营两年的平台上能命中<b>几十万行</b>。
     * 没有上限的话，这个查询会把它们全部捞进 JVM，然后拼成一个几十万项的
     * {@code IN} 列表发给 MySQL —— 那不是一个慢查询，是一次 OOM 或一次
     * {@code max_allowed_packet} 超限。
     *
     * <p>★ 上限存在的意义是<b>让调用方知道「你搜得太宽了」</b>，
     * 而不是「返回前 N 个然后假装那就是全部」——
     * 后者会给出一个<b>看起来正常的错误答案</b>。取到 {@code limit} 条时
     * 调用方应当拒绝这次查询并提示输入更多位数字，
     * 所以这里用的是 {@code LIMIT #{limit}} 而不是 {@code LIMIT limit+1}：
     * <b>「正好 N 条」和「超过 N 条」都不需要区分</b>，
     * 因为两种情况的结果都是「拒绝」。
     *
     * <p>★ 走 {@code uk_member_phone} 的前缀（{@code LIKE CONCAT(?, '%')}）。
     * 两侧 {@code %} 会让这个唯一索引完全用不上 —— 见
     * {@link #page} 的 XML 注释。
     */
    List<Long> listIdsByPhonePrefix(@Param("phone") String phone, @Param("limit") int limit);
}
