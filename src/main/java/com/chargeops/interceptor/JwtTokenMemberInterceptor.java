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
 * <p>和管理端的 {@link JwtTokenAdminInterceptor} 只有四处不同：
 * <ol>
 *   <li>读 member 端配置（{@code member-secret-key} / {@code member-token-name}）</li>
 *   <li>claim 的 key 是 {@code MEMBER_ID} 而不是 {@code STAFF_ID}</li>
 *   <li>拦截 {@code /api/member/**} 而不是 {@code /api/admin/**}</li>
 *   <li>往 BaseContext 里存的是 memberId</li>
 * </ol>
 *
 * <p><b>第 4 点是关键。</b>车主端几乎所有业务（我的车辆、我的订单、钱包余额、
 * 充电记录）都要靠 {@code BaseContext.getCurrentId()} 拿到当前车主 id，
 * 漏了这行会查不到数据<b>而且不报错</b> —— SQL 里 {@code where member_id = null}
 * 不匹配任何行，返回空列表，看起来就像「这个用户确实没有订单」。
 *
 * <p><b>为什么不把两个拦截器合并成一个、用配置区分身份：</b>
 * 合并之后「这个请求是谁」这件事就变成了运行时的分支判断，
 * 而分开之后它是<b>由路径前缀在配置里静态声明</b>的。
 * 后者的好处是排查「为什么这个接口拿不到身份」时，
 * 只需要看两个 {@code addInterceptors} 的路径匹配，不用去追分支逻辑。
 *
 * <p>这里重复的代码量大约 30 行。为一个「让身份归属一目了然」的收益
 * 接受这点重复是划算的 —— 反过来，如果这两个拦截器各自开始长出
 * 独立的业务逻辑（比如车主端要额外校验账号状态），那就更不该合并了。
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

    /**
     * 清理 ThreadLocal，和管理端同理。
     *
     * <p>车主端这边后果更直接：{@code /api/member/station/status} 这类
     * 「没登录也要能访问」的接口是放行的，走这条路时拦截器的 preHandle 不执行。
     * 如果线程是从上一个登录车主那里复用来的，业务层读 BaseContext
     * 就会拿到那个人的 memberId。
     * 今天这个接口不读 BaseContext 所以没事，
     * 但以后谁在这个接口里加一行「顺便返回一下当前用户的信息」，就会静默查错人。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
