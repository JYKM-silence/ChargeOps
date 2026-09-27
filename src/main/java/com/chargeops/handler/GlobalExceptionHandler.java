package com.chargeops.handler;

import com.chargeops.constant.MessageConstant;
import com.chargeops.exception.BaseException;
import com.chargeops.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p> 不加的话异常被 Spring 默认机制接管，返回带堆栈的 500，前端拿到的不是 {@code {code, msg, data}}。
 * 匹配顺序按异常类型的精确度决定、不看声明顺序，所以 {@code BaseException} 不会被 {@code Exception} 抢走。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 从数据库异常信息里抠出冲突的那个值（{@code Duplicate entry 'zhangsan' for key '...'}），
     * 让本项目的十几个唯一索引自动拥有「xxx 已存在」的提示，不必每张表写一个 handler。
     */
    private static final Pattern DUPLICATE_ENTRY_PATTERN =
            Pattern.compile("Duplicate entry '(.*?)' for key");

    /**
     * 业务异常：项目里所有自定义异常的父类，一个方法兜住全部。用 {@code warn} 而不是 {@code error}：
     * 业务异常是业务规则在正常生效（「余额不足」每天几百次），而告警通常挂在 error 上。
     */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBusinessException(BaseException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /** 参数校验失败（{@code @RequestBody} + {@code @Valid}）。不处理的话它落到兜底分支，前端看到的是「未知错误」 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                // 取的是 DTO 注解里 message 的值；没写的话 Spring 会给一句英文默认提示
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("；"));

        log.warn("参数校验失败：{}", msg);
        return Result.error(msg.isEmpty() ? MessageConstant.UNKNOWN_ERROR : msg);
    }

    /**
     * 数据库唯一约束冲突（账号重复、同一车主重复添加同一车牌号等）
     *
     * <p> 两个异常类型都挂：MySQL 驱动抛的是 {@link SQLIntegrityConstraintViolationException}，
     * 但 MyBatis-Spring 会先把它翻译成 Spring 的 {@link DuplicateKeyException}，只挂前者的话
     * 一旦被翻译掉这个 handler 永远不触发，重名照样返回 500。
     *
     * <p> 用正则取值而不是按空格 split：{@code DuplicateKeyException} 的 message 前面还拼了 SQL。
     */
    @ExceptionHandler({SQLIntegrityConstraintViolationException.class, DuplicateKeyException.class})
    public Result<Void> handleDuplicateKeyException(Exception ex) {
        String message = ex.getMessage();
        log.warn("数据库约束冲突：{}", message);

        if (message != null) {
            Matcher matcher = DUPLICATE_ENTRY_PATTERN.matcher(message);
            if (matcher.find()) {
                return Result.error(matcher.group(1) + MessageConstant.ALREADY_EXISTS);
            }
        }

        // 其余数据库异常不把原始信息透给前端：message 里可能带表名、列名甚至数据内容
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    /**
     * 兜底：捕获所有没被上面接住的异常。比如 Mapper 接口写了方法但 XML 里少了对应语句时 MyBatis
     * 抛的 {@code BindingException} 不是 {@code BaseException}，没有兜底 handler 时它返回 500 且一行日志都不打。
     *
     * <p> {@code log.error} 必须传 {@code ex} 本身：只传 {@code ex.getMessage()} 没有堆栈，等于白打。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception ex) {
        log.error("系统异常：", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
