package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定时任务的业务参数，对应 {@code chargeops.scheduled.*}
 *
 * <h2>★★ 注意它和 {@code chargeops.scheduling.enabled} 是两个不同的东西 ★★</h2>
 *
 * <pre>
 *   chargeops.scheduling.enabled        这个环境<b>跑不跑</b>定时任务   → 在 dev/prod/测试的配置文件里
 *   chargeops.scheduled.*               跑起来之后<b>多久扫一次、扫多少</b> → 在 application.yml 里
 * </pre>
 *
 * <p>两个前缀只差一个字母，这是历史原因，<b>而且是个真实的踩坑点</b>：
 * 把 {@code enabled} 写进 {@code chargeops.scheduled} 下面不会报错，只是完全不起作用
 * （{@code @ConditionalOnProperty} 找的是另一个 key），表现是「我明明关了它，
 * 测试里订单还是被扫走了」。{@code application.yml} 的 {@code chargeops.scheduled}
 * 那一节里也写了这条。改名要动三个配置文件加所有 job 的注解，
 * 暂时留着，但这条注释必须留着。
 *
 * <p>★★ <b>字段上不写默认值</b>，和 {@link JwtProperties} / {@link StorageProperties}
 * 同一个理由：默认值写在 application.yml 里，这里再写一份就有了两个真相，
 * 而它们会在某次调参之后不一致 —— 表现为「配置文件里明明写着 15 分钟，
 * 行为却像是 30 分钟」（Spring 的字段初始值不会覆盖配置文件，但
 * 「读代码看到 30、读配置看到 15」本身就是一次真实的误导）。
 * 配置缺失时这些字段是 null，任务对象在第一次被调用时就 NPE —— 响亮地失败。
 */
@Component
@ConfigurationProperties(prefix = "chargeops.scheduled")
@Data
public class SchedulingProperties {

    /**
     * 支付失败兜底扫描的间隔（毫秒），同时是 {@code @Scheduled} 的 {@code fixedDelay}
     *
     * <p>★ <b>它同时被两处读，但只有一个真相：</b>
     * {@code ChargingOrderSweepJob} 用 {@code @Scheduled(fixedDelayString =
     * "${chargeops.scheduled.pay-timeout-scan-ms}")} <b>直接读配置</b>，
     * 不读这个字段 —— 因为 {@code @Scheduled} 的值必须在 Bean 创建时就确定，
     * 而那是 Bean 属性绑定<b>之前</b>的事。所以这个字段存在的意义是
     * 「让配置项在代码里有一个可被 IDE 跳转、可被 javadoc 解释的落点」，
     * <b>不是</b>它的取值来源。<b>改这个字段不会改变扫描间隔</b> ——
     * 改 application.yml 才会。这句话写在这里，是因为把 {@code @Scheduled}
     * 换成读字段的写法（或反过来）看起来像一次无害的重构。
     */
    private Long payTimeoutScanMs;

    /**
     * 「待支付多久算超时」的分钟数，用于算出扫描的时间下界
     *
     * <p>★ 这个值决定「用户有多久可以慢慢去充值」。扫早了的后果不是「提醒用户」，
     * 而是把这笔订单打成 {@code 6 支付失败}。虽然 6 仍然可以支付
     * （{@code markPaid} 的谓词是 {@code status IN (4,6)}），但订单上会留下
     * 一次本不该有的失败记录，用户看到的是「我还没付款它就失败了」。
     *
     * <p>★ 这是<b>业务参数</b>，不是技术参数：一个只支持余额支付、而充值需要
     * 跳转到另一个 App 再回来的系统，15 分钟可能是短的。调整它之前先想清楚
     * 用户完成一次充值要多久。
     */
    private Integer payTimeoutMinutes;

    /**
     * 单轮最多处理多少笔
     *
     * <p>★ <b>有上限是必要的，不是优化。</b>一次把几千笔待支付订单全捞进内存、
     * 逐笔开事务去写，会在数据库上形成一个长尾，而扫描本身是固定间隔重复的 ——
     * 剩下的下一轮会处理掉。没有这个上限，第一个把它跑在积压数据上的人
     * 会得到一个 OOM，而且是在一个「看起来什么都不做」的定时任务里。
     */
    private Integer payTimeoutBatchSize;

    /**
     * 权益过期落库的间隔（毫秒），同时是 {@code @Scheduled} 的 {@code fixedDelay}
     *
     * <p>★ 和 {@link #payTimeoutScanMs} 一样，{@code @Scheduled} <b>直接读配置</b>
     * 而不读这个字段（{@code @Scheduled} 的值必须在 Bean 创建时就确定，
     * 而那发生在属性绑定之前）。**改这个字段不会改变扫描间隔**，
     * 改 {@code application.yml} 才会 —— 完整说明见 {@link #payTimeoutScanMs}。
     *
     * <p>★ <b>它可以比支付兜底扫描慢得多，而且应该慢得多。</b>
     * 两者的紧迫性完全不同：
     * <ul>
     *   <li>支付兜底扫描处理的是「用户正卡在支付页面上等着」的订单，
     *       扫晚了用户就干等；</li>
     *   <li>本任务处理的是「状态列没跟上 {@code expire_time}」的缓存陈旧 ——
     *       <b>没有任何人在等它</b>。读路径（{@code listUsable}）本来就带着
     *       {@code expire_time > now} 兜底，一份状态还写着 1 的过期权益
     *       <b>照样不会被发出去</b>。这个任务的存在意义是让
     *       「运营看到的过期数量」和「车主看到的灰态」正确，
     *       而不是拦住任何错误。</li>
     * </ul>
     * 所以这里可以给一个宽松的间隔，把数据库的写入压力降到最低。
     */
    private Long packageExpireScanMs;

    /**
     * 单轮最多把多少份过期权益落库
     *
     * <p>★ 有上限的理由和 {@link #payTimeoutBatchSize} 一样（不要形成长事务），
     * 但<b>这里的形状更简单</b>：整个批量是<b>一条 UPDATE</b>
     * （{@code WHERE ... LIMIT n}），不是「捞进内存逐笔开事务」。
     * 所以它不占堆内存，占的是<b>行锁和 undo log</b> ——
     * 一次改几十万行会生成一个巨大的 undo、并把那些行的锁持有到提交。
     *
     * <p>★ <b>取值可以远大于支付批次</b>：一条 {@code UPDATE ... LIMIT 5000}
     * 的成本远低于「5000 次独立的 UPDATE 事务」，而积压时（比如第一次上线，
     * 库里躺着两年的过期数据）收敛速度直接取决于它。
     */
    private Integer packageExpireBatchSize;
}
