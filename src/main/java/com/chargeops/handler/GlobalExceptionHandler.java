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
 * <p><b>为什么必须有：</b>不加的话异常会被 Spring 默认机制接管，
 * 返回一段带堆栈的 500 响应体。前端拿到的东西不是 {@code {code, msg, data}}，
 * 解析会直接报错，表现成「接口挂了」而不是「接口返回了一个错误提示」。
 *
 * <p>处理顺序由 Spring 按<b>异常类型的精确度</b>决定，不是按方法声明顺序：
 * 越具体的类型越优先。所以 {@code BaseException} 不会被下面的
 * {@code Exception} 兜底抢走。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 从数据库异常信息里抠出冲突的那个值
     *
     * <p>原始信息形如：{@code Duplicate entry 'zhangsan' for key 'staff.uk_staff_username'}
     *
     * <p>本项目用到的唯一索引有十几个（详见 DDL），这个正则让它们全部
     * 自动拥有「xxx 已存在」的友好提示，不需要为每张表写一个 handler。
     */
    private static final Pattern DUPLICATE_ENTRY_PATTERN =
            Pattern.compile("Duplicate entry '(.*?)' for key");

    /**
     * 业务异常：项目里所有自定义异常的父类
     *
     * <p>一个方法兜住全部 —— 账号不存在、密码错误、充电枪被占用、余额不足
     * 等等，新增业务异常时不需要来这里加代码（这正是 {@code BaseException}
     * 存在的意义，见那个类的注释）。
     *
     * <p><b>★ 用 {@code warn} 而不是 {@code error}。</b>
     * 这一点和老库不同。业务异常<b>不是故障</b>，是业务规则在正常生效：
     * 「该充电枪正在使用中」在两车同时扫码时必然出现，
     * 「余额不足」更是每天都会发生几百次。
     *
     * <p>用 error 级别的后果是：真正的故障（数据库连不上、空指针）
     * 淹没在一片「余额不足」里。而告警通常挂在 error 级别上 ——
     * 结果是运维每天被几百条正常业务提示叫醒，
     * 然后学会忽略这个告警，然后真正的故障也没人看。
     *
     * <p>日志分级的原则是「<b>这条日志需要谁在什么时候看到</b>」，
     * 而不是「这个请求失败了没有」。
     */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBusinessException(BaseException ex) {
        log.warn("业务异常：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 参数校验失败（{@code @RequestBody} + {@code @Valid}）
     *
     * <p>这是相比老库<b>新增</b>的一个 handler，因为本项目真的会用参数校验：
     * 充电桩的 DTO 里有大量「不能为空、必须为正数、长度不能超」的约束
     * （电量 {@code @Positive}、单价 {@code @DecimalMin}、车牌 {@code @Pattern}）。
     *
     * <p>不处理的话，校验失败抛出的是 {@code MethodArgumentNotValidException}，
     * 它既不是 {@code BaseException} 也不是数据库异常，
     * 会落到最下面的兜底分支 —— 前端看到的是「未知错误」。
     * 而用户实际需要知道的是「手机号格式不对」。
     *
     * <p><b>为什么把字段错误拼成一句话返回：</b>
     * {@code BindingResult} 里可能有多条错误，前端一般只显示一个 toast。
     * 拼成「字段名: 提示语」的形式，比只取第一条更能让用户一次改对。
     * 用 {@code String.join} 而不是 {@code Collectors.joining} 也一样，
     * 这里保留下面的写法只是为了和 stream 的写法保持一处说明：
     * <b>不要把这个 message 当作可直接展示给终端用户的中文文案</b> ——
     * 它是给开发者看的。要展示中文请在 DTO 的注解里写 {@code message = "..."}，
     * 那样取出来的就是你自己写的那句。
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
     * 数据库唯一约束冲突
     *
     * <p>典型场景：新增运维人员时账号重复、同一车主重复添加同一车牌号。
     * 不处理的话前端看到 500，而这其实是一次正常的业务反馈。
     *
     * <p><b>★ 为什么要同时挂两个异常类型 —— 这一步很容易踩坑：</b>
     * <ul>
     *   <li>MySQL 驱动抛出的原始异常是
     *       {@link SQLIntegrityConstraintViolationException}</li>
     *   <li>但 MyBatis-Spring 在 {@code SqlSessionTemplate} 里会先过一遍
     *       {@code MyBatisExceptionTranslator}，把它翻译成 Spring 自己的
     *       {@link DuplicateKeyException}</li>
     * </ul>
     * 到底哪一个最终冒到 Controller 层，取决于版本和调用路径的翻译行为。
     * 只挂前者的话，万一被翻译掉了，这个 handler <b>永远不触发</b>，
     * 重名照样返回 500，而且你会在断点里看到「异常明明抛出来了」。
     * 两个都挂上，哪种情况都能兜住。
     *
     * <p><b>取值用正则而不是按空格 split：</b>两种异常的 message 格式不一样，
     * {@code DuplicateKeyException} 的 message 前面还拼了一大段 SQL 语句，
     * 按空格切开取第 N 段会取到 SQL 片段而不是那个重复的值。
     *
     * <p><b>★ 对充电业务的额外意义：</b>这个 handler 是几道「数据库幂等闸门」
     * 的兜底（{@code uk_connector_current_order} 枪口互斥、
     * {@code uk_txn_order_type} 资金幂等、{@code uk_segment_order_start} 结算幂等）。
     * 那些地方的条件更新把冲突挡在了 SQL 层，正常情况下不会走到这里；
     * 但如果并发真的撞上了，返回的会是一句能看懂的提示，
     * 而不是一个什么都没有的 500。
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
     * <p><b>为什么必须有 —— 这是实测踩出来的：</b>
     * Mapper 接口写了方法但 XML 里少了对应的 {@code <insert>} 时，
     * MyBatis 抛的是 {@code org.apache.ibatis.binding.BindingException}
     * （{@code Invalid bound statement (not found)}），
     * 它继承自 {@code RuntimeException}，不是 {@code BaseException}。
     *
     * <p>没有兜底 handler 时，这个异常一路冒到 DispatcherServlet，
     * Spring 返回 500，<b>而且一行日志都不打</b>。
     * 排查时只有一个空白的 500，完全不知道发生了什么。
     *
     * <p>所以这里的关键不是「给前端返回什么」（统一返回未知错误就够了，
     * 真实原因不能透给前端），关键是 <b>{@code log.error} 要把完整堆栈打出来</b>。
     * 注意这里传的是 {@code ex} 本身而不是 {@code ex.getMessage()} ——
     * 后者只会打一行消息，没有堆栈，等于白打。
     *
     * <p><b>已知的取舍：</b>Spring MVC 自己的 4xx 异常
     * （比如请求体 JSON 格式错的 {@code HttpMessageNotReadableException}、
     * 路径变量类型不匹配的 {@code MethodArgumentTypeMismatchException}）
     * 也会被这里接住，于是本该返回 400 的响应变成 200 + {@code code:0}。
     * 对这个项目是划算的：前端只按 {@code {code, msg, data}} 解析，
     * 而且这些异常同样会被完整记进日志。
     *
     * <p><b>注意 {@code @Validated} 加在 Controller 类上的场景没被覆盖</b>：
     * 那种写法校验的是 {@code @PathVariable} / {@code @RequestParam} 这类方法参数，
     * 抛的是 {@code HandlerMethodValidationException}，不是上面那个
     * {@code MethodArgumentNotValidException}，会落到这里变成「未知错误」。
     * P0 没有用到那个写法，等真用到时再加一个 handler ——
     * 那时候也应该顺便把它的 {@code getAllValidationResults()} 结构看清楚再写，
     * 而不是照着记忆拍一个。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception ex) {
        log.error("系统异常：", ex);
        return Result.error(MessageConstant.UNKNOWN_ERROR);
    }
}
