package com.chargeops.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储：把上传的文件写到服务器本地目录，
 * 通过 Spring MVC 的静态资源映射对外提供访问
 *
 * <p><b>什么时候用它：</b>本地开发和演示。零成本、零依赖、断网也能跑。
 *
 * <p><b>什么时候不该用它：</b>生产环境。三个原因，面试要能说出来：
 * <ol>
 *   <li><b>多实例不可用</b> —— 部署两台服务器做负载均衡，图片只传到了 A 机，
 *       下次请求被转发到 B 机就是 404。对象存储天然共享，没这个问题。
 *       充电桩运营平台尤其如此：站点分布在城市各处，后端几乎一定是多实例的</li>
 *   <li><b>和发布绑死</b> —— 文件在应用服务器磁盘上，换机器/重建容器就没了，
 *       得单独做数据卷和备份</li>
 *   <li><b>没有 CDN</b> —— 图片流量全部压在应用服务器上，
 *       而应用服务器的带宽通常远小于静态资源服务器</li>
 * </ol>
 * 所以这个类正确的定位是「开发环境的默认实现」，不是「OSS 的替代品」。
 */
@Slf4j
public class LocalFileStorage implements FileStorage {

    /** 存储根目录，已转成绝对路径 */
    private final String baseDir;

    /** URL 前缀，形如 {@code http://localhost:8081}（末尾不带 /） */
    private final String urlPrefix;

    /** 访问路径前缀，形如 {@code img}（首尾都不带 /） */
    private final String urlPath;

    public LocalFileStorage(String baseDir, String urlPrefix, String urlPath) {
        this.baseDir = resolveAgainstProjectRoot(baseDir);
        this.urlPrefix = stripTrailingSlash(urlPrefix);
        this.urlPath = stripSurroundingSlash(urlPath);
    }

    /**
     * 把相对路径锚定到项目根目录，而不是 JVM 的工作目录
     *
     * <p><b>为什么需要这一步 —— 这是实测踩出来的：</b>
     * 配置里写的是 {@code ./data/upload}，而相对路径是按「进程的工作目录」解析的，
     * 那个目录取决于你怎么启动：
     * <ul>
     *   <li>VS Code 里点 Run → 工作区根目录 → 路径正确</li>
     *   <li>{@code mvn spring-boot:run} → 项目根目录 → 路径正确</li>
     *   <li>{@code java -jar} → 你敲命令时所在的任意目录 → 路径跑飞</li>
     * </ul>
     * 同一个配置指向不同的地方，而表现只是「图片打不开」——
     * 排查时会去怀疑静态资源映射、拦截器、URL 拼错，很难想到是工作目录。
     *
     * <p>做法是从工作目录逐级往上找 {@code .git}，找到的第一层就是项目根，
     * 相对路径按它解析。找不到（源码是解压出来的 zip，或者打包后部署到服务器）
     * 就退回工作目录 —— 也就是原来的行为，不会因为找不到而启动失败。
     *
     * <p>绝对路径不受影响，原样使用。
     */
    private static String resolveAgainstProjectRoot(String baseDir) {
        Path path = Paths.get(baseDir);

        if (path.isAbsolute()) {
            return path.normalize().toString();
        }

        Path root = findProjectRoot(Paths.get("").toAbsolutePath());
        Path resolved = (root != null ? root : Paths.get("")).resolve(path).normalize();

        if (root != null) {
            log.info("相对路径 {} 已按项目根目录解析为 {}", baseDir, resolved);
        } else {
            log.warn("没找到 .git，相对路径 {} 只能按工作目录解析为 {}。"
                    + "如果文件位置不对，请把配置项改成绝对路径", baseDir, resolved);
        }
        return resolved.toString();
    }

    /** 从 start 逐级往上找含 .git 的目录；到盘符根还没找到就返回 null */
    private static Path findProjectRoot(Path start) {
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(".git"))) {
                return p;
            }
        }
        return null;
    }

    @Override
    public String upload(byte[] bytes, String objectName) {
        Path root = Paths.get(baseDir);
        Path target = root.resolve(objectName).normalize();

        // 防目录穿越。
        // 现在 objectName 由调用方用 UUID 生成，天生安全；但这个校验必须写在实现里 ——
        // 校验写在调用方的话，哪天多了一个调用方（比如把 objectName 暴露成
        // 用户可控的参数）就会漏掉。守在自己这一层，谁都绕不过去。
        // 攻击方式是传 ../../../../etc/passwd 这种名字，把文件写到目录外面去。
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件名：" + objectName);
        }

        try {
            // 支持 objectName 带子目录（比如 station/12.png）。
            // getParent() 在 objectName 只是个裸文件名时返回的就是 baseDir 本身，
            // createDirectories 对已存在的目录是安全的，不用先判断
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            // 一定要把 target 带进消息里 —— 「上传失败」这四个字对排查毫无帮助，
            // 而磁盘满 / 目录没权限 / 路径写错 全都表现为上传失败
            throw new IllegalStateException("文件写入本地磁盘失败：" + target, e);
        }

        String url = urlPrefix + "/" + urlPath + "/" + objectName;
        log.info("文件已落本地磁盘：{} → {}", target, url);
        return url;
    }

    /** 去掉末尾的 /，避免拼出 {@code http://host:8081//img/x.png} 这种双斜杠 */
    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** 首尾的 / 都去掉，让「配置里写 img 还是 /img 都对」 */
    private static String stripSurroundingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String r = s;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        return stripTrailingSlash(r);
    }

    /**
     * 解析后的真实存储目录
     *
     * <p>WebMvcConfiguration 注册静态资源映射时会来问它，而**不是**自己
     * 再用 {@code Paths.get(localDir).toAbsolutePath()} 算一遍 ——
     * 那样会用到 JVM 的工作目录，和这里锚定的项目根目录不一致，
     * 结果是「文件存到一个地方，映射指向另一个地方」，
     * 表现为上传成功但立刻 404。这个 bug 实测踩到过，
     * 启动日志里两行清清楚楚地指向了两个不同的 data 目录。
     *
     * <p>所以这里只保留一个事实来源：谁解析的，谁提供。
     */
    public String getBaseDir() {
        return baseDir;
    }
}
