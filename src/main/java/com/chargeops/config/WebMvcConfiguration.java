package com.chargeops.config;

import com.chargeops.interceptor.JwtTokenAdminInterceptor;
import com.chargeops.interceptor.JwtTokenMemberInterceptor;
import com.chargeops.properties.StorageProperties;
import com.chargeops.utils.FileStorage;
import com.chargeops.utils.LocalFileStorage;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：拦截器、静态资源、接口文档元信息
 *
 * <p> 绝不 {@code extends WebMvcConfigurationSupport}：{@code WebMvcAutoConfiguration} 上带着
 * {@code @ConditionalOnMissingBean(WebMvcConfigurationSupport.class)}，容器里一出现这个类型的 Bean
 * （子类也算），静态资源映射、默认的 HttpMessageConverter 全套、{@code spring.mvc.*} 整块失效，且没有任何报错。
 */
@Configuration
@Slf4j
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final JwtTokenAdminInterceptor jwtTokenAdminInterceptor;
    private final JwtTokenMemberInterceptor jwtTokenMemberInterceptor;
    private final StorageProperties storageProperties;
    private final FileStorage fileStorage;

    public WebMvcConfiguration(JwtTokenAdminInterceptor jwtTokenAdminInterceptor,
                               JwtTokenMemberInterceptor jwtTokenMemberInterceptor,
                               StorageProperties storageProperties,
                               FileStorage fileStorage) {
        this.jwtTokenAdminInterceptor = jwtTokenAdminInterceptor;
        this.jwtTokenMemberInterceptor = jwtTokenMemberInterceptor;
        this.storageProperties = storageProperties;
        this.fileStorage = fileStorage;
    }

    /**
     * 注册 JWT 拦截器。路径约定：{@code /api/auth/**} 双端登录放行，{@code /api/admin/**} 需 staff token，
     * {@code /api/member/**} 需 member token。
     *
     * <p> 登录接口放在 {@code /api/auth/} 下、靠路径前缀放行，而不是留在 {@code /api/admin/} 里用 exclude 排除：
     * exclude 是一份需要人记住的黑名单，新增免登录接口时忘了加，表现是「这个接口莫名 401」。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("注册 JWT 拦截器");

        // 管理端：运维人员 / 平台管理员
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/api/admin/**");

        // 车主端
        registry.addInterceptor(jwtTokenMemberInterceptor)
                .addPathPatterns("/api/member/**");
    }

    /**
     * 静态资源映射：把上传目录暴露成一个可访问的 URL 前缀
     *
     * <p> 三个坑：location 末尾的斜杠不能少（少了会被当成文件，全部 404 且只报 404）；Windows 上要把
     * 反斜杠换成正斜杠；判断类型和取目录都必须问 Bean，不能读配置字符串也不能重算一遍路径 ——
     * 重算用的是 JVM 工作目录，会变成文件存一个地方、映射指向另一个地方，上传成功但立刻 404（实测踩到过）。
     *
     * <p> 别在这里写真实的 Windows 路径示例：反斜杠后跟 u 会被当成 Unicode 转义，直接报 illegal unicode escape。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (fileStorage instanceof LocalFileStorage local && storageProperties.getUrlPath() != null) {
            // 路径分隔符统一成正斜杠
            String location = "file:" + local.getBaseDir().replace('\\', '/') + "/";
            String pattern = "/" + storageProperties.getUrlPath()
                    .replaceAll("^/+", "")
                    .replaceAll("/+$", "") + "/**";

            registry.addResourceHandler(pattern).addResourceLocations(location);
            log.info("静态资源映射：{} -> {}", pattern, location);
        }
    }

    /**
     * 接口文档的元信息。没有「扫描哪个包」的配置：springdoc 默认扫全应用的 {@code @RestController}，
     * 显式写 basePackage 在改包名时忘了同步会让文档变成空白。地址 {@code /swagger-ui/index.html}。
     */
    @Bean
    public OpenAPI chargeOpsOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("ChargeOps 新能源充电桩运营平台")
                .version("0.1.0")
                .description("""
                        充电桩运营平台后端接口文档。

                        路径约定：
                          /api/auth/**    登录接口，无需鉴权
                          /api/admin/**   管理端，需请求头携带 staff token
                          /api/member/**  车主端，需请求头携带 member token
                        """));
    }
}
