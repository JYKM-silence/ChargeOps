package com.chargeops.config;

import com.chargeops.properties.StorageProperties;
import com.chargeops.utils.FileStorage;
import com.chargeops.utils.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储装配：按 {@code chargeops.storage.type} 决定注册哪个 {@link FileStorage}
 *
 * <p> 用 switch 白名单而不是 {@code @ConditionalOnProperty} 二选一：后者在配置值不认识时两个 Bean
 * 都不满足条件，启动报的是「找不到 FileStorage」，把配置写错说成了少写 Bean。
 *
 * <p> 上传调用方注入的都是接口，将来加对象存储只要在这个类里加一个分支。
 */
@Configuration
@Slf4j
public class StorageConfiguration {

    /**
     * 唯一的 FileStorage Bean。{@code default} 分支抛异常而不是回退到 local：{@code storage.type: os}
     * （少一个 s）会安静地把文件写到应用服务器本地磁盘，而所有人都以为它在对象存储上。
     */
    @Bean
    public FileStorage fileStorage(StorageProperties storageProperties) {
        // type 可能是 null，也可能是 " LOCAL " 这种带空格大写的形式，统一归一化再比较
        String type = storageProperties.getType() == null
                ? "local"
                : storageProperties.getType().trim().toLowerCase();

        return switch (type) {
            case "", "local" -> localFileStorage(storageProperties);

            // 将来接对象存储时在这里加一个 case 即可，业务代码一行不用改
            default -> throw new IllegalStateException(String.format(
                    "不支持的 chargeops.storage.type=%s，当前只实现了 local。"
                            + "请把配置改成 local，或去掉这一项使用默认值",
                    storageProperties.getType()));
        };
    }

    /** {@code LocalFileStorage} 没有 {@code @Component}，注册与否完全由上面的 switch 决定，不受组件扫描影响 */
    private FileStorage localFileStorage(StorageProperties storageProperties) {
        LocalFileStorage storage = new LocalFileStorage(
                storageProperties.getLocalDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath());

        // 打绝对路径：配置里是相对路径，按进程工作目录解析，而工作目录取决于怎么启动
        log.info("文件存储：本地磁盘 {}，访问前缀 {}/{}",
                storage.getBaseDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath().replaceAll("^/+", ""));

        return storage;
    }
}
