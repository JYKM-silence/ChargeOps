package com.chargeops.config;

import com.chargeops.properties.StorageProperties;
import com.chargeops.utils.FileStorage;
import com.chargeops.utils.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储装配：按 {@code chargeops.storage.type} 决定注册哪个 {@link FileStorage}
 * （目前只有 {@code local} → {@link LocalFileStorage}，不配也是它）
 *
 * <h2>★ 现在只有一个实现，为什么还留这个开关</h2>
 *
 * <p>「文件存哪」是<b>一定会变</b>的一个点，且变的时候不该动业务代码：所有上传调用方
 * 注入的都是 {@link FileStorage} 接口，没有一处 import 过 {@code LocalFileStorage}
 * （唯一必须知道具体类型的是 {@code WebMvcConfiguration} 里的 {@code instanceof}）。
 * 将来加对象存储，改动范围就是这个类加一个分支。平台图片迟早会超出单机磁盘，
 * 那时才发现上传逻辑散落在十几个 Service 里，成本就不是加个分支了。
 *
 * <h2>★ 为什么没用 {@code @ConditionalOnProperty} 二选一</h2>
 *
 * <p>那种写法（两个 {@code @Bean} 各挂一个条件）有个不好受的性质：<b>配置项写了个
 * 不认识的值时两个 Bean 都不满足条件，结果「容器里没有 FileStorage」，
 * 启动报「required a bean of type 'FileStorage' that could not be found」</b>——
 * 把「你配置写错了」说成了「你少写了个 Bean」，排查方向被带偏到包扫描上。
 * 下面用 {@code switch} 白名单，报错消息直接点名配置项、写错的值和支持哪些值。
 *
 * <h2>★ 默认值的方向</h2>
 *
 * <p>不配 {@code type} 时落到 <b>local</b> 而不是启动失败：默认值应该指向
 * 「不需要任何外部依赖就能跑起来」的那一项，刚 clone 下来的仓库不该因为
 * 没去对象存储开 bucket 就传不了图。生产环境的保护反过来做 ——
 * {@code application-prod.yml} 里写成不提供默认值的
 * {@code ${CHARGE_OPS_STORAGE_TYPE}}，忘配就启动报错。
 */
@Configuration
@Slf4j
public class StorageConfiguration {

    /**
     * 唯一的 FileStorage Bean
     *
     * <p><b>★ {@code default} 分支抛异常而不是回退到 local：</b>「配置写错了但功能还能用」
     * 在生产是灾难 —— {@code chargeops.storage.type: os}（少一个 s）会安静地把文件写到
     * 应用服务器本地磁盘，而所有人都以为它在对象存储上，直到扩容成两台机器、
     * 一半的图片开始 404。不认识的值就该是「启动失败 + 一句能看懂的话」。
     */
    @Bean
    public FileStorage fileStorage(StorageProperties storageProperties) {
        // type 可能是 null（配置里没写），也可能是 " LOCAL " 这种带空格大写的形式。
        // 统一归一化再比较 —— 配置项的书写差异不该导致启动失败
        String type = storageProperties.getType() == null
                ? "local"
                : storageProperties.getType().trim().toLowerCase();

        return switch (type) {
            case "", "local" -> localFileStorage(storageProperties);

            // 将来接对象存储时，在这里加一个 case "oss" -> ossFileStorage(...) 即可，
            // 业务代码一行不用改。这是这个 switch 存在的全部意义
            default -> throw new IllegalStateException(String.format(
                    "不支持的 chargeops.storage.type=%s，当前只实现了 local。"
                            + "请把配置改成 local，或去掉这一项使用默认值",
                    storageProperties.getType()));
        };
    }

    /**
     * 本地磁盘实现
     *
     * <p>{@code LocalFileStorage} 自己没有 {@code @Component}，是被这里 {@code new} 出来的：
     * 让「它有没有被注册」完全由上面那个 switch 决定，而不是同时受组件扫描和条件注解
     * 两套规则影响 —— 一个 Bean 的注册路径只有一条，排查时才不会有歧义。
     */
    private FileStorage localFileStorage(StorageProperties storageProperties) {
        LocalFileStorage storage = new LocalFileStorage(
                storageProperties.getLocalDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath());

        // ★ 把解析后的绝对路径打出来。配置里写的是相对路径（./data/upload），
        //   按「进程的工作目录」解析，而那个目录取决于怎么启动（VS Code Run /
        //   mvn spring-boot:run / java -jar 是三个不同的目录）。于是
        //   「上传返回的 URL 看起来正常，但文件找不到」几乎必然遇到一次，
        //   而排查时会去怀疑静态资源映射、拦截器、URL 拼错。
        //   LocalFileStorage 已把它锚定到项目根目录，这里再打一遍是为了让
        //   「锚定」这件事本身可以被验证
        log.info("文件存储：本地磁盘 {}，访问前缀 {}/{}",
                storage.getBaseDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath().replaceAll("^/+", ""));

        return storage;
    }
}
