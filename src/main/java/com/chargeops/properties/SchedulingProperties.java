package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务的业务参数，对应 {@code chargeops.scheduled.*}
 *
 * <p> 它和 {@code chargeops.scheduling.enabled}（跑不跑任务）前缀只差一个字母，写错地方不报错、
 * 只是完全不起作用；{@code @Scheduled} 也直接读配置、不读 {@code *ScanMs} 字段，改字段没用。
 *
 * <p> {@code payTimeoutMinutes} 扫早了会把订单打成 6 支付失败；两个 batch-size 有上限是必要的。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.scheduled")
@Data
public class SchedulingProperties {

    private Long payTimeoutScanMs;

    private Integer payTimeoutMinutes;

    private Integer payTimeoutBatchSize;

    /** 权益过期落库的间隔（毫秒）。它可以也应该比支付兜底慢得多：处理的是缓存陈旧，没有人在等它（读路径本来就带 {@code expire_time > now} 兜底） */
    private Long packageExpireScanMs;

    private Integer packageExpireBatchSize;
}
