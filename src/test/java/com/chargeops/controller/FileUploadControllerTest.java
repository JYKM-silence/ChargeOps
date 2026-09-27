package com.chargeops.controller;

import com.chargeops.constant.MessageConstant;
import com.chargeops.controller.admin.AdminUploadController;
import com.chargeops.controller.user.MemberUploadController;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.properties.StorageProperties;
import com.chargeops.result.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 两个上传入口（{@code POST /api/admin/upload} / {@code POST /api/member/upload}）的集成测试
 *
 * <h2>★ 一、每一条用例都<b>同时打两个入口</b></h2>
 *
 * <p>校验逻辑只有一份（{@code FileUploadService}），但入口有两个 —— 于是最常见的坏写法
 * 就是「某个 Controller 里自己抄了一份白名单」。测试只打一个入口的话，那个抄出来的
 * 第二份<b>不会被任何断言发现</b>：它的表现只是「那个入口开始接受它不该接受的文件」，
 * 没有任何报错，而那些文件还会被 {@code /img/**} 匿名地提供出去。
 *
 * <p>所以本类里所有用例都通过 {@link #realms()} 同时打两个入口，入口标签进断言消息 ——
 * 失败时能立刻看出是哪一个入口漏了，而不是只知道「有一个入口坏了」。
 *
 * <h2>★ 二、直接调 Controller 的方法，不走 MockMvc</h2>
 *
 * <p>仓库里没有任何 MockMvc 用法。两个入口唯一的差别是路径前缀，而<b>路径前缀是拦截器
 * 的事</b>（{@code /api/admin/**} 与 {@code /api/member/**}）—— 架一个 MockMvc 只会把
 * JWT 那一层拉进来，对本类要证明的东西（校验、命名、落盘）一点帮助都没有，
 * 还得为它准备两套 token。随 HTTP 层变化的那一小部分（参数名 {@code file}、
 * multipart 绑定）由两个 Controller 各自的一行签名固定住，改错了是响亮的
 * {@code MultipartException}，不是静默失败。
 *
 * <h2>★★ 三、存储目录指到临时目录：为什么不用 {@code @TempDir}</h2>
 *
 * <p>{@code LocalFileStorage} 的 {@code baseDir} 是在 <b>Bean 构造时</b>算出来的
 * （见 {@code LocalFileStorage} 的类注释），所以「换目录」必须发生在
 * ApplicationContext 创建之前 —— 也就是 {@link DynamicPropertySource}。
 *
 * <p>★ <b>这里用自己 {@code Files.createTempDirectory} 出来的目录，而不用
 * {@code @TempDir}：</b>{@code @TempDir} 的字段注入是 JUnit 扩展做的，它和 Spring
 * 上下文创建之间的先后顺序<b>不是这个测试该依赖的东西</b>；静态字段初始化则一定发生在
 * 任何属性解析之前，没有歧义。另外每次 JVM 一个独立目录，让本类可以和别的测试
 * <b>并发</b>跑（仓库里其余测试共用一个开发库，见 {@code ChargingOrderSweepJobTest}
 * 的类注释）—— 谁都不该往共享目录里写文件。
 *
 * <h2>★ 四、清理的是磁盘，没有 JdbcTemplate</h2>
 *
 * <p>本类<b>一行库都不写</b>，所以没有 {@code TEST_ID_BASE} 哨兵区间要清 —— 仓库里其余
 * 集成测试的 {@code @AfterEach} 在用 JdbcTemplate 删数据，是因为它们造了 member /
 * charging_order 那些行，那是它们的清理对象。本类的清理对象是文件：
 * 每次成功上传得到的对象名都记进 {@link #writtenObjectNames}，{@code @AfterEach} 逐个删；
 * {@code @AfterAll} 再断言目录里没有「本类没认领的文件」。
 *
 * <h2>★★★ 五、本类最值钱的三条断言</h2>
 *
 * <ol>
 *   <li><b>URL 里的对象名 == 磁盘上的文件名</b>（{@link #assertUploaded} 里同时断言
 *       {@code Files.exists} 和字节内容）。这两件事一旦不一致，表现是「上传接口返回成功、
 *       图片永远 404，日志里一条错误都没有」—— 而它已经在这个项目里真实发生过一次
 *       （{@code LocalFileStorage} 的 {@code getBaseDir} 注释记着）。</li>
 *   <li><b>对象名里没有任何一个字符来自原始文件名</b>
 *       （{@link #objectNameMustNotComeFromTheOriginalFilename}）：断言 URL 里不含
 *       那个探针 token、且「如果实现真拿原始名拼路径，文件会落到的那个位置」上什么都没有。</li>
 *   <li><b>同名文件互不覆盖</b>（{@link #sameFilenameShouldNotOverwriteThePreviousUpload}）：
 *       两次 {@code avatar.png} 必须得到两个 URL，且第一张图还在原地 ——
 *       覆盖掉的表现是「我的头像变成了别人的脸」，两个用户都没做错任何事。</li>
 * </ol>
 */
@SpringBootTest
class FileUploadControllerTest {

    @Autowired
    private AdminUploadController adminUploadController;

    @Autowired
    private MemberUploadController memberUploadController;

    /** ★ 只用来读 url-prefix / url-path，用来断言返回的 URL 形状 —— 见 {@link #assertUploaded} */
    @Autowired
    private StorageProperties storageProperties;

    /**
     * 本类的存储根目录，每个 JVM 一个
     *
     * <p>★ 静态字段的初始化一定发生在 Spring 解析 {@code @DynamicPropertySource} 之前，
     * 所以 {@link #pointStorageAtTempDir} 里那个 supplier 取到的永远是同一个非空目录。
     * 它必须同时被「指向配置」和「断言文件真的落地」两处用到，且必须是<b>同一个值</b>。
     */
    private static final Path UPLOAD_DIR = createTempDir();

    /**
     * 对象名的形状：UUID + 小写后缀
     *
     * <p>★ 它把两件事一起钉死：名字<b>只能</b>是 UUID（任何原始文件名的痕迹都会让它不匹配），
     * 后缀<b>只能</b>是小写的白名单那一份。断言一个正则比断言「不等于原始文件名」更强 ——
     * 后者永远只能证明你想到的那一种构造方式不存在。
     */
    private static final Pattern UUID_OBJECT_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z]{2,5}$");

    /** 测试用的假图片内容。★ 具体的字节不重要，重要的是它<b>非空</b>（空文件会被拒） */
    private static final byte[] CONTENT = "假装这是一张 PNG".getBytes(StandardCharsets.UTF_8);

    /** 本类写出去的对象名，{@code @AfterEach} 逐个删 */
    private final List<String> writtenObjectNames = new ArrayList<>();

    /**
     * 把存储根目录指到本类的临时目录
     *
     * <p>★ 必须是 {@code static} 且要在上下文创建之前注册 —— 见类注释第三节。
     * 这里改的是 {@code chargeops.storage.local-dir}，不是
     * {@code chargeops.storage.url-prefix}：URL 前缀留在配置里原样使用，
     * 断言里正好用它来验证「返回的 URL 确实按配置拼出来的」。
     */
    @DynamicPropertySource
    static void pointStorageAtTempDir(DynamicPropertyRegistry registry) {
        registry.add("chargeops.storage.local-dir", () -> UPLOAD_DIR.toString());
    }

    @AfterEach
    void deleteWrittenFiles() {
        for (String objectName : writtenObjectNames) {
            deleteQuietly(UPLOAD_DIR.resolve(objectName));
        }
        writtenObjectNames.clear();
    }

    /**
     * 收尾：存储目录必须是空的，然后删掉它
     *
     * <p>★ 那句 {@code isEmpty} 断言不是在洁癖：走到这里时 {@code @AfterEach} 已经把
     * 本类认领的文件都删了，<b>还剩东西就说明有一条路径把文件写到了我们不知道的地方</b>
     * （最典型的是「校验失败却已经落盘」）。它在这里比在任何单条用例里都更全面，
     * 因为它是所有用例跑完之后的总账。
     */
    @AfterAll
    static void deleteUploadDir() {
        assertThat(filesInUploadDir())
                .as("★★ 存储目录里不该有本类没认领的文件：有就说明某条路径在没登记的情况下写了盘")
                .isEmpty();
        deleteQuietly(UPLOAD_DIR);
    }

    // ==================== 工具方法 ====================

    /**
     * 两个入口 + 给人看的标签
     *
     * <p>★ 用 {@code LinkedHashMap} 而不是 {@code Map.of}：顺序固定，日志和失败消息
     * 每次都是同一个样子（{@code Map.of} 的迭代顺序是未定义的）。
     */
    private Map<String, Function<MultipartFile, Result<String>>> realms() {
        Map<String, Function<MultipartFile, Result<String>>> realms = new LinkedHashMap<>();
        realms.put("管理端 /api/admin/upload", adminUploadController::upload);
        realms.put("车主端 /api/member/upload", memberUploadController::upload);
        return realms;
    }

    /**
     * 走一次「应当成功」的上传，并把它写出去的文件名登记下来
     *
     * <p>★ <b>登记必须发生在任何断言之前</b>：断言失败时测试方法会立刻退出，
     * 而那个文件已经在磁盘上了 —— 先断言再登记的话，{@code @AfterEach} 就删不掉它，
     * 于是 {@code @AfterAll} 那条「目录必须是空的」会以一个和真正的失败毫无关系的样子红。
     *
     * @return 接口返回的完整 URL
     */
    private String assertUploaded(Function<MultipartFile, Result<String>> api, String label,
                                  String originalFilename, String contentType, byte[] content) {
        Result<String> result = api.apply(
                new MockMultipartFile("file", originalFilename, contentType, content));

        assertThat(result.getCode())
                .as("%s：%s 应当上传成功（msg=%s）", label, originalFilename, result.getMsg())
                .isEqualTo(1);
        assertThat(result.getMsg()).as("%s：成功时不该有错误信息", label).isNull();
        String url = result.getData();
        assertThat(url).as("%s：data 必须是给前端的完整 URL（会被直接存进数据库）", label).isNotNull();

        String objectName = objectNameOf(url);
        writtenObjectNames.add(objectName);

        assertThat(url)
                .as("%s：URL 必须以配置的 url-prefix 开头", label)
                .startsWith(storageProperties.getUrlPrefix());
        assertThat(url)
                .as("%s：URL 必须落在配置的 url-path（%s）上 —— 对不上的话静态资源映射看不见它，"
                        + "图片 404 而上传接口一切正常", label, storageProperties.getUrlPath())
                .contains("/" + urlPathSegment() + "/");
        assertThat(objectName)
                .as("%s：URL 的最后一段就是对象名，它必须是 UUID + 白名单后缀，实际是 %s", label, objectName)
                .matches(UUID_OBJECT_NAME);

        Path stored = UPLOAD_DIR.resolve(objectName);
        assertThat(stored)
                .as("%s：★★ URL 里的对象名必须在存储根目录下真实存在（%s）—— "
                        + "URL 与磁盘上的文件名不一致就是「上传成功但图片永远 404」", label, stored)
                .exists();
        assertThat(stored)
                .as("%s：写进磁盘的字节必须和上传的一模一样", label)
                .hasBinaryContent(content);
        return url;
    }

    /**
     * 走一次「应当被拒」的上传，断言异常类型和消息
     *
     * <p>★ 参数类型是 {@link MultipartFile} 而不是 {@code MockMultipartFile}：
     * 因为「压根没选文件」这一种情况在容器里的样子就是 {@code null}，
     * 它没有 originalFilename 可读，消息只能由调用方在 label 里说清。
     */
    private static void assertRejected(Function<MultipartFile, Result<String>> api, String label,
                                       MultipartFile file, String expectedMessage) {
        assertThatThrownBy(() -> api.apply(file))
                .as("%s 必须被拒绝", label)
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(expectedMessage);
    }

    /** 取 URL 的最后一个路径段，也就是对象名 */
    private static String objectNameOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /**
     * 配置里 {@code url-path} 的那一段裸值（去掉首尾斜杠）
     *
     * <p>★ {@code LocalFileStorage} 会把配置里的 {@code /img} 归一成 {@code img} 再拼 URL，
     * 所以断言「URL 里有 /img/」时也得做同样的归一 —— 直接在断言里复制这一行，
     * 比为了让测试能调用它去改生产代码的形状划算（那一行只是「两种写法都接受」）。
     */
    private String urlPathSegment() {
        return storageProperties.getUrlPath().replace("/", "");
    }

    private static List<Path> filesInUploadDir() {
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            return files.toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列不出存储目录：" + UPLOAD_DIR, e);
        }
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("chargeops-upload-test-");
        } catch (IOException e) {
            throw new UncheckedIOException("建不出测试用的临时目录", e);
        }
    }

    /**
     * 删文件，失败就算了
     *
     * <p>★ 收尾阶段的失败不该盖住真正的失败：一个测试方法已经因为断言失败而红的时候，
     * 清理再抛异常只会让输出里多一段噪音，甚至把真正的原因挤掉。
     * 而「有没有删干净」这件事由 {@code @AfterAll} 的总账断言负责，不靠这里。
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 故意的：见上面的注释
        }
    }

    // ==================== 一、空文件 ====================

    @Test
    @DisplayName("★ 空文件被拒：file 为 null、0 字节的文件，两个入口都报「上传文件为空」")
    void emptyFileShouldBeRejected() {
        realms().forEach((label, api) -> {
            assertRejected(api, label + "：file 为 null（表单里没有这个 part）",
                    null, MessageConstant.UPLOAD_FILE_EMPTY);

            assertRejected(api, label + "：0 字节的文件（选了文件，但内容是空的）",
                    new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]),
                    MessageConstant.UPLOAD_FILE_EMPTY);
        });

        // ★★ 为什么这一条必须拦：不拦的话后面走的是「读了 0 个字节、然后写了一个空文件」——
        //    它会成功、会返回一个能打开的 URL，用户得到一张永远加载不出来的图，
        //    而接口一声不吭。空文件和「没选文件」对上传者也是同一件事：重选一张。
        assertThat(filesInUploadDir())
                .as("★ 被拒的上传不能在存储目录里留下任何文件").isEmpty();
    }

    // ==================== 二、白名单后缀 ====================

    @Test
    @DisplayName("★ 五个白名单后缀全部通过，文件按 URL 里的对象名真实落盘、内容一字不差")
    void everyWhitelistedExtensionShouldPass() {
        // ★ 逐个跑一遍，而不是挑一个代表：白名单是这份契约本身，
        //   将来有人往 ALLOWED_EXTENSIONS 里加一项、或者手滑删掉 gif，
        //   这一条会红；否则那个改动只会表现为「某些用户上传失败/某类文件被放进来」。
        for (String extension : List.of("jpg", "jpeg", "png", "gif", "webp")) {
            realms().forEach((label, api) -> {
                String url = assertUploaded(api, label, "photo." + extension,
                        "image/" + extension, CONTENT);

                assertThat(url)
                        .as("%s：.%s 在白名单里，必须通过并把同一份后缀写进对象名", label, extension)
                        .endsWith("." + extension);
                // 对象名的完整形状（UUID + 小写后缀）由 assertUploaded 里的正则钉死，
                // 这里不重复断言，只补一条「后缀是它自己」的对应关系
            });
        }
    }

    @Test
    @DisplayName("★ 后缀大小写不敏感：.JPG / .PNG 通过，且对象名里统一落成小写的那一份")
    void uppercaseExtensionShouldPass() {
        realms().forEach((label, api) -> {
            String jpg = assertUploaded(api, label, "PHOTO.JPG", "image/jpeg", CONTENT);
            assertThat(jpg)
                    .as("%s：★ .JPG 必须在对象名里落成 .jpg —— 同一个类型在库里、在 URL 里、"
                            + "在磁盘上只该有一种写法，否则排查时还要去猜大小写", label)
                    .endsWith(".jpg");
            assertThat(jpg)
                    .as("%s：原始文件名里的字符一个都不该出现在 URL 里", label)
                    .doesNotContain("PHOTO");

            String png = assertUploaded(api, label, "AVATAR.PNG", "image/png", CONTENT);
            assertThat(png).as("%s：同理 .PNG → .png", label).endsWith(".png");
            assertThat(png).as("%s：原始文件名不该出现在 URL 里", label).doesNotContain("AVATAR");
        });
    }

    @Test
    @DisplayName("★★ 非白名单后缀一律被拒，提示语里带上他实际传的后缀，且磁盘上不留任何文件")
    void unsupportedExtensionShouldBeRejected() {
        // 原始文件名 → 会被填进提示语里的那一段后缀。每一条都有它非在这里不可的理由：
        //   shell.jsp     容器能当代码执行的那种类型，是「黑名单永远漏」的典型
        //   logo.svg      ★ 它是图片，但 SVG 是 XML，可以内嵌 <script> ——
        //                 直接打开时脚本就在本站域下执行，那是一条 XSS
        //   page.html     会被 /img/** 原样当作页面提供出去
        //   shell.JSP     全大写：大小写不敏感不能变成「大小写不敏感地放行」
        //   photo.webp.exe 双后缀，取的是最后一个点之后的那一段（取第一个点就漏了）
        //   报告.PDF      中文名 + 大写，确认「取后缀」和文件名的其余部分无关
        Map<String, String> rejectedNames = new LinkedHashMap<>();
        rejectedNames.put("shell.jsp", "jsp");
        rejectedNames.put("logo.svg", "svg");
        rejectedNames.put("page.html", "html");
        rejectedNames.put("shell.JSP", "JSP");
        rejectedNames.put("photo.webp.exe", "exe");
        rejectedNames.put("报告.PDF", "PDF");

        realms().forEach((label, api) -> rejectedNames.forEach((originalFilename, rawExtension) ->
                assertRejected(api,
                        label + "：." + rawExtension + " 不在白名单里（" + originalFilename + "）",
                        new MockMultipartFile("file", originalFilename, "image/png", CONTENT),
                        // ★ 提示语里填的是【原始后缀】不是小写后的那一份：
                        //   这句话是给上传的人看的，他手上那个文件叫 .JSP，
                        //   回一句「不支持的文件类型：jsp」会让他再去核对自己传了什么
                        String.format(MessageConstant.UPLOAD_TYPE_UNSUPPORTED, rawExtension))));

        assertThat(filesInUploadDir())
                .as("★★ 被拒的上传一个文件都不该写到磁盘上：校验必须排在落盘之前 —— "
                        + "先写后拒会在这个目录里留下一个永远没人认领、却能被匿名访问的文件")
                .isEmpty();
    }

    // ==================== 三、没有后缀 ====================

    @Test
    @DisplayName("★ 没有后缀被拒：「avatar」「avatar.」和 originalFilename 为 null 都报「缺少扩展名」")
    void missingExtensionShouldBeRejected() {
        realms().forEach((label, api) -> {
            assertRejected(api, label + "：文件名里根本没有点（avatar）",
                    new MockMultipartFile("file", "avatar", "image/png", CONTENT),
                    MessageConstant.UPLOAD_FILE_NAME_INVALID);

            assertRejected(api, label + "：点在最后一位，取出来是空后缀（avatar.）",
                    new MockMultipartFile("file", "avatar.", "image/png", CONTENT),
                    MessageConstant.UPLOAD_FILE_NAME_INVALID);

            // ★★ 这一条防的是一个 NPE：getOriginalFilename() 可以返回 null（表单里没有 filename 段，
            //    或者客户端构造了一个不带它的 part）。在它上面直接 lastIndexOf / toLowerCase
            //    会炸出一个和「上传」毫无关系的空指针，用户看到「未知错误」——
            //    而真正的原因是这个文件名压根没传。
            assertRejected(api, label + "：originalFilename 为 null",
                    new MockMultipartFile("file", null, "image/png", CONTENT),
                    MessageConstant.UPLOAD_FILE_NAME_INVALID);
        });

        // ★ 「不能有半截文件」在这里同样成立，且这一组最容易破坏它 —— 见上面第三条
        assertThat(filesInUploadDir())
                .as("★ 没有后缀的文件同样不该在磁盘上留下任何痕迹").isEmpty();

        // ★ 边界：只有扩展名的文件名（.png）【不是】「没有后缀」——
        //   点在第一位，最后一位有点但不是它。这条不写下来的话，将来有人把判据
        //   顺手改成「点在开头也算没有名字」，测试全绿而行为已经变了
        realms().forEach((label, api) -> assertUploaded(api, label, ".png", "image/png", CONTENT));
    }

    // ==================== 四、对象名不是原始文件名 ====================

    @Test
    @DisplayName("★★★ 对象名绝不是原始文件名：../../evil.png 这类名字进不了路径，文件只落在存储根目录下")
    void objectNameMustNotComeFromTheOriginalFilename() {
        // ★ token 是唯一的：「URL 里有没有原始文件名的痕迹」和「如果实现真拿原始名拼路径，
        //   那个文件会落在哪」这两件事因此都能被精确断言，不会因为别的测试碰巧同名而假绿
        String token = "chargeops-escape-probe-" + System.nanoTime();
        String sep = "\\";

        // 每一条都是「把用户输入当路径用」会真的出事的写法
        List<String> hostileNames = List.of(
                "../../" + token + ".png",
                ".." + sep + ".." + sep + token + ".png",
                "/etc/" + token + ".png",
                "C:" + sep + "Windows" + sep + "Temp" + sep + token + ".png",
                token + ".png%00.jpg");

        realms().forEach((label, api) -> hostileNames.forEach(hostileName -> {
            String url = assertUploaded(api, label, hostileName, "image/png", CONTENT);

            // ★★★ 这两条是本类最值钱的断言。★★★
            // 原始文件名是用户完全可控的字符串：它可以带 ../、反斜杠、绝对路径、空字节，
            // 以及 %00.jpg 这类「看起来还是张图」的绕过写法。把它拼进路径的实现，
            // 写文件那一刻是【静默】的 —— 它照样返回一个成功的 URL，只是那个文件
            // 已经跑到存储目录外面去了，或者覆盖掉了别人的文件。
            assertThat(url)
                    .as("★★★ %s：URL 里不能出现原始文件名的任何一个字符（传进去的是 %s）",
                            label, hostileName)
                    .doesNotContain(token)
                    .doesNotContain("..")
                    .doesNotContain("etc")
                    .doesNotContain("Windows");
            assertThat(objectNameOf(url))
                    .as("★★★ %s：对象名必须是 UUID + 后缀，不能是原始文件名的任何变形", label)
                    .matches(UUID_OBJECT_NAME);
        }));

        // ★ 反过来证明「穿越确实没有发生」：如果实现真的拿原始文件名拼了路径，
        //   文件会落在下面这些位置上 —— 它们必须全都不存在。
        //   （LocalFileStorage 里那道 startsWith(root) 守卫正是为这种写法准备的兜底，
        //    它拦得住；但那是第二道防线，第一道是这里根本不该把用户输入当路径。）
        assertThat(UPLOAD_DIR.resolve("../../" + token + ".png")).doesNotExist();
        assertThat(UPLOAD_DIR.resolve("../" + token + ".png")).doesNotExist();
        assertThat(UPLOAD_DIR.resolve(token + ".png"))
                .as("★ 连「把目录剥掉、只留文件名」那种实现也不该留下痕迹").doesNotExist();
    }

    // ==================== 五、同名文件 ====================

    @Test
    @DisplayName("★★ 同名的两个文件互不覆盖：两次 avatar.png 得到两个 URL，两张图都还在")
    void sameFilenameShouldNotOverwriteThePreviousUpload() {
        byte[] firstContent = "第一个人的头像".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "第二个人的头像".getBytes(StandardCharsets.UTF_8);

        realms().forEach((label, api) -> {
            String first = assertUploaded(api, label, "avatar.png", "image/png", firstContent);
            String second = assertUploaded(api, label, "avatar.png", "image/png", secondContent);

            // ★★ 用原始文件名做对象名的实现在这里会红：两次上传返回的是【同一个 URL】。
            //    后果是第二次覆盖了第一次的文件，而第一个人库里那条 URL 一个字符都没变 ——
            //    他看到的是「我的头像变成了别人的脸」，两个人都没做错任何事，
            //    日志里也什么都看不出来。
            assertThat(second)
                    .as("★★ %s：同一个原始文件名必须得到两个不同的 URL", label)
                    .isNotEqualTo(first);
            assertThat(UPLOAD_DIR.resolve(objectNameOf(first)))
                    .as("★ %s：第一张图必须还在原地，内容也没有被第二张覆盖", label)
                    .hasBinaryContent(firstContent);
        });
    }
}
