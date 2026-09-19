package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电站（对应 {@code station} 表）
 *
 * <h2>★ 一、{@code openTime} / {@code closeTime} 用 {@link String} 而不是 {@code LocalTime}</h2>
 *
 * <p>因为 {@code close_time} 的默认值和常见取值是 <b>{@code '24:00:00'}</b> ——
 * 而 {@code LocalTime} 的上限是 {@code 23:59:59.999999999}，<b>它装不下这个值</b>。
 *
 * <p>更本质的原因是：<b>MySQL 的 {@code TIME} 不是「一天中的时刻」，而是有符号时长</b>
 * （范围 {@code -838:59:59} 到 {@code 838:59:59}）—— 这才是 {@code '24:00:00'}
 * 能存进去的原因。把它映射成 {@code LocalTime} 是范畴错误，
 * 只是在我们常用的取值范围内恰好能用而已。
 *
 * <p>不用 {@code LocalTime.MAX} 当哨兵：它会序列化成 {@code "23:59:59"}，
 * 于是管理界面上会显示「本站 23:59:59 关门」—— 一个迟早会被人「修正」掉的谎言。
 *
 * <p>不用 {@code Integer} 分钟数：算术上更原生，但它会把「分钟」这个单位
 * 推到 API、日志、Swagger 示例、前端表单的每一处，而那些地方想看的
 * 都是 {@code "08:00:00"}。{@code String} 让转换只在一个方向发生一次
 * （见 {@link com.chargeops.utils.TimeCodec}）。
 *
 * <h2>★ 二、{@code close_time} 不能早于 {@code open_time} —— 这是 DDL 的限制，不是疏漏</h2>
 *
 * <p>校验规则是 {@code closeMinute > openMinute}，于是一对 open/close
 * <b>表达不了跨夜营业</b>：{@code 22:00 - 06:00} 这样的夜间站点写不出来。
 *
 * <p>这是从单对 open/close 的存储方式继承来的限制 —— 没有「次日」这个标记位，
 * {@code 22:00 - 06:00} 到底是当天 22 点到次日 6 点，还是别的什么，
 * 光看数据无法区分。要支持跨夜得改成两张表（营业时段表）或加一个标记列，
 * 而那个改动应该由真实需求驱动，不是现在凭空加。
 *
 * <h2>★ 三、经纬度用 {@link BigDecimal}，绝不用 {@code double}</h2>
 *
 * <p>列类型是 {@code decimal(10,6)}。{@code double} 的二进制浮点无法精确表示
 * 大多数十进制小数，写进去再读出来会漂移 —— 在经纬度上是<b>几十米</b>的误差，
 * 表现为「导航把用户导到了马路对面的小区」。
 *
 * <p>而且 {@code decimal(10,6)} 的精度是 0.11 米，两位小数就够描述一个
 * 停车场的入口。用 {@code double} 既得不到这个精度，也丢掉了「这是一个精确值」
 * 的语义。
 *
 * <h2>★ 四、{@code status} 有 3 个值，不要复用 {@code StatusConstant}</h2>
 *
 * <p>{@code 0停用 1启用 2维护中}。{@code StatusConstant} 只有 ENABLE/DISABLE 两个值，
 * 把「维护中」硬塞进 DISABLE 的语义里，读代码的人就再也无法从常量名看出真实含义。
 * 用 {@link com.chargeops.enumeration.StationStatus}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Station implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 站点编码，对外可见。唯一索引 {@code uk_station_code} */
    private String code;

    private String name;

    /** 运营商 */
    private String operator;

    private String province;

    private String city;

    private String district;

    private String address;

    /** 经度。{@code decimal(10,6)}，★ 绝不用 {@code double} */
    private BigDecimal longitude;

    /** 纬度。{@code decimal(10,6)}，★ 绝不用 {@code double} */
    private BigDecimal latitude;

    private String servicePhone;

    /**
     * 营业开始时间，形如 {@code "08:00:00"}。★ 是 {@code String} 不是 {@code LocalTime}
     *
     * <p>入库前由 Service 调 {@code TimeCodec.normalize} 补成带秒的 {@code "HH:mm:ss"}，
     * 免得库里同时存在 {@code "08:00"} 和 {@code "08:00:00"} 两种写法。
     */
    private String openTime;

    /**
     * 营业结束时间。{@code "24:00:00"} 表示营业到当天结束
     *
     * <p>★ 必须严格晚于 {@link #openTime}（按分钟数比较），
     * 所以表达不了跨夜营业 —— 原因见类注释第二节。
     */
    private String closeTime;

    /** 停车费，元/小时 */
    private BigDecimal parkingFee;

    /**
     * {@code 0停用 1启用 2维护中}
     *
     * <p>★ 用 {@link Integer} 而不是 {@code int}：参与动态 SQL 的
     * {@code <if test="status != null">}。声明成 {@code int} 的话它永远非 null，
     * 「不传 status」和「传 status=0（停用）」在 SQL 里就无法区分了 ——
     * 后者是合法的业务值，结果是改状态时把没传的字段一起写掉了。
     */
    private Integer status;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，命名必须和 AutoFillConstant 里的常量一致：
    // setCreateTime / setUpdateTime / setCreateUser / setUpdateUser
    //
    // 本表四列齐全，所以 StationMapper 的 insert/update 可以标 @AutoFill。
    // ★ 对照 Connector：那张表没有 create_user/update_user 两列，
    //   插件反射找不到 setCreateUser(Long) 会直接抛 IllegalStateException，
    //   所以绝不能标 —— 见 Connector 的类注释

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
