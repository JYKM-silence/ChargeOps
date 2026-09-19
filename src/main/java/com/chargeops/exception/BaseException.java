package com.chargeops.exception;

/**
 * 所有业务异常的父类
 *
 * <p><b>它存在的唯一目的是让全局异常处理器能一行兜住全部业务异常：</b>
 * <pre>
 *   &#64;ExceptionHandler(BaseException.class)
 *   public Result handle(BaseException ex) { ... }
 * </pre>
 * 没有这个父类的话，每新增一种业务异常就要去 GlobalExceptionHandler
 * 里加一个 {@code @ExceptionHandler} 方法 —— 迟早会漏，
 * 漏掉的那个会变成 500 + 一段给前端看不懂的堆栈。
 *
 * <p><b>为什么继承 RuntimeException 而不是 Exception：</b>
 * 继承 Exception 是受检异常，会让每一层调用方被迫处理它 ——
 * 从 Service 到 Controller 一路 {@code throws} 或者写空 catch。
 * 业务异常的本质是「这次操作不成功，把原因告诉用户」，
 * 它是正常流程的一部分，不是需要每一层都处理的意外情况。
 * Spring 的 {@code @Transactional} 默认也只对 RuntimeException 回滚，
 * 继承 Exception 的话抛业务异常不会回滚事务 —— 这是个很容易踩的坑。
 */
public class BaseException extends RuntimeException {

    public BaseException() {
    }

    public BaseException(String msg) {
        super(msg);
    }
}
