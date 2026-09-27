package com.chargeops.exception;

/**
 * 数据冲突 —— 请求本身没问题，但当前数据状态不允许这个操作
 *
 * <p> 有子记录不能删、乐观锁失败、状态不允许、被引用不能改，共同点是「换个时机或换个
 * 做法就能成」。所以消息必须说清替代做法（「请先删除或转移」而不是「存在关联数据」），
 * 抛出点拼上具体数量或对象名，{@code MessageConstant} 里只放模板部分。
 */
public class DataConflictException extends BaseException {

    public DataConflictException() {
    }

    public DataConflictException(String msg) {
        super(msg);
    }
}
