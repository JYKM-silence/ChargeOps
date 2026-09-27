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
 * 车主端 - 文件上传（目前只用于换头像）。路径 {@code /api/member/**}，
 * 由 {@code JwtTokenMemberInterceptor} 保护，请求头名是 {@code authentication}（管理端是 {@code token}）。
 * 和 {@code AdminUploadController} 内容一样但不合并：合并后只能落在两个拦截器都不管的路径上。
 * 上传目录由 {@code /img/**} 匿名对外提供，所以写入口必须挂在 token 后面。
 *
 * <p>它只把文件变成 URL，不写 {@code member.avatar}：换头像还要再调一次 {@code PUT /api/member/me}。
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

    /** 参数名固定是 {@code file}，前端表单字段名必须一致 */
    @PostMapping
    @Operation(summary = "上传图片（返回可访问的 URL）")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileUploadService.upload(file));
    }
}
