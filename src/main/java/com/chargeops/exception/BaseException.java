package com.chargeops.exception;

/**
 * 所有业务异常的父类：让全局异常处理器一行兜住全部业务异常，新增异常不用去改 handler。
 *
 * <p> 继承 {@code RuntimeException} 而不是 {@code Exception}：受检异常会逼着每一层
 * {@code throws} 或写空 catch，而且 Spring 的 {@code @Transactional} 默认只对
 * RuntimeException 回滚 —— 继承 Exception 的话抛业务异常不会回滚事务。
 */
public class BaseException extends RuntimeException {

    public BaseException() {
    }

    public BaseException(String msg) {
        super(msg);
    }
}
