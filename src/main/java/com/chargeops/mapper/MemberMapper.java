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
 * 车主数据访问。方法按「是否返回 {@code password}」切开：登录和改密码各要一个，其余场景一律不查；
 * {@code MemberMapper.xml} 的 {@code Base_Column_List} 里也没有 password，是第二层保险。
 * 没有 {@code deleteById}（刻意）：车主被 wallet / charging_order / vehicle 引用，停用手段是 {@code status = 0}。
 */
@Mapper
public interface MemberMapper {

    /** 不含密码列。返回 null 表示记录不存在 —— token 有效只证明「签发时这个 id 存在」 */
    Member getById(@Param("id") Long id);

    /** 含密码列（BCrypt 密文），只给登录校验用；{@code uk_member_phone} 保证最多返回一行 */
    @Select("SELECT id, phone, password, nickname, avatar, gender, status, last_login_time, "
            + "create_time, update_time, create_user, update_user "
            + "FROM member WHERE phone = #{phone}")
    Member getByPhone(@Param("phone") String phone);

    /** 含密码列，只给改密码验证旧密码用 —— 不要图省事让它改调 {@link #getByPhone} */
    @Select("SELECT id, phone, password, nickname, avatar, gender, status, last_login_time, "
            + "create_time, update_time, create_user, update_user "
            + "FROM member WHERE id = #{id}")
    Member getByIdWithPassword(@Param("id") Long id);

    /** 注册时 {@code BaseContext} 是空的（{@code /api/auth/**} 不过拦截器），所以 {@code create_user} 会是 NULL，这是对的 */
    @AutoFill(OperationType.INSERT)
    void insert(Member member);

    /** 不碰 {@code phone} / {@code status} / {@code password}：DTO 里没有、Service 逐个字段拷、XML 的 {@code <set>} 里也没有 */
    @AutoFill(OperationType.UPDATE)
    void update(Member member);

    /** 收实体而不是散参数：{@code AutoFillInterceptor} 要求第一个参数是实体，收 {@code Long} 会到运行时才抛 {@code IllegalStateException} */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE member SET password = #{password}, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id}")
    int updatePassword(Member member);

    /** 管理端对车主的权限面恰好是这 1 bit，所以不做成「给 {@code update} 传个 status」。{@code update_user} 这里存的是 staff id（这一列是多态的） */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE member SET status = #{status}, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id}")
    int updateStatus(Member member);

    /**
     * 唯一不标 {@code @AutoFill} 的写方法：登录成功那一刻 {@code BaseContext} 是空的，
     * 标了会把 {@code update_user} 写成 NULL，而且 HTTP 200 不报错。
     */
    @Update("UPDATE member SET last_login_time = #{lastLoginTime} WHERE id = #{id}")
    int updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);

    /** 分页调用约定见 {@link StationMapper#page}；将来要补「车辆数」之类的富化，那条查询必须放在分页查询之后 */
    List<Member> page(MemberPageQueryDTO query);

    /** 给其它列表页补「这是谁」。{@code ids} 绝对不能是空集合：{@code IN ()} 是语法错误，报出来的错和 SQL 看不出关系 */
    List<Member> listByIds(@Param("ids") List<Long> ids);

    /**
     * 手机号前缀 → 一批 id，给「按手机号搜订单」当索引入口。{@code limit} 必须有：
     * 前缀 {@code 138} 能命中几十万行；取满即由调用方拒绝这次查询，所以不需要 {@code limit+1}。
     * 走 {@code uk_member_phone} 的前缀，两侧 {@code %} 会让这个索引完全用不上。
     */
    List<Long> listIdsByPhonePrefix(@Param("phone") String phone, @Param("limit") int limit);
}
