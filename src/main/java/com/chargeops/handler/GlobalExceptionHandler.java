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
 * <p>不加的话异常会被 Spring 默认机制接管，返回带堆栈的 500 响应体，
 * 前端拿到的不是 {@code {code, msg, data}}，表现成「接口挂了」而不是「接口返回了一个错误提示」。
 *
 * <p>匹配顺序由 Spring 按<b>异常类型的精确度</b>决定，不是按方法声明顺序，
 * 所以 {@code BaseException} 不会被下面的 {@code Exception} 兜底抢走。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 从数据库异常信息里抠出冲突的那个值
     *
     * <p>原始信息形如：{@code Duplicate entry 'zhangsan' for key 'staff.uk_staff_username'}。
     * 本项目的唯一索引有十几个（见 DDL），这个正则让它们全部自动拥有
     * 「xxx 已存在」的提示，不必为每张表写一个 handler。
     */
    private static final Pattern DUPLICATE_ENTRY_PATTERN =
            Pattern.compile("Duplicate entry '(.*?)' for key");

    /**
     * 业务异常：项目里所有自定义异常的父类
     *
     * <p>一个方法兜住全部（账号不存在、枪被占用、余额不足……），
     * 新增业务异常不需要来这里加代码。
     *
     * <p><b>★ 用 {@code warn} 而不是 {@code error}。</b>业务异常<b>不是故障</b>，
     * 是业务规则在正常生效：「枪正在使用中」在两车同时扫码时必然出现，
     * 「余额不足」每天几百次。用 error 的后果是真正的故障（数据库连不上、空指针）
     * 淹没在一片「余额不足」里，而告警通常挂在 error 级别上 ——
     * 运维被几百条正常提示叫醒、然后学会忽略这个告警、然后真故障也没人看。
     */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBusinessException(BaseException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 参数校验失败（{@code @RequestBody} + {@code @Valid}）
     *
     * <p>不处理的话抛的是 {@code MethodArgumentNotValidException}，
     * 它既不是 {@code BaseException} 也不是数据库异常，会落到兜底分支，
     * 前端看到「未知错误」，而用户实际需要知道的是「手机号格式不对」。
     *
     * <p>把多条字段错误拼成一句话返回（前端一般只显示一个 toast），
     * 比只取第一条更能让用户一次改对。
     *
     * <p><b>★ 这个 message 不是可直接展示给用户的中文文案</b>，它是给开发者看的：
     * 想展示中文就在 DTO 注解里写 {@code message = "..."}，那样取出来的才是那句。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                // getDefaultMessage 取的是 DTO 注解里 message 的值；
                // 没写 message 时 Spring 会给一句英文默认提示，能用但不友好
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("；"));

        log.warn("参数校验失败：{}", msg);
        return Result.error(msg.isEmpty() ? MessageConstant.UNKNOWN_ERROR : msg);
    }

    /**
     * 数据库唯一约束冲突（账号重复、同一车主重复添加同一车牌号等）
     *
     * <p><b>★ 为什么同时挂两个异常类型：</b>MySQL 驱动抛的是
     * {@link SQLIntegrityConstraintViolationException}，但 MyBatis-Spring 在
     * {@code SqlSessionTemplate} 里会先经 {@code MyBatisExceptionTranslator}
     * 翻译成 Spring 的 {@link DuplicateKeyException}。最终冒到 Controller 的是哪个
     * 取决于版本和调用路径。只挂前者的话，一旦被翻译掉这个 handler <b>永远不触发</b>，
     * 重名照样返回 500，而你会在断点里看到「异常明明抛出来了」。
     *
     * <p>取值用正则而不是按空格 split：两种异常的 message 格式不同，
     * {@code DuplicateKeyException} 前面还拼了一大段 SQL，按空格取第 N 段会取到 SQL 片段。
     *
     * <p><b>★ 对充电业务的额外意义：</b>这是几道「数据库幂等闸门」的兜底
     * （{@code uk_connector_current_order} 枪口互斥、{@code uk_txn_order_type} 资金幂等、
     * {@code uk_segment_order_start} 结算幂等）。那些条件更新把冲突挡在了 SQL 层，
     * 正常不会走到这里；真撞上时返回的是一句能看懂的提示，而不是一个空白的 500。
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

        // 其余数据库异常（外键、非空约束等）不把原始信息透给前端，只记日志 ——
        // 原始 message 里可能带表名、列名甚至数据内容，那是信息泄露
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }

    /**
     * 兜底：捕获所有没被上面接住的异常
     *
     * <p><b>为什么必须有 —— 实测踩出来的：</b>Mapper 接口写了方法但 XML 里少了对应的
     * {@code <insert>} 时，MyBatis 抛的是 {@code BindingException}
     * （{@code Invalid bound statement (not found)}），它是 {@code RuntimeException}，
     * 不是 {@code BaseException}。没有兜底 handler 时它一路冒到 DispatcherServlet，
     * 返回 500 <b>而且一行日志都不打</b>，排查时只有一个空白的 500。
     *
     * <p>所以这里的关键不是「给前端返回什么」（统一返回未知错误就够了），
     * 而是 <b>{@code log.error} 必须传 {@code ex} 本身</b> —— 传 {@code ex.getMessage()}
     * 只有一行消息、没有堆栈，等于白打。
     *
     * <p><b>已知取舍：</b>Spring MVC 自己的 4xx 异常（请求体 JSON 格式错的
     * {@code HttpMessageNotReadableException}、路径变量类型不匹配的
     * {@code MethodArgumentTypeMismatchException}）也会被这里接住，
     * 于是本该 400 的响应变成 200 + {@code code:0}。对本项目划算：前端只按
     * {@code {code, msg, data}} 解析，且这些异常同样会完整记进日志。
     *
     * <p><b>未被覆盖的场景：{@code @Validated} 加在 Controller 类上。</b>
     * 那种写法校验 {@code @PathVariable} / {@code @RequestParam}，抛的是
     * {@code HandlerMethodValidationException}，会落到这里变成「未知错误」。
     * 真用到时再加 handler —— 先把它的 {@code getAllValidationResults()} 结构看清楚再写。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception ex) {
        log.error("系统异常：", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
