package com.chargeops.constant;

/**
 * 公共字段自动填充用到的「方法名」常量
 *
 * <p><b>为什么要把方法名写成常量：</b>
 * {@code AutoFillInterceptor} 是反射调用 setter 的
 * （{@code getDeclaredMethod("setCreateTime", LocalDateTime.class)}），
 * 方法名拼错成不存在的字符串时编译期不报错，只在运行时抛
 * {@code NoSuchMethodException}；该异常若被 catch 吞掉，
 * 表现就是「新增的记录 create_time 是 null」，且日志里什么都没有。
 * 写成常量能把「运行时的字符串错误」提前成「编译期的符号错误」。
 */
public class AutoFillConstant {

    public static final String SET_CREATE_TIME = "setCreateTime";
    public static final String SET_UPDATE_TIME = "setUpdateTime";
    public static final String SET_CREATE_USER = "setCreateUser";
    public static final String SET_UPDATE_USER = "setUpdateUser";
}
