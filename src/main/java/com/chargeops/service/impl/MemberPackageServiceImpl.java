package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.RedisConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.MemberPackageVoidDTO;
import com.chargeops.entity.ChargePackage;
import com.chargeops.entity.MemberPackage;
import com.chargeops.enumeration.MemberPackageStatus;
import com.chargeops.enumeration.PackageType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.ChargePackageMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.redis.CacheClient;
import com.chargeops.service.MemberPackageService;
import com.chargeops.service.MemberService;
import com.chargeops.service.WalletService;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import com.chargeops.vo.MemberPackageVoidVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 车主端权益服务实现
 *
 * <p>设计取舍见 {@link MemberPackageService} 的类注释（直购、无退款、站点范围不校验）。
 * 这里只讲实现上的两处要害。
 *
 * <h2>★★ 一、类型专属列的映射是<b>结构性</b>正确的，不靠校验 ★★</h2>
 *
 * <p>{@code member_package} 和 {@code charge_package} 一样，<b>七列</b>类型专属列里
 * 只有一组该有值（{@code total_kwh/remaining_kwh}、{@code total_minutes/remaining_minutes}、
 * {@code service_discount}、{@code total_times/remaining_times} ——
 * 折扣包那一组只有一列，七 = 2+2+1+2），
 * 而且「其余几列必须为 NULL」比「必填的那个要有」更要紧 —— 残留字段是<b>静默的</b>。
 *
 * <p>★ <b>{@link #copyBenefit} 里没有任何「先清空再赋值」的代码</b>，
 * 因为那件事根本不需要做：它<b>只读</b> SKU 上属于当前类型的那一个字段，
 * 写的时候也只写对应的那一对。其余五列从来没被碰过，自然是 NULL
 * （折扣包那一行走完是六列 NULL —— 它连「剩余」都没有）。
 * <b>「必须为 NULL」这件事由写入形状保证，不由一行校验保证</b> ——
 * 校验会漏，形状不会。
 *
 * <p>★ 但<b>另一半必须校验</b>：该有值的那个字段在库里是不是空的。
 * 一个 {@code package_type = 1} 而 {@code energy_kwh IS NULL} 的 SKU（有人直接改过库）
 * 会产出一份 {@code remaining_kwh IS NULL} 的权益，而「这份权益还能用吗」的判据是
 * {@code remaining_kwh > 0} —— <b>NULL 参与比较恒为 false</b>，
 * 于是它永远选不中、也永远标不成已用尽。见 {@link #requireBenefit}.
 *
 * <h2>★ 二、削峰锁在事务<b>内部</b>释放，那个窗口是存在的</h2>
 *
 * <p>{@link #purchase} 是 {@code @Transactional}，而锁的 {@code finally} 在方法体里 ——
 * <b>释放发生在事务提交之前</b>。所以「锁已经放开、钱还没提交」是一个真实存在的窗口。
 *
 * <p>★ <b>为什么可以接受</b>：这把锁本来就不是正确性保证（见
 * {@code RedisConstant.LOCK_MEMBER_PACKAGE_PURCHASE}），而买重了的后果
 * 不是资金错误 —— 两条流水各自对应一份权益，钱是对得上的。
 *
 * <p>★★ <b>想修的话不要用「自注入代理调另一个方法」那招。</b>★★
 * {@code ChargingOrderServiceImpl.settle} 里
 * {@code incrementChargeCount} 的注释已经为同一类问题立了规矩：
 * 自注入代理会让调用链多一层看不见的转发，而它失效时表现为
 * 「事务没了但功能看起来正常」。要真解决得让 Controller 持有锁，
 * 那是一次接口形状的改动，不是这里加一行。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberPackageServiceImpl implements MemberPackageService {

    /** 削峰锁的过期秒数。★ 它必须<b>远大于</b>一次购买事务的耗时 —— 见下方注释 */
    private static final long PURCHASE_LOCK_TTL_SECONDS = 10L;

    private final ChargePackageMapper chargePackageMapper;
    private final MemberPackageMapper memberPackageMapper;
    private final WalletService walletService;
    private final MemberService memberService;
    private final CacheClient cacheClient;

    /**
     * ★ 只被 {@link #voidPackage} 用，只读一个 COUNT。
     * 跨到订单表上看着越界，但「这份权益有没有被用过」的答案确实不在权益表里 ——
     * 在引用它的那些订单行里。理由和 {@code StationServiceImpl} /
     * {@code ChargerServiceImpl} 里同名字段一样，那条 SQL 的论证见
     * {@link ChargingOrderMapper#countByPackage}。
     */
    private final ChargingOrderMapper orderMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ChargePackageVO> listAvailable(Long stationId) {
        Long memberId = requireMemberId();
        // ★ 只读接口也查 —— 理由见 MemberPackageService 类注释第三节
        memberService.assertActive(memberId);

        if (stationId == null) {
            // ★ 抛参数错误而不是「当没传、查全平台」。见 ChargePackageMapper
            //   .listAvailableForMember：静默放宽范围是这个接口最坏的失败方式
            throw new ParamErrorException("请指定站点，无法判断要展示哪个站点的套餐");
        }

        return chargePackageMapper.listAvailableForMember(stationId).stream()
                .map(ChargePackageVO::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberPackageVO> listMine() {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);

        // ★ 不过滤状态 —— 已用尽/已过期/已作废的也要显示成灰态，
        //   理由见 MemberPackageService#listMine。过滤在 SQL 里和在这里都不做。
        return memberPackageMapper.listByMember(memberId).stream()
                .map(MemberPackageVO::from)
                .toList();
    }

    // ==================== 管理端 ====================

    @Override
    @Transactional(readOnly = true)
    public List<MemberPackageVO> listByMember(Long memberId) {
        // ★★ 注意这里**没有** requireMemberId()，也**没有** assertActive。★★
        //   两个都不是漏了：
        //   · requireMemberId() 取的是 BaseContext，而管理端请求里那是【员工】的 id ——
        //     用它去查「我的套餐」会返回运营自己的权益（通常是空列表，
        //     于是运营会以为「这个车主没有套餐」，而车主其实有三份）；
        //   · assertActive 查的是发起人（运营）的账号状态，答非所问。
        //   完整论证见 MemberPackageService 类注释第三节。
        if (memberId == null) {
            throw new ParamErrorException("车主 id 不能为空");
        }

        return memberPackageMapper.listByMember(memberId).stream()
                .map(MemberPackageVO::from)
                .toList();
    }

    @Override
    @Transactional
    public MemberPackageVoidVO voidPackage(Long id, MemberPackageVoidDTO dto) {
        if (id == null) {
            throw new ParamErrorException("权益 id 不能为空");
        }

        LocalDateTime now = LocalDateTime.now();

        // ---- ① 存在 ----
        MemberPackage mp = memberPackageMapper.getById(id);
        if (mp == null) {
            throw new NotFoundException(MessageConstant.MEMBER_PACKAGE_NOT_FOUND);
        }

        // ---- ② 状态 ----
        // ★ 用「当前是什么」而不是「不是什么」来组织这句话：管理员需要知道
        //   这份权益现在到底处于什么状态（已用尽？已过期？已经作废过了？），
        //   而那三者的下一步动作完全不同。
        //   只写一句「状态不允许」的话，运营唯一能做的就是来问开发。
        MemberPackageStatus status = MemberPackageStatus.of(mp.getStatus());
        if (status != MemberPackageStatus.ACTIVE) {
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_STATUS_CONFLICT
                    + "：这份权益当前是「" + status.getLabel() + "」，只有「生效中」的可以作废");
        }

        // ---- ③ 没被订单占着 ----
        // ★ 带上订单 id：运营据此可以直接去看那笔订单，而不是只知道「有东西占着」。
        //   ★ 这条和 ⑥ 的 CAS 里的 locked_order_id IS NULL 是同一件事的两次检查，
        //   看起来冗余，但<b>不能删</b>：这条负责给出一句带订单号的话，
        //   ⑥ 那条负责在并发下真的挡住。删了这条，用户看到的会是一句
        //   「状态不允许」——而他的权益明明还是「生效中」，那句话是错的。
        if (mp.getLockedOrderId() != null) {
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_IN_USE
                    + "：占用它的订单 id = " + mp.getLockedOrderId());
        }

        // ---- ④ 金额 ----
        // ★★ 这一条必须在扣钱之前 —— 一个算不出退多少钱的请求，什么都不该发生。★★
        BigDecimal payAmount = mp.getPayAmount();
        if (payAmount == null) {
            // ★ null 是「不知道」（建于 V3 之前），**不是**「0」。
            //   拒绝并让人工处理，而不是拿 SKU 的现价编一个数字 —— 见 MemberPackage#payAmount。
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_PAY_AMOUNT_MISSING);
        }

        // ---- ⑤ 没用过 ----
        int usedByOrders = orderMapper.countByPackage(id);
        if (usedByOrders > 0) {
            // ★ 带上笔数：它把「为什么不能退」变得可核对。
            //   运营看到「被 2 笔订单用过」之后能自己去查那两笔，而不是和客服来回扯。
            throw new DataConflictException(String.format(
                    MessageConstant.MEMBER_PACKAGE_ALREADY_USED, usedByOrders));
        }

        // ---- ⑥ CAS：权威判据 ----
        // ★★ 上面五步都是「给出一句能解释的话」，这一步才是**真正决定要不要退钱**的。★★
        //   两个运营同时点「作废」时，前五步两边都会通过 —— 只有这条条件更新
        //   会把其中一个挡下来（影响 0 行）。
        //   ★ 所以**必须检查返回值**，而且**必须排在退钱之前**：
        //     不检查的话，症状是同一份权益退两次钱，而 wallet_transaction 上
        //     会留下两条一模一样的 PACKAGE_REFUND 流水，每条单独看都完全正常。
        //   ★ 这条流水的 order_id 是 NULL，uk_txn_order_type 不生效 ——
        //     **数据库层没有第二道防线**，这一行就是全部。
        if (memberPackageMapper.voidPackage(id, dto.getRemark(), now) == 0) {
            // ★ 走到这里说明第 ② 到第 ⑥ 步之间有人动了这一行 ——
            //   另一笔作废、一笔订单锁上了它、或者一次结算把它标成了已用尽。
            //   用 ②的那句话是不诚实的（那时它确实是生效中），所以单独说一句
            //   「请刷新后重试」：这是并发，不是用错了流程。
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_STATUS_CONFLICT
                    + "：这份权益在你确认期间被别的操作改动了（另一笔作废、一笔订单锁上了它、"
                    + "或一次结算扣光了余量），请刷新后重试");
        }

        // ---- ⑦ 退钱 ----
        // ★ 只有 ⑥ 影响 1 行才走到这里。
        // ★ 金额为 0 时**不调钱包**：赠送套餐没有钱可退，而一条金额为 0 的流水
        //   只会给对账添噪音 —— 和 WalletService.deduct / unfreeze 对 0 元的宽容同一条理由。
        //   注意 refunPackage 自己拒绝 0，所以这个 if 不是多余的防御，是必需的。
        if (payAmount.signum() > 0) {
            // ★ remark 由这里拼，不由调用方传：只有这一层同时知道
            //   「作废的是哪一份权益」和「运营写的原因为何」。
            //   那条流水上没有 order_id，这一行文字是它唯一的解释。
            walletService.refundPackage(mp.getMemberId(), payAmount,
                    "作废套餐权益退款：" + mp.getPackageName() + "（" + dto.getRemark() + "）");
        }

        // ★ 用 warn 而不是 info：这是一个**由人发起的、动了真钱**的操作。
        //   和普通的状态变更不同，它值得在日志里被一眼看到 ——
        //   事后有人问「这笔钱是谁退的」，这条日志就是答案。
        log.warn("作废套餐权益：memberPackageId={}, memberId={}, packageName={}, 退款={}, 原因={}",
                id, mp.getMemberId(), mp.getPackageName(), payAmount, dto.getRemark());

        return MemberPackageVoidVO.from(mp, payAmount);
    }

    @Override
    @Transactional
    public MemberPackageVO purchase(Long packageId) {
        Long memberId = requireMemberId();
        // ★★ 这一句必须在扣钱之前。★ 它和 WalletService.deduct 的分工是：
        //   钱包层刻意不查账号状态（它由订单状态机调用，设备上报时没有登录人），
        //   所以「查状态」这件事落在有用户上下文的入口上 —— 就是这里。
        memberService.assertActive(memberId);

        if (packageId == null) {
            throw new ParamErrorException("套餐 id 不能为空");
        }

        // ---- ① 削峰锁 ----
        String lockKey = RedisConstant.LOCK_MEMBER_PACKAGE_PURCHASE + memberId;
        // ★ TTL 取 10 秒，远大于一次购买事务（两次写 + 一次查，毫秒级）。
        //   它必须**明显大于**事务耗时，否则锁会在业务跑完之前过期，
        //   那就等于没有锁 —— 而这种情况不会报错，只会让「挡重复提交」悄悄失效。
        String token = cacheClient.tryLock(lockKey, PURCHASE_LOCK_TTL_SECONDS);
        if (token == null) {
            // ★ 是「正在处理」不是「失败」—— 上一次请求大概率会成功，
            //   说失败会让车主以为要重来一遍。见 MessageConstant 里那一条。
            throw new DataConflictException(MessageConstant.PACKAGE_PURCHASE_DUPLICATE);
        }
        try {
            return doPurchase(memberId, packageId);
        } finally {
            // ★ 必须在 finally 里放：中间抛异常（余额不足、套餐下架）时如果不放，
            //   这把锁要等 10 秒自动过期，而这 10 秒内这位车主**什么都买不了**，
            //   提示永远是「购买请求正在处理中」—— 一个看不出原因的假故障。
            cacheClient.unlock(lockKey, token);
        }
    }

    /**
     * 真正的购买：载入 SKU → 校验 → 扣钱 → 写权益
     *
     * <p>★ 抽成私有方法只是为了让 {@link #purchase} 的 {@code try/finally} 读起来清楚，
     * <b>不是</b>为了让事务边界分开 —— 它没有自己的 {@code @Transactional}，
     * 而且<b>绝不能</b>为了让某个理由给它加上（那需要自注入代理才能生效，
     * 见类注释第二节）。
     */
    private MemberPackageVO doPurchase(Long memberId, Long packageId) {
        LocalDateTime now = LocalDateTime.now();

        // ---- ② 载入 SKU 并校验 ----
        ChargePackage sku = chargePackageMapper.getById(packageId);
        if (sku == null) {
            throw new NotFoundException(MessageConstant.PACKAGE_NOT_FOUND);
        }
        // ★ 下架和不存在分成两句，理由见 MessageConstant.PACKAGE_OFF_SHELF：
        //   对车主是两件不同的事（「换个套餐」vs「你的链接是旧的」）
        if (!StatusConstant.ENABLE.equals(sku.getStatus())) {
            throw new DataConflictException(MessageConstant.PACKAGE_OFF_SHELF);
        }
        // ★ 拒绝负数，但**允许 0** —— ChargePackageDTO 明确写了「可以填 0（赠送套餐）」，
        //   所以 0 是一个合法的商品价格。负数只可能来自直接改库。
        BigDecimal price = sku.getPrice();
        if (price == null || price.signum() < 0) {
            throw new ParamErrorException(
                    MessageConstant.PACKAGE_FIELD_MISSING + "：售价非法（" + price + "）");
        }
        PackageType type = PackageType.of(sku.getPackageType());

        // ---- ③ 组这一行 ----
        MemberPackage mp = MemberPackage.builder()
                .memberId(memberId)
                .packageId(sku.getId())
                // ★ 恒为 null。不是「还没填」—— 直购模型下没有购买单，
                //   见 MemberPackage#buyOrderId。写出来是为了让这一行的完整性一目了然
                .buyOrderId(null)
                .packageType(type.getCode())
                // ★ 名称快照。SKU 改名不影响已售出的这一份（见 MemberPackage 类注释第二节）
                .packageName(sku.getName())
                // ★★ 实付金额快照 —— 退款路径的**唯一**金额来源。
                //   它必须是这个价格，而不是「退款时再去查 SKU」：SKU 的 price 可编辑，
                //   管理员把 100 改成 80 之后，所有按老价格买过的用户都会被按 80 退款。
                //   见 MemberPackage#payAmount。
                //
                //   ★ 0 元套餐照写 0.00，不写 null：null 的含义是「不知道」（V3 之前的老行），
                //     而这里是**确知**的 0。两者触发的动作完全不同 ——
                //     一个照常作废但不退钱，一个拒绝并转人工。
                .payAmount(price)
                .effectiveFrom(now)
                .expireTime(resolveExpireTime(now, sku.getValidDays()))
                // ★ 恒为 null：新买的权益没有被任何订单锁定。
                //   带上非 null 会立即占用 uk_mp_locked_order，让别的订单锁不上它
                .lockedOrderId(null)
                .status(MemberPackageStatus.ACTIVE.getCode())
                .version(0)
                .build();
        copyBenefit(sku, type, mp);

        // ---- ④ 扣钱 ----
        // ★★ 次序是「先 copyBenefit、再扣钱、最后 insert」，这三步的顺序是有理由的：
        //   · copyBenefit 排在扣钱之前 —— 它是纯内存操作，但**可能抛**
        //     「数据不完整」。一个卖不出去的 SKU 不该先动车主的钱
        //     （虽然同事务里扣了也会回滚，但那意味着日志里留下一次
        //     本不该发生的扣款尝试，排查时是纯噪音）；
        //   · insert 排在最后 —— 它需要 mp 已经完全组好，而 mp 的 id
        //     还要靠 useGeneratedKeys 回填给返回的 VO。
        if (price.signum() > 0) {
            // ★ remark 是这笔扣款唯一的可读线索（order_id 是空的），所以必须写清买的是什么
            walletService.deduct(memberId, price, "购买套餐：" + sku.getName());
        }
        // ★ 0 元套餐不写流水 —— 和 WalletService.freeze 对 0 元的宽容是同一条理由：
        //   一条金额为 0 的流水只是给对账添噪音。见 WalletService#deduct 的注释。

        // ---- ⑤ 落库 ----
        // ★ 审计四列由 @AutoFill 反射填好后再 INSERT，所以写完之后
        //   mp 上的 createTime/updateTime/createUser 已经有值，可以直接转 VO ——
        //   不需要回查一次。useGeneratedKeys 也把 id 回填了。
        memberPackageMapper.insert(mp);

        log.info("套餐购买成功：memberId={}, packageId={}, memberPackageId={}, name={}, price={}",
                memberId, sku.getId(), mp.getId(), sku.getName(), price);

        return MemberPackageVO.from(mp);
    }

    /**
     * 把 SKU 的<b>类型专属列</b>抄成持有权益的「总量 + 剩余」
     *
     * <p>★ <b>注意这里没有任何「清空另外几列」的代码，这是刻意的。</b>
     * 它只读当前类型对应的那一个字段、只写对应的那一组，
     * 其余五列从头到尾没被碰过 —— 于是「其余几列必须为 NULL」是
     * <b>由写入形状保证的</b>，不是由一行校验保证的。见类注释第一节。
     *
     * <p>★★ <b>{@code type = 3 服务费折扣包}没有「总量/剩余」这一对</b>：
     * 它不是消耗品，而是「有效期内自动适用」的修饰符，只写 {@code serviceDiscount}。
     * 这一点在 {@code member_package} 的<b>七列</b>类型专属列里是唯一的不对称
     * （它使得「其余几列」在这一支上是六列而不是五列），别为了「整齐」
     * 给它编一个 {@code total_discount}。</p>
     */
    private void copyBenefit(ChargePackage sku, PackageType type, MemberPackage mp) {
        switch (type) {
            case ENERGY -> {
                BigDecimal kwh = requireBenefit(sku.getEnergyKwh(), "energy_kwh", type);
                mp.setTotalKwh(kwh);
                // ★ 剩余量从总量开始，不是从 0 —— 这是「刚买下」的定义
                mp.setRemainingKwh(kwh);
            }
            case DURATION -> {
                Integer minutes = requireBenefit(sku.getDurationMinutes(), "duration_minutes", type);
                mp.setTotalMinutes(minutes);
                mp.setRemainingMinutes(minutes);
            }
            case TIMES -> {
                Integer times = requireBenefit(sku.getTimes(), "times", type);
                mp.setTotalTimes(times);
                mp.setRemainingTimes(times);
            }
            case SERVICE_DISCOUNT -> {
                BigDecimal discount = requireBenefit(
                        sku.getServiceDiscount(), "service_discount", type);
                // ★ 折扣还要求 (0,1] —— 0 或负数意味着「打 0 折 / 倒贴」，
                //   而 >1 是涨价。这两者在计费里都不会报错，只会算出一个错的钱
                if (discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) > 0) {
                    throw new ParamErrorException(MessageConstant.PACKAGE_FIELD_MISSING
                            + "：服务费折扣必须在 (0, 1] 之间，实际 " + discount);
                }
                mp.setServiceDiscount(discount);
            }
        }
    }

    /**
     * 专属列必须有值，而且是正数 —— 这是购买路径上唯一一条「校验」而不是「形状」
     *
     * <p>★ 为什么值得为它写三个重载都要用到的一段：见类注释第一节 ——
     * 空值的后果是<b>全程静默</b>的（{@code remaining_* IS NULL} 让这份权益
     * 永远选不中、也永远标不成已用尽，用户看到的是「买了套餐但一直按余额扣钱」）。
     * 报错的另一面是会拦住真库上已有的脏数据 ——那正是我们想要的。
     */
    private <T> T requireBenefit(T value, String column, PackageType type) {
        if (value == null) {
            throw new ParamErrorException(MessageConstant.PACKAGE_FIELD_MISSING
                    + "：" + type.getLabel() + " 缺少 " + column
                    + "（该列在 charge_package 里为空，正常路径下不会有这种数据）");
        }
        if (value instanceof BigDecimal number && number.signum() <= 0) {
            throw new ParamErrorException(MessageConstant.PACKAGE_FIELD_MISSING
                    + "：" + type.getLabel() + " 的 " + column + " 必须大于 0，实际 " + number);
        }
        if (value instanceof Integer number && number <= 0) {
            throw new ParamErrorException(MessageConstant.PACKAGE_FIELD_MISSING
                    + "：" + type.getLabel() + " 的 " + column + " 必须大于 0，实际 " + number);
        }
        return value;
    }

    /**
     * 过期时间 = 购买时刻 + {@code valid_days} 天；{@code valid_days = 0} 表示永久
     *
     * <p>★★ <b>存的是绝对时间，不是天数</b>：{@code valid_days} 是 SKU 属性、
     * 管理员随时可以改，而「你这一份到哪天为止」是持有属性，
     * <b>不该被任何人的后续操作改变</b>。所以买入的那一刻就把天数折算掉。
     *
     * <p>★ <b>{@code 0 表示永久} 这个约定来自 DDL（{@code valid_days int NOT NULL
     * DEFAULT 0 COMMENT '0=永久'}）。永久写成 {@link MemberPackage#PERMANENT_EXPIRE_TIME}
     * 哨兵而不是 NULL —— 因为「有效期到那天」的查询是 {@code expire_time > NOW()}，
     * 而 NULL 参与比较恒不成立，永久有效的权益会被<b>漏掉</b>。
     *
     * <p>★ 用 {@code plusDays} 而不是「加 365 天」之类：天数语义由 DDL 定义，
     * 月/年制的套餐应该由运营折算成天数填进 SKU，不要在代码里引入第二种时间单位。
     */
    private LocalDateTime resolveExpireTime(LocalDateTime now, Integer validDays) {
        if (validDays == null || validDays <= 0) {
            return MemberPackage.PERMANENT_EXPIRE_TIME;
        }
        return now.plusDays(validDays);
    }

    private Long requireMemberId() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException();
        }
        return memberId;
    }
}
