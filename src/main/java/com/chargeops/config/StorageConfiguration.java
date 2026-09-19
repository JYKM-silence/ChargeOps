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
 * <table border="1">
 *   <tr><th>chargeops.storage.type</th><th>生效的实现</th><th>场景</th></tr>
 *   <tr><td>local（不配也是它）</td><td>{@link LocalFileStorage}</td><td>开发 / 演示</td></tr>
 * </table>
 *
 * <h2>★ 现在只有一个实现，为什么还要留这个开关</h2>
 *
 * <p>因为「文件存哪」是<b>一定会变</b>的一个点，而且变的时候不该动业务代码。
 * 所有的上传调用方注入的都是 {@link FileStorage} 这个接口，
 * 没有一处 import 过 {@code LocalFileStorage}（除了
 * {@code WebMvcConfiguration} 里的 {@code instanceof} 判断，那是必须知道具体类型的唯一场景）。
 * 所以将来加对象存储时，改动范围就是这个类加一个分支 —— 全平台一行业务代码都不用动。
 *
 * <p>这个「花一次力气把变化点关进一个文件」的动作，比「现在只有一种实现所以不需要抽象」
 * 更值。充电桩平台的图片（站点实景、桩位照片、故障现场）迟早会超出单机磁盘，
 * 而到那时才发现上传逻辑散落在十几个 Service 里，改动成本就不是加个分支了。
 *
 * <h2>★ 为什么没用 {@code @ConditionalOnProperty} 二选一</h2>
 *
 * <p>老库（以及绝大多数教程）的写法是两个 {@code @Bean} 方法各挂一个条件：
 * <pre>
 *   &#64;Bean &#64;ConditionalOnProperty(name="sky.storage.type", havingValue="local", matchIfMissing=true)
 *   public FileStorage localFileStorage(...) { ... }
 *
 *   &#64;Bean &#64;ConditionalOnProperty(name="sky.storage.type", havingValue="oss")
 *   public FileStorage ossFileStorage(...) { ... }
 * </pre>
 *
 * <p>这个写法有个不好受的性质：<b>配置项写了个不认识的值时，两个 Bean 都不满足条件，
 * 结果是「容器里没有 FileStorage」，启动直接失败，报错是
 * 「required a bean of type 'FileStorage' that could not be found」</b>。
 * 这句话把「你配置写错了」说成了「你少写了个 Bean」，
 * 排查方向被带偏到「是不是我的包没被扫描到」。
 *
 * <p>下面用 {@code switch} 白名单，配置写错时抛出的消息里
 * 直接点名了配置项、写错的值、以及当前支持哪些值。
 * 一个错误消息省掉的排查时间，通常比这段代码本身还值。
 *
 * <h2>★ {@code matchIfMissing} 那个默认值的方向问题</h2>
 *
 * <p>不配 {@code type} 时落到 <b>local</b>，而不是启动失败。
 * 判断依据是：这个开关的默认值应该指向<b>「不需要任何外部依赖就能跑起来」</b>的那一项。
 * 一个刚 clone 下来的仓库，不该因为「没去对象存储服务商那边开 bucket」
 * 就传不了图 —— 那是把外部的注册成本转嫁给了每一个想跑起来看看的人。
 *
 * <p>生产环境的保护反过来做：{@code application-prod.yml} 里写成
 * 不提供默认值的 {@code ${CHARGE_OPS_STORAGE_TYPE}}，
 * 忘配环境变量时占位符解析失败、启动报错。两侧配合才既好上手又不误配。
 */
@Configuration
@Slf4j
public class StorageConfiguration {

    /**
     * 唯一的 FileStorage Bean
     *
     * <p><b>{@code switch} 的 {@code default} 分支抛异常而不是回退到 local</b> ——
     * 这是有意的。「配置写错了但功能还能用」在开发环境是好体验，
     * 在生产环境是灾难：{@code chargeops.storage.type: os}（少一个 s）会安静地
     * 把用户上传的文件写到应用服务器本地磁盘，而所有人都以为它在对象存储上，
     * 直到某天扩容成两台机器，一半的图片开始 404。
     *
     * <p>所以：<b>不认识的值 = 启动失败 + 一句能看懂的话</b>，
     * 而不是「猜一个最接近的」。
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
     * <p>{@code LocalFileStorage} 自己没有 {@code @Component} —— 它是被这里
     * {@code new} 出来的。这样做是为了让「它有没有被注册」完全由上面那个
     * switch 决定，而不是同时受组件扫描和条件注解两套规则影响。
     * 一个 Bean 的注册路径只有一条，排查时才不会有歧义。
     */
    private FileStorage localFileStorage(StorageProperties storageProperties) {
        LocalFileStorage storage = new LocalFileStorage(
                storageProperties.getLocalDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath());

        // ★ 把解析后的绝对路径打出来。
        //
        // 配置里写的是相对路径（./data/upload），而相对路径按「进程的工作目录」解析，
        // 那个目录取决于你怎么启动：VS Code 点 Run、mvn spring-boot:run、java -jar
        // 是三个不同的目录。于是「上传返回的 URL 看起来正常，但文件找不到」
        // 几乎必然会遇到一次，而排查时会去怀疑静态资源映射、拦截器、URL 拼错。
        //
        // 有这行日志，直接复制路径去文件管理器里确认就行。
        // LocalFileStorage 已经把它锚定到项目根目录了（见那个类的注释），
        // 这里再打一遍是因为「锚定」这件事本身也需要能被验证
        log.info("文件存储：本地磁盘 {}，访问前缀 {}/{}",
                storage.getBaseDir(),
                storageProperties.getUrlPrefix(),
                storageProperties.getUrlPath().replaceAll("^/+", ""));

        return storage;
    }
}
