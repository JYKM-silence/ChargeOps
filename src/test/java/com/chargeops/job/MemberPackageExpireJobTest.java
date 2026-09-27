package com.chargeops.job;

import com.chargeops.entity.MemberPackage;
import com.chargeops.enumeration.MemberPackageStatus;
import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.properties.SchedulingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemberPackageExpireJob} 的集成测试 —— 权益过期落库的五条边界
 *
 * <h2>★★ 一、任务 Bean 是<b>手工 new 出来的</b>，不是 {@code @Autowired} 进来的 ★★</h2>
 *
 * <p>同 {@code ChargingOrderSweepJobTest}，理由完全一样：
 * {@code src/test/resources/application.properties} 把
 * {@code chargeops.scheduling.enabled} 设成了 {@code false}，
 * {@code @ConditionalOnProperty} 把它挡在容器外 —— 那一条配置是为了关掉<b>调度器</b>
 * （{@code @EnableScheduling} 在测试上下文里是真开着的）。
 * 需要这个类时把它显式造出来即可，被测的仍然是生产代码里那个对象。
 *
 * <p>★ 所以 {@code schedulingProperties} 也是从容器注入的：它是
 * {@code @ConfigurationProperties}，读的是本模块的 {@code application.yml}
 * （测试侧的 {@code application.properties} 没有覆盖 {@code chargeops.scheduled.*}），
 * 于是这里拿到的 {@code packageExpireBatchSize} 就是真实的 5000。
 * <b>下面每一条断言都不依赖那个具体数字</b> —— 用 {@code 5000} 写死断言的话，
 * 一次调参就会让本类红，而那是运维动作，不是回归。
 *
 * <h2>★★ 二、本类最值钱的一条：被订单锁着的权益<b>哪怕早就过期了也不能动</b> ★★</h2>
 *
 * <p>见 {@link #lockedPackageShouldNotBeTouchedEvenWhenOverdue()}。
 *
 * <p>它的 SQL 谓词里 {@code locked_order_id IS NULL} 很容易被看成「顺手加的条件」
 * （毕竟「过期的就是过期的」）。但它挡的是一件具体的事：
 * 一份<b>正被一笔活着的订单持有</b>的权益，如果被标成 {@code 3 已过期}，
 * 那么
 * <ul>
 *   <li>这笔订单结算时读到的是一个已过期的权益，抵扣口径要靠别的地方兜；</li>
 *   <li>更直接的：{@code MemberPackageServiceImpl.voidPackage} 的 ② 会把
 *       「已过期」拒掉 —— 一份<b>一点都没被用过</b>的权益从此永远退不了款，
 *       而且没有任何东西能把它改回 1。</li>
 * </ul>
 * 而这一条 <b>不会报错</b>：任务照常返回一个非零的行数，日志上看起来一切正常。
 *
 * <h2>★★ 三、第二条：它<b>只</b>认 {@code status = 1} ★★</h2>
 *
 * <p>见 {@link #onlyActivePackagesShouldBeTouched()}。已用尽（2）/ 已过期（3）/
 * 已作废（4）三种状态都必须原封不动。其中 3 和 4 看起来像废话，
 * 但它们各自钉住一次真实的回退：把谓词从 {@code status = 1} 改成
 * {@code status <> 3} 就会把「已用尽」和「已作废」一起翻成已过期 ——
 * 一个<b>已作废（钱已经退给车主了）</b>的权益被改成「已过期」，
 * 运营看到的是一个「还能补救」的状态，而钱其实早退了。
 *
 * <h2>★ 四、不做的事</h2>
 *
 * <p>本类不测「任务多久跑一次」——{@code @Scheduled} 的间隔是运维参数，
 * 「等 30 分钟看它会不会自己跑」既慢又不稳定，而且它由
 * {@code @ConditionalOnProperty} 管着（见第一节）。本类测的是
 * <b>「它被调一次时做了什么」</b>。
 */
@SpringBootTest
class MemberPackageExpireJobTest {

    @Autowired
    private MemberPackageMapper memberPackageMapper;

    @Autowired
    private SchedulingProperties schedulingProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 本类的手工构造的任务对象
     *
     * <p>★ {@code @BeforeEach} 而不是字段初始化：{@code @Autowired} 的字段
     * 在构造之后才被注入，字段初始化那一行跑的时候它们还是 null。
     */
    private MemberPackageExpireJob job;

    /** 测试造出来的 member id，收尾时按范围删 */
    private static final long TEST_ID_BASE = 9_000_000_000_000L;

    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach
    void setUp() {
        job = new MemberPackageExpireJob(memberPackageMapper, schedulingProperties);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM member_package WHERE member_id >= ?", TEST_ID_BASE);
    }

    // ==================== 工具方法 ====================

    private static long newMemberId() {
        return TEST_ID_BASE + SEQ.incrementAndGet();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 造一份权益
     *
     * <p>★ {@code expireTime} 由调用方给：本类全部用例的区别就在这一个字段上，
     * 把它埋在工具方法里会让「这条测的是过期还是没过期」变成要去数天数才知道的事。
     *
     * <p>★ {@code packageId} 填一个不存在的 SKU id 是安全的：{@code member_package}
     * 上没有外键，而这个任务<b>只读自己的表和自己的列</b>
     * （它连 {@code charge_package} 都不查）。这正是它能写成一条 UPDATE 的原因。
     */
    private MemberPackage givenPackage(long memberId, int status, LocalDateTime expireTime) {
        LocalDateTime now = LocalDateTime.now();
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(1).packageName("过期测试电量包")
                .totalKwh(bd("150.00")).remainingKwh(bd("150.00"))
                .effectiveFrom(now.minusDays(120)).expireTime(expireTime)
                .status(status).version(0)
                .createTime(now).updateTime(now)
                .build();
        memberPackageMapper.insert(mp);
        return mp;
    }

    /** 把一份权益锁给某笔订单 —— 模拟「它正被一笔活着的订单持有」 */
    private void lockTo(long packageId, long orderId) {
        jdbcTemplate.update("UPDATE member_package SET locked_order_id = ? WHERE id = ?",
                orderId, packageId);
    }

    private MemberPackage reload(long id) {
        return memberPackageMapper.getById(id);
    }

    /**
     * 跑一轮扫描，并返回它落库了几行
     *
     * <p>★★ 为什么要绕过 Job 直接调 Mapper：{@code expireOverduePackages()} 是
     * {@code void}。★★ 那不是漏了返回值 —— 它的调用方是 Spring 的调度器，
     * 没有任何人会去读那个数字，而<b>为了让测试能断言而加一个返回值</b>
     * 是把测试的方便放到接口的诚实前面。
     *
     * <p>所以本类这样分工：<b>状态断言一律走 Job</b>（那才是被测对象，
     * 它的行为包括「返回 0 行时不打日志」这类看不见的东西），
     * <b>「落了几行」这件事直接问 Mapper</b> —— 参数和 Job 里的那三个完全一致，
     * 用的就是 {@code application.yml} 里真实的批量大小。
     */
    private int sweepAndCount() {
        LocalDateTime now = LocalDateTime.now();
        return memberPackageMapper.expireOverdue(
                now, schedulingProperties.getPackageExpireBatchSize(), now);
    }

    // ==================== 落库 ====================

    @Test
    @DisplayName("★★ 过期且未被锁 → 落库成 3，而且【只碰 expire_time 和 status 两列】")
    void overdueUnlockedPackageShouldBecomeExpired() {
        long memberId = newMemberId();
        MemberPackage pack = givenPackage(memberId, 1, LocalDateTime.now().minusMinutes(1));
        // ★★ 回读一次再当基准，不拿入参的 LocalDateTime 去比。★★
        //   expire_time 是 datetime(3)，而 LocalDateTime.now() 带纳秒 ——
        //   写进去的时候 MySQL 截到了毫秒，于是「入参」和「读回来的值」永远不等。
        //   拿入参比的话这条测试会 100% 失败（而它本来要证明的是「这一列没被动过」），
        //   一个被改成 truncatedTo(MILLIS) 的假修复则会把它变成一个恒真的断言。
        LocalDateTime expireTimeBeforeJob = reload(pack.getId()).getExpireTime();

        job.expireOverduePackages();

        MemberPackage after = reload(pack.getId());
        assertThat(after.getStatus()).isEqualTo(MemberPackageStatus.EXPIRED.getCode());
        assertThat(after.getExpireTime())
                .as("★ 只改状态，expire_time 是事实、不能动 —— 它是「这份权益什么时候失效」的唯一真相")
                .isEqualTo(expireTimeBeforeJob);
        assertThat(after.getRemainingKwh())
                .as("★★ 余量一个都不能动。★★ 它和 status 是两回事："
                        + "这份权益过期了，但那 150 度还在账上。"
                        + "把余量清零看起来「更干净」，实际是静默销毁了一条对账依据 —— "
                        + "车主来问「我那 150 度呢」，谁也算不出来")
                .isEqualByComparingTo("150.00");
        assertThat(after.getLockedOrderId()).isNull();
    }

    @Test
    @DisplayName("★★ 被订单锁着的权益：哪怕早就过期了也【一个都不动】")
    void lockedPackageShouldNotBeTouchedEvenWhenOverdue() {
        long memberId = newMemberId();
        MemberPackage pack = givenPackage(memberId, 1, LocalDateTime.now().minusDays(5));
        long orderId = 9_900_000_000_002L;
        lockTo(pack.getId(), orderId);

        job.expireOverduePackages();

        // ★ 再问一次「有几行满足条件」。本类造的这份权益是全表唯一由本测试控制的行，
        //   而它此刻已经（或从来就）不满足条件 —— 所以这个 0 读作
        //   「它没有资格被扫到」，而不是「上一轮扫过了」。
        assertThat(sweepAndCount()).as("★★ 它必须没被扫到。★★").isZero();

        MemberPackage after = reload(pack.getId());
        assertThat(after.getStatus())
                .as("★★ 一旦它变成 3，这份【一点没被用过】的权益就永远退不了款了：★★ "
                        + "voidPackage 的预检会以「已过期」拒掉，"
                        + "而 releaseLock 只清锁、不改状态 —— 没有任何东西能把它改回 1。"
                        + "整个失败过程不报错，任务返回的行数看起来完全正常")
                .isEqualTo(1);
        assertThat(after.getLockedOrderId())
                .as("★ 任务也不能顺手把锁清掉 —— 清锁是订单流程的事（releaseLock / releaseLockByOrder），"
                        + "两边都写同一个字段的话，谁清的在事后查不出来")
                .isEqualTo(orderId);
    }

    @Test
    @DisplayName("★★ 只认 status = 1：已用尽 / 已过期 / 已作废的三种【一律不动】")
    void onlyActivePackagesShouldBeTouched() {
        long memberId = newMemberId();
        LocalDateTime overdue = LocalDateTime.now().minusDays(30);

        MemberPackage exhausted = givenPackage(memberId, MemberPackageStatus.EXHAUSTED.getCode(), overdue);
        MemberPackage expired = givenPackage(memberId, MemberPackageStatus.EXPIRED.getCode(), overdue);
        MemberPackage voided = givenPackage(memberId, MemberPackageStatus.VOIDED.getCode(), overdue);

        job.expireOverduePackages();

        assertThat(sweepAndCount()).as("三份都不满足 status = 1").isZero();

        assertThat(reload(exhausted.getId()).getStatus())
                .as("★ 已用尽 → 已过期 是一次静默的信息丢失："
                        + "「他用完了」和「它过期了」是两件不同的事，"
                        + "而运营看到的补救动作也不同")
                .isEqualTo(MemberPackageStatus.EXHAUSTED.getCode());
        assertThat(reload(expired.getId()).getStatus())
                .isEqualTo(MemberPackageStatus.EXPIRED.getCode());
        assertThat(reload(voided.getId()).getStatus())
                .as("★★ 最要命的一个：已作废意味着【钱已经退给车主了】。★★ "
                        + "把它改成「已过期」，运营看到的是一个像是还能补救的状态，"
                        + "而那份权益背后的钱早就不在账上了")
                .isEqualTo(MemberPackageStatus.VOIDED.getCode());
    }

    @Test
    @DisplayName("★★ 到期时刻【还没到】的不能被扫走 —— 边界是 <=，而 listUsable 那边是 >")
    void notYetExpiredPackageShouldBeLeftAlone() {
        long memberId = newMemberId();
        // 一秒之后才过期。★ 这一秒就是本条的边界：任务用的是 expire_time <= now，
        //   而 listUsable 用的是 expire_time > now —— 两者严丝合缝，
        //   「这一刻刚好到期」的权益不会被判成一边能用、一边已过期。
        MemberPackage stillValid = givenPackage(memberId, 1, LocalDateTime.now().plusSeconds(1));

        job.expireOverduePackages();

        assertThat(sweepAndCount()).isZero();
        assertThat(reload(stillValid.getId()).getStatus())
                .as("★ 把还没过期的扫掉，等于提前没收车主买过的东西")
                .isEqualTo(MemberPackageStatus.ACTIVE.getCode());
    }

    @Test
    @DisplayName("★★ 幂等：连跑两次，第二次一行都不改")
    void secondRunShouldBeANoOp() {
        long memberId = newMemberId();
        MemberPackage pack = givenPackage(memberId, 1, LocalDateTime.now().minusMinutes(1));

        job.expireOverduePackages();
        LocalDateTime firstUpdateTime = reload(pack.getId()).getUpdateTime();

        assertThat(sweepAndCount())
                .as("★★ 谓词 status = 1 本身就是一次 CAS —— 这也是多实例部署不需要分布式锁的原因："
                        + "两个实例同时扫到同一批行，行锁会把它们排成序，"
                        + "后到的那个会发现那些行已经不满足 status = 1 了")
                .isZero();

        assertThat(reload(pack.getId()).getUpdateTime())
                .as("★ 第二次连 update_time 都不该被推一下 —— 推了就说明 WHERE 里少了 status = 1，"
                        + "而那个缺陷的症状是：每一轮扫描都把全部历史过期权益重写一遍，"
                        + "行数永远是几万，看起来像是「一直在正常工作」")
                .isEqualTo(firstUpdateTime);
    }

    @Test
    @DisplayName("★ 单轮上限：批量大小是 1 时只落一份，而且返回的行数就是真实影响行数")
    void batchLimitShouldCapASingleRun() {
        long memberId = newMemberId();
        LocalDateTime overdue = LocalDateTime.now().minusMinutes(1);
        givenPackage(memberId, 1, overdue);
        givenPackage(memberId, 1, overdue);

        // ★ 直接调 Mapper 而不是 Job：Job 读的是 application.yml 里的 5000，
        //   要把「上限」这件事测出来就得把它改小 —— 而那是运维参数，
        //   专门为一条测试在生产配置里留一个可覆盖的钩子，是把测试的方便
        //   放到了配置的诚实前面。这里改测那条 SQL 本身。
        int rows = memberPackageMapper.expireOverdue(
                LocalDateTime.now(), 1, LocalDateTime.now());

        assertThat(rows).as("★★ LIMIT 1 就是只落一份。★★ 它同时钉住了「这不是逐笔循环」这一条："
                + "本项目的形状是【一条 UPDATE ... LIMIT n】，"
                + "目的是把状态判断留在 SQL 的 WHERE 里 —— "
                + "拆成「SELECT 出来再逐行 UPDATE」会引入一个真实的 bug 面，"
                + "查出来的行在 UPDATE 之前可能已经被别的路径改了")
                .isEqualTo(1);
    }
}
