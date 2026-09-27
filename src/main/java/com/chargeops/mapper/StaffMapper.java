package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.Staff;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运维人员数据访问。会返回 {@code password} 的方法和不返回的方法分开、用不同的 SQL ——
 * 安全性由默认行为保证，不靠每个调用方都记得别把它返回出去。
 */
@Mapper
public interface StaffMapper {

    /** 含密码列（BCrypt 密文），只给登录校验用。{@code uk_staff_username} 保证最多一行，返回 null = 账号不存在 */
    @Select("SELECT id, username, password, name, phone, sex, id_number, role, station_id, status, "
            + "create_time, update_time, create_user, update_user "
            + "FROM staff WHERE username = #{username}")
    Staff getByUsername(@Param("username") String username);

    /** 不含密码列，SQL 在 XML 里（要和 {@link #insert} / {@link #update} 共用列清单）。返回 null = 记录不存在 */
    Staff getById(@Param("id") Long id);

    /** {@link AutoFill} 会在执行前把四个审计字段填进 {@code staff}，XML 里不需要写取值逻辑 */
    @AutoFill(OperationType.INSERT)
    void insert(Staff staff);

    /** 密码不在这条 update 的范围内，改密码是独立接口（要校验旧密码） */
    @AutoFill(OperationType.UPDATE)
    void update(Staff staff);
}
