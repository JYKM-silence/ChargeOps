package com.chargeops.exception;

/**
 * AI 这次用不了：没启用、调用超时、模型返回的内容解析不了。
 *
 * <p> 三种情况合成一个异常，因为对调用方来说是同一件事 —— 「这次 AI 帮不上忙，请手工录入」。
 * 分开的话前端要为三种情况写三段几乎一样的提示，而运营能做的动作完全一样。
 * 具体是哪一种写在 message 里。
 */
public class AiUnavailableException extends BaseException {

    public AiUnavailableException(String msg) {
        super(msg);
    }
}
