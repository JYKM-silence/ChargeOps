package com.chargeops.controller.user;

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
 * 车主端 - 文件上传（当前唯一的使用场景是换头像）
 *
 * <p>路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>（请求头里那个 {@code authentication} 由拦截器负责）。
 * 它返回的 URL 会被塞进 {@code MemberUpdateDTO.avatar} 再存进 {@code member.avatar}。
 *
 * <h2>★★ 一、为什么它和 {@code AdminUploadController} 是两个类，而不是一个 ★★</h2>
 *
 * <p>完整论证见 {@link AdminUploadController} 第一节，两句话概括：
 * <b>鉴权按路径前缀分工</b>（{@code /api/admin/**} 与 {@code /api/member/**} 各一个拦截器），
 * <b>两套 token 的请求头名字也不同</b>（{@code token} / {@code authentication}）——
 * 合并到一个 {@code /api/upload} 上，等于把它放到两个拦截器都不管的路径下，
 * 变成一个匿名可写的接口，而且不会有任何报错。
 *
 * <p>★ <b>两个类里各自只有一句转发</b>：白名单、UUID 命名、四条校验都只有
 * {@link FileUploadService} 里那一份，所以「分成两个类」不会带来「两处要同步改」。
 *
 * <h2>★ 二、头像和 {@code /me} 是两条独立的路</h2>
 *
 * <p>本接口<b>只负责把文件变成 URL</b>，不写 {@code member.avatar} ——
 * 换头像要等车主随后调 {@code PUT /api/member/me}，把返回的 URL 传进
 * {@code MemberUpdateDTO.avatar}。两步是刻意的：上传可以先做，
 * 用户在中途放弃换头像时也不会留下一次「头像已经变了」的副作用。
 *
 * <p>★ 这也是 {@code MemberUpdateDTO} 里 {@code avatar} 字段那行注释
 * 「只存 URL，不做上传」的实现侧：本接口负责前半句，那个 DTO 负责后半句。
 *
 * <h2>★ 三、为什么要登录才能上传</h2>
 *
 * <p>上传会往服务器磁盘（或对象存储）写一个真实文件，是一个有成本的写操作。
 * 放开成匿名接口，等于给任何人一个免费的文件托管 —— 而存下来的文件还会被
 * {@code /img/**} 匿名地提供出去。所以它必须挂在车主 token 后面，
 * 而不是做成「登录页也要用，所以先放行」。
 */
@RestController
@RequestMapping("/api/member/upload")
@Tag(name = "车主端-文件上传")
@Slf4j
public class MemberUploadController {

    private final FileUploadService fileUploadService;

    public MemberUploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    /**
     * 上传一张图片（头像），返回它的完整访问 URL
     *
     * <p>★ 参数名固定是 {@code file}，理由见 {@link AdminUploadController#upload}：
     * 它是接口契约的一部分，靠参数名推断的话改个局部变量名就会把契约改掉。
     */
    @PostMapping
    @Operation(summary = "上传图片（返回可访问的 URL）")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileUploadService.upload(file));
    }
}
