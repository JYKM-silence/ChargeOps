package com.chargeops.mapper;

import com.chargeops.context.BaseContext;
import com.chargeops.entity.MemberPackage;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemberPackageMapper} 的集成测试
 *
 * <p>通用写法见 {@link StaffMapperTest}。本类要钉死的是
 * <b>四个承重行为</b>，它们每一个失效时都是静默的（不报错、只是钱对不上）：
 *
 * <ol>
 *   <li>{@code uk_mp_locked_order}：同一份权益不能被两笔订单同时锁住</li>
 *   <li>{@code locked_order_id} 兼作幂等闸门：重复结算只扣一次</li>
 *   <li>{@code listUsable} 的四个条件缺一不可，尤其
 *       <b>折扣包不能有余量条件</b></li>
 *   <li>结算用尽时 {@code status} 由 SQL 的 {@code CASE} 自动置 2</li>
 * </ol>
 *
 * <h2>★★ 为什么每条测试用自己的 memberId，而不是复用种子里的 member 1 ★★</h2>
 *
 * <p>种子里已经给 {@code member_id = 1} 塞了一份「150度电包」（见
 * {@code sql/chargeops.sql} 的 seed 段）。如果测试也往 member 1 上面挂，
 * {@link MemberPackageMapper#listUsable} 就会同时看到种子行和测试行 ——
 * 断言「可用列表里有 1 条」会变成一条依赖种子数据的脆弱测试，
 * 而且失败信息会指向一个和本次改动无关的行。
 *
 * <p>用 {@code System.nanoTime()} 生成独立的 memberId，每条测试
 * 就只看得见自己造的数据。{@code member_id} 上<b>没有外键</b>
 * （全库统一不加 FK，理由见 {@code sql/chargeops.sql} 的头部说明），
 * 所以凭空造一个 id 是合法的 —— 这一点由下面的
 * {@code insertShouldNotNeedARealMember} 显式固定下来。
 */
@SpringBootTest
class MemberPackageMapperTest {

    @Autowired
    private MemberPackageMapper memberPackageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的权益 id，收尾时按 id 删 */
    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM member_package WHERE id = ?", id);
        }
        createdIds.clear();
        PageHelper.clearPage();
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    /** 每条测试独立的持有人 id，避开种子数据 */
    private long newMemberId() {
        return System.nanoTime();
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 造一份电量包（{@code package_type = 1}）
     *
     * <p>★ 四个 {@code NOT NULL} 且带 DDL 默认值的列在这里全部显式给出
     * （{@code effectiveFrom / expireTime / status / version}）——
     * 因为 insert 是全列插入，DDL 的 {@code DEFAULT} 不生效。
     * 生产代码里它们的来源见 {@link MemberPackageMapper#insert} 的 javadoc。
     *
     * <p>★ {@code lockedOrderId} <b>不设</b>，保持 null ——
     * 新买的权益没有被任何订单锁定，带上非 null 值会立即占用唯一索引。
     */
    private MemberPackage insertEnergyPackage(long memberId, String kwh) {
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId)
                .packageId(1L)
                .packageType(1)
                .packageName("150度电包")
                .totalKwh(new BigDecimal(kwh))
                .remainingKwh(new BigDecimal(kwh))
                .effectiveFrom(now().minusDays(1))
                .expireTime(now().plusDays(89))
                .status(1)
                .version(0)
                .build();
        memberPackageMapper.insert(mp);
        assertNotNull(mp.getId(), "insert 后 id 应被回填");
        createdIds.add(mp.getId());
        return mp;
    }

    /** 造一份折扣包（{@code package_type = 3}）—— 不消耗，没有 remaining_* */
    private MemberPackage insertDiscountPackage(long memberId) {
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId)
                .packageId(3L)
                .packageType(3)
                .packageName("服务费八折卡")
                .serviceDiscount(new BigDecimal("0.8000"))
                .effectiveFrom(now().minusDays(1))
                .expireTime(now().plusDays(89))
                .status(1)
                .version(0)
                .build();
        memberPackageMapper.insert(mp);
        createdIds.add(mp.getId());
        return mp;
    }

    /** 造一份次卡（{@code package_type = 4}） */
    private MemberPackage insertTimesPackage(long memberId, int times) {
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId)
                .packageId(4L)
                .packageType(4)
                .packageName("10次卡")
                .totalTimes(times)
                .remainingTimes(times)
                .effectiveFrom(now().minusDays(1))
                .expireTime(now().plusDays(89))
                .status(1)
                .version(0)
                .build();
        memberPackageMapper.insert(mp);
        createdIds.add(mp.getId());
        return mp;
    }

    // ==================== INSERT / @AutoFill ====================

    @Test
    @DisplayName("INSERT：@AutoFill 填审计四列，且 member_id / package_id 不需要对应的真实行")
    void insertShouldFillAuditColumnsAndNotNeedRealParents() {
        BaseContext.setCurrentId(1L);
        LocalDateTime before = LocalDateTime.now();

        MemberPackage mp = insertEnergyPackage(newMemberId(), "150.00");

        LocalDateTime after = LocalDateTime.now();

        assertEquals(1L, mp.getCreateUser());
        assertEquals(1L, mp.getUpdateUser());
        assertTrue(mp.getCreateTime().isAfter(before.minusSeconds(1))
                        && mp.getCreateTime().isBefore(after.plusSeconds(1)),
                "create_time 应该是 Java 侧的时钟，不是数据库默认值");
        assertEquals(mp.getCreateTime(), mp.getUpdateTime());

        // ★ 上面用的是凭空生成的 memberId 和写死的 packageId=1 之外的 id，
        //   但它们并不存在对应的 member / charge_package 行 —— 插入照样成功。
        //   这是全库不加外键这个决定的直接后果，**故意固定下来**：
        //   换成有 FK 的设计，这个测试会第一个红，那时需要重新想清楚
        //   「权益能不能先于支付单存在」这个问题（P1c-3 会给出答案）。
        MemberPackage reloaded = memberPackageMapper.getById(mp.getId());
        assertNotNull(reloaded);
        assertNull(reloaded.getLockedOrderId(), "新买的权益不该被锁定");
        assertEquals(1, reloaded.getStatus());
        assertEquals(0, new BigDecimal("150.00").compareTo(reloaded.getRemainingKwh()));
    }

    @Test
    @DisplayName("★ expire_time 是 NOT NULL 无默认值：漏传直接报错，不会静默变成「永久有效」")
    void missingExpireTimeShouldFailLoudly() {
        BaseContext.setCurrentId(1L);

        // ★ 这条测试守的是一个能白送钱的错误：
        //   如果 expire_time 有 DEFAULT（比如 9999 哨兵或 NOW()），
        //   那么任何一处漏传都会造出一份「永久有效」的权益 ——
        //   查询正常、结算正常，只有几个月后对账才会发现。
        //   没有 DEFAULT 时它是一次响亮的 errno 1364。
        assertThrows(DataIntegrityViolationException.class,
                () -> memberPackageMapper.insert(MemberPackage.builder()
                        .memberId(newMemberId())
                        .packageId(1L)
                        .packageType(1)
                        .packageName("漏了有效期")
                        .totalKwh(new BigDecimal("10.00"))
                        .remainingKwh(new BigDecimal("10.00"))
                        .effectiveFrom(now())
                        // expireTime 不给
                        .status(1)
                        .version(0)
                        .build()),
                "expire_time 是 NOT NULL 且没有 DDL 默认值，漏传应该报错");
    }

    @Test
    @DisplayName("★★ 永久有效的哨兵值 9999-12-31 23:59:59.000 必须能被 datetime(3) 原样存取")
    void permanentExpireTimeShouldRoundTrip() {
        BaseContext.setCurrentId(1L);

        MemberPackage mp = MemberPackage.builder()
                .memberId(newMemberId())
                .packageId(1L)
                .packageType(1)
                .packageName("永久畅充")
                .totalKwh(new BigDecimal("9999.00"))
                .remainingKwh(new BigDecimal("9999.00"))
                .effectiveFrom(now())
                .expireTime(MemberPackage.PERMANENT_EXPIRE_TIME)
                .status(1)
                .version(0)
                .build();
        memberPackageMapper.insert(mp);
        createdIds.add(mp.getId());

        // ★ 这一条守的是「为什么不用 LocalDateTime.MAX」：
        //   MAX 是 +999999999-12-31T23:59:59.999999999，写进 datetime(3)
        //   会被 MySQL 拒绝。哨兵值必须真的能往返，否则「永久套餐」
        //   会在购买那一刻炸掉，而不是在使用时。
        assertEquals(MemberPackage.PERMANENT_EXPIRE_TIME,
                memberPackageMapper.getById(mp.getId()).getExpireTime(),
                "永久有效的哨兵值往返后应该一模一样 —— 注意是 equals 不是 compareTo，"
                        + "微秒被 datetime(3) 截断也属于失败");
    }

    // ==================== ★★ 唯一索引锁语义 ★★ ====================

    @Test
    @DisplayName("★★ uk_mp_locked_order：多份未锁定并存，一旦锁定第二笔订单在数据库层就锁不上")
    void onlyOneOrderCanLockAGivenPackage() {
        BaseContext.setCurrentId(1L);
        long memberId = newMemberId();

        // 三份未锁定的权益并存 —— NULL 不被唯一索引约束，这是整个设计的前提
        MemberPackage a = insertEnergyPackage(memberId, "150.00");
        MemberPackage b = insertEnergyPackage(memberId, "50.00");
        MemberPackage c = insertEnergyPackage(memberId, "50.00");
        assertNull(memberPackageMapper.getById(a.getId()).getLockedOrderId());
        assertNull(memberPackageMapper.getById(b.getId()).getLockedOrderId());
        assertNull(memberPackageMapper.getById(c.getId()).getLockedOrderId());

        // 订单 1001 锁住 a
        assertEquals(1, memberPackageMapper.lockForOrder(a.getId(), 1001L, now()));
        assertEquals(1001L, memberPackageMapper.getById(a.getId()).getLockedOrderId());

        // ★ 条件更新先兜住：第二个订单锁同一份，走的是「影响行数 0」这条正常分支，
        //   而不是抛异常。调用方据此回到 listUsable 取下一份重试。
        assertEquals(0, memberPackageMapper.lockForOrder(a.getId(), 1002L, now()),
                "已被锁定的权益再锁应该影响 0 行");

        // ★ 唯一索引是最后一道防线。绕过条件更新直接写 SQL 才会撞上它 ——
        //   两个并发事务可能都通过了 locked_order_id IS NULL 的检查，
        //   那时唯一索引必须让后者失败。这里直接验证它真的在。
        //
        //   ★ 注意撞的是什么：唯一索引约束的是「两行不能有同一个
        //     locked_order_id」，所以要让 **b 去抢 a 已经持有的 1001**。
        //     把 a 自己的值从 1001 改成 1002 是**合法**的（没有第二行占着 1002）,
        //     第一版测试就是这么写的，于是它压根没触发索引、白测了一场。
        assertThrows(DuplicateKeyException.class,
                () -> jdbcTemplate.update(
                        "UPDATE member_package SET locked_order_id = ? WHERE id = ?",
                        1001L, b.getId()),
                "uk_mp_locked_order 必须在数据库层拦住「两笔订单锁同一份权益」");

        // ★ 反向：b、c 仍然可以各自被别的订单锁住，a 被锁不影响它们
        assertEquals(1, memberPackageMapper.lockForOrder(b.getId(), 1002L, now()));
        assertEquals(1, memberPackageMapper.lockForOrder(c.getId(), 1003L, now()));
        assertNotNull(memberPackageMapper.getById(b.getId()).getLockedOrderId());
    }

    @Test
    @DisplayName("★ lockForOrder：非 status=1（已作废）的权益锁不上")
    void lockShouldRefuseNonActivePackage() {
        BaseContext.setCurrentId(1L);
        MemberPackage mp = insertEnergyPackage(newMemberId(), "150.00");

        // 作废（退款 / 客服补偿 / 数据修复）
        jdbcTemplate.update("UPDATE member_package SET status = 4 WHERE id = ?", mp.getId());

        // ★ 少了 status = 1 这个条件的话，「作废」和「下单」并发时
        //   会出现「钱没退、权益也不能用」的中间状态：作废改的是 status，
        //   锁定只看 locked_order_id，两者互不干涉，锁能成功。
        assertEquals(0, memberPackageMapper.lockForOrder(mp.getId(), 1001L, now()),
                "已作废的权益不该被锁住");
        assertNull(memberPackageMapper.getById(mp.getId()).getLockedOrderId());
    }

    // ==================== ★★ 幂等结算 ★★ ====================

    @Test
    @DisplayName("★★ 幂等：结算第一次影响 1 行，重复结算影响 0 行（locked_order_id 就是幂等闸门）")
    void settleShouldBeIdempotent() {
        BaseContext.setCurrentId(1L);
        MemberPackage mp = insertEnergyPackage(newMemberId(), "150.00");

        memberPackageMapper.lockForOrder(mp.getId(), 1001L, now());

        // ---- 第一次结算：扣 30 度 ----
        assertEquals(1, memberPackageMapper.settleEnergy(
                mp.getId(), 1001L, new BigDecimal("30.00"), now()));

        MemberPackage afterFirst = memberPackageMapper.getById(mp.getId());
        assertEquals(0, new BigDecimal("120.00").compareTo(afterFirst.getRemainingKwh()));
        assertNull(afterFirst.getLockedOrderId(), "结算必须顺手把锁释放掉");
        assertEquals(1, afterFirst.getStatus(), "还有 120 度，不该是用尽");

        // ---- 设备重复上报「充电结束」，第二次结算 ----
        // ★ 这是整个设计里最要紧的一条：防重复扣减不需要额外的幂等表，
        //   WHERE locked_order_id = ? 本身就是闸门 —— 第一次执行后它被置为
        //   NULL，第二次必然匹配不上。已经在真库上验证过同样的行为。
        assertEquals(0, memberPackageMapper.settleEnergy(
                        mp.getId(), 1001L, new BigDecimal("30.00"), now()),
                "★★ 重复结算必须影响 0 行。返回 1 意味着用户被扣了两次电");

        assertEquals(0, new BigDecimal("120.00").compareTo(
                        memberPackageMapper.getById(mp.getId()).getRemainingKwh()),
                "★★ 重复结算之后余额必须还是 120 —— 变成 90 就是重复扣款");

        // ---- 第三次，换个订单号也不行（那笔订单根本没锁过这份权益）----
        assertEquals(0, memberPackageMapper.settleEnergy(
                        mp.getId(), 1002L, new BigDecimal("30.00"), now()),
                "没锁过这份权益的订单不该能扣它的量");
    }

    @Test
    @DisplayName("★ 结算后用尽：status 由 SQL 的 CASE 自动置 2，锁一并释放")
    void settleExhaustingShouldFlipStatusToExhausted() {
        BaseContext.setCurrentId(1L);
        MemberPackage mp = insertEnergyPackage(newMemberId(), "150.00");

        memberPackageMapper.lockForOrder(mp.getId(), 1001L, now());
        assertEquals(1, memberPackageMapper.settleEnergy(
                mp.getId(), 1001L, new BigDecimal("150.00"), now()));

        MemberPackage reloaded = memberPackageMapper.getById(mp.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(reloaded.getRemainingKwh()));
        // ★ 判状态和改状态在同一条语句里，没有中间窗口
        assertEquals(2, reloaded.getStatus(), "用尽后 status 应该被 CASE 置成 2");
        assertNull(reloaded.getLockedOrderId());
    }

    @Test
    @DisplayName("★ settleTimes：一次充电消耗一次；剩余 1 次时结算即用尽")
    void settleTimesShouldConsumeExactlyOne() {
        BaseContext.setCurrentId(1L);
        MemberPackage mp = insertTimesPackage(newMemberId(), 3);

        // 第一次
        memberPackageMapper.lockForOrder(mp.getId(), 1001L, now());
        assertEquals(1, memberPackageMapper.settleTimes(mp.getId(), 1001L, now()));
        MemberPackage afterFirst = memberPackageMapper.getById(mp.getId());
        assertEquals(2, afterFirst.getRemainingTimes());
        assertEquals(1, afterFirst.getStatus(), "还剩 2 次，不该是用尽");

        // 第二次 —— 换个订单号，因为上一次结算已经释放了锁
        memberPackageMapper.lockForOrder(mp.getId(), 1002L, now());
        assertEquals(1, memberPackageMapper.settleTimes(mp.getId(), 1002L, now()));
        assertEquals(1, memberPackageMapper.getById(mp.getId()).getRemainingTimes());

        // 第三次：用掉最后一次 → 状态自动变 2
        memberPackageMapper.lockForOrder(mp.getId(), 1003L, now());
        assertEquals(1, memberPackageMapper.settleTimes(mp.getId(), 1003L, now()));
        MemberPackage exhausted = memberPackageMapper.getById(mp.getId());
        assertEquals(0, exhausted.getRemainingTimes());
        assertEquals(2, exhausted.getStatus(), "最后一次用掉之后应该自动置为已用尽");

        // ★ 用尽之后锁不上、也结算不了 —— 唯一键和余量两道闸门都关上了
        assertEquals(0, memberPackageMapper.lockForOrder(mp.getId(), 1004L, now()));
        assertEquals(0, memberPackageMapper.settleTimes(mp.getId(), 1004L, now()));
    }

    @Test
    @DisplayName("★ releaseLock：只释放自己的锁，且幂等 —— 不会误清掉别人的锁")
    void releaseLockShouldOnlyReleaseItsOwnLock() {
        BaseContext.setCurrentId(1L);
        MemberPackage mp = insertEnergyPackage(newMemberId(), "150.00");

        memberPackageMapper.lockForOrder(mp.getId(), 1001L, now());

        // ★ 一个延迟到达的释放请求（超时任务扫到了一笔并非它锁的订单）
        assertEquals(0, memberPackageMapper.releaseLock(mp.getId(), 9999L, now()),
                "不是自己锁的，不该释放");
        assertEquals(1001L, memberPackageMapper.getById(mp.getId()).getLockedOrderId(),
                "★★ 别人的锁必须还在 —— 被误清的话，那笔订单结算时扣不到量，"
                        + "钱收了量没扣，而且全程没有报错");

        // 正常释放
        assertEquals(1, memberPackageMapper.releaseLock(mp.getId(), 1001L, now()));
        assertNull(memberPackageMapper.getById(mp.getId()).getLockedOrderId());

        // ★ 释放不动余量 —— 这是它和 settle 的唯一区别
        assertEquals(0, new BigDecimal("150.00").compareTo(
                memberPackageMapper.getById(mp.getId()).getRemainingKwh()),
                "取消订单不该消耗任何权益");

        // 重复释放：幂等，影响 0 行
        assertEquals(0, memberPackageMapper.releaseLock(mp.getId(), 1001L, now()));

        // ★ 释放之后可以被下一笔订单重新锁定 —— 权益回到可用池
        assertEquals(1, memberPackageMapper.lockForOrder(mp.getId(), 1005L, now()));
    }

    // ==================== listUsable ====================

    @Test
    @DisplayName("★★ listUsable：四个条件缺一不可（status / 未锁定 / 未过期 / 有余量）")
    void listUsableShouldApplyAllFourConditions() {
        BaseContext.setCurrentId(1L);
        long memberId = newMemberId();
        LocalDateTime now = now();

        MemberPackage usable = insertEnergyPackage(memberId, "150.00");

        // ① 被别的订单锁着
        MemberPackage locked = insertEnergyPackage(memberId, "150.00");
        memberPackageMapper.lockForOrder(locked.getId(), 1001L, now);

        // ② 已作废
        MemberPackage voided = insertEnergyPackage(memberId, "150.00");
        jdbcTemplate.update("UPDATE member_package SET status = 4 WHERE id = ?", voided.getId());

        // ③ 已过期（expire_time 在当前时刻之前）
        MemberPackage expired = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(1)
                .packageName("过期包")
                .totalKwh(new BigDecimal("150.00")).remainingKwh(new BigDecimal("150.00"))
                .effectiveFrom(now.minusDays(100)).expireTime(now.minusDays(1))
                .status(1).version(0).build();
        memberPackageMapper.insert(expired);
        createdIds.add(expired.getId());

        // ④ 余量为 0（但 status 还是 1 —— 模拟定时任务还没刷新状态）
        MemberPackage drained = insertEnergyPackage(memberId, "150.00");
        jdbcTemplate.update("UPDATE member_package SET remaining_kwh = 0 WHERE id = ?",
                drained.getId());

        List<MemberPackage> result = memberPackageMapper.listUsable(memberId, 1, now);

        // ★★ 只有 ① 那份可用。
        //   ②③④ 每一个漏掉都是静默的：查询正常返回，只是多了一行不该有的，
        //   然后在结算那一刻变成「过期权益被发出去」或「余额扣成负数」。
        assertEquals(1, result.size(),
                "只应该剩 1 份可用，实际 " + result.size()
                        + " 份 —— 多出来的每一份都对应 listUsable 少了一个条件");
        assertEquals(usable.getId(), result.get(0).getId());

        // 逐条点名，失败时能直接看出是哪个条件漏了
        List<Long> got = result.stream().map(MemberPackage::getId).toList();
        assertTrue(!got.contains(voided.getId()), "status != 1 的不该出现");
        assertTrue(!got.contains(locked.getId()), "被锁定的不该出现");
        assertTrue(!got.contains(expired.getId()), "过期的不该出现");
        assertTrue(!got.contains(drained.getId()), "余量为 0 的不该出现");
    }

    @Test
    @DisplayName("★★ listUsable：折扣包（type=3）不消耗、没有 remaining_*，必须仍能被选出来")
    void listUsableShouldFindDiscountPackageWithoutAnyRemainingColumn() {
        BaseContext.setCurrentId(1L);
        long memberId = newMemberId();

        MemberPackage discount = insertDiscountPackage(memberId);

        List<MemberPackage> result = memberPackageMapper.listUsable(memberId, 3, now());

        // ★ 这条测试守的是一个**极易被改坏**的地方：
        //   折扣包的 remaining_kwh / remaining_minutes / remaining_times
        //   全是 NULL。如果哪天有人「顺手统一一下」给 type=3 也加上
        //   余量条件，`NULL > 0` 恒不成立 ——
        //   结果是折扣包**永远选不出来**，而且完全静默：
        //   查询正常返回空列表，没有报错，只是用户的八折卡再也没生效过。
        assertEquals(1, result.size(),
                "★★ 折扣包被 listUsable 漏掉了。检查 XML 里的 <choose> 是不是给 "
                        + "packageType == 3 也加了余量条件 —— NULL 参与比较恒不成立");
        assertEquals(discount.getId(), result.get(0).getId());
        assertNull(result.get(0).getRemainingKwh());
        assertNull(result.get(0).getRemainingMinutes());
        assertNull(result.get(0).getRemainingTimes());
        assertEquals(0, new BigDecimal("0.8000").compareTo(result.get(0).getServiceDiscount()));

        // ★ 而且折扣包**不该被锁定**（它不消耗，锁它反而会因为
        //   「一笔订单同时用了电量包和折扣包」撞上 uk_mp_locked_order）
        assertNull(result.get(0).getLockedOrderId());
    }

    @Test
    @DisplayName("★ listUsable：先到期的先用（ORDER BY expire_time ASC）")
    void listUsableShouldUseTheSoonestExpiringFirst() {
        BaseContext.setCurrentId(1L);
        long memberId = newMemberId();
        LocalDateTime now = now();

        // 故意先插晚到期的，后插早到期的 —— 结果顺序不能跟着插入顺序走
        MemberPackage later = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(1)
                .packageName("明年到期")
                .totalKwh(new BigDecimal("150.00")).remainingKwh(new BigDecimal("150.00"))
                .effectiveFrom(now.minusDays(1)).expireTime(now.plusDays(300))
                .status(1).version(0).build();
        memberPackageMapper.insert(later);
        createdIds.add(later.getId());

        MemberPackage sooner = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(1)
                .packageName("这个月到期")
                .totalKwh(new BigDecimal("150.00")).remainingKwh(new BigDecimal("150.00"))
                .effectiveFrom(now.minusDays(1)).expireTime(now.plusDays(10))
                .status(1).version(0).build();
        memberPackageMapper.insert(sooner);
        createdIds.add(sooner.getId());

        List<MemberPackage> result = memberPackageMapper.listUsable(memberId, 1, now);

        assertEquals(2, result.size());
        // ★ 反过来的话，这份这个月过期的会被压在明年到期的后面，
        //   直到彻底作废都没被用过 —— 用户会认为「我的套餐白买了」。
        assertEquals(sooner.getId(), result.get(0).getId(),
                "★★ 应该先用快过期的那份，否则权益会白白作废");
        assertEquals(later.getId(), result.get(1).getId());
    }

    // ==================== listByMember ====================

    @Test
    @DisplayName("★ listByMember：不过滤状态（灰态项也要返回），排序 status → expire_time → id")
    void listByMemberShouldNotFilterByStatus() {
        BaseContext.setCurrentId(1L);
        long memberId = newMemberId();

        MemberPackage active = insertEnergyPackage(memberId, "150.00");

        MemberPackage exhausted = insertEnergyPackage(memberId, "150.00");
        jdbcTemplate.update("UPDATE member_package SET status = 2 WHERE id = ?",
                exhausted.getId());

        MemberPackage voided = insertEnergyPackage(memberId, "150.00");
        jdbcTemplate.update("UPDATE member_package SET status = 4 WHERE id = ?", voided.getId());

        List<MemberPackage> rows = memberPackageMapper.listByMember(memberId);

        // ★ 已用尽 / 已作废的**也要返回**，让前端显示成灰态。
        //   滤掉的话用户会以为「我的套餐凭空消失了」。
        assertEquals(3, rows.size(),
                "★★ listByMember 不该过滤状态，灰态的权益也要返回");
        assertEquals(active.getId(), rows.get(0).getId(), "status=1 的排最前");

        // ★ 排序覆盖 idx_mp_member_status_expire (member_id, status, expire_time)：
        //   等值列在最左、排序列在后面，不产生 filesort。
        //   这里只断言「status 分组有序」这一条可观察的性质 ——
        //   上面对 active 排第一的断言已经覆盖了它。
        assertTrue(rows.get(0).getStatus() <= rows.get(rows.size() - 1).getStatus());

        // ★ 别人的权益不能出现
        assertTrue(memberPackageMapper.listByMember(memberId + 1).isEmpty(),
                "不该查出别的车主的权益");
    }
}
