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
 * <p><b>★ 只用于本地开发和演示</b>（零成本、零依赖、断网也能跑）。生产环境不能这么干：
 * 多实例部署时图片只传到 A 机，请求被负载均衡转到 B 机就是 404；文件还会和发布绑死
 * （重建容器就没了），也没有 CDN 扛流量。这个类的定位是「开发环境的默认实现」，
 * 不是「对象存储的替代品」。
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
     * <p><b>★ 为什么需要这一步 —— 实测踩出来的：</b>配置里写的是 {@code ./data/upload}，
     * 而相对路径按「进程的工作目录」解析，那个目录取决于怎么启动：
     * VS Code 点 Run → 工作区根目录，{@code mvn spring-boot:run} → 项目根目录，
     * {@code java -jar} → 敲命令时所在的任意目录。同一个配置指向不同的地方，
     * 而表现只是「图片打不开」，排查时会去怀疑静态资源映射、拦截器、URL 拼错。
     *
     * <p>做法是从工作目录逐级往上找 {@code .git}，找到的第一层就是项目根。
     * 找不到（源码是解压出来的 zip，或打包部署到服务器）就退回工作目录 ——
     * 也就是原来的行为，不会因为找不到而启动失败。绝对路径原样使用。
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

        // 防目录穿越（传 ../../../../etc/passwd 这种名字把文件写到目录外面）。
        // ★ 校验必须写在实现这一层：现在 objectName 由调用方用 UUID 生成、天生安全，
        //   但哪天多一个调用方（比如把它暴露成用户可控的参数）就会漏掉，守在这里谁都绕不过去。
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件名：" + objectName);
        }

        try {
            // 支持 objectName 带子目录（比如 station/12.png）。getParent() 在 objectName
            // 是裸文件名时返回 baseDir 本身，createDirectories 对已存在目录是安全的
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            // 一定要把 target 带进消息里 —— 「上传失败」这四个字对排查毫无帮助，
            // 而磁盘满 / 目录没权限 / 路径写错全都表现为上传失败
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
     * <p>WebMvcConfiguration 注册静态资源映射时来问它，而<b>不是</b>自己再用
     * {@code Paths.get(localDir).toAbsolutePath()} 算一遍 —— 那样会用到 JVM 工作目录，
     * 和这里锚定的项目根目录不一致，变成「文件存一个地方、映射指向另一个地方」，
     * 表现是上传成功但立刻 404（这个 bug 实测踩到过）。只保留一个事实来源。
     */
    public String getBaseDir() {
        return baseDir;
    }
}
