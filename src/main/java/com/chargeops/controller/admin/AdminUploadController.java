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
 * 管理端 - 文件上传。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护，
 * 请求头名是 {@code token}（车主端是 {@code authentication}）。
 *
 * <p>它和 {@code MemberUploadController} 内容一样但不合并：合并后只能落在两个拦截器都不管的
 * 路径上，变成一个匿名可写、不会报错的接口。
 *
 * <p>上传目录由 {@code /img/**} 匿名对外提供，所以写入口必须挂在 token 后面。
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

    /** 参数名固定是 {@code file}，前端表单里那个字段名必须和它一致，否则报的是一句和上传无关的话 */
    @PostMapping
    @Operation(summary = "上传图片（返回可访问的 URL）")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileUploadService.upload(file));
    }
}
