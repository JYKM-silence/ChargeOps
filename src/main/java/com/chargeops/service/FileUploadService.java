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
 * 文件上传：校验 + 命名 + 落盘，两个上传接口共用的<b>唯一</b>一份实现
 *
 * <h2>★★ 一、为什么校验逻辑必须在这里，而不是两个 Controller 里各抄一份 ★★</h2>
 *
 * <p>本项目有<b>两个</b>上传入口（{@code POST /api/admin/upload} 和
 * {@code POST /api/member/upload}），它们<b>不能</b>合并成一个 Controller，理由见
 * {@link com.chargeops.controller.admin.AdminUploadController}。分开之后，最顺手的写法
 * 就是「每个 Controller 里抄一遍后缀白名单」。
 *
 * <p><b>那是最坏的写法，而且坏得很安静。</b>白名单是一个集合，抄两遍就有两份，
 * 改一处漏一处；而<b>漏掉的那一半不会报错</b>，它只会开始接受本来不该接受的文件 ——
 * 收到的那些文件会被 {@code /img/**} <b>匿名地</b>对外提供（那个前缀不在任何拦截器的
 * 范围内，见 {@code StorageProperties.urlPath}）。也就是说，
 * <b>一个漏改的白名单等于一个公开的、谁都能往里放文件的托管服务</b>，
 * 而发布流程里没有任何一步会变红。
 *
 * <p>所以后缀白名单、后缀的取法、UUID 的生成、参数的判空，全部只有下面这一份。
 * 两个 Controller 里各自只剩一句转发，<b>没有可以「漏改」的地方</b>；
 * 对应的测试也是同一组用例分别打两个入口（见 {@code FileUploadControllerTest}）。
 *
 * <h2>★★ 二、对象名是 UUID，绝不用 {@code getOriginalFilename()} ★★</h2>
 *
 * <p>这是本类存在的主要理由。<b>原始文件名是用户完全可控的字符串</b>，它可以带
 * {@code ../}、{@code ..\}、{@code /}、空字节，以及 {@code %00.jpg} 这类
 * 「看起来像图片」的绕过写法。把它拼进路径，意味着用户输入直接变成了文件系统路径。
 *
 * <p>★ <b>不要指望 {@link LocalFileStorage} 里那道 {@code startsWith(root)} 守卫来兜这件事。</b>
 * 那道守卫是<b>兜底</b>：它是在「恶意名字已经被拼成路径之后」才生效的，它的存在不是
 * 「可以拿用户输入当路径用」的许可。而且它只守得住本地磁盘这一种实现 ——
 * 换成对象存储时，key 里的 {@code ../} 是写到别人的前缀下，那是另一种更贵的事故。
 * 用 UUID 命名，这条攻击面根本不存在，守卫也就永远不必被触发。
 *
 * <p>★ 它顺带解决的还有另一件<b>静默</b>的事故：两个人上传同名的 {@code avatar.png}，
 * 后者覆盖前者，而前者的数据库里那条 URL 仍然指向同一个文件 ——
 * 用户看到的是「我的头像变成了别人的脸」，两个人都没做错任何事。
 *
 * <h2>★ 三、后缀从白名单那一份取：只看后缀，且大小写不敏感</h2>
 *
 * <p>{@code .JPG} 通过，但<b>写进对象名的是小写的那一份</b> ——
 * 同一个类型在库里、在 URL 里、在磁盘上只有一种写法，排查时不必再去猜大小写。
 *
 * <p>★ 判断<b>只看后缀，不看 {@code getContentType()}</b>：那个请求头是客户端随便填的，
 * 信它等于没校验（一个 {@code evil.jsp} 带上 {@code image/png} 就进来了）。
 * 反过来，{@code /img/**} 静态映射返回的 {@code Content-Type} 是由<b>扩展名</b>
 * 推断的、同样不看客户端声明 —— 所以「按后缀白名单」与实际的风险面
 * （浏览器最终拿它当什么处理）是对齐的。
 *
 * <p>★ <b>白名单里没有 {@code svg}</b>：它是图片，但 SVG 是 XML，可以内嵌
 * {@code <script>}，被直接打开时脚本就在本站域下执行 —— 那是一条 XSS。
 * 真需要 SVG 时应当先做净化，而不是往这个集合里加一行。
 * 同理，{@code html} / {@code js} / {@code jsp} 这类「一旦放进来就会被静态映射原样提供」
 * 的类型一个都不收。
 *
 * <h2>★ 四、为什么是具体的 {@code @Service} 类，没有接口 + 实现两个文件</h2>
 *
 * <p>本包（{@code com.chargeops.service}）里其余每一个类都是「接口 + impl」两个文件，
 * 那套写法的意义有两个：<b>换实现时调用方不用改</b>（{@link FileStorage} 就是为这个
 * 而存在的，见它的类注释），以及<b>测试里可以手写桩</b>。这两条在这里都不成立 ——
 * 上传校验只有一种做法，没有第二个实现的规划；也没有任何测试需要替换它。
 *
 * <p>★ 为一件只有一种做法的事先立一个接口，是在仓库里放一段永远不会被第二个实现
 * 证伪的抽象：读代码的人会以为「这里可以换」，然后去找那个并不存在的第二份实现。
 * 这与 {@code StorageProperties} 里「对象存储是有意留空的、不要现在就补上」是同一个判断。
 *
 * <h2>★ 五、为什么这里会出现 {@code MultipartFile}（一个 Web 类型）</h2>
 *
 * <p>分层上它确实越了界：{@link FileStorage} 刻意只收 {@code byte[]} 和 {@code String}，
 * 是为了不认识 Web。但「空文件 / 没选文件 / 文件名里没有后缀 / 后缀不在白名单」
 * 这四条判断里有三条必须看 {@code MultipartFile} 本身（{@code file == null} 尤其如此），
 * 换成 {@code (String originalFilename, byte[] bytes)} 的签名，就等于把这几条校验
 * <b>推回两个 Controller 各写一遍</b> —— 正是第一节要消灭的东西。
 *
 * <p>★ 所以边界划在这里：{@code FileStorage} 是存储接口（不认识 Web），
 * 本类是 Web 上传入参到「字节 + 对象名」的<b>适配层</b>，Web 类型停在它这一层，
 * 再往下就只剩两样东西。
 */
@Service
@Slf4j
public class FileUploadService {

    /**
     * 允许上传的后缀白名单，全部小写
     *
     * <p>★ <b>白名单而不是黑名单</b>，理由见 {@code MessageConstant.UPLOAD_TYPE_UNSUPPORTED}：
     * 黑名单（禁 {@code jsp} / {@code phtml} / …）永远漏，而漏掉的那一个会被写进
     * 静态资源目录、由 {@code /img/**} 直接对外提供。
     *
     * <p>★ 这份集合是<b>整个项目里唯一的一份</b>：两个上传入口都走
     * {@link #upload(MultipartFile)}，没有第二个地方判断后缀 —— 见类注释第一节。
     */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final FileStorage fileStorage;

    public FileUploadService(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 校验并保存一个上传文件，返回可以直接访问的 URL
     *
     * <p>四步校验，顺序是「先便宜的、后昂贵的」，并且在任何 I/O 之前完成：
     * <ol>
     *   <li><b>{@code file == null} / {@code file.isEmpty()}</b> →
     *       {@link MessageConstant#UPLOAD_FILE_EMPTY}。
     *       表单里选了一个 0 字节的文件、或者压根没选文件，都会落到这里。
     *       ★ 不拦的话后面是「读了 0 个字节、然后写了一个空文件」——
     *       它<b>会成功</b>并返回一个能打开的 URL，用户得到一张永远加载不出来的图，
     *       而接口一声不吭。这是本方法拒绝得最便宜、也最容易被漏掉的一条。</li>
     *   <li><b>文件名里没有后缀</b> → {@link MessageConstant#UPLOAD_FILE_NAME_INVALID}。
     *       ★ 它和「不支持的类型」<b>刻意分成两句</b>：前者是「看不出这是什么」，
     *       后者是「看得出，但不在允许范围内」，下一步该做什么不一样。
     *       {@code getOriginalFilename()} 返回 {@code null} 也落在这里
     *       （它同样「没有后缀」），所以这一支不会 NPE。</li>
     *   <li><b>后缀不在白名单</b> → {@link MessageConstant#UPLOAD_TYPE_UNSUPPORTED}
     *       （{@code String.format} 把<b>原始后缀</b>填进去，见下面的实现注释）。</li>
     *   <li>前三步都过了才<b>读字节、才落盘</b> —— 被拒的文件不产生任何 I/O，
     *       也就不会在存储目录里留下一个半截文件。</li>
     * </ol>
     *
     * @param file 表单里名为 {@code file} 的那一项，允许为 {@code null}
     * @return 完整访问 URL，形如 {@code {urlPrefix}/{urlPath}/{uuid}.{ext}}。
     * ★ 它会被<b>直接存进数据库</b>（{@code charge_package.image} / {@code member.avatar}），
     * 所以「URL 里的对象名」和「磁盘上的文件名」必须是同一个字符串 ——
     * 不一致的表现是上传接口返回成功、而图片永远 404，日志里一条错误都没有
     * @throws ParamErrorException 校验不通过（上面四条），走全局异常处理器变成一句中文提示
     * @throws IllegalStateException 读上传内容失败（客户端把连接断了之类）。
     * ★ 转成运行时异常而不是声明 checked exception，理由同 {@link FileStorage} 的类注释：
     * 让兜底处理器把它<b>连堆栈一起</b>记进日志，而不是变成一个空 catch
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

        // ★★ 对象名 = UUID + 后缀，没有任何一个字符来自用户。见类注释第二节。★★
        String objectName = UUID.randomUUID() + "." + extension;
        String url = fileStorage.upload(bytes, objectName);

        // ★ 原始文件名只出现在日志里，不进路径、不进返回的 URL。
        //   它是「用户说他传的是哪张图」和「磁盘上那个 UUID 是谁」之间唯一的线索
        log.info("文件已上传：原始文件名 {} → {}", file.getOriginalFilename(), url);
        return url;
    }

    /**
     * 取出并校验后缀，返回<b>白名单那一份（小写）</b>
     *
     * <p>★ <b>不要在 {@code originalFilename} 上直接 {@code toLowerCase()}：</b>
     * {@code getOriginalFilename()} 可以返回 {@code null}（表单里没有 filename 段，
     * 或者客户端构造了一个不带它的 part），那样会先炸出一个和「上传」毫无关系的
     * NPE —— 用户看到「未知错误」，而真正的原因是这个文件名压根没传。
     * 判空必须排在一切字符串操作<b>之前</b>，并且归到「没有后缀」那一类。
     *
     * <p>★ 返回<b>规范化后的小写</b>而不是原始后缀：调用方拿它拼对象名，
     * 于是 {@code .PNG} 和 {@code .png} 在磁盘上是同一种文件，见类注释第三节。
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
            // ★ 报错里填的是【原始后缀】而不是小写后的那一份：这句话是给上传的人看的，
            //   他手上那个文件叫 a.JSP，回一句「不支持的文件类型：jsp」会让他再去核对一遍
            //   自己到底传了什么。对象名（机器读的）用小写规范形式，提示语（人读的）用原始形式
            throw new ParamErrorException(
                    String.format(MessageConstant.UPLOAD_TYPE_UNSUPPORTED, rawExtension));
        }
        return extension;
    }
}
