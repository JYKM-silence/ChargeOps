package com.chargeops.exception;

/**
 * 充电枪已被占用
 *
 * <p> 抛在 {@code ConnectorMapper.occupy} 的条件更新影响行数为 0 之后。不能写成
 * 「先查枪空不空闲再更新」：查完到更新之间别人可能已经把枪占了，两个请求都会通过，
 * 结果是同一把枪挂上两个订单。也不该重试 —— 让用户换一把枪才是正确的反馈。
 */
public class ConnectorOccupiedException extends BaseException {

    public ConnectorOccupiedException() {
    }

    public ConnectorOccupiedException(String msg) {
        super(msg);
    }
}
