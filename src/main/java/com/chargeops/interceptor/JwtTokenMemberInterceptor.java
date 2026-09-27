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
 * 车主端 JWT 校验拦截器
 *
 * <p> 和管理端的 {@link JwtTokenAdminInterceptor} 只有四处不同：读 member 端配置
 * （{@code member-secret-key} / {@code member-token-name}）、claim 的 key 是
 * {@code MEMBER_ID} 而不是 {@code STAFF_ID}、拦 {@code /api/member/**}、
 * 往 BaseContext 里存的是 memberId。
 *
 * <p> 最后一点是关键：车主端几乎所有业务（我的车辆、我的订单、钱包余额、充电记录）都靠
 * {@code BaseContext.getCurrentId()} 拿当前车主 id，漏了这行查不到数据而且不报错 ——
 * SQL 里 {@code where member_id = null} 不匹配任何行，返回空列表，
 * 看起来就像「这个用户确实没有订单」。
 */
@Component
@Slf4j
public class JwtTokenMemberInterceptor implements HandlerInterceptor {

    private final JwtProperties jwtProperties;

    public JwtTokenMemberInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getMemberTokenName());

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getMemberSecretKey(), token);
            Long memberId = Long.valueOf(claims.get(JwtClaimsConstant.MEMBER_ID).toString());

            BaseContext.setCurrentId(memberId);
            log.debug("车主端鉴权通过，memberId={}", memberId);
            return true;
        } catch (Exception ex) {
            log.debug("车主端鉴权失败：{}", ex.getMessage());
            response.setStatus(401);
            return false;
        }
    }

    /** 清理 ThreadLocal。车主端有放行路径（{@code /api/member/station/status} 这类），那些请求不走 preHandle，线程复用时会读到上一个车主的 memberId */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
