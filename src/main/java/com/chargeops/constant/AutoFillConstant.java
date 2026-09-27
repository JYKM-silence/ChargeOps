package com.chargeops.constant;

/**
 * 自动填充用到的 setter 方法名。{@code AutoFillInterceptor} 靠反射调用 setter，
 * 方法名拼错时只在运行时抛 {@code NoSuchMethodException}，写成常量能提前到编译期。
 */
public class AutoFillConstant {

    public static final String SET_CREATE_TIME = "setCreateTime";
    public static final String SET_UPDATE_TIME = "setUpdateTime";
    public static final String SET_CREATE_USER = "setCreateUser";
    public static final String SET_UPDATE_USER = "setUpdateUser";
}
