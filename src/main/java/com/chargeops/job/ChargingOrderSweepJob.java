package com.chargeops.job;

import com.chargeops.entity.ChargingOrder;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.properties.SchedulingProperties;
import com.chargeops.service.ChargingOrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电订单的兜底扫描任务
 *
 * <p>这是本项目<b>第一个也是目前唯一一个</b>{@code @Scheduled} 方法。
 * {@code ChargeOpsApplication} 上的 {@code @EnableScheduling} 早就开了，
 * 但一直没有任何方法被它调起来 —— 这个类把那条通路接上。
 *
 * <h2>★★ 一、只写 {@code markPayFailed}，<b>绝不</b>重试 {@code pay()} ★★</h2>
 *
 * <p><b>这是本类最重要的一个取舍，改它之前请先读完这一段。</b>
 *
 * <p>{@code pay()} 刻意<b>不查</b> {@code assertActive}（车主是否被禁用），
 * 理由写在 {@code ChargingOrderServiceImpl.pay} 上，而且那个理由是对的：
 * 它是被禁用车主<b>解冻资金的唯一出口</b> —— 一个被禁用的账号如果连
 * 「把已经冻住的钱付掉、结束这笔订单」都做不到，那笔钱就永久冻死了。
 *
 * <p>★ 正因为那个校验不在，<b>自动重试等于一次未经同意的扣款</b>：
 * 车主可能刚充值是为了别的事（比如明天要充另一辆车），扫描把钱拿走了。
 * 本仓库其余每一处资金变动都由<b>用户动作</b>或<b>设备事件</b>触发，
 * 定时任务不在此列 —— 它只做「把订单推到一个诚实的、等人工或等用户的停留态」。
 *
 * <p>★★ 而且：{@code README} 缺口表的原文是「调用方忘了写 {@code markPayFailed}，
 * 这笔单就停在 4」。<b>缺的是那个 6，不是一次重试。</b>
 * {@code 4 → 6} 不丢任何东西：
 * <ul>
 *   <li>{@code markPaid} 的谓词是 {@code status IN (4, 6)}，用户充值回来点
 *       「重新支付」照常工作 —— 这条路本来就支持从 6 出发；</li>
 *   <li>6 比 4 多一条 {@code charging_order_event}（{@code EventSource.SCHEDULED}），
 *       说的是「它在什么时候因为什么卡住了」，可查、可告警；</li>
 *   <li>钱一直<b>冻着</b>，既没扣掉也没退回 —— {@code markPayFailed} 不动钱包。</li>
 * </ul>
 *
 * <h2>★★ 二、本方法<b>不能</b>标 {@code @Transactional} ★★</h2>
 *
 * <p>两个独立的理由，任何一个都足够：
 * <ol>
 *   <li>{@code markPayFailed} 自己的 javadoc 就写着它「必须在旧事务<b>回滚之后</b>
 *       的新事务里调用」—— 它设计成自成一笔事务；</li>
 *   <li>一轮扫几百笔放在同一个事务里，会在 {@code charging_order} 上累积大量行锁，
 *       直到方法结束才一起释放 —— 而这期间真实的用户在支付、在扫码建单。
 *       一笔慢扫描拖住整张订单表的写入，是典型的「定时任务把线上搞挂」。</li>
 * </ol>
 *
 * <p>★ 所以每一笔 {@code markPayFailed} 都是<b>独立的一笔事务</b>（Spring 的
 * {@code @Transactional} 在 {@code orderService} 这个代理上生效）。这意味着
 * 第 37 笔失败不会回滚前 36 笔 —— 对扫描类任务，这是想要的行为。
 *
 * <p>★★ <b>注意 {@code orderService} 必须是从容器注入的 Bean，不能是
 * {@code this}。</b>★★ 本类没有 {@code @Transactional}，但如果哪天有人给它加上，
 * 然后为了「少一层调用」把 {@code orderService.markPayFailed(...)} 改成
 * 自己类里的一个私有方法，那么：代理不生效 → 每笔都挤在外层事务里 →
 * 又变回上面第 2 条那个形状，而且<b>不会有任何报错</b>。
 * 这和 {@code ChargingOrderServiceImpl.settle} 里
 * {@code incrementChargeCount} 那条「绝不可改成 REQUIRES_NEW / 自注入代理」的
 * 警告是同一类陷阱，方向相反。
 *
 * <h2>★ 三、失败隔离：一笔出问题不能带走一轮</h2>
 *
 * <p>循环里的 {@code try/catch} 是刻意的。★ <b>它和 {@code ChargingOrderServiceImpl}
 * 类注释里那个「绝不能 catch {@code BalanceNotEnoughException}}」是两回事，
 * 不矛盾：</b>那里的问题是「在<b>同一个</b>事务里 catch 住异常继续写库」——
 * 事务已经被标记 rollback-only，写进去的东西提交时会丢。这里每一笔
 * {@code markPayFailed} 是一个<b>已经完成、已经回滚完</b>的独立事务，
 * 异常是代理在回滚之后才抛回来的，catch 它不会污染任何东西。
 *
 * <h2>★ 四、多实例部署不需要分布式锁</h2>
 *
 * <p>{@code markPayFailed} 的 {@code WHERE ... AND status = 4} 本身就是一次
 * 条件更新（CAS）：两个实例同时扫到同一笔订单，只有一个的影响行数是 1，
 * 另一个是 0 并走进「订单已不在待支付状态，跳过」那条日志。
 * <b>代价只是日志噪音，不是重复扣款</b> —— 这个方法不动钱。
 * 加一把分布式锁换来的是「少几条日志」，不划算，而且要处理锁过期、时钟漂移。
 */
@Component
// ★ 没有 havingValue —— 用的是 Spring 的宽松布尔解析：属性缺失 → 不装配；
//   值是 "false" → 不装配；其它任何值 → 装配。写成 havingValue = "true"
//   的话 "TRUE"/"1"/"yes" 都不匹配，而配置里写成什么纯属运气。
//   matchIfMissing 默认就是 false，即「配置文件里没写这个键 = 不跑任务」——
//   这是安全的方向：一个没配过的环境不该自己开始写订单。
@ConditionalOnProperty(name = "chargeops.scheduling.enabled")
@RequiredArgsConstructor
@Slf4j
public class ChargingOrderSweepJob {

    /**
     * 写进 {@code charging_order.remark} 的失败原因
     *
     * <p>★ <b>为什么不放进 {@code MessageConstant}：</b>那个类的职责是
     * 「哪些信息可以被 {@code GlobalExceptionHandler} 透给前端」，它管的是
     * <b>异常消息</b>。而这一条是被<b>写进数据库</b>的、订单详情页会读到的一段文字 ——
     * 两者的生命周期和审阅标准都不同（一个随响应消失，一个永久留在订单旁边）。
     * 混在一起会让「改一句提示语」变成「改一段已经写进历史数据的文本」。
     *
     * <p>★ 它<b>不带</b>具体分钟数：那个值来自配置，会变，而这句话会被永久写进
     * 每一笔被扫到的订单里。<b>把可变的配置固化进业务数据</b>，
     * 半年后没人能解释「为什么这批订单写的是 15 分钟、那批写的是 30」。
     * 需要知道具体阈值时去查 {@code charging_order_event} 的时间戳，
     * 那才是事实。
     */
    private static final String STALE_REASON =
            "超过支付时限仍未支付，系统标记为支付失败；充值后可在订单详情重新支付";

    private final ChargingOrderMapper orderMapper;

    /**
     * ★ 从容器注入，<b>不是 {@code this}</b> —— 理由见类注释第二节。
     */
    private final ChargingOrderService orderService;

    private final SchedulingProperties scheduling;

    /**
     * 把卡在 {@code 4 待支付} 太久的订单标成 {@code 6 支付失败}
     *
     * <p>★ {@code fixedDelay} 而不是 {@code cron}：{@code fixedDelay} 计时从
     * <b>上一次执行结束</b>开始，所以一次慢扫描不会和下一次重叠。
     * {@code cron} 是「到点就跑」，扫描耗时超过间隔时同一个任务会并发地跑起来 ——
     * 而这是个会写数据库的任务。<b>扫描类任务用 {@code fixedDelay} 是默认答案，
     * 用 {@code cron} 要有理由。</b>
     *
     * <p>★ 占位符 {@code ${chargeops.scheduled.pay-timeout-scan-ms}} 故意不给默认值：
     * 配置缺失时应用<b>启动失败</b>，而不是静默用一个硬编码的间隔 ——
     * 后者会让「我明明把间隔调成了 1 分钟，怎么还是 5 分钟」变成一个需要读源码的问题。
     *
     * <p>★ 没有 {@code @Transactional}，也<b>不要</b>加 —— 见类注释第二节。
     */
    @Scheduled(fixedDelayString = "${chargeops.scheduled.pay-timeout-scan-ms}")
    public void sweepStalePendingPay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = now.minusMinutes(scheduling.getPayTimeoutMinutes());

        List<ChargingOrder> stale = orderMapper.listStalePendingPay(
                before, scheduling.getPayTimeoutBatchSize());

        // ★ 空结果不打日志。这个任务每 5 分钟醒一次，绝大多数轮次都是 0 笔 ——
        //   「扫到 0 笔」刷屏的结果是把真正的异常挤出日志轮转窗口。
        //   反过来，「这一轮处理了 N 笔」必须打，因为它是这个任务存在的唯一证据。
        if (stale.isEmpty()) {
            return;
        }

        log.info("支付失败兜底扫描：待支付超过 {} 分钟的有 {} 笔，开始处理",
                scheduling.getPayTimeoutMinutes(), stale.size());

        int called = 0;
        for (ChargingOrder order : stale) {
            try {
                orderService.markPayFailed(order.getId(), STALE_REASON);
                called++;
            } catch (Exception e) {
                // ★ 单笔失败不影响其余。记 ERROR 带上 orderId，不做任何重试 ——
                //   下一轮扫描会再看到它（如果它还在 4 上），那已经是一次自然的重试了。
                log.error("支付失败兜底扫描：标记订单失败，跳过这一笔：orderId={}, orderNo={}",
                        order.getId(), order.getOrderNo(), e);
            }
        }

        // ★★ 措辞不能写成「成功标记 3 笔」—— 这个数不是那个意思。★★
        //   markPayFailed 对「订单已经不在 4 上」（比如用户自己刚付掉了）返回正常、
        //   影响 0 行、内部记一条 info 就结束了，算在这里面；
        //   而那笔订单并没有被我们标成失败。真正准确的说法是「调用未抛异常」。
        //   想数真实影响行数，唯一的位置在 markPayFailed 里 —— 而它返回 void。
        //   这里宁可用一个笨拙但诚实的词，也不要报一个好看但错的数字：
        //   一个虚高的失败数会让人去查一个不存在的问题。
        log.info("支付失败兜底扫描完成：取件 {} 笔，已提交处理 {} 笔（含被用户抢先支付的）",
                stale.size(), called);
    }
}
