package com.chargeops.exception;

/**
 * 入参不合法 —— 需要跨字段或跨行才能发现的问题，DTO 注解看不了。别塞进 {@code @AssertTrue}：
 * 更新接口的 DTO 只有要改的字段，没传的是 null，会被误判成「用户要清空」。
 * 和 {@link DataConflictException} 的区别是数据本身有问题（要改表单），而不是当前状态不允许。
 */
public class ParamErrorException extends BaseException {

    public ParamErrorException() {
    }

    public ParamErrorException(String msg) {
        super(msg);
    }
}
