package com.chargeops.context;

/**
 * 当前登录者的身份上下文（ThreadLocal）
 *
 * <p> 身份约定：{@code /api/admin/**} 放的是 {@code staff.id}，{@code /api/member/**} 放的是
 * {@code member.id}；两端路径前缀不重叠，一个请求只会经过其中一个拦截器，所以一个 Long 够用。
 * 这个不变式靠约定维持，挂错前缀时不报错、只是数据错。
 *
 * <p> 只有 JWT 拦截器在 token 校验通过之后才会写它，所以这里取到的 id 可以当可信身份来源。
 * 必须清理：Tomcat 线程池会复用线程，不清的话走放行路径的请求（没人覆盖这个值）
 * 会读到上一个请求的 id 并安静地写进数据库。
 */
public class BaseContext {

    private static final ThreadLocal<Long> THREAD_LOCAL = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        THREAD_LOCAL.set(id);
    }

    public static Long getCurrentId() {
        return THREAD_LOCAL.get();
    }

    public static void removeCurrentId() {
        THREAD_LOCAL.remove();
    }
}
