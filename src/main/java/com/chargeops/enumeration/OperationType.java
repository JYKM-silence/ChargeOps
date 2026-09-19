package com.chargeops.enumeration;

/**
 * 数据库操作类型，给公共字段自动填充切面用
 *
 * <p>切面靠它决定填几个字段：
 * <ul>
 *   <li>{@link #INSERT} —— 填 create_time / create_user / update_time / update_user</li>
 *   <li>{@link #UPDATE} —— 只填 update_time / update_user</li>
 * </ul>
 * 为什么 UPDATE 不填 create_*：那会把「谁创建的」改成「谁最后改的」，
 * 是一类查起来极痛苦的数据污染 —— 字段还在，值也合法，只是语义被悄悄换掉了。
 *
 * <p><b>注意不要和 {@code period_type}（尖峰平谷）之类的业务枚举混淆。</b>
 * 这个枚举只服务于「切面该填哪些审计字段」这一个技术目的，
 * 它和充电业务没有任何关系。
 */
public enum OperationType {

    /** 更新操作 */
    UPDATE,

    /** 插入操作 */
    INSERT
}
