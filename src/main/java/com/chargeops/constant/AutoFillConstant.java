package com.chargeops.constant;

/**
 * 公共字段自动填充用到的「方法名」常量
 *
 * <p><b>为什么要把方法名写成常量：</b>
 * {@code AutoFillInterceptor} 是通过反射调用 setter 的，
 * {@code entity.getClass().getDeclaredMethod("setCreateTime", LocalDateTime.class)}。
 * 方法名写错成一个不存在的字符串时，编译期完全不会报错 ——
 * 只有运行时抛 {@code NoSuchMethodException}，而那个异常如果被 catch 吞掉，
 * 表现就是「新增的记录 create_time 是 null」，且日志里什么都没有。
 *
 * <p>写成常量之后，拼错名字会在编译期就被发现（引用了一个不存在的常量）。
 * 这是把「运行时的字符串错误」提前成「编译期的符号错误」。
 */
public class AutoFillConstant {

    /** 实体类中的方法名称 */
    public static final String SET_CREATE_TIME = "setCreateTime";
    public static final String SET_UPDATE_TIME = "setUpdateTime";
    public static final String SET_CREATE_USER = "setCreateUser";
    public static final String SET_UPDATE_USER = "setUpdateUser";
}
