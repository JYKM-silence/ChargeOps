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
 * <h2>★ 只 {@code implements WebMvcConfigurer}，绝不 {@code extends WebMvcConfigurationSupport}</h2>
 *
 * <p>后者会<b>静默地关掉半个 Spring Boot</b>：{@code WebMvcAutoConfiguration} 上带着
 * {@code @ConditionalOnMissingBean(WebMvcConfigurationSupport.class)}，容器里一出现
 * 这个类型的 Bean（子类也算），静态资源映射（含 Swagger UI 的 {@code /webjars/**}）、
 * 默认的 {@code HttpMessageConverter} 全套、{@code spring.mvc.*} 那批配置项整块失效，
 * 且没有任何报错。{@code WebMvcConfigurer} 是「追加」而不是「取代」，
 * 与自动配置是叠加关系。症状识别：要自己写 {@code addResourceHandlers}
 * 才能打开 Swagger UI 时，该做的是去掉继承，而不是补映射。
 *
 * <p>JSON 格式因此改由 {@link JacksonConfiguration} 的 customizer 配置，
 * 作用在 ObjectMapper 本身而不是某个转换器上。
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
     * 注册 JWT 拦截器
     *
     * <p><b>路径规划（整个平台的约定，写代码前先看这里）：</b>
     * <pre>
     *   /api/auth/staff/**    管理端登录，放行
     *   /api/auth/member/**   车主端登录，放行
     *   /api/admin/**         管理端业务，需 staff token
     *   /api/member/**        车主端业务，需 member token
     * </pre>
     *
     * <p><b>★ 登录接口放在 {@code /api/auth/} 下，而不是留在 {@code /api/admin/} 里用
     * exclude 排除：</b>两条路都能达到目的，但失败方式不一样。放行名单是黑名单 ——
     * 新增一个免登录接口（短信验证码、密码重置、站点列表公开查询）时很容易忘了往
     * exclude 里加，表现是「这个接口莫名 401」，排查时你会先看 Controller、看 token，
     * 最后才想到拦截器配置。放在拦截器路径<b>之外</b>，「不需要登录」就变成了
     * 「路径前缀」这一件看得见的事，不需要记忆任何名单。
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
     * <p>让 {@code http://localhost:8081/img/xxx.png} 直接指向磁盘上的上传目录，
     * {@link LocalFileStorage} 写进去的文件立刻就能被访问到。
     *
     * <p><b>★ 三个必须注意的点：</b>
     * <ol>
     *   <li><b>末尾的斜杠不能少。</b>{@code "file:.../upload/"} 少了最后那个 {@code /}
     *       就会被当成一个<b>文件</b>而不是目录，所有请求 404，且报错只有 404，
     *       完全不提示是斜杠的问题。</li>
     *   <li><b>反斜杠要换成正斜杠。</b>Windows 上 {@code Path.toAbsolutePath()} 给的是
     *       反斜杠，塞进 {@code file:} URL 里解析会出问题。</li>
     *   <li><b>前缀不能以 {@code /api} 开头</b>（配在 {@code chargeops.storage.url-path}，
     *       默认 {@code /img}）。那个前缀被 JWT 拦截器盯着，而静态资源请求不带 token，
     *       结果是前端图片全部裂掉，<b>日志里却没有一条报错</b>。</li>
     * </ol>
     *
     * <p><b>★ 这段注释本身踩过的坑：不要写出真实的 Windows 路径示例。</b>
     * Java 编译器在<b>词法分析之前</b>处理整个源文件的 Unicode 转义
     * （反斜杠 + u + 四位十六进制），<b>注释和字符串都不例外</b>；路径里
     * 「反斜杠后正好跟了 u」的那一段会被当成转义序列的开头，直接报
     * {@code illegal unicode escape}，报错行还指向注释。所以改用文字描述。
     *
     * <p><b>为什么只有 local 存储才注册：</b>{@code type=oss} 时文件在对象存储上，
     * 再暴露本地目录没有意义，还多一个信息泄露面。
     *
     * <p><b>判断方式看注入的 {@link FileStorage} 实际类型，而不是读
     * {@code chargeops.storage.type} 字符串</b>（字符串是「猜测」，配置写 oss
     * 而 Bean 因别的原因仍是本地实现时会漏注册，图片 404 且毫无线索）。
     * <b>目录也不能在这里重算一遍</b>：{@code LocalFileStorage} 构造时已把相对路径
     * 锚定到项目根目录，这里再算一次用的却是 JVM 工作目录，会变成
     * <b>文件存一个地方、映射指向另一个地方</b>，上传成功但立刻 404 ——
     * 这个 bug 在迁移前的项目里实测踩到过。所以直接问 Bean 要它解析好的目录。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (fileStorage instanceof LocalFileStorage local && storageProperties.getUrlPath() != null) {
            // 路径分隔符统一成正斜杠（见上面第 2 点）
            String location = "file:" + local.getBaseDir().replace('\\', '/') + "/";
            String pattern = "/" + storageProperties.getUrlPath()
                    .replaceAll("^/+", "")
                    .replaceAll("/+$", "") + "/**";

            registry.addResourceHandler(pattern).addResourceLocations(location);
            log.info("静态资源映射：{} -> {}", pattern, location);
        }
    }

    /**
     * 接口文档的元信息（对应老库的 {@code Docket}，springdoc 只做 OpenAPI 3）
     *
     * <p>这里没有「扫描哪个包」的配置：springdoc 默认扫全应用的 {@code @RestController}。
     * 少一个控制点，也少一个出错的地方 —— 显式写 basePackage 的写法在改包名时
     * 忘了同步，会让文档变成空白，而应用本身完全正常。
     *
     * <p><b>文档地址（Spring Boot 4 + springdoc 3.x）：</b>
     * <pre>
     *   Swagger UI   http://localhost:8081/swagger-ui/index.html
     *   OpenAPI JSON http://localhost:8081/v3/api-docs
     * </pre>
     * <b>★ 验收时先访问 {@code /v3/api-docs}：</b>它是纯 JSON，不依赖任何前端资源。
     * 有 JSON 说明文档数据本身是好的，UI 打不开就是静态资源的问题（多半是
     * {@code WebMvcConfigurationSupport} 那个坑）；JSON 也 404 则是 springdoc 没生效，
     * 方向完全不同。先看 JSON 能把这两类问题分开。
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
