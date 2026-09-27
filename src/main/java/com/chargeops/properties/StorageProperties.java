package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置，对应 {@code chargeops.storage.*}，具体实现见
 * {@link com.chargeops.utils.FileStorage}
 *
 * <p> 目前只实现了 {@code local}。对象存储有意留空 —— 加一个需要真 AK/SK 才能验证的实现，
 * 等于往仓库里放一段没人跑过的代码，留一个 switch 分支位置是更诚实的做法。
 *
 * <p> 两个字段级约定：{@code localDir} 支持相对路径，但按项目根目录锚定，不是 JVM 工作目录；
 * {@code urlPath} 不要配成 {@code /api} 开头 —— 那个前缀被 JWT 拦截器盯着，而静态资源请求
 * 不带 token，结果是图片全裂、日志里一条报错都没有。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.storage")
@Data
public class StorageProperties {

    private String type;

    private String localDir;

    /** 拼在返回 URL 前面的部分，形如 {@code http://localhost:8081}。它会被写进数据库，换域名时历史数据里的旧 URL 也要一起刷 */
    private String urlPrefix;

    private String urlPath;
}
