package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.Staff;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运维人员数据访问
 *
 * <p><b>★ 这个接口在方法划分上体现了一条规则：把「会返回密码」的方法
 * 和「绝不返回密码」的方法分开，并且用不同的 SQL。</b>
 *
 * <table border="1">
 *   <tr><th>方法</th><th>是否查 password</th><th>调用者</th></tr>
 *   <tr><td>{@link #getByUsername}</td><td><b>是</b></td><td>只有登录校验</td></tr>
 *   <tr><td>{@link #getById}</td><td>否</td><td>所有其它场景</td></tr>
 * </table>
 *
 * <p>为什么不用「一个方法查全字段，调用方自己注意别返回出去」：
 * 那是把安全性寄托在<b>每一个调用方都记得</b>这件事上。
 * 而 {@code getById} 这种名字看起来就是「查一个人」，
 * 谁都可能顺手拿它去填一个返回给前端的对象。
 *
 * <p>拆成两个方法之后，「查密码」这件事在调用点上是<b>显式可见</b>的 ——
 * code review 时看到 {@code getByUsername} 就会多问一句「这里为什么需要密码」。
 * 让危险的操作用一个更扎眼的名字，比在文档里写一句「注意不要……」有效得多。
 *
 * <p><b>为什么用 {@code @Mapper} 而不是在启动类上 {@code @MapperScan}：</b>
 * 两种都能用。{@code @Mapper} 标在接口上，好处是「这个接口是 Mapper」
 * 这件事和接口本身放在一起，移动或复制文件时不会漏；
 * {@code @MapperScan} 则要求在启动类上维护一个包路径字符串，
 * 改了包名而忘了改它，表现是启动时报
 * 「找不到 StaffMapper 这个 Bean」—— 一个不会指向上面的报错。
 */
@Mapper
public interface StaffMapper {

    /**
     * 按登录账号查（<b>含密码列</b>）
     *
     * <p>★ 只给登录校验用。返回的对象里 {@code password} 是 BCrypt 密文，
     * <b>绝不能直接返回给前端，也不能塞进任何 VO 之外的响应对象</b>。
     *
     * <p>账号上有唯一索引 {@code uk_staff_username}，所以这个查询最多返回一行 ——
     * 方法直接返回 {@code Staff} 而不是 {@code List<Staff>}，
     * 免去调用方写 {@code list.get(0)} 和空判断。
     * 返回值是 {@code null} 表示账号不存在，这是正常分支不是异常。
     *
     * <p>用 {@code @Select} 写在注解上而没有放进 XML：这条 SQL 是固定的、
     * 没有动态条件、只有一行。为了它单独去 XML 里找一段
     * {@code <select id="getByUsername">}，读者要在两个文件之间跳。
     * <b>简单的固定 SQL 放注解，带动态条件的放 XML</b>，
     * 这样每种 SQL 都在「最容易被看到的地方」。
     */
    @Select("SELECT id, username, password, name, phone, sex, id_number, role, station_id, status, "
            + "create_time, update_time, create_user, update_user "
            + "FROM staff WHERE username = #{username}")
    Staff getByUsername(@Param("username") String username);

    /**
     * 按主键查（<b>不含密码列</b>）
     *
     * <p>SQL 在 XML 里，因为它要和 {@link #insert} / {@link #update} 共用一套
     * 列名清单 —— 三处写同样的列名，迟早有一处漏改。
     *
     * <p>返回 {@code null} 表示记录不存在或已被删除。
     * 调用方必须处理这个分支：<b>token 是有效的，但用户可能已经被删了</b> ——
     * 这两件事是独立的。token 只证明「签发时这个人是存在的」，
     * 从签发到使用之间管理员完全可能被删掉或者被禁用。
     */
    Staff getById(@Param("id") Long id);

    /**
     * 新增运维人员
     *
     * <p>{@link AutoFill} 会在执行前把四个审计字段填进 {@code staff}，
     * 所以 XML 里的 {@code <insert>} 不需要写 {@code create_time} 的取值逻辑。
     *
     * <p>{@code useGeneratedKeys} 让自增主键回填到 {@code staff.id} ——
     * 没有它的话插入后 id 仍然是 null，紧接着要用 id 做关联就会 NPE。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Staff staff);

    /**
     * 按主键更新非空字段
     *
     * <p>{@code UPDATE} 只会填充 {@code update_time / update_user}，
     * <b>不会碰 {@code create_*} </b>—— 见 {@link com.chargeops.mybatis.AutoFillInterceptor}。
     *
     * <p><b>★ 密码不在这条 update 的范围内。</b>
     * 改密码是一个独立接口（需要校验旧密码、需要单独审计），
     * 混在「修改资料」里会变成一个可以绕过的入口。
     */
    @AutoFill(OperationType.UPDATE)
    void update(Staff staff);
}
