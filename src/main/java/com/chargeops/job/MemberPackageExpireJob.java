package com.chargeops.job;

import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.properties.SchedulingProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 把过期的车主权益落库成 {@code 3 已过期}
 *
 * <p> 这个任务不负责正确性，只负责让 status 这个给人看的东西跟上事实。权威判据是
 * {@code listUsable} 谓词里的 {@code expire_time > #{now}} —— 本任务一次都没跑过，过期权益也不会
 * 被发出去、不会被抵扣。反过来，哪天把过期判断改成只看 {@code status = 1}，它就会突然变成
 * 正确性的一环，而它没有那个强度的保证。
 *
 * <p> 一条 {@code UPDATE ... LIMIT} 而不是「捞出来逐笔处理」：逐笔要重新判一次状态，
 * 而那些判断必然会漏掉某一支（行可能在 SELECT 和 UPDATE 之间被结算扣光或被作废）。
 *
 * <p> 本方法不能标 {@code @Transactional}：它本来就是一条语句，标上不改变行为。
 * 真正要防的是给它加循环 —— 循环里每次调用会落在同一个事务里、行锁累积到方法结束。
 * 多实例也不需要分布式锁：{@code WHERE status = 1} 本身是 CAS。
 */
@Component
// 和 ChargingOrderSweepJob 一样不能写 havingValue。这行也是测试能全绿的前提：
// @SpringBootTest 会加载完整上下文，少了它这个任务会在测试期间跑起来，
// 把测试构造的权益行翻成「已过期」（test 的 application.properties 里关掉了开关）。
@ConditionalOnProperty(name = "chargeops.scheduling.enabled")
@RequiredArgsConstructor
@Slf4j
public class MemberPackageExpireJob {

    private final MemberPackageMapper memberPackageMapper;

    private final SchedulingProperties scheduling;

    /**
     * {@code fixedDelay} 而不是 {@code cron}：一次慢扫描不会和下一次重叠。占位符故意不给默认值，
     * 配置缺失时启动失败，而不是静默用一个硬编码的间隔。
     *
     * <p> {@code now} 只用一次，同时当判定边界和写入的 update_time；用数据库的 {@code NOW()}
     * 会让两者各取一次时钟，留下对不上的记录。返回 0 行不打日志，扫满上限时打 warn（积压，无需人工干预）。
     */
    @Scheduled(fixedDelayString = "${chargeops.scheduled.package-expire-scan-ms}")
    public void expireOverduePackages() {
        LocalDateTime now = LocalDateTime.now();
        int limit = scheduling.getPackageExpireBatchSize();

        int rows = memberPackageMapper.expireOverdue(now, limit, now);

        if (rows == 0) {
            return;
        }

        log.info("权益过期落库：本轮把 {} 份过期权益标记为已过期", rows);

        if (rows >= limit) {
            // 措辞避开「失败」「异常」：这是积压不是故障，一个像故障的词会让人去查不存在的问题
            log.warn("权益过期落库：本轮扫满了上限 {} 份，说明有过期权益积压 —— "
                            + "首次上线（V3 之前没有代码写过 status = 3）或本任务停过一段时间；"
                            + "下一轮会继续处理，无需人工干预",
                    limit);
        }
    }
}
