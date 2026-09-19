package com.chargeops.interceptor;

import com.chargeops.constant.JwtClaimsConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.properties.JwtProperties;
import com.chargeops.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端 JWT 校验拦截器（运维人员 / 平台管理员）
 *
 * <p><b>迁移说明：从 {@code javax.servlet} 换成 {@code jakarta.servlet}。</b>
 * 这是 Jakarta EE 9 那次改名（javax → jakarta）的直接后果，
 * Spring Boot 3 起全面使用 jakarta 命名空间。
 * 编译器在这件事上<b>帮不上忙</b>：{@code javax.servlet.http.HttpServletRequest}
 * 这个类在新项目里压根不存在，所以 copy 过来会直接编译报错 ——
 * 这一类迁移反而是最省心的，错了立刻知道。
 *
 * <p>真正难查的是<b>没有编译错误的那些</b>，比如配置项名称变了、
 * 某个类还在但语义变了。所以迁移时不要把「编译通过」当成「迁移完成」。
 */
@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    private final JwtProperties jwtProperties;

    /**
     * <b>构造器注入，不用 {@code @Autowired} 字段注入。</b>
     *
     * <p>老库所有依赖都是 {@code @Autowired} 字段注入，这里改了。三个理由，
     * 从「无所谓的偏好」到「真会出问题」排列：
     * <ol>
     *   <li>字段可以是 {@code final}，编译期保证它一定被赋值过，
     *       不会出现「忘了注入导致 NPE」</li>
     *   <li>单元测试可以直接 {@code new JwtTokenAdminInterceptor(props)}，
     *       不需要启动 Spring 容器或反射注入私有字段</li>
     *   <li><b>循环依赖会在启动时直接失败</b>，而不是被 Spring 的
     *       三级缓存悄悄「修好」。这看起来是坏事，其实是好事 ——
     *       循环依赖本身就是设计问题，启动时就暴露远比运行到某个分支才出问题强。
     *       老库之所以要写 {@code spring.main.allow-circular-references: true}，
     *       正是因为字段注入掩盖了循环依赖，等到升级 Boot 默认禁止时才炸出来</li>
     * </ol>
     */
    public JwtTokenAdminInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 判断当前拦截到的是 Controller 的方法还是其他资源（静态资源等）。
        // 不是 HandlerMethod 说明请求没落到某个具体的接口方法上，直接放行 ——
        // 不加这个判断的话，springdoc 加载接口文档、浏览器请求 favicon 都会被拦下来要求登录
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 从请求头中获取令牌
        String token = request.getHeader(jwtProperties.getAdminTokenName());

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
            Long staffId = Long.valueOf(claims.get(JwtClaimsConstant.STAFF_ID).toString());

            // 通过：把当前登录者的 id 存入 ThreadLocal，供业务层取用
            // （记录 create_user / update_user，以及 agent 工具层的登录闸门）
            BaseContext.setCurrentId(staffId);
            log.debug("管理端鉴权通过，staffId={}", staffId);
            return true;
        } catch (Exception ex) {
            // ★ 不打印 token 本身。
            // 老库这里写的是 log.info("jwt校验:{}", token)，
            // 把完整的 token 打进了日志 —— 而 token 就是凭证，
            // 任何能读日志的人（运维、日志平台、被误配的日志采集）都能拿它冒充这个用户
            // 直到过期。日志里应该记录「校验失败」这个事实，而不是失败时用的那把钥匙。
            //
            // 用 debug 级别而不是 info：鉴权失败在生产环境可能大量出现
            // （token 过期、爬虫乱扫），用 info 会把正常业务日志淹掉。
            log.debug("管理端鉴权失败：{}", ex.getMessage());

            // 响应 401 状态码，对应「未登录/凭证失效」。
            // 这个异常分支不区分「token 过期」和「签名不对」——
            // 对客户端来说处理方式完全一样（重新登录），
            // 而对攻击者来说，区分它们等于免费提供了「猜对了签名但过期了」的情报。
            response.setStatus(401);
            return false;
        }
    }

    /**
     * 请求结束后清理 ThreadLocal
     *
     * <p><b>必须清理，否则会同时踩两个坑：</b>
     *
     * <p><b>1. 数据错乱（更严重）。</b>Tomcat 用线程池处理请求，线程用完是还回池子里的，
     * 下一个请求可能还是这根线程。ThreadLocal 没清的话，新请求一进来
     * 读到的还是上一个请求存的 id。
     * 如果这个新请求恰好走的是被 {@code excludePathPatterns} 放行的路径
     * （拦截器压根没执行、没人覆盖这个值），业务层拿到的就是<b>上一个用户的 id</b>，
     * 然后安静地把它写进 create_user / update_user。
     * 不报错，数据是错的 —— 这是最难发现的一类 bug。
     *
     * <p><b>2. 内存泄漏。</b>ThreadLocalMap 的 key 是弱引用、value 是强引用，
     * 线程一直活着（池里的线程基本不会销毁），这条 entry 就一直挂着。
     *
     * <p>{@code afterCompletion} 只在 {@code preHandle} 返回 true 时才会被调用，
     * 也就是说只有「确实 setCurrentId 过」的请求才会走到这里 —— 逻辑刚好对上。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
