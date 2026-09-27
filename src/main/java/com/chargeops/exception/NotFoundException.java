package com.chargeops.exception;

/**
 * 记录不存在
 *
 * <p> 按 id 查不到要抛而不是返回 null：null 会被 Controller 包成 HTTP 200 的
 * {@code Result.success(null)}，前端没有任何依据区分「成功」和「查不到」，只能显示空白页。
 */
public class NotFoundException extends BaseException {

    public NotFoundException() {
    }

    public NotFoundException(String msg) {
        super(msg);
    }
}
