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
 * <p>设计取舍见 {@link MemberPackageService} 的类注释，这里只讲实现上的两处要害。
 *
 * <p> 类型专属列的映射是结构性正确的：{@link #copyBenefit} 只读 SKU 上属于当前类型的
 * 那一个字段，也只写对应的那一对，其余几列从来没被碰过，自然是 NULL ——
 * 「必须为 NULL」由写入形状保证，不由一行校验保证，校验会漏，形状不会。
 * 但另一半必须校验：一个 {@code package_type = 1} 而 {@code energy_kwh IS NULL}
 * 的 SKU（有人直接改过库）会产出一份 {@code remaining_kwh IS NULL} 的权益，
 * 而「这份权益还能用吗」的判据是 {@code remaining_kwh > 0} —— NULL 参与比较恒为
 * false，于是它永远选不中、也永远标不成已用尽。
 *
 * <p> 削峰锁在事务内部释放，所以「锁已经放开、钱还没提交」是一个真实存在的窗口。
 * 可以接受：这把锁本来就不是正确性保证，而买重了的后果不是资金错误 ——
 * 两条流水各自对应一份权益，钱是对得上的。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberPackageServiceImpl implements MemberPackageService {

    /** 削峰锁的过期秒数。必须远大于一次购买事务的耗时，否则锁在业务跑完之前就过期了 */
    private static final long PURCHASE_LOCK_TTL_SECONDS = 10L;

    private final ChargePackageMapper chargePackageMapper;
    private final MemberPackageMapper memberPackageMapper;
    private final WalletService walletService;
    private final MemberService memberService;
    private final CacheClient cacheClient;

    /** 只被 {@link #voidPackage} 用，只读一个 COUNT：「这份权益有没有被用过」的答案在引用它的那些订单行里 */
    private final ChargingOrderMapper orderMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ChargePackageVO> listAvailable(Long stationId) {
        Long memberId = requireMemberId();
        // 只读接口也查 —— 让「权益域一律要求账号可用」成为一条没有例外的规则
        memberService.assertActive(memberId);

        if (stationId == null) {
            // 抛参数错误而不是「当没传、查全平台」：静默放宽范围是这个接口最坏的失败方式
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

        // 不过滤状态 —— 已用尽/已过期/已作废的也要显示成灰态。过滤在 SQL 里和在这里都不做。
        return memberPackageMapper.listByMember(memberId).stream()
                .map(MemberPackageVO::from)
                .toList();
    }

    // ---------- 管理端 ----------

    @Override
    @Transactional(readOnly = true)
    public List<MemberPackageVO> listByMember(Long memberId) {
        // 这里没有 requireMemberId()，也没有 assertActive，两个都不是漏了：
        // requireMemberId() 取的是 BaseContext，而管理端请求里那是员工的 id ——
        // 用它去查会返回运营自己的权益（通常是空列表，于是运营会以为「这个车主没有
        // 套餐」，而车主其实有三份）；assertActive 查的是发起人（运营）的状态，答非所问。
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
        // 用「当前是什么」而不是「不是什么」来组织这句话：只写一句「状态不允许」，运营唯一能做的就是来问开发。
        MemberPackageStatus status = MemberPackageStatus.of(mp.getStatus());
        if (status != MemberPackageStatus.ACTIVE) {
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_STATUS_CONFLICT
                    + "：这份权益当前是「" + status.getLabel() + "」，只有「生效中」的可以作废");
        }

        // ---- ③ 没被订单占着 ----
        // 带上订单 id：运营据此可以直接去看那笔订单。这道和 ⑥ 的 CAS 是同一件事的两次检查，
        // 不能删 —— 这道给出一句带订单号的话（用户看到「状态不允许」时权益明明还是生效中），
        // ⑥ 那道负责在并发下真的挡住。
        if (mp.getLockedOrderId() != null) {
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_IN_USE
                    + "：占用它的订单 id = " + mp.getLockedOrderId());
        }

        // ---- ④ 金额 ----
        // 这一条必须在扣钱之前 —— 一个算不出退多少钱的请求，什么都不该发生。
        BigDecimal payAmount = mp.getPayAmount();
        if (payAmount == null) {
            // null 是「不知道」（建于 V3 之前），不是「0」，所以拒绝并转人工，不编一个数字
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_PAY_AMOUNT_MISSING);
        }

        // ---- ⑤ 没用过 ----
        int usedByOrders = orderMapper.countByPackage(id);
        if (usedByOrders > 0) {
            // 带上笔数：它把「为什么不能退」变得可核对
            throw new DataConflictException(String.format(
                    MessageConstant.MEMBER_PACKAGE_ALREADY_USED, usedByOrders));
        }

        // ---- ⑥ CAS：权威判据 ----
        // 上面五步都是「给出一句能解释的话」，这一步才真正决定要不要退钱。两个运营同时点
        // 「作废」时前五步都会通过，只有这条条件更新会把其中一个挡下来（影响 0 行）——
        // 所以必须检查返回值，而且必须排在退钱之前。不检查的话症状是同一份权益退两次钱，
        // 而 wallet_transaction 上留下两条一模一样的 PACKAGE_REFUND 流水。
        // 这条流水的 order_id 是 NULL，uk_txn_order_type 不生效，数据库层没有第二道防线。
        if (memberPackageMapper.voidPackage(id, dto.getRemark(), now) == 0) {
            // 走到这里说明第 ② 到第 ⑥ 步之间有人动了这一行。用 ② 的那句话是不诚实的
            // （那时它确实是生效中），所以单独说一句「请刷新后重试」：这是并发。
            throw new DataConflictException(MessageConstant.MEMBER_PACKAGE_STATUS_CONFLICT
                    + "：这份权益在你确认期间被别的操作改动了（另一笔作废、一笔订单锁上了它、"
                    + "或一次结算扣光了余量），请刷新后重试");
        }

        // ---- ⑦ 退钱 ----
        // 只有 ⑥ 影响 1 行才走到这里。金额为 0 时不调钱包：refundPackage 自己拒绝 0，
        // 所以这个 if 不是多余的防御，是必需的。
        if (payAmount.signum() > 0) {
            // remark 由这里拼，不由调用方传：只有这一层同时知道「作废的是哪一份权益」
            // 和「运营写的原因为何」。那条流水上没有 order_id，这一行文字是它唯一的解释。
            walletService.refundPackage(mp.getMemberId(), payAmount,
                    "作废套餐权益退款：" + mp.getPackageName() + "（" + dto.getRemark() + "）");
        }

        // 用 warn 而不是 info：这是一个由人发起的、动了真钱的操作
        log.warn("作废套餐权益：memberPackageId={}, memberId={}, packageName={}, 退款={}, 原因={}",
                id, mp.getMemberId(), mp.getPackageName(), payAmount, dto.getRemark());

        return MemberPackageVoidVO.from(mp, payAmount);
    }

    @Override
    @Transactional
    public MemberPackageVO purchase(Long packageId) {
        Long memberId = requireMemberId();
        // 这一句必须在扣钱之前：钱包层刻意不查账号状态（它由订单状态机调用，
        // 设备上报时没有登录人），所以「查状态」落在有用户上下文的入口上，就是这里。
        memberService.assertActive(memberId);

        if (packageId == null) {
            throw new ParamErrorException("套餐 id 不能为空");
        }

        // ---- ① 削峰锁 ----
        String lockKey = RedisConstant.LOCK_MEMBER_PACKAGE_PURCHASE + memberId;
        // TTL 必须明显大于事务耗时，否则锁会在业务跑完之前过期，那就等于没有锁 ——
        // 而这种情况不会报错，只会让「挡重复提交」悄悄失效。
        String token = cacheClient.tryLock(lockKey, PURCHASE_LOCK_TTL_SECONDS);
        if (token == null) {
            // 是「正在处理」不是「失败」—— 上一次请求大概率会成功
            throw new DataConflictException(MessageConstant.PACKAGE_PURCHASE_DUPLICATE);
        }
        try {
            return doPurchase(memberId, packageId);
        } finally {
            // 必须在 finally 里放：中间抛异常时不放的话，这把锁要等 10 秒自动过期，
            // 而这 10 秒内这位车主什么都买不了，提示永远是「购买请求正在处理中」。
            cacheClient.unlock(lockKey, token);
        }
    }

    /**
     * 真正的购买：载入 SKU → 校验 → 扣钱 → 写权益
     *
     * <p> 抽成私有方法只是为了让 {@link #purchase} 的 {@code try/finally} 读起来清楚。
     * 它没有自己的 {@code @Transactional}，而且绝不能为了让某个理由给它加上 ——
     * 那需要自注入代理才能生效。
     */
    private MemberPackageVO doPurchase(Long memberId, Long packageId) {
        LocalDateTime now = LocalDateTime.now();

        // ---- ② 载入 SKU 并校验 ----
        ChargePackage sku = chargePackageMapper.getById(packageId);
        if (sku == null) {
            throw new NotFoundException(MessageConstant.PACKAGE_NOT_FOUND);
        }
        // 下架和不存在分成两句：对车主是两件不同的事（「换个套餐」vs「你的链接是旧的」）
        if (!StatusConstant.ENABLE.equals(sku.getStatus())) {
            throw new DataConflictException(MessageConstant.PACKAGE_OFF_SHELF);
        }
        // 拒绝负数，但允许 0 —— 0 是一个合法的商品价格（赠送套餐），负数只可能来自直接改库
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
                // 恒为 null：直购模型下没有购买单
                .buyOrderId(null)
                .packageType(type.getCode())
                // 名称快照。SKU 改名不影响已售出的这一份
                .packageName(sku.getName())
                // 实付金额快照，退款路径的唯一金额来源。必须是这个价格，而不是「退款时再去
                // 查 SKU」：SKU 的 price 可编辑，管理员把 100 改成 80 之后，所有按老价格
                // 买过的用户都会被按 80 退款。
                // 0 元套餐照写 0.00，不写 null：null 的含义是「不知道」（V3 之前的老行）。
                .payAmount(price)
                .effectiveFrom(now)
                .expireTime(resolveExpireTime(now, sku.getValidDays()))
                // 恒为 null：新买的权益没有被任何订单锁定。带上非 null 会立即占用
                // uk_mp_locked_order，让别的订单锁不上它
                .lockedOrderId(null)
                .status(MemberPackageStatus.ACTIVE.getCode())
                .version(0)
                .build();
        copyBenefit(sku, type, mp);

        // ---- ④ 扣钱 ----
        // copyBenefit 排在扣钱之前：它是纯内存操作，但可能抛「数据不完整」，
        // 一个卖不出去的 SKU 不该先动车主的钱。
        if (price.signum() > 0) {
            // remark 是这笔扣款唯一的可读线索（order_id 是空的），所以必须写清买的是什么
            walletService.deduct(memberId, price, "购买套餐：" + sku.getName());
        }
        // 0 元套餐不写流水 —— 一条金额为 0 的流水只是给对账添噪音

        // ---- ⑤ 落库 ----
        // 审计四列由 @AutoFill 反射填好后再 INSERT，所以写完之后 mp 上的
        // createTime/updateTime/createUser 已经有值，可以直接转 VO；id 由 useGeneratedKeys 回填。
        memberPackageMapper.insert(mp);

        log.info("套餐购买成功：memberId={}, packageId={}, memberPackageId={}, name={}, price={}",
                memberId, sku.getId(), mp.getId(), sku.getName(), price);

        return MemberPackageVO.from(mp);
    }

    /**
     * 把 SKU 的类型专属列抄成持有权益的「总量 + 剩余」
     *
     * <p> 没有任何「清空另外几列」的代码，这是刻意的：只读当前类型对应的那一个字段、
     * 只写对应的那一组，其余几列从头到尾没被碰过。
     *
     * <p> {@code type = 3 服务费折扣包}没有「总量/剩余」这一对：它不是消耗品，而是
     * 「有效期内自动适用」的修饰符，只写 {@code serviceDiscount}。别为了「整齐」
     * 给它编一个 {@code total_discount}。
     */
    private void copyBenefit(ChargePackage sku, PackageType type, MemberPackage mp) {
        switch (type) {
            case ENERGY -> {
                BigDecimal kwh = requireBenefit(sku.getEnergyKwh(), "energy_kwh", type);
                mp.setTotalKwh(kwh);
                // 剩余量从总量开始，不是从 0 —— 这是「刚买下」的定义
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
                // 折扣还要求 (0,1]：0 或负数意味着「打 0 折 / 倒贴」，而 >1 是涨价。
                // 这两者在计费里都不会报错，只会算出一个错的钱。
                if (discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) > 0) {
                    throw new ParamErrorException(MessageConstant.PACKAGE_FIELD_MISSING
                            + "：服务费折扣必须在 (0, 1] 之间，实际 " + discount);
                }
                mp.setServiceDiscount(discount);
            }
        }
    }

    /**
     * 专属列必须有值，而且是正数 —— 购买路径上唯一一条「校验」而不是「形状」
     *
     * <p> 空值的后果全程静默：{@code remaining_* IS NULL} 让这份权益永远选不中、
     * 也永远标不成已用尽，用户看到的是「买了套餐但一直按余额扣钱」。
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
     * <p> 存绝对时间而不是天数：{@code valid_days} 是 SKU 属性、管理员随时可以改，
     * 而「你这一份到哪天为止」是持有属性。永久写成
     * {@link MemberPackage#PERMANENT_EXPIRE_TIME} 哨兵而不是 NULL —— 「有效期到那天」
     * 的查询是 {@code expire_time > NOW()}，而 NULL 参与比较恒不成立，永久有效的
     * 权益会被漏掉。
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
