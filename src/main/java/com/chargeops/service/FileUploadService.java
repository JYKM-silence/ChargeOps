package com.chargeops.service;

import com.chargeops.constant.MessageConstant;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.utils.FileStorage;
import com.chargeops.utils.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传：校验 + 命名 + 落盘，两个上传入口共用的唯一一份实现
 *
 * <p>后缀白名单、后缀的取法、UUID 的生成只有下面这一份：两个 Controller 里各自
 * 只剩一句转发，没有可以「漏改」的地方。抄两份的话漏掉的那一半不会报错，
 * 它只会开始接受本来不该接受的文件，而那些文件由 {@code /img/**} 匿名对外提供
 * （那个前缀不在任何拦截器的范围内，见 {@code StorageProperties.urlPath}）。
 *
 * <p>对象名是 UUID，绝不用 {@code getOriginalFilename()}：原始文件名是用户完全可控的
 * 字符串，可以带 {@code ../}、空字节以及 {@code %00.jpg} 这类「看起来像图片」的绕过写法，
 * 拼进路径就意味着用户输入直接变成了文件系统路径。{@link LocalFileStorage} 里那道
 * {@code startsWith(root)} 守卫是兜底，不是「可以拿用户输入当路径用」的许可。
 *
 * <p>只看后缀，不看 {@code getContentType()}：那个请求头是客户端随便填的，信它等于没校验。
 * 白名单里没有 {@code svg}（SVG 是 XML，可以内嵌 {@code <script>}，被直接打开时
 * 脚本就在本站域下执行），{@code html} / {@code js} 同理。
 *
 * <p>本包其余每个类都是「接口 + impl」两个文件，这里是具体类：上传校验只有一种做法，
 * 没有第二个实现的规划。它出现 {@code MultipartFile} 这个 Web 类型是有意的 ——
 * 「空文件 / 没选文件 / 文件名里没有后缀 / 后缀不在白名单」里有三条必须看
 * {@code MultipartFile} 本身，推回 Controller 就等于把校验又抄了一遍。
 */
@Service
@Slf4j
public class FileUploadService {

    /** 允许上传的后缀白名单，全部小写。白名单而不是黑名单，理由见 {@code MessageConstant.UPLOAD_TYPE_UNSUPPORTED} */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final FileStorage fileStorage;

    public FileUploadService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 校验并保存一个上传文件，返回可以直接访问的 URL
     *
     * <p>四步校验在任何 I/O 之前完成：{@code file == null} / {@code isEmpty}、
     * 文件名里没有后缀、后缀不在白名单、前三步都过了才读字节落盘 ——
     * 被拒的文件不产生任何 I/O，也就不会在存储目录里留下一个半截文件。
     *
     * @param file 表单里名为 {@code file} 的那一项，允许为 {@code null}
     * @return 完整访问 URL，形如 {@code {urlPrefix}/{urlPath}/{uuid}.{ext}}。
     * 它会被直接存进数据库（{@code charge_package.image} / {@code member.avatar}），
     * 所以「URL 里的对象名」和「磁盘上的文件名」必须是同一个字符串 ——
     * 不一致的表现是上传接口返回成功、而图片永远 404，日志里一条错误都没有
     * @throws ParamErrorException 校验不通过，走全局异常处理器变成一句中文提示
     * @throws IllegalStateException 读上传内容失败（客户端把连接断了之类）
     */
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ParamErrorException(MessageConstant.UPLOAD_FILE_EMPTY);
        }

        // 后缀的解析放在读字节之前：一个 10MB 的 .exe 不该先被读进内存再被拒绝
        String extension = allowedExtensionOf(file.getOriginalFilename());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 一定要把文件名带进消息里：「读取上传文件失败」这五个字对排查毫无帮助，
            // 而「客户端传了一半就断了」和「临时目录满了」都表现为这一句
            throw new IllegalStateException(
                    "读取上传文件内容失败：" + file.getOriginalFilename(), e);
        }

        // 对象名 = UUID + 后缀，没有任何一个字符来自用户
        String objectName = UUID.randomUUID() + "." + extension;
        String url = fileStorage.upload(bytes, objectName);

        // 原始文件名只出现在日志里：它是「用户说他传的是哪张图」和「磁盘上那个 UUID 是谁」之间唯一的线索
        log.info("文件已上传：原始文件名 {} → {}", file.getOriginalFilename(), url);
        return url;
    }

    /**
     * 取出并校验后缀，返回白名单那一份（小写）
     *
     * <p> 判空必须排在一切字符串操作之前：{@code getOriginalFilename()} 可以返回
     * {@code null}，那样会先炸出一个和「上传」毫无关系的 NPE。
     *
     * @param originalFilename 原始文件名，允许为 {@code null}
     * @return 小写的后缀，保证在 {@link #ALLOWED_EXTENSIONS} 里
     * @throws ParamErrorException 没有后缀，或后缀不在白名单
     */
    private static String allowedExtensionOf(String originalFilename) {
        if (originalFilename == null) {
            throw new ParamErrorException(MessageConstant.UPLOAD_FILE_NAME_INVALID);
        }

        int dot = originalFilename.lastIndexOf('.');
        // 两个方向都要拦：找不到点（"avatar"）、以及点在最后一位（"avatar."）
        // —— 后者取出来是空串，不拦的话会拼出 "uuid." 这样一个没有后缀的对象名
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new ParamErrorException(MessageConstant.UPLOAD_FILE_NAME_INVALID);
        }

        String rawExtension = originalFilename.substring(dot + 1);
        String extension = rawExtension.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            // 报错里填的是原始后缀而不是小写后的那一份：这句话是给上传的人看的，
            // 他手上那个文件叫 a.JSP，回一句「不支持的文件类型：jsp」会让他再核对一遍传了什么
            throw new ParamErrorException(
                    String.format(MessageConstant.UPLOAD_TYPE_UNSUPPORTED, rawExtension));
        }
        return extension;
    }
}
