package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维人员（对应 {@code staff} 表）
 *
 * <p> 字段类型的两个约定，写实体时最容易踩：
 *
 * <p>1. 时间一律 {@link LocalDateTime}，不用 {@code Date}。
 * 数据库列类型是 {@code datetime(3)}，{@code java.util.Date} 带时区语义，
 * 会在「JDBC 驱动怎么把它写进 datetime 列」这一层引入隐式时区换算。
 *
 * <p>2. 参与动态 SQL 的字段一律用包装类型（{@code Integer} 而非 {@code int}）。
 * MyBatis 的 {@code <if test="status != null">} 判断的是包装类型 ——
 * 声明成 {@code int} 就永远不为 null，「不传 status」和「传 status=0（禁用）」
 * 在动态 SQL 里无法区分，结果是改状态时把没传的字段一起写了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Staff implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 登录账号，唯一索引 {@code uk_staff_username} */
    private String username;

    /** BCrypt 密文。 永远不能出现在任何返回给前端的对象里：查询接口返回 {@code StaffVO}（无此字段），只有登录校验这条路径会读它 */
    private String password;

    private String name;

    private String phone;

    /** 0 女 1 男（沿用旧系统的 char(1) 约定，与 {@code gender} 的 0/1/2 不是一套） */
    private String sex;

    private String idNumber;

    /** 1 平台管理员 2 站点运维 */
    private Integer role;

    /** 负责的站点 id；{@code 0} 表示平台级（不绑定具体站点） */
    private Long stationId;

    /** 1 启用 0 禁用 —— 对应 {@code StatusConstant} */
    private Integer status;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充，setter 命名必须和 AutoFillConstant 一致：
    // setCreateTime / setUpdateTime / setCreateUser / setUpdateUser

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
