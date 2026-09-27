package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.RedisConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.MemberPackageVoidDTO;
import com.chargeops.entity.ChargePackage;
import com.chargeops.entity.MemberPackage;
import com.chargeops.entity.Wallet;
import com.chargeops.entity.WalletTransaction;
import com.chargeops.enumeration.MemberPackageStatus;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.enumeration.PackageType;
import com.chargeops.enumeration.WalletTransactionType;
import com.chargeops.exception.AccountLockedException;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargePackageMapper;
import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.mapper.WalletMapper;
import com.chargeops.mapper.WalletTransactionMapper;
import com.chargeops.redis.CacheClient;
import com.chargeops.service.MemberPackageService;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import com.chargeops.vo.MemberPackageVoidVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code MemberPackageServiceImpl} 的集成测试 —— 套餐购买（直购）链路
 *
 * <p>通用写法（为什么是集成测试、为什么不能标 {@code @Transactional}、
 * 为什么每条测试自己造 member）见 {@code WalletServiceImplTest} 的类注释，
 * 三条理由完全一样。本类只写它自己特有的部分。
 *
 * <h2>★★ 一、本类最值钱的断言是「其余三组列必须为 NULL」★★</h2>
 *
 * <p>{@code member_package} 的八列里只有一组该有值。漏了「必填的那个要有」，
 * 后果是<b>算不出东西、看得见</b>；而<b>残留字段是静默的</b>：一份「30 天时长包」
 * 上如果还留着 {@code remaining_kwh = 150}，结算时读到它就会白白送出 150 度电 ——
 * 每一步都不报错，只有对账时才发现钱对不上，而那时已经无从追溯是哪一行数据引入的。
 *
 * <p>所以 {@link #purchaseEnergyShouldFillTheKwhPair} 那四条测试里都有一句
 * {@code assertBenefitColumns}，它断言的是<b>另外三个类型的六个列全是 {@code null}</b>。
 * 这一句比「这一组有值」更重要，不要为了少一次断言把它删掉。
 *
 * <p>★ 服务端这边是<b>由写入形状保证的</b>（{@code copyBenefit} 只读当前类型的
 * 那一个字段、只写对应的那一组，没有「清空其余」的代码，因为它不需要），
 * 测试这一侧才需要逐列断言 —— 形状是设计，断言是证据。
 *
 * <h2>★★ 二、{@code charge_package} 必须<b>按显式 id 列表</b>删，不能按范围/前缀 ★★</h2>
 *
 * <p>其余几张表（{@code member_package} / {@code wallet_transaction} /
 * {@code wallet} / {@code member}）都挂在测试自己指定的 {@code member_id} 上，
 * 按 {@code >= TEST_ID_BASE} 一次删干净是安全的。
 *
 * <p><b>但 {@code charge_package} 不行：</b>它里面有<b>种子行</b>
 * （{@code V2__seed_data.sql} 的「150度电包」「新站首充 50 度」），
 * 别的测试类和演示环境都依赖它们；而且它是 {@code AUTO_INCREMENT}，
 * 测试<b>指定不了</b> id，只能靠 {@code useGeneratedKeys} 回填 ——
 * 于是「按范围删」会连别人的行一起删，「按名字前缀删」也不安全
 * （前缀是人为约定，撞上别人的命名就会误删）。
 * 所以本类把每一个自己建出来的 id 记在 {@link #createdSkuIds} 里，逐个删。
 *
 * <h2>★ 三、脏数据用 {@code JdbcTemplate} 直接插库造</h2>
 *
 * <p>{@code energy_kwh IS NULL} 的电量包、{@code service_discount = 1.5} 的折扣包
 * 这几行，<b>走正常路径建不出来</b>（{@code ChargePackageServiceImpl.assertTypeFields}
 * 会拦住）。它们代表的是「有人直接改过库」或者「校验上线之前留下的数据」——
 * 购买路径必须对它们<b>响亮地拒绝</b>，而不是卖出一份用不了的权益。
 */
@SpringBootTest
class MemberPackageServiceImplTest {

    @Autowired
    private MemberPackageService memberPackageService;

    @Autowired
    private MemberPackageMapper memberPackageMapper;

    @Autowired
    private ChargePackageMapper chargePackageMapper;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试造出来的 member id，收尾时按范围删 */
    private static final long TEST_ID_BASE = 5_000_000_000_000L;

    private static long SEQ = 0;

    /** 本类自己建的 SKU id —— 见类注释第二节，只能逐个删 */
    private final List<Long> createdSkuIds = new ArrayList<>();

    /**
     * 本类不关心分类，用种子里那个「充电套餐分类」的 id。
     *
     * <p>★ 购买路径<b>不校验分类</b>（那是管理端 {@code ChargePackageServiceImpl}
     * 的事），这里填一个存在的值只是为了让测试数据看起来像真的。
     */
    private static final long CATEGORY_ID = 1L;

    @AfterEach
    void cleanUp() {
        // ★ 先删引用方：这些表的 id 和 charge_package 没有外键关系，
        //   顺序其实无所谓，但保持「先删事实、再删定义」的习惯。
        //   ★ charging_order 在这一行，因为作废路径要问「这份权益被几笔订单引用过」——
        //     本类会直接插几笔订单行来构造那个场景，不清掉它们会污染别的测试
        //     （那些订单指向的权益已经不在了）。
        jdbcTemplate.update("DELETE FROM charging_order WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM member_package WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet_transaction WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM wallet WHERE member_id >= ?", TEST_ID_BASE);
        jdbcTemplate.update("DELETE FROM member WHERE id >= ?", TEST_ID_BASE);

        // ★★ 种子行不能碰 —— 见类注释第二节，只能按本类自己记下的 id 删 ★★
        for (Long skuId : createdSkuIds) {
            jdbcTemplate.update("DELETE FROM charge_package WHERE id = ?", skuId);
        }
        createdSkuIds.clear();

        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 断言两个金额相等
     *
     * <p>★ 用 {@code compareTo} 而不是 {@code isEqualTo}：{@code BigDecimal} 的
     * {@code equals} 会把 scale 算进去 —— {@code 100.00} 和 {@code 100} 数值相等但
     * {@code equals} 返回 false，而它们经过一圈 MySQL {@code decimal(12,2)} 往返之后
     * 确实会变成不同 scale。金额比的是数值，不是小数位数。
     */
    private static void assertDecimal(String expected, BigDecimal actual, String what) {
        assertThat(actual).as("%s（期望 %s，实际 %s）", what, expected, actual).isNotNull();
        assertThat(actual.compareTo(bd(expected)))
                .as("%s（期望 %s，实际 %s）", what, expected, actual).isZero();
    }

    /**
     * 造一个车主并登入上下文
     *
     * <p>★ {@code assertActive} 要求 {@code member.status = 1}，
     * 所以必须是一行真 member —— {@code member_id} 上没有外键，
     * 但「账号可用」这个判断是真的会去查库的。
     */
    private long loginAsNewMember() {
        return givenMember(StatusConstant.ENABLE);
    }

    /** 造一个被禁用的车主并登入上下文 —— 「token 有效 ≠ 账号可用」的那条路 */
    private long givenDisabledMember() {
        return givenMember(StatusConstant.DISABLE);
    }

    private long givenMember(int status) {
        long memberId = TEST_ID_BASE + (SEQ++);
        jdbcTemplate.update(
                "INSERT INTO member (id, phone, nickname, status) VALUES (?, ?, ?, ?)",
                memberId, "139" + memberId, "权益测试车主", status);
        BaseContext.setCurrentId(memberId);
        return memberId;
    }

    /**
     * 直接给钱包塞一个初始余额
     *
     * <p>★ 走 {@code insertIgnore} 而不是 {@code recharge}：本类要测的是购买，
     * 用例必须从一个确定的状态出发，而不是先跑一遍「充值」那条链路。
     */
    private void givenBalance(long memberId, String balance) {
        LocalDateTime now = LocalDateTime.now();
        walletMapper.insertIgnore(Wallet.builder()
                .memberId(memberId).balance(bd(balance))
                .frozenAmount(BigDecimal.ZERO).totalRecharge(bd(balance))
                .version(0).createTime(now).updateTime(now).build());
    }

    /**
     * 一个上架 SKU 的骨架：名称、分类、类型、价格、有效期、状态
     *
     * <p>★ 名称带 {@code System.nanoTime()}（沿用 {@code ChargePackageMapperTest}
     * 的做法）：{@code uk_pkg_name_station (name, station_id)} 是唯一键，
     * 而测试是并发跑在同一个开发库上的 —— 名字撞了会得到一个
     * {@code DuplicateKeyException}，看起来像是「购买功能坏了」。
     *
     * <p>★ 默认 {@code stationId = 0}（全平台）、{@code validDays = 30}：
     * 这两个值让绝大多数用例不必关心「站点范围」和「有效期」这两件事。
     */
    private ChargePackage.ChargePackageBuilder skuOf(PackageType type, String price) {
        return ChargePackage.builder()
                .name("权益测试套餐-" + System.nanoTime())
                .categoryId(CATEGORY_ID)
                .packageType(type.getCode())
                .stationId(0L)
                .price(bd(price))
                .validDays(30)
                .status(StatusConstant.ENABLE)
                .sort(0);
    }

    /** 落库一个 SKU 并记住它的 id（收尾时逐个删） */
    private ChargePackage givenSku(ChargePackage sku) {
        chargePackageMapper.insert(sku);
        assertThat(sku.getId()).as("insert 后 id 应被 useGeneratedKeys 回填").isNotNull();
        createdSkuIds.add(sku.getId());
        return sku;
    }

    /**
     * 直接插一行「脏数据」SKU —— <b>绕过管理端的类型专属字段校验</b>
     *
     * <p>★ 这种行在正常路径下造不出来（{@code ChargePackageServiceImpl.assertTypeFields}
     * 两个方向都查）。它能存在的唯一方式是有人<b>直接改过库</b>，
     * 而购买路径必须对它响亮地拒绝 —— 见类注释第三节。
     *
     * @param typeColumn 要写入非法值的类型专属列名。
     *                   ★ 它是<b>拼进 SQL</b> 的，只允许传字面量，不要传变量进来
     * @param typeValue  该列的值，传 {@code null} 就是「这列为空」
     */
    private long givenRawSku(PackageType type, String price, String typeColumn, Object typeValue) {
        String name = "权益测试套餐-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO charge_package "
                        + "(name, category_id, package_type, station_id, price, " + typeColumn
                        + ", valid_days, status) VALUES (?, ?, ?, 0, ?, ?, 30, 1)",
                name, CATEGORY_ID, type.getCode(), bd(price), typeValue);

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM charge_package WHERE name = ?", Long.class, name);
        assertThat(id).as("插入后应查得到这一行").isNotNull();
        createdSkuIds.add(id);
        return id;
    }

    /**
     * 从库里回读刚买到的那一行
     *
     * <p>★ 断言的对象必须是<b>库里的行</b>，不是返回的 VO —— VO 只是内存里那个
     * 对象的投影，mapper 的列映射写错了的话，VO 照样是对的。
     */
    private MemberPackage loadRow(MemberPackageVO vo) {
        assertThat(vo.getId()).as("购买必须返回一个真实落库的 id").isNotNull();
        MemberPackage row = memberPackageMapper.getById(vo.getId());
        assertThat(row).as("member_package 里必须有这一行").isNotNull();
        return row;
    }

    private Wallet wallet(long memberId) {
        return walletMapper.getByMemberId(memberId);
    }

    private List<WalletTransaction> txns(long memberId) {
        return walletTransactionMapper.listByMember(memberId);
    }

    private long memberPackageCount(long memberId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_package WHERE member_id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    /**
     * ★★ 断言「只有该类型的那一组列有值，其余三组必须为 NULL」★★
     *
     * <p>本类里每一次购买成功都要调它。见类注释第一节：
     * <b>「不该有的列有值」是静默的</b> —— 一份时长包上残留的
     * {@code remaining_kwh} 会被结算读走，凭空送出电量，而全程不报错。
     *
     * <p>★ <b>折扣包（type=3）不在这套「一对」里</b>：它不是消耗品，
     * 只写 {@code service_discount} 一列，<b>没有「总量/剩余」这一对</b>。
     * 这个不对称是设计（见 {@code MemberPackageServiceImpl#copyBenefit}），
     * 所以这里也要顺手断言它另外六列全空，而不是替它编一个 {@code total_discount}。
     */
    private static void assertBenefitColumns(MemberPackage row, PackageType type) {
        if (type != PackageType.ENERGY) {
            assertThat(row.getTotalKwh()).as("★ %s 上残留 total_kwh —— 结算会按电量抵扣，白白送出电量",
                    type.getLabel()).isNull();
            assertThat(row.getRemainingKwh()).as("★ %s 上残留 remaining_kwh —— 同上，且它比 total 更致命",
                    type.getLabel()).isNull();
        }
        if (type != PackageType.DURATION) {
            assertThat(row.getTotalMinutes()).as("★ %s 上残留 total_minutes", type.getLabel()).isNull();
            assertThat(row.getRemainingMinutes()).as("★ %s 上残留 remaining_minutes", type.getLabel()).isNull();
        }
        if (type != PackageType.TIMES) {
            assertThat(row.getTotalTimes()).as("★ %s 上残留 total_times", type.getLabel()).isNull();
            assertThat(row.getRemainingTimes()).as("★ %s 上残留 remaining_times", type.getLabel()).isNull();
        }
        if (type != PackageType.SERVICE_DISCOUNT) {
            assertThat(row.getServiceDiscount()).as("★ %s 上残留 service_discount —— 结算会静默打折",
                    type.getLabel()).isNull();
        }
    }

    /** 手工插一份「已用尽 + 已过期」的权益，用来验证 listMine 不过滤状态 */
    private long givenDeadPackage(long memberId) {
        LocalDateTime now = LocalDateTime.now();
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId).packageId(1L).packageType(PackageType.TIMES.getCode())
                .packageName("已过期的次卡").totalTimes(5).remainingTimes(0)
                .effectiveFrom(now.minusDays(90)).expireTime(now.minusDays(1))
                .status(MemberPackageStatus.EXHAUSTED.getCode()).version(0)
                .build();
        memberPackageMapper.insert(mp);
        return mp.getId();
    }

    /**
     * 直接插一笔「引用过这份权益」的订单 —— <b>走正常路径造不出来</b>
     *
     * <p>★ 本类不关心充电链路的任何东西，所以只填 DDL 里那些 NOT NULL 且没有
     * 默认值的列（{@code order_no} / {@code member_id} / 三个设备 id /
     * {@code tariff_template_id} / {@code status}），其余全部留空。
     * <b>设备 id 填 0 是安全的</b>：{@code charging_order} 上没有任何外键，
     * 而本类要问的问题只有「{@code member_package_id} 上挂着几笔非取消的订单」。
     *
     * <p>★★ 用 {@code status = 8（已取消）} 调它就是「这份权益从未被消耗过」——
     * 那是 {@code cancel} 的语义：它调 {@code releaseLock} 放掉权益，
     * <b>余量一个都不动</b>。所以「只被已取消订单引用过」必须仍然可退。
     * 这正是 {@code countByPackage} 里那个 {@code <> 8} 的全部意义。
     *
     * @param status 见 {@code OrderStatus}。★ 传 8 就是「没用过」那一侧
     */
    private long givenOrderUsingPackage(long memberId, long packageId, int status) {
        String orderNo = "CO-VOID-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO charging_order "
                        + "(order_no, member_id, station_id, charger_id, connector_id, "
                        + " member_package_id, tariff_template_id, status) "
                        + "VALUES (?, ?, 0, 0, 0, ?, 0, ?)",
                orderNo, memberId, packageId, status);

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM charging_order WHERE order_no = ?", Long.class, orderNo);
        assertThat(id).as("插入后应查得到这一行").isNotNull();
        return id;
    }

    /** 走完整购买路径买一份 SKU，返回 {@code member_package.id} */
    private long buy(ChargePackage sku) {
        return memberPackageService.purchase(sku.getId()).getId();
    }

    /** 一份每次都能买到的「5 次卡」SKU */
    private ChargePackage timesSku(String price) {
        return givenSku(skuOf(PackageType.TIMES, price).times(5).validDays(30).build());
    }

    // ==================== 四种类型 ====================

    @Test
    @DisplayName("电量包：total/remaining 双双等于 SKU 的电量，刚买下就是满的")
    void purchaseEnergyShouldFillTheKwhPair() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        // 返回的是【刚写进 member_package 的那一行】，不是 SKU —— 车主买完要看的是
        // 「我这份还剩多少、什么时候过期」，那是持有属性
        assertThat(vo.getPackageType()).isEqualTo(PackageType.ENERGY.getCode());
        assertThat(vo.getPackageTypeLabel()).isEqualTo(PackageType.ENERGY.getLabel());
        assertThat(vo.getPackageName()).as("★ 名称是快照，SKU 改名不影响已售出的这一份")
                .isEqualTo(sku.getName());

        MemberPackage row = loadRow(vo);
        assertBenefitColumns(row, PackageType.ENERGY);
        assertDecimal("150.00", row.getTotalKwh(), "总电量");
        assertDecimal("150.00", row.getRemainingKwh(), "剩余电量");
        assertThat(row.getRemainingKwh())
                .as("★ 剩余量必须从【总量】开始，不是从 0 —— 这是「刚买下」的定义")
                .isEqualByComparingTo(row.getTotalKwh());
        assertThat(row.getStatus()).isEqualTo(MemberPackageStatus.ACTIVE.getCode());
    }

    @Test
    @DisplayName("时长包：写的是 total/remaining 分钟数，六个别列全空")
    void purchaseDurationShouldFillTheMinutesPair() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.DURATION, "30.00")
                .durationMinutes(600).validDays(30).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        MemberPackage row = loadRow(vo);
        assertBenefitColumns(row, PackageType.DURATION);
        assertThat(row.getTotalMinutes()).isEqualTo(600);
        assertThat(row.getRemainingMinutes()).isEqualTo(600);
    }

    @Test
    @DisplayName("次卡：写的是 total/remaining 次数，六个别列全空")
    void purchaseTimesShouldFillTheTimesPair() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.TIMES, "50.00")
                .times(5).validDays(30).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        MemberPackage row = loadRow(vo);
        assertBenefitColumns(row, PackageType.TIMES);
        assertThat(row.getTotalTimes()).isEqualTo(5);
        assertThat(row.getRemainingTimes()).isEqualTo(5);
    }

    @Test
    @DisplayName("★★ 折扣包：只抄 service_discount 一列，另外六列全为 NULL（它没有总量/剩余这一对）")
    void purchaseServiceDiscountShouldCopyOnlyTheDiscountColumn() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.SERVICE_DISCOUNT, "20.00")
                .serviceDiscount(bd("0.8000")).validDays(30).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        MemberPackage row = loadRow(vo);
        // ★★ 六列全空由这一句钉死。折扣包和另外三个类型【形状不同】，
        //    别为了「整齐」给它编一个 total_discount / remaining_discount：
        //    它不是消耗品，是「有效期内自动适用」的修饰符
        assertBenefitColumns(row, PackageType.SERVICE_DISCOUNT);
        assertDecimal("0.8000", row.getServiceDiscount(), "服务费折扣快照");
        assertThat(row.getServiceDiscount()).isEqualByComparingTo("0.8000");
    }

    // ==================== 有效期 ====================

    @Test
    @DisplayName("★ valid_days = 0 → expire_time 是 9999 哨兵，既不是 NULL 也不是当下")
    void zeroValidDaysShouldWriteThePermanentSentinel() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.TIMES, "50.00")
                .times(3).validDays(0).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        MemberPackage row = loadRow(vo);
        // ★★ 为什么是哨兵而不是 NULL：「有效期到那天」的查询写的是
        //    expire_time > NOW()，而 NULL 参与任何比较都不成立 ——
        //    永久有效的权益会被这个查询【漏掉】，表现是「明明买了永久套餐，结算时说没有」。
        //    （「0 = 永久」这个约定来自 DDL：valid_days int NOT NULL DEFAULT 0 COMMENT '0=永久'）
        assertThat(row.getExpireTime())
                .as("★ 永久有效必须是一个真实的、足够远的绝对时间")
                .isNotNull()
                .isEqualTo(MemberPackage.PERMANENT_EXPIRE_TIME);
        assertThat(row.getExpireTime()).isAfter(LocalDateTime.now().plusYears(100));
    }

    @Test
    @DisplayName("★★ valid_days = 30 → expire_time 是「购买时刻 + 30 天」的绝对时间快照")
    void positiveValidDaysShouldSnapshotAnAbsoluteExpireTime() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());
        LocalDateTime before = LocalDateTime.now();

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());
        LocalDateTime after = LocalDateTime.now();

        MemberPackage row = loadRow(vo);
        // ★★ 存的是【绝对时间】不是天数：valid_days 是 SKU 属性、管理员随时可以改，
        //    而「你这一份到哪天为止」是持有属性，不该被任何人的后续操作改变。
        //    下面这一句是最强形式 —— 购买时刻和过期时刻必须【精确】相差 30 天，
        //    而不是「大约」。
        assertThat(row.getExpireTime())
                .as("★★ 过期时刻 = 生效时刻 + valid_days 天；管理员之后把 SKU 的 valid_days "
                        + "从 30 改成 7，也不能让已经卖出去的那一份提前失效")
                .isEqualTo(row.getEffectiveFrom().plusDays(30));
        assertThat(row.getEffectiveFrom())
                .as("生效时刻 = 购买时刻")
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    // ==================== 钱 ====================

    @Test
    @DisplayName("★★ 扣款：余额恰好少掉售价，流水 type=6、金额为负、order_id 为 NULL")
    void purchaseShouldDeductThePriceAndWriteAPackagePurchaseTxn() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());
        assertThat(txns(memberId)).as("前置条件：这一刻还没有任何流水").isEmpty();

        memberPackageService.purchase(sku.getId());

        Wallet after = wallet(memberId);
        assertDecimal("400.00", after.getBalance(), "可用余额（500 − 100）");
        assertDecimal("0", after.getFrozenAmount(),
                "★ 直购【不经过冻结】—— 冻了就等于凭空多出一个要人去解的中间态");
        assertDecimal("500.00", after.getTotalRecharge(),
                "★ 累计充值记的是「充过多少」这个历史事实，花掉它不改变它");

        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(1);
        WalletTransaction txn = rows.get(0);
        assertThat(txn.getType()).isEqualTo(WalletTransactionType.PACKAGE_PURCHASE.getCode());
        assertDecimal("-100.00", txn.getAmount(), "★ 出账记负数");
        assertDecimal("500.00", txn.getBalanceBefore(), "前值");
        assertDecimal("400.00", txn.getBalanceAfter(), "后值");
        assertThat(txn.getOrderId())
                .as("★★ order_id 恒为 NULL：直购模型没有购买单，于是 wallet_transaction 上那道"
                        + "【数据库级】的幂等闸门 uk_txn_order_type 对本类型不生效 ——"
                        + "这是直购的固有代价，不是漏填")
                .isNull();
        assertThat(txn.getRemark())
                .as("★ order_id 是空的，remark 是这笔扣款唯一的可读线索")
                .contains(sku.getName());
    }

    @Test
    @DisplayName("★★ 余额不足：抛异常，且 member_package 里【一行都不留】")
    void insufficientBalanceShouldRollBackTheWholePurchase() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "10.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());

        assertThatThrownBy(() -> memberPackageService.purchase(sku.getId()))
                .isInstanceOf(BalanceNotEnoughException.class);

        // ★★ 后半句才是重点：只断言「抛了异常」的话，一个「先 insert 权益、再扣钱」
        //    的实现在异常被 catch 掉之后会留下一份白送的套餐，而测试照样绿。
        assertThat(memberPackageCount(memberId))
                .as("★★ 整笔事务必须回滚 —— 留一行权益就是白送了一份套餐")
                .isZero();
        assertThat(txns(memberId)).as("★ 扣款流水也必须一起回滚").isEmpty();
        assertDecimal("10.00", wallet(memberId).getBalance(), "余额原封不动");
    }

    @Test
    @DisplayName("★ 0 元（赠送）套餐：买得到，但【不写任何流水】")
    void freePackageShouldSucceedWithoutAnyTransaction() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "100.00");
        ChargePackage sku = givenSku(skuOf(PackageType.TIMES, "0.00")
                .times(1).validDays(7).build());

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        MemberPackage row = loadRow(vo);
        assertBenefitColumns(row, PackageType.TIMES);
        assertThat(row.getRemainingTimes()).as("赠送的次卡也是满的").isEqualTo(1);
        // ★ 这是刻意的：一条金额为 0 的流水不改变任何余额，只会给对账添噪音
        //   （和 WalletService.freeze 对 0 元的宽容是同一条理由）
        assertThat(txns(memberId)).as("★ 0 元不写流水").isEmpty();
        assertDecimal("100.00", wallet(memberId).getBalance(), "一分钱都不该动");
    }

    // ==================== 账号与 SKU 状态 ====================

    @Test
    @DisplayName("★★ 被禁用的车主买不了：AccountLockedException，且先于一切套餐校验")
    void disabledMemberShouldNotBeAbleToPurchase() {
        long memberId = givenDisabledMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());

        assertThatThrownBy(() -> memberPackageService.purchase(sku.getId()))
                .isInstanceOf(AccountLockedException.class)
                .hasMessage(MessageConstant.MEMBER_DISABLED);

        // ★ 连一个【不存在】的套餐 id 也报「账号被禁用」—— 这证明状态检查排在载入 SKU 之前，
        //   而不是「碰巧先撞上了别的校验」。token 有效 ≠ 账号可用（禁用不撤销 token，
        //   这是已记录的缺口），所以这一句必须在这个入口上自己查。
        assertThatThrownBy(() -> memberPackageService.purchase(9_999_999_999_999L))
                .isInstanceOf(AccountLockedException.class);

        assertThat(memberPackageCount(memberId)).isZero();
        assertDecimal("500.00", wallet(memberId).getBalance(), "一分钱都不能动");
    }

    @Test
    @DisplayName("★ 「已下架」和「不存在」是两句刻意的不同消息")
    void offShelfAndMissingSkuShouldBeToldApart() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage offShelf = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).status(StatusConstant.DISABLE).build());

        // 「这个套餐现在不卖了」→ 车主该做的是换个套餐或者稍后再来
        assertThatThrownBy(() -> memberPackageService.purchase(offShelf.getId()))
                .isInstanceOf(DataConflictException.class)
                .hasMessage(MessageConstant.PACKAGE_OFF_SHELF);

        // 「没有这个套餐」→ 车主手上是一个过期链接
        // ★ 两者合并成一句，第二种情况就会看起来像一次数据错误
        assertThatThrownBy(() -> memberPackageService.purchase(9_999_999_999_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.PACKAGE_NOT_FOUND);

        assertThat(memberPackageCount(memberId)).isZero();
        assertDecimal("500.00", wallet(memberId).getBalance(), "★ 校验失败的车主连钱包都不该被碰");
    }

    @Test
    @DisplayName("★★ SKU 的专属列是 NULL 时必须拒卖，而不是卖出一份永远选不中的权益")
    void truncatedSkuShouldBeRejectedInsteadOfSellingAnUnusablePackage() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long skuId = givenRawSku(PackageType.ENERGY, "100.00", "energy_kwh", null);

        assertThatThrownBy(() -> memberPackageService.purchase(skuId))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining(MessageConstant.PACKAGE_FIELD_MISSING);

        // ★★ 这条守卫防的是一个【全程静默】的失败：remaining_kwh IS NULL 让
        //    「这份权益还能用吗」的判据（remaining_kwh > 0）恒为 false ——
        //    它永远选不中、也永远标不成已用尽。用户看到的是
        //    「我买了套餐，充电却一直按余额扣钱」，而库里那行看起来完全正常。
        assertThat(memberPackageCount(memberId)).isZero();
        // ★ 次序也是设计的一部分：copyBenefit 排在扣钱之前，一个卖不出去的 SKU
        //   不该先在车主的钱上动一次手（同事务里会回滚，但日志里留下的是一次
        //   本不该发生的扣款尝试，排查时是纯噪音）
        assertThat(txns(memberId)).as("★ 一个卖不出去的 SKU 不该先动车主的钱").isEmpty();
        assertDecimal("500.00", wallet(memberId).getBalance(), "余额原封不动");
    }

    @Test
    @DisplayName("★ 0 度的电量包同样拒卖 —— remaining_kwh = 0 和 NULL 一样静默")
    void zeroEnergySkuShouldBeRejected() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long skuId = givenRawSku(PackageType.ENERGY, "0.00", "energy_kwh", BigDecimal.ZERO);

        assertThatThrownBy(() -> memberPackageService.purchase(skuId))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining(MessageConstant.PACKAGE_FIELD_MISSING);

        assertThat(memberPackageCount(memberId)).isZero();
    }

    @Test
    @DisplayName("★ 服务费折扣必须在 (0, 1]：0 和 1.5 拒绝，1.0 通过")
    void outOfRangeDiscountShouldBeRejected() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long zero = givenRawSku(PackageType.SERVICE_DISCOUNT, "20.00",
                "service_discount", bd("0.0000"));
        long overOne = givenRawSku(PackageType.SERVICE_DISCOUNT, "20.00",
                "service_discount", bd("1.5000"));

        // ★ 0 折 = 白送，>1 = 涨价。这两个值在计费里【都不会报错】，
        //   只会算出一个错的钱，所以只能在这里拦
        assertThatThrownBy(() -> memberPackageService.purchase(zero))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining(MessageConstant.PACKAGE_FIELD_MISSING);
        assertThatThrownBy(() -> memberPackageService.purchase(overOne))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining(MessageConstant.PACKAGE_FIELD_MISSING);

        // ★ 上界是闭的：1.0 表示「不打折」，它是合法值（0.8000 表示八折）
        ChargePackage exact = givenSku(skuOf(PackageType.SERVICE_DISCOUNT, "20.00")
                .serviceDiscount(bd("1.0000")).validDays(30).build());
        MemberPackage row = loadRow(memberPackageService.purchase(exact.getId()));
        assertThat(row.getServiceDiscount()).as("★ 1.0 是合法折扣（不打折），不能被上界误伤")
                .isEqualByComparingTo("1.0000");

        assertThat(memberPackageCount(memberId)).as("只有 1.0 那一份买成了").isEqualTo(1);
        assertThat(txns(memberId)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 负售价拒卖 —— 那是一条「买套餐反而给车主充值」的路径")
    void negativePriceShouldBeRejected() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long skuId = givenRawSku(PackageType.TIMES, "-1.00", "times", 5);

        assertThatThrownBy(() -> memberPackageService.purchase(skuId))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining(MessageConstant.PACKAGE_FIELD_MISSING);

        // ★ 拒到这一步是靠 price 的符号检查。少了它，deduct 会以 amount < 0 抛
        //   ParamErrorException —— 结果看着一样，但那是一句「扣款金额必须大于 0」，
        //   指向钱包而不是指向这条脏数据，修它的人会去错的地方找原因。
        //   （price 为 NULL 在 DDL 上到不了：charge_package.price 是 NOT NULL）
        assertThat(memberPackageCount(memberId)).isZero();
        assertDecimal("500.00", wallet(memberId).getBalance(), "余额一分不动");
    }

    // ==================== 削峰锁 ====================

    @Test
    @DisplayName("★ 同一车主的并发购买被削峰锁挡住：抛 DataConflictException，什么都没发生")
    void concurrentPurchaseShouldBeRejectedByTheThrottleLock() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());

        // ★ 直接占住那把锁，模拟「上一次请求还在跑」——
        //   key 里是 memberId 而不是 packageId：要挡的是同一个人连点两次
        String lockKey = RedisConstant.LOCK_MEMBER_PACKAGE_PURCHASE + memberId;
        String token = cacheClient.tryLock(lockKey, 10L);
        assertThat(token).as("前置条件：锁应该抢得到").isNotNull();
        try {
            assertThatThrownBy(() -> memberPackageService.purchase(sku.getId()))
                    // ★ 措辞是「正在处理」不是「购买失败」：上一次请求大概率会成功，
                    //   说失败会让车主以为要重来一遍
                    .isInstanceOf(DataConflictException.class)
                    .hasMessage(MessageConstant.PACKAGE_PURCHASE_DUPLICATE);

            assertThat(memberPackageCount(memberId)).isZero();
            assertThat(txns(memberId)).isEmpty();
            assertDecimal("500.00", wallet(memberId).getBalance(), "抢不到锁就不该动钱");
            // ★ 解锁要带 token 比对，所以 service 的 finally 删不掉【别人的】锁
            assertThat(cacheClient.tryLock(lockKey, 10L))
                    .as("★ 这把锁还是我的（Lua 里比对了 token 才删）").isNull();
        } finally {
            cacheClient.unlock(lockKey, token);
        }
    }

    @Test
    @DisplayName("★★ 购买失败后锁必须被放掉，否则这位车主在 TTL 内什么都买不了")
    void failedPurchaseShouldReleaseTheLockSoTheMemberCanRetry() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "10.00");
        ChargePackage sku = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).validDays(30).build());

        assertThatThrownBy(() -> memberPackageService.purchase(sku.getId()))
                .isInstanceOf(BalanceNotEnoughException.class);

        // ★ 这一步不是在测「重试」，是在测 purchase 的 finally 里的 unlock：
        //   少了它，这把锁要等 10 秒自动过期，而这 10 秒内车主【什么都买不了】，
        //   提示永远是「购买请求正在处理中」—— 一个看不出原因的假故障。
        jdbcTemplate.update("UPDATE wallet SET balance = 500.00 WHERE member_id = ?", memberId);

        MemberPackageVO vo = memberPackageService.purchase(sku.getId());

        assertThat(loadRow(vo).getRemainingKwh()).isEqualByComparingTo("150.00");
        assertThat(memberPackageCount(memberId)).isEqualTo(1);
        assertDecimal("400.00", wallet(memberId).getBalance(), "500 − 100");
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("★★ listMine 不过滤状态：已用尽、已过期的也要返回")
    void listMineShouldReturnEveryPackageIncludingTheDeadOnes() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        ChargePackage sku = givenSku(skuOf(PackageType.TIMES, "50.00")
                .times(5).validDays(30).build());
        MemberPackageVO bought = memberPackageService.purchase(sku.getId());
        long dead = givenDeadPackage(memberId);

        List<MemberPackageVO> mine = memberPackageService.listMine();

        assertThat(mine).extracting(MemberPackageVO::getId).contains(bought.getId(), dead);
        MemberPackageVO deadVo = mine.stream()
                .filter(vo -> vo.getId().equals(dead)).findFirst().orElseThrow();
        assertThat(deadVo.getStatus()).isEqualTo(MemberPackageStatus.EXHAUSTED.getCode());
        assertThat(deadVo.getStatusLabel()).isEqualTo(MemberPackageStatus.EXHAUSTED.getLabel());

        // ★★ 为什么不能在这里过滤：车主需要看到「我买过、但用完了」，而不是
        //    「我的套餐凭空消失了」。滤掉之后，车主来问客服「我那个 150 度的包去哪了」，
        //    而客服在后台查得到 —— 两边看的是同一个系统，却给出不同的答案。
        //    SQL 里和这里都不过滤，这个决定只有一个落点。
    }

    @Test
    @DisplayName("★ listAvailable：只返回「全平台 + 本站」的上架套餐")
    void listAvailableShouldReturnGlobalAndThisStationOnly() {
        // 只为了登入上下文 —— 货架是「这个车主的账号此刻能不能看」，不看他是谁
        loginAsNewMember();
        // ★ 站点是否存在不在这个查询的判据里（SQL 只过滤 station_id IN (0, ?)，
        //   全项目没有外键），所以这里用一个哨兵 id，不必真的造一个站 ——
        //   造站是 ChargingOrderServiceImplTest 那类测试的事。
        long stationId = TEST_ID_BASE + (SEQ++);

        long global = givenSku(skuOf(PackageType.ENERGY, "100.00")
                .energyKwh(bd("150.00")).build()).getId();
        long thisStation = givenSku(skuOf(PackageType.TIMES, "50.00")
                .times(5).stationId(stationId).build()).getId();
        long otherStation = givenSku(skuOf(PackageType.DURATION, "30.00")
                .durationMinutes(120).stationId(stationId + 1).build()).getId();
        long disabledGlobal = givenSku(skuOf(PackageType.ENERGY, "10.00")
                .energyKwh(bd("10.00")).status(StatusConstant.DISABLE).build()).getId();

        List<ChargePackageVO> shelf = memberPackageService.listAvailable(stationId);

        // ★★ 货架上还有【种子套餐】（150度电包 / 新站首充 50 度），别的测试也会往里加行 ——
        //    所以断言必须收窄到本类自己建的那四个 id 上。直接写 hasSize 的话，
        //    这条测试会因为库里还有别人造的数据而红；更糟的是它可能因为
        //    「别的行碰巧被删掉」而绿，而那是两种完全不同的失败。
        List<Long> created = List.of(global, thisStation, otherStation, disabledGlobal);
        assertThat(shelf.stream().map(ChargePackageVO::getId).filter(created::contains).toList())
                .as("★★ 本站货架 = 全平台通用 + 本站专属，别的站的和下架的一律不出现")
                .containsExactlyInAnyOrder(global, thisStation);
        assertThat(shelf).extracting(ChargePackageVO::getId)
                .doesNotContain(otherStation, disabledGlobal);
        assertThat(shelf).as("★ 货架上的都是上架的").allSatisfy(
                vo -> assertThat(vo.getStatus()).isEqualTo(StatusConstant.ENABLE));
    }

    @Test
    @DisplayName("★★ listAvailable(null) 必须报错，绝不能静默放宽成「全平台所有套餐」")
    void listAvailableWithoutStationShouldBeRejected() {
        loginAsNewMember();

        // ★ 「忘了传站点」变成「返回全平台所有套餐」是一个不报错的范围放大：
        //   车主会在科技园站看到别的站的专属套餐，而那些套餐他其实买不了
        //   （购买路径不校验站点范围，见 MemberPackageService 类注释第二节）。
        assertThatThrownBy(() -> memberPackageService.listAvailable(null))
                .isInstanceOf(ParamErrorException.class);
    }

    // ==================== 管理端：作废 + 退款 ====================

    /** 作废请求体。★ 只有一个字段 —— 金额是算出来的，不是传进来的 */
    private static MemberPackageVoidDTO voidDto(String remark) {
        MemberPackageVoidDTO dto = new MemberPackageVoidDTO();
        dto.setRemark(remark);
        return dto;
    }

    @Test
    @DisplayName("★★ 作废：status 变 4、备注写进去、原价全额退回余额、流水 type=8")
    void voidShouldRefundTheFullPayAmount() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));
        assertDecimal("432.00", wallet(memberId).getBalance(), "前提：购买扣掉了 68.00");

        MemberPackageVoidVO vo = memberPackageService.voidPackage(id, voidDto("车主误购"));

        assertDecimal("68.00", vo.getRefundAmount(), "★ 退的是 pay_amount 快照，不是 SKU 的现价");
        assertThat(vo.getMemberId()).isEqualTo(memberId);
        assertThat(vo.getMemberPackageId()).isEqualTo(id);
        assertDecimal("500.00", wallet(memberId).getBalance(), "★★ 一分不少地回来");

        MemberPackage row = memberPackageMapper.getById(id);
        assertThat(row.getStatus()).as("★ 3 已过期之外，4 是本项目第二个『以前从没被写入过』的取值")
                .isEqualTo(MemberPackageStatus.VOIDED.getCode());
        assertThat(row.getRemark()).as("★ 备注是客服打开这份权益时看到的『为什么它是灰的』")
                .isEqualTo("车主误购");

        // 两条流水：购买时的 6 和作废时的 8
        List<WalletTransaction> rows = txns(memberId);
        assertThat(rows).hasSize(2);
        WalletTransaction refund = rows.stream()
                .filter(t -> t.getType().equals(WalletTransactionType.PACKAGE_REFUND.getCode()))
                .findFirst().orElseThrow();
        assertDecimal("68.00", refund.getAmount(), "★ 退的是 pay_amount 快照");
        assertThat(refund.getOrderId())
                .as("★★ 它必须是 NULL。这条流水没有订单可挂靠 —— "
                        + "编一个 order_id 出来，uk_txn_order_type 会开始生效，"
                        + "看起来更安全，但那个订单并不存在，对账的人会查到一个空单")
                .isNull();
        assertThat(refund.getRemark())
                .as("★ 没有 order_id 时，这一行文字是这笔钱唯一的解释。"
                        + "它必须同时带上【哪一份权益】和【运营写的原因】—— "
                        + "少了前者，半年后没人知道这笔钱是哪来的；少了后者，"
                        + "退款原因就只剩下一句「已作废」")
                .contains(vo.getPackageName(), "车主误购");
    }

    @Test
    @DisplayName("★★ 作废：0 元赠送套餐照常作废，但【不写任何退款流水】")
    void voidOfAFreePackageShouldNotWriteAnyRefundTxn() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("0.00"));

        MemberPackageVoidVO vo = memberPackageService.voidPackage(id, voidDto("活动赠送，作废重发"));

        assertDecimal("0.00", vo.getRefundAmount(), "★ 赠送套餐退 0 元");
        assertThat(memberPackageMapper.getById(id).getStatus())
                .as("★ 作废的是『这份权益不能用了』，和『退多少钱』是两件事")
                .isEqualTo(MemberPackageStatus.VOIDED.getCode());
        assertThat(wallet(memberId).getBalance()).isEqualByComparingTo("500.00");
        assertThat(txns(memberId))
                .as("★★ 一条金额为 0 的退款流水只会让『退款笔数』多出一个假数字。"
                        + "★ 这一条同时钉住了 WalletServiceImpl.refundPackage 里"
                        + "那个 amount <= 0 的守卫：把调用方的 if (signum() > 0) 删掉之后，"
                        + "这里会抛 ParamErrorException，而不是静静地写一条 0 元流水")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 作废：被【任何一笔非取消订单】引用过就不能退 —— 哪怕那笔单还没付钱")
    void voidShouldBeRejectedWhenAnyNonCancelledOrderUsedThePackage() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));

        // ★★ 四笔，四种状态，全部必须拒。★★
        //   4 待支付：用户正在付款页面上 —— 而 settle 已经把余量扣掉了；
        //   5 已支付：最显然的那一笔；
        //   6 支付失败：用户没付成，但权益同样已经被 consume；
        //   9 异常挂账：最容易被漏掉的一笔 —— 它既没结算也没付款，
        //             「只查 status = 5」的实现会放它过去，于是车主
        //             白用了一次充电还拿回全款。这四个都用同一句 SQL 回答。
        for (int status : List.of(4, 5, 6, 9)) {
            long orderId = givenOrderUsingPackage(memberId, id, status);

            assertThatThrownBy(() -> memberPackageService.voidPackage(id, voidDto("误购")))
                    .as("★★ status = %d 的订单引用过它，必须拒绝退款", status)
                    .isInstanceOf(DataConflictException.class)
                    .hasMessageContaining("1 笔订单");

            assertThat(memberPackageMapper.getById(id).getStatus())
                    .as("★ 被拒之后状态必须原封不动 —— 已作废但没退钱是最坏的一种中间态")
                    .isEqualTo(MemberPackageStatus.ACTIVE.getCode());

            jdbcTemplate.update("DELETE FROM charging_order WHERE id = ?", orderId);
        }

        assertDecimal("432.00", wallet(memberId).getBalance(), "★★ 全程一分钱都没退出去");
    }

    @Test
    @DisplayName("★★ 作废：只被【已取消】订单引用过的仍然可退 —— 取消不消耗余量")
    void voidShouldSucceedWhenOnlyCancelledOrdersReferencedIt() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));
        givenOrderUsingPackage(memberId, id, OrderStatus.CANCELLED.getCode());

        MemberPackageVoidVO vo = memberPackageService.voidPackage(id, voidDto("车主误购"));

        assertDecimal("68.00", vo.getRefundAmount(), "★ 退的是 pay_amount 快照");
        assertDecimal("500.00", wallet(memberId).getBalance(), "★ 余额回到购买前");
    }

    @Test
    @DisplayName("★★ 作废：被订单【锁着】时拒绝，消息里带上那笔订单的 id")
    void voidShouldBeRejectedWhileAnOrderHoldsTheLock() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));
        // 模拟「有一笔订单正拿着它」—— 正常路径下这是 create() 里 lockForOrder 干的。
        // ★ 这个数字刻意选成一个不可能存在的订单 id（别的测试类的 TEST_ID_BASE
        //   都不在这个量级），这样它绝不可能碰巧撞上另一条测试留下的行。
        long orderId = 9_900_000_000_001L;
        jdbcTemplate.update("UPDATE member_package SET locked_order_id = ? WHERE id = ?",
                orderId, id);

        assertThatThrownBy(() -> memberPackageService.voidPackage(id, voidDto("误购")))
                .as("★ 此刻作废会让那笔订单结算时读到一个已作废的权益")
                .isInstanceOf(DataConflictException.class)
                .hasMessageContaining(String.valueOf(orderId));

        assertThat(memberPackageMapper.getById(id).getStatus())
                .isEqualTo(MemberPackageStatus.ACTIVE.getCode());
        assertDecimal("432.00", wallet(memberId).getBalance(), "★ 余额停在购买后的状态，一分没退");
    }

    @Test
    @DisplayName("★★ 作废：pay_amount 为 NULL（V3 之前买的）时【拒绝】并转人工，绝不编一个数字出来")
    void voidShouldBeRejectedWhenThereIsNoPayAmountSnapshot() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));
        // ★ 模拟 V3 之前建的权益：那一列是 NULL —— 表示「不知道当时付了多少」，
        //   不是「0」。★ 这里绝不能用 charge_package.price 现算：那个价格是可编辑的，
        //   用它算出来的金额可能比车主实付的多，也可能少，而两边都无从核对。
        jdbcTemplate.update("UPDATE member_package SET pay_amount = NULL WHERE id = ?", id);

        assertThatThrownBy(() -> memberPackageService.voidPackage(id, voidDto("误购")))
                .as("★ 一个算不出退多少钱的请求，什么都不该发生")
                .isInstanceOf(DataConflictException.class)
                .hasMessageContaining(MessageConstant.MEMBER_PACKAGE_PAY_AMOUNT_MISSING);

        assertThat(memberPackageMapper.getById(id).getStatus())
                .as("★★ 状态必须还是生效中 —— 已作废但退不出钱是最坏的一种中间态："
                        + "车主既失去了权益，也拿不回钱，而且系统里没有任何东西能修它")
                .isEqualTo(MemberPackageStatus.ACTIVE.getCode());
        assertDecimal("432.00", wallet(memberId).getBalance(), "★ 余额停在购买后的状态，一分没退");
    }

    @Test
    @DisplayName("★★ 作废：状态不是「生效中」的一律拒绝，消息里说清它现在【是什么】")
    void voidShouldBeRejectedForAnyNonActiveStatus() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));

        for (MemberPackageStatus status : List.of(MemberPackageStatus.EXHAUSTED,
                MemberPackageStatus.EXPIRED, MemberPackageStatus.VOIDED)) {
            jdbcTemplate.update("UPDATE member_package SET status = ? WHERE id = ?",
                    status.getCode(), id);

            assertThatThrownBy(() -> memberPackageService.voidPackage(id, voidDto("误购")))
                    .as("★ 已经是「%s」的不能再作废（第二次作废就是第二次退款）", status.getLabel())
                    .isInstanceOf(DataConflictException.class)
                    // ★★ 消息里必须有那个标签 ★★
                    //   只写一句「状态不允许」，运营唯一能做的就是来问开发 ——
                    //   而「已用尽 / 已过期 / 已经作废过了」的下一步动作完全不同。
                    .hasMessageContaining(status.getLabel());
        }

        assertDecimal("432.00", wallet(memberId).getBalance(), "★★ 三次尝试，一分钱都没动");
    }

    @Test
    @DisplayName("★ 作废：权益不存在 → NotFoundException（不是 DataConflictException）")
    void voidShouldReportAMissingPackageAsNotFound() {
        loginAsNewMember();

        assertThatThrownBy(() -> memberPackageService.voidPackage(
                TEST_ID_BASE + 999_999L, voidDto("误购")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("★★ 作废：重复调用只退一次钱，且只留一条 PACKAGE_REFUND 流水")
    void voidTwiceShouldRefundOnlyOnce() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));

        memberPackageService.voidPackage(id, voidDto("车主误购"));
        assertDecimal("500.00", wallet(memberId).getBalance(), "★ 余额回到购买前");

        assertThatThrownBy(() -> memberPackageService.voidPackage(id, voidDto("车主误购")))
                .as("前提：第二次被 status 那道预检拦下")
                .isInstanceOf(DataConflictException.class);

        assertDecimal("500.00", wallet(memberId).getBalance(), "★ 余额回到购买前");
        assertThat(txns(memberId).stream()
                .filter(t -> t.getType().equals(WalletTransactionType.PACKAGE_REFUND.getCode())))
                .as("★★ 只能有一条。★★ 这条流水的 order_id 是 NULL，"
                        + "所以 uk_txn_order_type 对它【不生效】—— 数据库层没有第二道防线，"
                        + "全部防护就是 voidPackage 里那个 CAS 加一行 if。"
                        + "出现两条的症状是同一份权益退了两次钱，"
                        + "而两条流水单看都完全正常")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★ CAS 是权威判据：同一行并发作废，数据库层【只让一次赢】")
    void voidCasShouldLetExactlyOneWinnerThrough() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));

        LocalDateTime now = LocalDateTime.now();

        // ★★ 这一条绕过 Service 的五道预检，直接打 Mapper —— 那是「两个运营同时点了作废」时
        //    真正决定命运的那一行代码。★★ 预检在并发下两边都会通过（它们读的是同一份
        //    快照），所以「只退一次钱」这件事完全落在这条 SQL 的 WHERE 上。
        //    ★ 它同时钉住了谓词里的 status = 1：如果哪天有人把它放宽成
        //      「status <> 4」，这条测试会红，而不是让第二次退款悄悄成功。
        assertThat(memberPackageMapper.voidPackage(id, "第一个运营", now))
                .as("第一个赢").isEqualTo(1);
        assertThat(memberPackageMapper.voidPackage(id, "第二个运营", now))
                .as("★★ 第二个必须影响 0 行 —— 调用方据此拒绝退款").isZero();

        MemberPackage row = memberPackageMapper.getById(id);
        assertThat(row.getStatus()).isEqualTo(MemberPackageStatus.VOIDED.getCode());
        assertThat(row.getRemark())
                .as("★ 后到的那次连备注都不该覆盖上去，否则退款理由会变成最后那个人写的")
                .isEqualTo("第一个运营");
    }

    @Test
    @DisplayName("★★ 管理端 listByMember：查的是【参数里的】车主，不是 BaseContext 里的那个")
    void listByMemberShouldQueryTheGivenMemberNotTheLoggedInOne() {
        long targetMember = loginAsNewMember();
        givenBalance(targetMember, "500.00");
        long mine = buy(timesSku("68.00"));

        // ★★ 模拟管理端请求：/api/admin/** 的拦截器往 BaseContext 里放的是【员工】id。★★
        //   本方法必须无视它。写成 requireMemberId() 的症状是：运营点开某车主的权益列表，
        //   看到的是【自己】名下的（通常是空的），然后以为那位车主什么都没买过。
        //   ★ 这个 id 刻意选成一个**不存在的** member：用种子里的 member 1 反证不了任何事 ——
        //     它是一个真车主，名下真有一份「150度电包」，查出来非空，
        //     于是「查错人了」会被误判成「查对了」。
        long staffId = 9_900_000_000_009L;
        BaseContext.setCurrentId(staffId);

        List<MemberPackageVO> rows = memberPackageService.listByMember(targetMember);

        assertThat(rows).extracting(MemberPackageVO::getId).containsExactly(mine);

        // ★★ 反证：用那个 staffId 去查必须什么都查不到。★★
        //   ★ MemberPackageVO 上没有 memberId 字段（车主端本来就不需要它 ——
        //     那份权益必然是他自己的），所以「查的是谁」只能这样反向证明：
        //   写成 requireMemberId() 的实现会在这里返回空列表，
        //   而上面那句 containsExactly 会失败 —— 两处一起把方向钉死。
        assertThat(memberPackageService.listByMember(staffId))
                .as("★★ staffId 是员工 id，不是车主 id —— 它名下不该有任何权益。★★ "
                        + "这一条同时说明为什么本方法必须是「收参数」而不是「读 BaseContext」："
                        + "管理端请求里 BaseContext 放的是员工 id，"
                        + "读它的症状是运营点开某车主的权益列表，看到的是自己名下的（通常是空的），"
                        + "然后以为那位车主什么都没买过")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 管理端 listByMember：不过滤状态，已作废的也要看得到")
    void listByMemberShouldNotFilterStatus() {
        long memberId = loginAsNewMember();
        givenBalance(memberId, "500.00");
        long id = buy(timesSku("68.00"));
        memberPackageService.voidPackage(id, voidDto("车主误购"));

        List<MemberPackageVO> rows = memberPackageService.listByMember(memberId);

        assertThat(rows).extracting(MemberPackageVO::getId).containsExactly(id);
        assertThat(rows.get(0).getStatus()).isEqualTo(MemberPackageStatus.VOIDED.getCode());
    }
}
