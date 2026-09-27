package com.chargeops.enumeration;

/**
 * 数据库操作类型，给公共字段自动填充切面用
 *
 * <p>切面靠它决定填几个字段：{@link #INSERT} 填四个审计字段，
 * {@link #UPDATE} 只填 {@code update_time} / {@code update_user}。
 * UPDATE 不填 {@code create_*} —— 那会把「谁创建的」改成「谁最后改的」，
 * 是一类查起来极痛苦的数据污染：字段还在，值也合法，只是语义被悄悄换掉了。
 *
 * <p>★ 它只服务于「切面该填哪些审计字段」这一个技术目的，
 * 不要和 {@code period_type}（尖峰平谷）之类的业务枚举混淆。
 */
public enum OperationType {

    UPDATE,

    INSERT
}
