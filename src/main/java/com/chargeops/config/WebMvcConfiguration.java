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
 * <h2>★★ 这个类最要紧的一件事：{@code implements WebMvcConfigurer}</h2>
 *
 * <p>老库写的是 {@code extends WebMvcConfigurationSupport}，<b>这是一个会
 * 关掉半个 Spring Boot 的写法</b>，而且是静默地关掉。
 *
 * <p><b>原因：</b>Spring Boot 的 {@code WebMvcAutoConfiguration} 上有
 * <pre>
 *   &#64;ConditionalOnMissingBean(WebMvcConfigurationSupport.class)
 * </pre>
 * 只要容器里出现了 {@code WebMvcConfigurationSupport} 类型的 Bean
 * （继承它的子类也算），整个 Web MVC 自动配置<b>整块不生效</b>。
 * 一起失效的包括：
 * <ul>
 *   <li>静态资源映射（含 {@code /webjars/**}，也就是 Swagger UI 的页面文件）</li>
 *   <li>默认的 {@code HttpMessageConverter} 全套</li>
 *   <li>欢迎页、favicon 处理</li>
 *   <li>{@code spring.mvc.*}、{@code spring.web.*} 那批配置项</li>
 * </ul>
 *
 * <p>老库之所以还能跑，是因为它在继承来的类里<b>手工把这些又补了一遍</b>：
 * {@code addResourceHandlers} 里加了 {@code /doc.html} 和 {@code /webjars/**}，
 * {@code extendMessageConverters} 里手工 new 了一个 Jackson 转换器。
 * 所以那段代码看起来「工作正常」，但它的正常是靠人工补票维持的 ——
 * 少补哪一张，对应的功能就静默失效，而没有任何报错。
 *
 * <p><b>为什么 {@code WebMvcConfigurer} 就没有这个问题：</b>
 * 它是「追加」而不是「取代」—— Spring Boot 先按自动配置装好一整套默认组件，
 * 然后依次调用所有 {@code WebMvcConfigurer} 让它们往上加东西。
 * 两者是<b>叠加</b>关系，不是<b>覆盖</b>关系。
 * 这也是为什么下面没有 {@code extendMessageConverters} 了：
 * JSON 格式改由 {@link JacksonConfiguration} 的 customizer 配置，
 * 作用在 ObjectMapper 本身而不是某个转换器上，覆盖的面反而更广。
 *
 * <p><b>面试可讲：</b>这两个类名字像、方法名几乎一样，
 * 但一个是「接管」一个是「扩展」。区分方法很简单 ——
 * 需要自己写 {@code addResourceHandlers} 才能让 Swagger UI 打开，
 * 就说明自动配置被关掉了，那时候该做的不是补映射，而是去掉继承。
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
     * <p><b>为什么登录接口要单独放在 {@code /api/auth/} 下面，
     * 而不是留在 {@code /api/admin/} 里用 exclude 排除：</b>
     * 两条路都能达到目的，但它们的<b>失败方式</b>不一样。
     * 放行名单是「黑名单」—— 新增一个不需要登录的接口（短信验证码、密码重置、
     * 站点列表公开查询）时，很容易忘了往 exclude 里加，
     * 表现是「这个接口莫名返回 401」，而排查时你会去看 Controller、看 token，
     * 最后才想到是拦截器配置。
     * 把「不需要登录」的接口放在拦截器路径<b>之外</b>，
     * 这个判断就变成了「路径前缀」这一件可以看到的事，不需要记忆任何名单。
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
     * 这样 {@link LocalFileStorage} 写进去的文件立刻就能被访问到。
     *
     * <p><b>三个必须注意的点：</b>
     *
     * <p><b>1. 末尾的斜杠不能少。</b>{@code "file:.../upload/"} 缺少最后那个 {@code /} 时，
     * Spring 会把它当成一个<b>文件</b>而不是目录，所有请求 404。
     * 这是这个映射最常见的一个坑，而且报错信息只有 404，完全不会提示是斜杠的问题。
     *
     * <p><b>2. 反斜杠要换成正斜杠。</b>Windows 上 {@code Path.toAbsolutePath()}
     * 给出的路径分隔符是反斜杠，塞进 {@code file:} URL 里解析会出问题。
     * 统一转成 {@code /} 最省事。
     *
     * <p><b>顺带一个 Java 语言层面的坑（本项目已经踩过）：</b>
     * 不要在这段注释里写出真实的 Windows 路径示例。
     * Java 编译器会在<b>词法分析之前</b>处理整个源文件的 Unicode 转义
     * （形如反斜杠加 u 再加四位十六进制数字），<b>注释和字符串都不例外</b>。
     * 而 Windows 路径里 {@code data} 后面跟 {@code upload} 的那一段，
     * 反斜杠后面正好跟了个 {@code u} —— 编译器把它当成转义序列的开头，
     * 再往后读到的不是十六进制数字，于是直接报
     * {@code error: illegal unicode escape}。
     * 报错行还指向注释，看起来毫无道理。所以这里改用文字描述。
     *
     * <p><b>3. 前缀不能以 {@code /api} 开头。</b>
     * 那个前缀被 JWT 拦截器盯着，图片会变成「登录才看得见」——
     * 而静态资源请求不会带 token，结果是前端图片全部裂掉，
     * 且<b>日志里一条报错都没有</b>（拦截器返回 401，
     * 静态资源加载失败在浏览器端是静默的）。
     * 前缀配在 {@code chargeops.storage.url-path}，默认 {@code /img}。
     *
     * <p><b>为什么只有 local 存储才注册这个映射：</b>
     * {@code type=oss} 时文件在对象存储上，再把本地目录暴露成一个可访问路径
     * 没有意义，还多一个信息泄露面。
     *
     * <p><b>判断方式是看注入进来的 {@link FileStorage} 到底是不是本地实现，
     * 而不是去读 {@code chargeops.storage.type} 这个字符串。</b>两个理由：
     * <ol>
     *   <li>字符串判断是「猜测」—— 配置写 {@code oss} 但 Bean 因为别的原因
     *       还是本地实现时，这里会漏注册，表现是图片 404 且毫无线索。
     *       按实际类型判断是确定的</li>
     *   <li><b>目录也不能在这里重新算一遍。</b>{@code LocalFileStorage} 构造时
     *       已经把相对路径锚定到了项目根目录（见那个类的注释），
     *       如果这里再用 {@code Paths.get(localDir).toAbsolutePath()} 算一次，
     *       用的却是 JVM 的工作目录 —— 结果是<b>文件存到一个地方、
     *       映射指向另一个地方</b>，上传成功但立刻 404。
     *       这个 bug 在迁移前的项目里实测踩到过，启动日志里两行清清楚楚地
     *       指向了两个不同的 data 目录。所以这里直接问 Bean 要它解析好的目录，
     *       保证只有一个事实来源</li>
     * </ol>
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
     * 接口文档的元信息
     *
     * <p><b>这个 Bean 对应老库里的 {@code Docket}。</b>替换关系是：
     * <pre>
     *   knife4j / springfox         springdoc
     *   ────────────────────────    ─────────────────────────────
     *   DocumentationType.SWAGGER_2  （不需要，springdoc 只做 OpenAPI 3）
     *   new Docket(...)              new OpenAPI()
     *   ApiInfoBuilder               Info
     *   RequestHandlerSelectors      （不需要，springdoc 自动扫 @RestController）
     * </pre>
     *
     * <p><b>注意这里没有「扫描哪个包」的配置。</b>springdoc 默认扫描整个应用里的
     * {@code @RestController}，不需要指定 basePackage。
     * 看起来是少了个控制点，实际上是少了个出错的地方 ——
     * 而显式写 {@code RequestHandlerSelectors.basePackage("com.xxx.controller")} 的写法
     * 在改包名时如果忘了同步，接口文档会变成空白的，而应用本身完全正常。
     *
     * <p><b>文档地址（Spring Boot 4 + springdoc 3.x）：</b>
     * <pre>
     *   Swagger UI   http://localhost:8081/swagger-ui/index.html
     *   OpenAPI JSON http://localhost:8081/v3/api-docs
     * </pre>
     * 验收时先访问 {@code /v3/api-docs}。<b>它是纯 JSON，不依赖任何前端资源</b> ——
     * 有 JSON 说明文档数据本身是好的，此时如果 UI 页面打不开，
     * 问题就锁定在静态资源上（多半是 {@code WebMvcConfigurationSupport} 那个坑）；
     * 反过来如果 JSON 都是 404，说明 springdoc 根本没生效，方向完全不同。
     * <b>先看 JSON 能把这两类问题分开，省掉大量瞎猜。</b>
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
