package com.chargeops.exception;

/**
 * 某个时间点没有任何计费规则覆盖它 —— 计费时段存在缺口。独立成一个类型是因为同一个缺口会在
 * 三处被发现（保存时校验、计费时算不出、已冻结的模板），而三处的处理动作不同。
 * 和 {@link DataConflictException} 的分工：这里是没规则匹配（要补规则），那里是匹配到多条重叠。
 */
public class TariffNotCoveredException extends BaseException {

    public TariffNotCoveredException() {
    }

    public TariffNotCoveredException(String msg) {
        super(msg);
    }
}
