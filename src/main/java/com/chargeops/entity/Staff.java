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
 * <p><b>为什么一张表装两种角色：</b>
 * 本表用 {@code role} 区分「平台管理员」（跨站点）和「站点运维」（只管自己站）。
 * 分成两张表的话，「查这个人是谁」要在两个地方查，而登录入口只有一个 ——
 * 鉴权链路会从一条变成两条，每加一个接口都要想「这个接口两种角色怎么处理」。
 * 一张表 + 一个 role 字段 + {@code station_id}（0 表示平台级）已经够表达了。
 *
 * <p><b>★ 字段类型的两个约定，写实体时最容易踩：</b>
 *
 * <p><b>1. 时间一律 {@link LocalDateTime}，不用 {@code Date}。</b>
 * 数据库列类型是 {@code datetime(3)}（毫秒精度），对应 Java 的 {@code LocalDateTime}。
 * {@code java.util.Date} 带时区语义、可变、且格式化是线程不安全的，
 * 而本项目所有业务时间都由 Java 侧 {@code LocalDateTime.now()} 生成
 * （见风险清单 B7），用 {@code Date} 会在「JDBC 驱动怎么把它写进 datetime 列」
 * 这一层引入一个隐式时区换算。
 *
 * <p><b>2. {@code status} 用 {@link Integer} 而不是 {@code int}。</b>
 * 看起来多此一举，但有个具体的后果：MyBatis 的 {@code <if test="status != null">}
 * 判断的是包装类型。如果字段声明成 {@code int}，它永远不为 null，
 * 于是「不传 status」和「传 status=0」在动态 SQL 里无法区分 ——
 * 而后者是一个合法的业务值（禁用）。结果是改状态时把没传的值也一起写了。
 * <b>凡是要参与动态 SQL 条件判断的字段，一律用包装类型。</b>
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

    /**
     * BCrypt 密文，形如 {@code $2a$10$...}，固定 60 字符
     *
     * <p>★ <b>这个字段永远不能出现在任何返回给前端的对象里。</b>
     * 所以查询接口要么在 SQL 里就不 select 它，要么必须经过一个 VO 转换。
     * 直接返回实体是这类泄露最常见的来源 —— 加个字段、忘了排除，密码哈希就出去了。
     * 哈希本身不可逆，但它是一个<b>可以离线无限次尝试爆破</b>的目标，
     * 泄露的代价远大于「反正他也解不开」。
     *
     * <p>本项目的做法：查询接口返回 {@code StaffVO}（无此字段），
     * 只有登录校验这条路径会真正读它。
     */
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

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，命名必须和 AutoFillConstant 里的常量一致：
    // setCreateTime / setUpdateTime / setCreateUser / setUpdateUser
    //
    // ★ 这四个 setter 是 Lombok @Data 生成的。如果哪天把审计字段抽到基类，
    //   填充仍然能工作 —— 插件用的是 getMethod（沿继承链查）而不是 getDeclaredMethod

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
