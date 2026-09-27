package com.chargeops.controller.admin;

import com.chargeops.result.Result;
import com.chargeops.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端 - 文件上传
 *
 * <p>路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>（请求头里那个 token 由拦截器负责）。
 * 它返回的 URL 会被管理员填进 {@code charge_package.image} 这类展示图字段 ——
 * 那些 DTO 上收的一直是「URL 字符串」，本接口就是产生那个字符串的地方。
 *
 * <h2>★★ 一、为什么它和 {@code MemberUploadController} 是两个类，而不是一个 ★★</h2>
 *
 * <p>它俩的方法体完全一样，合并成一个「通用上传」Controller 看起来更 DRY，但合不了：
 *
 * <ol>
 *   <li><b>鉴权是按路径前缀分工的</b>：{@code JwtTokenAdminInterceptor} 只盯
 *       {@code /api/admin/**}，{@code JwtTokenMemberInterceptor} 只盯 {@code /api/member/**}。
 *       一个 Controller 只能映射在一个前缀下，于是「管理员上传」和「车主上传」
 *       必然是两个入口。</li>
 *   <li><b>两套 token 的请求头名字不一样</b>（管理端 {@code token}、车主端
 *       {@code authentication}，见 {@code JwtProperties}）。合并成一个
 *       {@code /api/upload} 的话，它<b>落在两个拦截器都不管的路径上</b> ——
 *       也就是<b>任何人都能上传的匿名接口</b>，而接口本身工作得好好的，
 *       没有任何东西会提示这少了一道鉴权。</li>
 * </ol>
 *
 * <p>★ 代价是「两份一样的 Controller」。这个代价被压到了最小：
 * <b>两份里各自只有一句转发</b>，白名单、UUID 命名、四条校验全在
 * {@link FileUploadService} 里，只有一个落点（见那个类的第一节）。
 * 真正危险的是「校验逻辑抄两遍」，不是「转发语句写两遍」。
 *
 * <h2>★ 二、上传回来的图片是<b>匿名可访问</b>的，这是设计</h2>
 *
 * <p>{@code chargeops.storage.url-path}（默认 {@code /img}）不在任何拦截器覆盖范围内，
 * 所以 {@code GET /img/xxx.png} 不需要登录 —— 小程序里的 {@code <image>} 标签不会带 token，
 * 要登录才能看的话图片会全部裂掉。完整论证见 {@code StorageProperties.urlPath}。
 *
 * <p>★ 正因为它匿名可访问，「谁能往这个目录里写文件」才必须被管住：
 * 本接口的两个入口都在 JWT 后面，而文件类型由 {@link FileUploadService} 的白名单把关。
 */
@RestController
@RequestMapping("/api/admin/upload")
@Tag(name = "管理端-文件上传")
@Slf4j
public class AdminUploadController {

    private final FileUploadService fileUploadService;

    public AdminUploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    /**
     * 上传一张图片，返回它的完整访问 URL
     *
     * <p>★ 参数名固定是 {@code file}（显式写 {@code @RequestParam("file")} 而不是靠参数名推断）：
     * 它是这个接口对外契约的一部分，前端表单里那个字段名必须和它一致，
     * 不一致的表现是 {@code MultipartException: Required part 'file' is not present}
     * —— 一句和「上传」没什么关系的话。写成字面量之后，改参数名不会悄悄改掉契约。
     *
     * <p>★ <b>没有 {@code @Valid}，也没有对应的 DTO</b>：校验的对象是文件内容和文件名，
     * 不是一组字段，四条规则全在 {@link FileUploadService#upload} 里。
     */
    @PostMapping
    @Operation(summary = "上传图片（返回可访问的 URL）")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileUploadService.upload(file));
    }
}
