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
 * <p>它补的是 {@code MemberPackageStatus} 那个枚举里<b>两个从没被写入过的取值之一</b>：
 * 在本类之前，{@code status} 的迁移只到「被买下（1）」和「被用尽（2）」为止，
 * 而 {@code EXPIRED(3)} / {@code VOIDED(4)} 在代码里<b>一次都没出现过</b>。
 * 于是库里会留着「一份过期三年的权益，状态写着『生效中』」这样的行，
 * 而车主端的列表把它显示成一个正常的、可用态的条目。
 * （{@code 4} 由管理端的作废接口写入，见 {@code MemberPackageServiceImpl.voidPackage}。）
 *
 * <h2>★★ 一、这个任务<b>不</b>负责正确性，只负责让缓存跟上事实 ★★</h2>
 *
 * <p><b>这一点决定了它的全部设计，先看懂再改。</b>
 * {@code status} 是缓存，{@code expire_time} 和剩余量才是真相
 * （见 {@code MemberPackageStatus} 类注释）。判断「这份权益现在能不能用」的
 * 权威 SQL 是 {@code listUsable}，它的谓词里带着
 * {@code expire_time > #{now}} —— 所以<b>哪怕本任务一次都没跑过</b>，
 * 一份状态还写着 1 的过期权益也<b>绝不会</b>被发出去、不会被抵扣。
 *
 * <p>既然如此，本任务存在的意义只有一个：<b>让「状态」这个给人看的东西是对的。</b>
 * <ul>
 *   <li>车主端的列表要显示「已过期」的灰态（{@code listMine} <b>不过滤状态</b>，
 *       直接把 {@code status} 转成标签）—— 状态不刷新，用户看到的就是
 *       「我有一份能用的套餐」，点进去却说不能用；</li>
 *   <li>运营看「本月过期了多少份」时，一条 {@code WHERE status = 3}
 *       比一坨时间函数直观得多。</li>
 * </ul>
 *
 * <p>★★ <b>推论：这个任务扫晚了、甚至挂掉了，都不会让任何一笔钱算错。</b>★★
 * 所以它的间隔可以给得很宽松（{@code application.yml} 里是 30 分钟），
 * 而且<b>不需要分布式锁、不需要失败重试、不需要告警</b>。反过来，
 * 如果哪天有人把「过期判断」从 {@code listUsable} 里搬走、改成只看
 * {@code status = 1}，那本任务就会<b>突然变成正确性的一环</b>，
 * 而它现在完全不具备那个强度的保证 —— 这个改动会是一次静默的降级。
 *
 * <h2>★★ 二、一条 {@code UPDATE ... LIMIT}，不是「捞出来逐笔处理」★★</h2>
 *
 * <p>和 {@link ChargingOrderSweepJob} 的形状<b>刻意不同</b>，而这个不同是有理由的：
 * <pre>
 *   支付兜底   : 捞 N 笔 → 逐笔调 orderService.markPayFailed（每笔一个独立事务）
 *   本任务     : 一条 UPDATE ... LIMIT N（一个语句、一个事务）
 * </pre>
 * <p>区别来自「一笔要做什么」：
 * <ul>
 *   <li>支付兜底逐笔要做的是一段<b>业务逻辑</b>（改状态 + 记事件 + 带来源），
 *       而且它<b>必须</b>每笔独立事务（见那边的类注释第二节）；</li>
 *   <li>本任务要做的<b>就是一个 UPDATE</b>。把它拆成「SELECT 出来再逐行 UPDATE」
 *       会引入一个真实的 bug 面：查出来的行在 UPDATE 之前可能已经被别的路径改了
 *       （被结算扣光、被作废），于是每一行都要重新判一次状态 ——
 *       而那些判断必然会漏掉某一支。<b>把判断留在 SQL 的 {@code WHERE} 里，
 *       这个问题就不存在</b>，这正是本项目「条件更新优先」那条主线。</li>
 * </ul>
 *
 * <h2>★★ 三、本方法<b>不能</b>标 {@code @Transactional} ★★</h2>
 *
 * <p>★ 和 {@code ChargingOrderSweepJob} 那条结论相同，但理由不同，别照抄：
 * 那里是因为「每一笔必须独立成事务」；这里是因为<b>它本来就是一条语句</b> ——
 * 标上 {@code @Transactional} 只会把一条本来就自动提交的 UPDATE
 * 包进一个显式事务里，不改变任何行为，却让人以为「这里有一层事务边界要维护」。
 *
 * <p>★★ 真正要防的是<b>给它加循环</b>：如果有人为了「一轮多处理几批」写成
 * {@code while (rows == limit) { ... }}，那循环里的每一次
 * {@code expireOverdue} 会落在<b>同一个</b>（被 {@code @Transactional} 打开的）
 * 事务里，行锁累积到方法结束才释放 —— 于是一条「清理缓存」的任务
 * 变成了一条持锁几分钟的长事务，而此刻真实的用户在买套餐、在结算。
 * <b>要处理更多，改 {@code package-expire-batch-size}，不要加循环。</b>
 *
 * <h2>★ 四、多实例部署不需要分布式锁</h2>
 *
 * <p>同 {@code ChargingOrderSweepJob}：{@code UPDATE ... WHERE status = 1}
 * 本身就是一次 CAS，两个实例同时扫到同一批行，行锁会把它们排成序，
 * 后到的那个会发现那些行已经不满足 {@code status = 1} 了。
 * <b>代价只是日志噪音，而这里连噪音都很小</b> —— 本任务不改钱。
 */
@Component
// ★ 和 ChargingOrderSweepJob 用完全相同的写法，理由也相同：
//   没有 havingValue（用 Spring 的宽松布尔解析，避免 "TRUE"/"1"/"yes" 全不匹配），
//   matchIfMissing 默认为 false（配置里没写这个键 = 不跑任务）。
//   ★★ 这一行是测试能全绿的前提 ★★：@EnableScheduling 是开着的，
//   而 @SpringBootTest 加载完整上下文 —— 少了它，这个任务会在
//   全部测试期间跑起来，把测试构造的权益行翻成「已过期」。
//   （test/resources/application.properties 里把开关关掉了。）
@ConditionalOnProperty(name = "chargeops.scheduling.enabled")
@RequiredArgsConstructor
@Slf4j
public class MemberPackageExpireJob {

    private final MemberPackageMapper memberPackageMapper;

    private final SchedulingProperties scheduling;

    /**
     * 把 {@code status = 1} 但 {@code expire_time} 已过的权益落库成 {@code 3}
     *
     * <p>★ {@code fixedDelay} 而不是 {@code cron}：{@code fixedDelay} 计时从
     * <b>上一次执行结束</b>开始，一次慢扫描不会和下一次重叠。{@code cron} 是
     * 「到点就跑」，扫描耗时超过间隔时同一个任务会并发跑起来 ——
     * 而这是个会写数据库的任务。<b>扫描类任务用 {@code fixedDelay} 是默认答案。</b>
     *
     * <p>★ 占位符 {@code ${chargeops.scheduled.package-expire-scan-ms}} 故意不给默认值：
     * 配置缺失时应用<b>启动失败</b>，而不是静默用一个硬编码的间隔 ——
     * 后者会让「我明明把间隔调成了 1 分钟，怎么还是半小时」变成一个需要读源码的问题。
     * （和 {@code ChargingOrderSweepJob} 完全一致。）
     *
     * <p>★ {@code now} 只用一次，同时当<b>判定边界</b>和<b>写入的 update_time</b>：
     * 项目约定时间一律 Java 侧生成，而且同一批写入用同一个时刻，
     * 事后按 {@code update_time} 反查「哪一批是哪个时刻落的」才对得上。
     * ★ 不要在这一句里用数据库的 {@code NOW()} —— 那样判定边界和写入时刻
     * 会各自取一次时钟，两者之间漂移的那几毫秒里过期的行，
     * 会出现「按 A 判定过期、落库时间写的是 B」这种对不上的记录。
     *
     * <p>★ 返回 0 行<b>不打日志</b>。这个任务绝大多数轮次都是 0 行
     * （权益的过期是稀疏事件），「扫到 0 份」刷屏的结果是把真正的异常
     * 挤出日志轮转窗口 —— 同 {@code ChargingOrderSweepJob}。
     *
     * <p>★★ <b>返回等于上限时打一条 warn。</b>★★ 它说明<b>有过期权益积压</b>：
     * 正常情况下每 30 分钟过期的权益是个位数，一轮扫满 5000 份只有两种可能 ——
     * 这是<b>第一次上线</b>（V3 之前没有任何代码写过 {@code status = 3}，
     * 库里可能躺着两年的过期数据），或者<b>这个任务停了很久又恢复</b>。
     * 两种都不需要人工干预（下一轮会继续处理），但都值得在日志里留一句 ——
     * 否则「运营说过期数量少了 800 万」这件事没人能解释。
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
            // ★ 措辞刻意避开「失败」和「异常」：这不是故障，是积压。
            //   一个看起来像故障的词会让人去查一个不存在的问题。
            log.warn("权益过期落库：本轮扫满了上限 {} 份，说明有过期权益积压 —— "
                            + "首次上线（V3 之前没有代码写过 status = 3）或本任务停过一段时间；"
                            + "下一轮会继续处理，无需人工干预",
                    limit);
        }
    }
}
