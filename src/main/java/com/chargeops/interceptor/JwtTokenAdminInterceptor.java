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
 * <p> 请求头名和签名密钥都从 {@link JwtProperties} 取，校验通过后把 staffId 存进 {@link BaseContext}。
 */
@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    /** 构造器注入：依赖可以 final，单测能直接 new */
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
            // 不打印 token 本身：token 就是凭证，能读日志的人拿它就能冒充这个用户直到过期。
            // 用 debug 而不 info：鉴权失败在生产可能大量出现（过期、爬虫乱扫），info 会淹掉业务日志。
            log.debug("管理端鉴权失败：{}", ex.getMessage());

            // 401 不区分「token 过期」和「签名不对」：客户端处理方式一样（重新登录），
            // 而对攻击者来说，区分它们等于免费提供「猜对了签名但过期了」的情报。
            response.setStatus(401);
            return false;
        }
    }

    /** 请求结束后清理 ThreadLocal。{@code afterCompletion} 只在 {@code preHandle} 返回 true 时被调用，刚好只有 set 过的请求才会走到这里 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
