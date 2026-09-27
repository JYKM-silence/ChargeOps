package com.chargeops.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果
 *
 * <p><b>为什么不用 HTTP 状态码表达业务失败：</b>
 * 充电桩有很多「请求本身成功了，但业务上不允许」的情况，
 * 比如启动充电时枪已被别人占用、余额不足 —— 这些不是「接口坏了」，
 * 是业务规则在正常生效，前端需要把 msg 显示给用户。
 * 如果混用 4xx/5xx，前端的 axios 拦截器会把它们都当成异常，
 * 于是每个调用点都要写 try/catch 才能拿到提示语。
 *
 * <p><b>那么 401 为什么又是状态码：</b>
 * 因为「没登录」是拦截器层面的事，它发生在 Controller 之前，
 * 拿不到 Result 这个结构，只能设置状态码。所以本项目是：
 * <b>401 走状态码，其余业务结果一律走 code 字段</b>。
 */
@Data
public class Result<T> implements Serializable {

    /** 编码：1 成功，0 和其它数字为失败 */
    private Integer code;

    /** 错误信息，成功时为 null */
    private String msg;

    /** 数据，失败时为 null */
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 1;
        return result;
    }

    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<>();
        result.data = object;
        result.code = 1;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.msg = msg;
        result.code = 0;
        return result;
    }
}
