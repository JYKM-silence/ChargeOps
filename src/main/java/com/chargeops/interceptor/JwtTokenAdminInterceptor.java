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
 * <p>注意 import 是 {@code jakarta.servlet} 而不是 {@code javax.servlet}
 * （Spring Boot 3 起换了命名空间）。这种错误编译期就会炸，反而好办 ——
 * 难查的永远是「编译通过但语义变了」的那一类，别把「编译通过」当成「迁移完成」。
 */
@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    /** 构造器注入而不是 {@code @Autowired} 字段注入：依赖可以 final，单测能直接 new，循环依赖会在启动时直接失败而不是被悄悄修好 */
    private final JwtProperties jwtProperties;

    public JwtTokenAdminInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 只拦落到具体接口方法上的请求。不加这个判断的话，
        // springdoc 加载接口文档、浏览器请求 favicon 都会被拦下来要求登录
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getAdminTokenName());

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
            Long staffId = Long.valueOf(claims.get(JwtClaimsConstant.STAFF_ID).toString());

            // 当前登录者 id 存 ThreadLocal，供业务层取用（create_user / update_user）
            BaseContext.setCurrentId(staffId);
            log.debug("管理端鉴权通过，staffId={}", staffId);
            return true;
        } catch (Exception ex) {
            // ★ 不打印 token 本身。token 就是凭证，任何能读日志的人
            //   （运维、日志平台、被误配的采集）都能拿它冒充这个用户直到过期。
            //   日志记录「校验失败」这个事实，而不是失败时用的那把钥匙。
            //   用 debug 而不是 info：鉴权失败在生产可能大量出现（过期、爬虫乱扫），
            //   info 会把正常业务日志淹掉。
            log.debug("管理端鉴权失败：{}", ex.getMessage());

            // 401 对应「未登录/凭证失效」。★ 不区分「token 过期」和「签名不对」：
            // 对客户端处理方式一样（重新登录），而对攻击者来说，
            // 区分它们等于免费提供「猜对了签名但过期了」的情报。
            response.setStatus(401);
            return false;
        }
    }

    /**
     * 请求结束后清理 ThreadLocal
     *
     * <p><b>★ 必须清理。</b>Tomcat 用线程池处理请求，线程用完还回池子。没清的话，
     * 下一个请求进来读到的是上一个请求存的 id —— 如果它恰好走的是放行路径
     * （拦截器没执行、没人覆盖这个值），业务层拿到的就是<b>上一个用户的 id</b>，
     * 然后安静地写进 create_user / update_user。不报错，数据是错的。
     *
     * <p>{@code afterCompletion} 只在 {@code preHandle} 返回 true 时被调用，
     * 即只有确实 {@code setCurrentId} 过的请求会走到这里。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
