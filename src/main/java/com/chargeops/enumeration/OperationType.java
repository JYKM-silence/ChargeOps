package com.chargeops.enumeration;

/**
 * 数据库操作类型，给公共字段自动填充用。{@link #INSERT} 填四个审计字段，{@link #UPDATE} 只填
 * {@code update_time} / {@code update_user} —— 填 {@code create_*} 会把「谁创建的」改成「谁最后改的」。
 */
public enum OperationType {

    UPDATE,

    INSERT
}
