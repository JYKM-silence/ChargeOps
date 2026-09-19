package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置，对应 {@code chargeops.storage.*}
 *
 * <p>它是「选哪种存储介质」的总开关，具体实现见
 * {@link com.chargeops.utils.FileStorage}。目前只实现了：
 * <ul>
 *   <li><b>local</b> —— 写本地磁盘（默认，开发环境用，零成本零依赖）</li>
 * </ul>
 *
 * <p><b>★ 对象存储这一项是有意留空的，不要现在就补上。</b>
 * 加一个需要真 AK/SK 才能验证的实现，等于往仓库里放一段没人跑过的代码 ——
 * 它编译得过、启动不报错，但真配上去能不能用完全未知。
 * 留一个 {@code switch} 的分支位置（见 {@code StorageConfiguration}）是更诚实的做法：
 * 等真的有对象存储账号、真的需要多实例部署时再实现，那时它是被验证过的。
 *
 * <p><b>为什么默认是 local：</b>
 * 一个刚克隆下来的项目，不该因为「没去云厂商开 bucket」就传不了图 ——
 * 那是把外部账号的注册成本转嫁给了每一个想跑起来看看的人。
 * 生产环境由 application-prod.yml 里没有默认值的环境变量强制显式声明。
 *
 * <p>字段上不写默认值，理由见 JwtProperties 的注释。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.storage")
@Data
public class StorageProperties {

    /**
     * 存储类型：{@code local} | {@code oss}
     *
     * <p>Spring 对拼错的配置项名是<b>静默忽略</b>的，不会报错。
     * 所以 StorageConfiguration 里对这个值的判断是「白名单式」的 ——
     * 只有明确写了 {@code oss} 才用 OSS，其余一切值（包括拼错的 {@code os}、
     * {@code OSS}、{@code 阿里云}）都落到本地磁盘。
     * 这样最坏情况是「用错了存储介质，但功能是可用的」，而不是
     * 「启动了一个什么都没配的 Bean，上传时才 NPE」。
     */
    private String type;

    /**
     * 本地磁盘的存储根目录，仅 {@code type=local} 时生效
     *
     * <p>支持相对路径，但会按「项目根目录」锚定，而不是 JVM 的工作目录 ——
     * 后者取决于你怎么启动（VS Code 点 Run / mvn spring-boot:run / java -jar
     * 分别是三个不同的目录），同一个相对路径会指向三个地方，
     * 而表现只是「图片打不开」。详见 {@link com.chargeops.utils.LocalFileStorage}。
     */
    private String localDir;

    /**
     * 拼在返回 URL 前面的部分，形如 {@code http://localhost:8081}
     *
     * <p>★ 这个值会被**写进数据库**（{@code station.image} 之类的列存的就是完整 URL），
     * 所以换域名/换端口时，光改这里不够，历史数据里的旧 URL 也得一起刷，
     * 否则老图全部 404。这是「把可变的环境信息固化进业务数据」的典型代价，
     * 更稳妥的做法是库里只存相对路径、拼接放在读的时候做 ——
     * 但那会让 CDN 域名的切换需要改代码，各有取舍。
     */
    private String urlPrefix;

    /**
     * 本地上传文件的访问路径前缀，默认 {@code /img}
     *
     * <p>它同时被两处用到，必须保持一致，所以抽出来放在配置里：
     * <ol>
     *   <li>{@code LocalFileStorage} 拼返回的 URL</li>
     *   <li>{@code WebMvcConfiguration} 注册静态资源映射</li>
     * </ol>
     *
     * <p><b>不要配成 /api 开头</b> —— 那个前缀被 JWT 拦截器盯着，
     * 图片会变成「必须登录才能看」，而静态资源请求不会带 token，
     * 结果是小程序里的图片全部裂掉，且日志里一条报错都没有
     * （拦截器返回 401，静态资源加载失败是静默的）。
     */
    private String urlPath;
}
