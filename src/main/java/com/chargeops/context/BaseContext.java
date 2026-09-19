package com.chargeops.context;

/**
 * 当前登录者的身份上下文（ThreadLocal）
 *
 * <p><b>它解决什么问题：</b>
 * 业务层到处都需要「当前是谁在操作」—— 例如结算时要往
 * {@code wallet_transaction} 里记是谁触发的、{@code charging_order.update_user} 填谁。
 * 如果一路从 Controller 往下传参，那 Service / Mapper 的每个方法签名都要多一个 Long，
 * 而这些方法和「谁调用的」其实无关。
 *
 * <p><b>它怎么被填上：</b>
 * 只有 JWT 拦截器会调用 {@link #setCurrentId}，且只在 token 校验通过之后。
 * 也就是说 <b>这里拿到的 id 一定来自签名有效的 token</b>，不可伪造。
 * 这一点很关键 —— 它意味着「从 BaseContext 取 id」可以当作可信的身份来源，
 * 而「从请求参数里取 memberId」绝对不能。
 *
 * <p><b>本项目的身份约定（读代码前必须知道）：</b>
 * 平台里有两类登录者，但它们是**互斥**的：
 * <ul>
 *   <li>管理端 {@code /api/admin/**} 放的是 {@code staff.id}（运维人员）</li>
 *   <li>车主端 {@code /api/member/**} 放的是 {@code member.id}（车主）</li>
 * </ul>
 * 两端的路径前缀不重叠，一个请求只会经过其中一个拦截器，
 * 所以一个 ThreadLocal 存一个 Long 就够，不会同时有两种身份。
 *
 * <p><b>代价是这个不变式靠约定维持：</b>如果以后有人把管理端接口挂到
 * {@code /api/member/**} 底下，从 BaseContext 拿到的就是 staff.id，
 * 而被当成 member.id 用 —— 不会报错，只是数据错。
 * 真要打破这个约定时，应该把这里改成存一个带类型标记的对象，
 * 而不是继续往一个 Long 上叠语义。
 *
 * <p><b>为什么必须 removeCurrentId：</b>
 * Tomcat 用线程池处理请求，线程用完是还回池里的。ThreadLocal 不清的话：
 * <ol>
 *   <li><b>数据错乱</b>：下一个请求复用同一根线程，读到上一个请求的 id。
 *       最危险的是走「拦截器放行的路径」的请求 —— 没人覆盖这个值，
 *       于是业务层拿到别人的 id 并安静地写进数据库</li>
 *   <li><b>内存泄漏</b>：ThreadLocalMap 的 key 是弱引用、value 是强引用，
 *       线程一直活着的话这条 entry 就一直挂着</li>
 * </ol>
 * 清理写在拦截器的 afterCompletion 里（只有 preHandle 返回 true 才会走到，
 * 也就是只有确实 set 过的请求才会清，逻辑刚好对上）。
 */
public class BaseContext {

    private static final ThreadLocal<Long> THREAD_LOCAL = new ThreadLocal<>();

    /**
     * 存当前登录者 id。
     *
     * <p>老库把字段直接暴露成 {@code public static ThreadLocal<Long> threadLocal}，
     * 任何代码都能 {@code BaseContext.threadLocal.set(任意值)} 绕过拦截器伪造身份。
     * 改成 private + 方法之后，写入路径只剩 {@link #setCurrentId} 一个，
     * 审计「谁可能设过这个值」时用 IDE 的 Find Usages 就能一眼看全。
     */
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
