package com.chargeops.service.impl;

import com.chargeops.billing.BillingEngine;
import com.chargeops.billing.BillingRequest;
import com.chargeops.billing.BillingResult;
import com.chargeops.billing.BillingSegment;
import com.chargeops.billing.TariffRuleSnapshot;
import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.dto.OrderCreateDTO;
import com.chargeops.dto.OrderEndDTO;
import com.chargeops.dto.OrderPageQueryDTO;
import com.chargeops.dto.OrderStartDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.ChargingOrderEvent;
import com.chargeops.entity.ChargingSegment;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Member;
import com.chargeops.entity.MemberPackage;
import com.chargeops.entity.Station;
import com.chargeops.entity.TariffRule;
import com.chargeops.entity.Vehicle;
import com.chargeops.enumeration.ChargerStatus;
import com.chargeops.enumeration.ConnectorStatus;
import com.chargeops.enumeration.EventSource;
import com.chargeops.enumeration.OrderEventType;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.enumeration.PackageType;
import com.chargeops.enumeration.PayMethod;
import com.chargeops.enumeration.StopReason;
import com.chargeops.exception.BalanceNotEnoughException;
import com.chargeops.exception.ConnectorOccupiedException;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.ChargingOrderEventMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.ChargingSegmentMapper;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.MemberMapper;
import com.chargeops.mapper.MemberPackageMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.mapper.TariffRuleMapper;
import com.chargeops.mapper.VehicleMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.ChargingOrderService;
import com.chargeops.service.MemberService;
import com.chargeops.service.StationTariffService;
import com.chargeops.service.WalletService;
import com.chargeops.utils.BizNoGenerator;
import com.chargeops.vo.AdminOrderDetailVO;
import com.chargeops.vo.AdminOrderVO;
import com.chargeops.vo.ChargingOrderDetailVO;
import com.chargeops.vo.ChargingOrderEventVO;
import com.chargeops.vo.ChargingOrderVO;
import com.chargeops.vo.ChargingSegmentVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单状态机实现。契约见 {@link ChargingOrderService} 的类注释。
 *
 * <p>所有方法的骨架都一样：读订单 → 做纯计算 → 用 status 条件更新抢占这一步 →
 * 抢占成功之后才去写分段 / 动钱包 / 写事件。抢占必须在副作用之前，否则两个并发的
 * 结算请求会双双跑完计费引擎，然后撞唯一键抛 {@code DuplicateKeyException} ——
 * 而那个异常抛出时事务已经被标记回滚了。
 *
 * <p>{@link #pay} 里不能 catch {@link BalanceNotEnoughException}：那一刻事务
 * 已经是 rollback-only，写进去的 {@code status = 6} 提交时会丢。所以
 * {@code 6 支付失败} 只能由调用方在事务回滚之后调 {@link #markPayFailed} 写。
 *
 * <p>{@code now} 每个方法入口只取一次然后一路传下去：同一笔订单、同一条流水、
 * 同一个事件上的时间戳差几毫秒，会让对账时「同一笔操作」看起来像两笔。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingOrderServiceImpl implements ChargingOrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** 没传车辆时的兜底电池容量，用于估冻结额。取偏大值：多冻只是暂时占用，少冻要到支付那一步才失败 */
    private static final BigDecimal DEFAULT_BATTERY_KWH = new BigDecimal("60.00");

    /**
     * 单笔订单的冻结上限。
     *
     * <p>估算是「电池容量 × 最贵的一条规则单价」，而容量是用户自己填的字段，
     * 填成 99999 会把余额一次冻光 —— 这个上限让那个错误止步于 500。
     */
    private static final BigDecimal MAX_FREEZE_AMOUNT = new BigDecimal("500.00");

    /**
     * 管理端按手机号前缀搜订单时，一次最多匹配多少个车主。
     *
     * <p>取满即拒绝这次查询，不截断：截断会给出一个看起来正常的错误答案，
     * 而搜订单的运维不会去数「是不是还有更多」。
     */
    private static final int MEMBER_PREFIX_LIMIT = 500;

    private final ChargingOrderMapper orderMapper;

    private final ChargingSegmentMapper segmentMapper;

    private final ChargingOrderEventMapper eventMapper;

    private final MemberPackageMapper memberPackageMapper;

    private final TariffRuleMapper tariffRuleMapper;

    private final ConnectorMapper connectorMapper;

    private final ChargerMapper chargerMapper;

    private final StationMapper stationMapper;

    /** 只有管理端查询用得上：车主端那条路径带 member_id 查订单，不需要回表 */
    private final MemberMapper memberMapper;

    private final VehicleMapper vehicleMapper;

    private final StationTariffService stationTariffService;

    private final WalletService walletService;

    private final MemberService memberService;

    private final BizNoGenerator bizNoGenerator;

    // ---------- 车主端 ----------

    @Override
    @Transactional
    public ChargingOrderVO create(OrderCreateDTO dto) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);
        LocalDateTime now = LocalDateTime.now();

        PayMethod payMethod = resolvePayMethod(dto.getPayMethod());

        // ① 站点 / 桩 / 枪三层，逐个存在性 + 归属关系 + 可用状态
        Connector connector = assertConnectorUsable(dto);

        // ② 这把枪上不能有进行中的订单。这次查询只负责提前拒绝，给出能直接展示给
        //    用户的文案；真正的裁决在 ⑦ 的条件更新上
        assertConnectorFree(dto.getConnectorId());

        // ③ 车辆可选。有车就要查归属、查这辆车有没有在充
        Vehicle vehicle = resolveVehicle(dto.getVehicleId(), memberId);

        // ④ 冻结计费模板 —— 冻的是「此刻的价」，不是「结算时的价」
        Long templateId = stationTariffService.resolveTemplateIdAt(dto.getStationId(), now);

        // ⑤ 估算要冻多少钱
        BigDecimal estimate = estimateFreezeAmount(templateId, vehicle);

        // ⑥ 建单。status=1 待启动，此刻用户随时可以取消
        ChargingOrder order = ChargingOrder.builder()
                .orderNo(bizNoGenerator.orderNo())
                .memberId(memberId)
                .vehicleId(vehicle == null ? null : vehicle.getId())
                .plateNo(vehicle == null ? null : vehicle.getPlateNo())
                .stationId(connector.getStationId())
                .chargerId(connector.getChargerId())
                .connectorId(connector.getId())
                .tariffTemplateId(templateId)
                .status(OrderStatus.CREATED.getCode())
                .frozenAmount(estimate)
                .discountAmount(ZERO)
                .refundAmount(ZERO)
                .payMethod(payMethod.getCode())
                .version(0)
                .build();
        orderMapper.insert(order);

        // ⑦ 抢占这把枪 —— 「一把枪同时只能有一个订单」的真正裁决点。三个位置约束
        //    都不能挪：在 insert 之后（要写刚拿到的 orderId）、在冻钱之前（钱冻上再
        //    回滚就是白冻一次）、和建单同事务。影响 0 行 = 另一个请求刚抢走它，
        //    或者枪已被改成故障 / 维护，两者对用户是同一件事。
        if (connectorMapper.occupy(connector.getId(), order.getId(), now) == 0) {
            throw new ConnectorOccupiedException(MessageConstant.CONNECTOR_OCCUPIED);
        }

        // ⑧ 锁权益。必须在拿到 orderId 之后 —— locked_order_id 要写的就是它
        Long packageId = tryLockPackage(memberId, order.getId(), now);
        if (packageId != null && orderMapper.bindMemberPackage(order.getId(), packageId, now) > 0) {
            order.setMemberPackageId(packageId);
        }

        // ⑨ 冻钱。余额不够会抛 BalanceNotEnoughException 并回滚整个事务 ——
        //    订单、枪口占用、权益锁一起撤销
        walletService.freeze(memberId, order.getId(), estimate);

        // ⑩ 事件
        recordEvent(order.getId(), null, OrderStatus.CREATED, OrderEventType.CREATE,
                EventSource.USER, memberId,
                "枪 " + connector.getId() + "，计费模板 " + templateId + "，预估冻结 " + estimate,
                null, now);

        log.info("建单成功：orderNo={}, memberId={}, connectorId={}, templateId={}, frozen={}",
                order.getOrderNo(), memberId, connector.getId(), templateId, estimate);
        return ChargingOrderVO.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult pageMine(OrderPageQueryDTO query) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);

        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<ChargingOrder> rows = orderMapper.pageByMember(memberId, query.getStatus());
        long total = ((Page<ChargingOrder>) rows).getTotal();

        return new PageResult(total, rows.stream().map(ChargingOrderVO::from).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ChargingOrderDetailVO getMine(Long orderId) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);

        ChargingOrder order = orderMapper.getByIdAndMember(orderId, memberId);
        // 查不到和「不是你的」返回同一个异常 —— 区分它们会让接口变成
        //   「这个订单号存在吗」的探测器
        if (order == null) {
            throw new NotFoundException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<ChargingSegmentVO> segments = segmentMapper.listByOrderId(orderId)
                .stream().map(ChargingSegmentVO::from).toList();
        List<ChargingOrderEventVO> events = eventMapper.listByOrderId(orderId)
                .stream().map(ChargingOrderEventVO::from).toList();

        return ChargingOrderDetailVO.from(order, segments, events);
    }

    @Override
    @Transactional
    public void cancel(Long orderId) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);
        LocalDateTime now = LocalDateTime.now();

        ChargingOrder order = loadOwnedOrder(orderId, memberId);

        // 只有 1 待启动能取消。让充电中也走这条路等于给了一条
        //   「充完电把订单作废」的路径 —— 那不是取消，是偷电。
        if (markCancelledOrConflict(order, now)) {
            releaseOrderResources(order, now);
            // 枪也要放。取消是「1 待启动 → 8 已取消」，那把枪从头到尾
            //   没充过电，但不放的话它会永远停在「2 已占用」上再也点不动。
            releaseConnector(orderId, now);
            recordEvent(orderId, OrderStatus.CREATED, OrderStatus.CANCELLED,
                    OrderEventType.CANCEL, EventSource.USER, memberId, null, null, now);
        }
        log.info("订单已取消：orderId={}, memberId={}", orderId, memberId);
    }

    /**
     * 这是本类唯一不调 {@code memberService.assertActive} 的车主端方法，是刻意的。
     *
     * <p>补上它会制造一个死结：车主被禁用时若手上有一笔 {@code 4 待支付} 的订单，
     * {@code cancel} 会被 assertActive 挡住，{@code refund} 的 CAS 够不着 4，
     * {@code pay} 再挡住，这笔冻结资金就没有任何一条路能出去。真正要挡的
     * 「被禁用了还能再建单」在 {@link #create} 上。
     */
    @Override
    @Transactional
    public void pay(Long orderId) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // ① 先在订单侧抢占这一步。成功之后本单的扣款流水就不可能再被写第二次 ——
        //    uk_txn_order_type 只是最后一道防线
        int rows = orderMapper.markPaid(orderId, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.PENDING_PAY, OrderStatus.PAID);
        }

        // ② 再动钱：释放本单全部冻结，再扣实付。余额不够会抛
        //    BalanceNotEnoughException 并回滚整个事务，订单退回 4 待支付
        walletService.settle(order.getMemberId(), orderId,
                order.getFrozenAmount(), order.getPayAmount());

        recordEvent(orderId, OrderStatus.PENDING_PAY, OrderStatus.PAID,
                OrderEventType.PAY, EventSource.USER, order.getMemberId(),
                "实付 " + order.getPayAmount() + "，解冻 " + order.getFrozenAmount(),
                null, now);

        log.info("订单已支付：orderId={}, payAmount={}", orderId, order.getPayAmount());
    }

    // ---------- 管理端查询（只读） ----------

    @Override
    @Transactional(readOnly = true)
    public PageResult pageForAdmin(AdminOrderPageQueryDTO query) {
        List<Long> memberIds = resolveMemberIds(query.getMemberPhone());

        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<ChargingOrder> rows = orderMapper.pageForAdmin(query, memberIds);
        long total = ((Page<ChargingOrder>) rows).getTotal();

        return new PageResult(total, toAdminVos(rows));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOrderDetailVO getForAdmin(Long orderId) {
        // 这里用 getById 而不是 getByIdAndMember：管理端看的是全平台订单，
        //   没有归属可校验（接口注释里说明了为什么这不需要「同样的异常」那层考虑）
        ChargingOrder order = orderMapper.getById(orderId);
        if (order == null) {
            throw new NotFoundException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<ChargingSegmentVO> segments = segmentMapper.listByOrderId(orderId)
                .stream().map(ChargingSegmentVO::from).toList();
        List<ChargingOrderEventVO> events = eventMapper.listByOrderId(orderId)
                .stream().map(ChargingOrderEventVO::from).toList();

        // 派生字段查不到就是 null，不报错：车主注销 / 站点被删都不该让
        //   一笔真实存在的订单变得打不开。同 ChargerServiceImpl.toVos 的立场。
        Member member = order.getMemberId() == null
                ? null : memberMapper.getById(order.getMemberId());
        Station station = order.getStationId() == null
                ? null : stationMapper.getById(order.getStationId());

        return AdminOrderDetailVO.from(order, segments, events,
                member == null ? null : member.getPhone(),
                member == null ? null : member.getNickname(),
                station == null ? null : station.getName());
    }

    /**
     * 把手机号前缀解析成车主 id 列表。
     *
     * <p>三个返回值语义刻意不同，调用方和 XML 都要区分：{@code null} = 没传手机号，
     * 不过滤；空列表 = 传了但一个都没匹配到，返回 0 行；非空 = 收窄到这些车主。
     * 把「没匹配到」当成「不过滤」，会变成搜一个不存在的手机号却返回全平台订单 ——
     * 列表有数据、分页对、一条错都不报。正确性靠 XML 里那个 {@code AND 1 = 0} 分支。
     *
     * <p>{@code trim()} 不能省：{@code " 138"} 匹配不到任何手机号，用户会以为
     * 「这个人没有订单」。
     */
    private List<Long> resolveMemberIds(String phonePrefix) {
        if (phonePrefix == null || phonePrefix.isBlank()) {
            return null;
        }

        List<Long> ids = memberMapper.listIdsByPhonePrefix(
                phonePrefix.trim(), MEMBER_PREFIX_LIMIT);
        if (ids.size() >= MEMBER_PREFIX_LIMIT) {
            throw new ParamErrorException(MessageConstant.MEMBER_PREFIX_TOO_BROAD);
        }
        return ids;
    }

    /**
     * 批量转 VO，顺带补齐车主手机号 / 昵称与站点名。逐个回表一页就是 20 次查询，
     * 批量 IN 只要 2 次。
     *
     * <p>两个 {@code listByIds} 都不能传空集合 —— {@code IN ()} 是 SQL 语法错误而不是
     * 空结果，而「这一页一条都没有」很常见。补不上就留 null 不报错：只读路径上的
     * 派生字段失败要降级，不该让整个订单列表 500。
     */
    private List<AdminOrderVO> toAdminVos(List<ChargingOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        Set<Long> memberIds = orders.stream()
                .map(ChargingOrder::getMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> stationIds = orders.stream()
                .map(ChargingOrder::getStationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Member> members = memberIds.isEmpty()
                ? Map.of()
                : memberMapper.listByIds(List.copyOf(memberIds)).stream()
                        .collect(Collectors.toMap(Member::getId, member -> member));

        Map<Long, String> stationNames = stationIds.isEmpty()
                ? Map.of()
                : stationMapper.listByIds(List.copyOf(stationIds)).stream()
                        .collect(Collectors.toMap(Station::getId, Station::getName));

        return orders.stream()
                .map(order -> {
                    Member member = members.get(order.getMemberId());
                    return AdminOrderVO.from(order,
                            member == null ? null : member.getPhone(),
                            member == null ? null : member.getNickname(),
                            stationNames.get(order.getStationId()));
                })
                .toList();
    }

    // ---------- 订单流程（设备上报 / 定时任务 / 运维） ----------

    @Override
    @Transactional
    public void start(Long orderId, OrderStartDTO dto) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // 设备上报的时刻优先，缺省才用服务端时钟 —— 见 OrderEndDTO 的类注释
        int rows = orderMapper.markStarted(orderId, now,
                dto.getStartMeterKwh(), dto.getStartSoc(), now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.CREATED, OrderStatus.CHARGING);
        }

        // 枪跟着进「3 充电中」。影响 0 行只告警，不抛异常 ——
        //   订单才是真相，connector.status 是它的投影（见 ConnectorStatus 类注释）。
        //   这里真的抛出去的话，一笔「枪口没被正确占用」的订单就永远充不了电，
        //   而那是一个可观测量（下面的日志）换一个不可恢复的失败。
        //   历史数据（W0 之前建的订单没有占用记录）走的也是这条路。
        if (connectorMapper.markCharging(order.getConnectorId(), orderId, now) == 0) {
            log.warn("订单启动充电但枪口占用记录不匹配：orderId={}, connectorId={}",
                    orderId, order.getConnectorId());
        }

        // 不动钱：钱在建单时已经冻好了
        recordEvent(orderId, OrderStatus.CREATED, OrderStatus.CHARGING,
                OrderEventType.START, EventSource.DEVICE, null,
                "起始电表读数 " + dto.getStartMeterKwh(), null, now);

        log.info("订单启动充电：orderId={}, startMeter={}", orderId, dto.getStartMeterKwh());
    }

    @Override
    @Transactional
    public void end(Long orderId, OrderEndDTO dto) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = dto.getEndTime() == null ? now : dto.getEndTime();

        // 电量 = 电表差，不是结束读数本身。电表是累计值，
        //   不会因为换了辆车就归零。
        String meterProblem = checkMeterReadings(order, dto);

        Integer durationSeconds = order.getStartTime() == null ? null
                : (int) Duration.between(order.getStartTime(), endTime).getSeconds();
        BigDecimal energy = meterProblem == null
                ? dto.getEndMeterKwh().subtract(order.getStartMeterKwh())
                : null;

        // ① 先把「充电结束了」这个物理事实落库 —— 枪确实停了、车确实被充过，
        //    那是不可回滚的事实，任何后续问题都不该让它消失
        int rows = orderMapper.markEnded(orderId, endTime, durationSeconds,
                dto.getEndMeterKwh(), energy, dto.getEndSoc(), dto.getStopReason(), now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.CHARGING, OrderStatus.SETTLING);
        }

        // 车已经拔枪 → 枪回到「2 已占用」。订单还要结算，所以不释放 ——
        //   直接放空闲的话，结算期间别人可以下第二笔单。同 start() 的取舍：0 行只告警。
        if (connectorMapper.markOccupied(order.getConnectorId(), orderId, now) == 0) {
            log.warn("订单结束充电但枪口占用记录不匹配：orderId={}, connectorId={}",
                    orderId, order.getConnectorId());
        }

        recordEvent(orderId, OrderStatus.CHARGING, OrderStatus.SETTLING,
                OrderEventType.END, EventSource.DEVICE, null,
                "结束电表读数 " + dto.getEndMeterKwh() + "，电量 " + energy
                        + "，停止原因 " + describeStopReason(dto.getStopReason()),
                null, now);

        // ② 电量算不出来（读数缺失 / 倒退）时，紧接着转异常挂账。
        //    两步而不是一步：end_time 已经保住了，异常状态也拿到了，
        //      两个事实都在。合成一步的话必然丢掉其中一个。
        if (meterProblem != null) {
            markAbnormal(orderId, meterProblem, now);
            log.warn("订单转入异常挂账：orderId={}, 原因={}", orderId, meterProblem);
            return;
        }

        log.info("订单结束充电：orderId={}, energy={}, duration={}s", orderId, energy, durationSeconds);
    }

    @Override
    @Transactional
    public void settle(Long orderId) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // ① 纯计算。失败（电价规则有缺口）时抛异常让整个事务回滚 ——
        //    订单留在 3 待结算，可以重试。只有「重试也不会好」的才转 9，
        //    而那是一个需要人来判断的决定，不在这里自动做
        BillingResult result = bill(order);

        // ② 权益抵扣
        BigDecimal discount = ZERO;
        if (order.getMemberPackageId() != null) {
            discount = applyPackage(order, result, now);
        }
        BigDecimal payAmount = result.totalAmount().subtract(discount);

        // ③ 抢占这一步，然后才写分段。顺序不能反 —— 见类注释
        int rows = orderMapper.markSettled(orderId,
                result.totalEnergyFee(), result.totalServiceFee(), result.totalAmount(),
                discount, payAmount, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.SETTLING, OrderStatus.PENDING_PAY);
        }

        // ④ 分段明细。撞 uk_segment_order_start 说明 ③ 漏了，那时整笔失败并告警
        segmentMapper.batchInsert(toSegments(orderId, result, now));

        // ⑤ 两次对 connector 的写入放在一起，且都在 batchInsert 之后。
        //    两个理由：
        //      · 一次事务里只取一次那一行的 X 锁，且取得尽量晚 —— 早取会拉长
        //        与「管理员改这把枪」的等待窗口（那条 update 也锁同一行）；
        //      · 原子性上放哪儿都一样：本方法整个是一个事务，这两条语句
        //        提交当且仅当 ③ 的 markSettled 提交。
        //
        //    下面是 ⑤ 的两半，各自的「为什么不会重复计数」在注释里。
        //
        //    ⑤a 释放枪口：车充完了、账也结出来了，这把枪不再归本单。
        //        释放是幂等的（谓词只有 current_order_id = orderId），
        //          所以不需要先判断「我还占着它吗」。
        releaseConnector(orderId, now);
        //
        //    ⑤b 累计充电次数 +1。
        //        一次性的证明：本方法标了 @Transactional，自增是一条普通的
        //          mapper UPDATE —— 它提交当且仅当 ③ 的 CAS 提交。④ 撞唯一键时
        //          整笔回滚（含 ③ 和这一条），重试从状态 3 重新开始，最终恰好加一次。
        //          因此绝不可把这一条改成 REQUIRES_NEW / @Async / 自注入代理调用，
        //          那三种写法都会让它在 CAS 之外独立提交，变成重复计数。
        //        它数的是「结算成功的次数」，不是「车充过电的次数」：经 end() 的
        //          电表异常路径转入 9 的订单走不到这里，因此不计入。取舍写在
        //          ConnectorMapper.incrementChargeCount 上。
        connectorMapper.incrementChargeCount(order.getConnectorId());

        recordEvent(orderId, OrderStatus.SETTLING, OrderStatus.PENDING_PAY,
                OrderEventType.SETTLE, EventSource.SCHEDULED, null,
                "分段 " + result.segmentCount() + " 段，应付 " + result.totalAmount()
                        + "，优惠 " + discount + "，实付 " + payAmount,
                null, now);

        log.info("订单结算完成：orderId={}, segments={}, total={}, discount={}, pay={}",
                orderId, result.segmentCount(), result.totalAmount(), discount, payAmount);
    }

    @Override
    @Transactional
    public void refund(Long orderId, BigDecimal amount) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // 只有 5 已支付能退。4 待支付的时候钱还没扣，
        //   那时候要「退」的是冻结的钱，走的是取消 / 挂账
        int rows = orderMapper.markRefunded(orderId, amount, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.PAID, OrderStatus.REFUNDED);
        }

        // 必须排在 CAS 之后 —— 和 pay 里那条理由一样。
        //   金额为正的校验在 walletService.refund 里，传负数会在那里被拒
        walletService.refund(order.getMemberId(), orderId, amount);

        recordEvent(orderId, OrderStatus.PAID, OrderStatus.REFUNDED,
                OrderEventType.REFUND, EventSource.OPERATOR, currentOperatorId(),
                "退款 " + amount, null, now);

        log.info("订单已退款：orderId={}, amount={}", orderId, amount);
    }

    /**
     * 把订单转成 {@code 6 支付失败}。
     *
     * <p>必须在新的、{@link #pay} 那个事务已经回滚之后的事务里调用，见类注释。
     * 调用方是 Controller，它没有事务，天然就是正确的位置。
     *
     * <p>影响 0 行是正常的（别的线程可能刚把它推到 5），所以不抛异常：本方法记的是
     * 「这一次尝试失败了」，不是「订单必须变成失败态」。
     */
    @Override
    @Transactional
    public void markPayFailed(Long orderId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.markPayFailed(orderId, reason, now);
        if (rows == 0) {
            log.info("标记支付失败时订单已不在待支付状态，跳过：orderId={}", orderId);
            return;
        }
        recordEvent(orderId, OrderStatus.PENDING_PAY, OrderStatus.PAY_FAILED,
                OrderEventType.ABNORMAL, EventSource.SCHEDULED, null, reason, null, now);
        log.warn("订单支付失败：orderId={}, 原因={}", orderId, reason);
    }

    // ---------- 校验 ----------

    private Long requireMemberId() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException();
        }
        return memberId;
    }

    /** 运维 id。设备上报和定时任务没有操作人，返回 null 而不是硬编一个 0 */
    private Long currentOperatorId() {
        return BaseContext.getCurrentId();
    }

    private PayMethod resolvePayMethod(Integer code) {
        PayMethod method = code == null ? PayMethod.BALANCE : PayMethod.of(code);
        if (!PayMethod.isSupported(method)) {
            // 明确拒绝，不是静默降级成余额 —— 见 PayMethod 的类注释
            throw new ParamErrorException(MessageConstant.ORDER_PAY_METHOD_UNSUPPORTED
                    + "：" + method.getLabel());
        }
        return method;
    }

    /**
     * 站点 / 桩 / 枪三层校验
     *
     * <p>三层全查，而且查<B>归属关系</B>：枪挂在桩上、桩挂在站上。只查枪的话，
     * 一个前端 bug（把 A 站的订单发到 B 站的枪上）会建出一笔「站点字段是 A、枪是 B」
     * 的订单 —— 结算按 A 的电价算，而实际用的是 B 的电。两张表各自都自洽，
     * 只有把它们放一起看才发现对不上。
     */
    private Connector assertConnectorUsable(OrderCreateDTO dto) {
        if (stationMapper.getById(dto.getStationId()) == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        Charger charger = chargerMapper.getById(dto.getChargerId());
        if (charger == null) {
            throw new NotFoundException(MessageConstant.CHARGER_NOT_FOUND);
        }
        Connector connector = connectorMapper.getById(dto.getConnectorId());
        if (connector == null) {
            throw new NotFoundException(MessageConstant.CONNECTOR_NOT_FOUND);
        }

        if (!charger.getStationId().equals(dto.getStationId())) {
            throw new ParamErrorException("充电桩不属于该站点：chargerId=" + dto.getChargerId()
                    + "，stationId=" + dto.getStationId());
        }
        if (!connector.getChargerId().equals(dto.getChargerId())
                || !connector.getStationId().equals(dto.getStationId())) {
            throw new ParamErrorException("充电枪不属于该充电桩或站点：connectorId=" + dto.getConnectorId());
        }

        // 只有「空闲」的枪能建单。已占用 / 充电中由订单流程维护，
        //   离线 / 故障 / 维护是设备侧的状态
        //
        //   这里用的是 ConnectorStatus 而不是 StatusConstant —— 两者都有一个
        //     值等于 1，但含义完全不同（枪的空闲码是 1，而 StatusConstant.ENABLE
        //     讲的是「这一行有没有被删除」）。混用会写出一个能跑但读起来是错的
        //     判断：改天 ConnectorStatus 的码值变了，那个判断不会跟着变。
        ConnectorStatus connectorStatus = ConnectorStatus.of(connector.getStatus());
        if (connectorStatus != ConnectorStatus.IDLE) {
            throw new DataConflictException(MessageConstant.CONNECTOR_OCCUPIED
                    + "（当前：" + connectorStatus.getLabel() + "）");
        }

        // 桩也要查：枪空闲但桩离线时，建出来的单永远等不到设备上报
        ChargerStatus chargerStatus = ChargerStatus.of(charger.getStatus());
        if (chargerStatus != ChargerStatus.IDLE) {
            throw new DataConflictException("充电桩当前不可用（" + chargerStatus.getLabel()
                    + "）：chargerId=" + charger.getId());
        }
        return connector;
    }

    /**
     * 这把枪上不能有进行中的订单。
     *
     * <p>返回超过 1 条时必须停下来：随便挑一笔继续的话，另一笔会永远停在充电中，
     * 而它的钱还冻着。这里命中概率很低（枪的状态已经拦了一道），它挡的是
     * 「枪的状态被人工改错」和历史脏数据 —— 状态列是缓存，订单才是真相。
     */
    private void assertConnectorFree(Long connectorId) {
        List<ChargingOrder> holding = orderMapper.listHoldingByConnector(
                connectorId, OrderStatus.holdingConnectorCodes());
        if (holding.size() > 1) {
            throw new DataConflictException(MessageConstant.ORDER_CONNECTOR_STATE_INVALID
                    + "：connectorId=" + connectorId + " 上有 " + holding.size() + " 笔进行中的订单");
        }
        if (!holding.isEmpty()) {
            throw new DataConflictException(MessageConstant.CONNECTOR_OCCUPIED
                    + "：已有订单 " + holding.get(0).getOrderNo());
        }
    }

    /** 车辆可选。给了就要查归属，并确认这辆车没有在充 */
    private Vehicle resolveVehicle(Long vehicleId, Long memberId) {
        if (vehicleId == null) {
            // 扫码即充：没绑车也能充。订单的 vehicle_id 和 plate_no 都是 NULL，
            //   详情页展示成「未绑定车辆」—— 那是一个合法的状态，不是数据缺失
            return null;
        }
        // getByIdAndMember 而不是 getById：归属校验在 SQL 里完成，
        //   「忘了校验」在这个形状下写不出来（忘传 memberId 是查不到）
        Vehicle vehicle = vehicleMapper.getByIdAndMember(vehicleId, memberId);
        if (vehicle == null) {
            throw new NotFoundException(MessageConstant.VEHICLE_NOT_FOUND);
        }

        // 同一时刻一辆车只能有一笔进行中的订单。拦不住的话，
        //   用户会拿到两笔都算满额的订单，而设备那边只充了一次电
        List<ChargingOrder> holding = orderMapper.listHoldingByVehicle(
                memberId, vehicleId, OrderStatus.holdingConnectorCodes());
        if (!holding.isEmpty()) {
            throw new DataConflictException(MessageConstant.ORDER_VEHICLE_BUSY
                    + "：已有订单 " + holding.get(0).getOrderNo());
        }
        return vehicle;
    }

    /**
     * 预估要冻结多少钱。这是一个上界，不是估计值。
     *
     * <p>公式是「电池容量 × 该模板下最贵的一条规则（电费 + 服务费）」。取最贵而不是
     * 平均，是为了让结算时的实付必然 ≤ 冻结额，把「差额要从可用余额补」的概率降到最低。
     * 真实产品会结合 SOC 与目标 SOC 来估，但 SOC 是设备上报的，这里没有，只能按容量取上界。
     *
     * <p>容量是用户自己填的字段，所以要压 {@link #MAX_FREEZE_AMOUNT}。
     */
    private BigDecimal estimateFreezeAmount(Long templateId, Vehicle vehicle) {
        BigDecimal capacity = (vehicle != null && vehicle.getBatteryCapacityKwh() != null
                && vehicle.getBatteryCapacityKwh().signum() > 0)
                ? vehicle.getBatteryCapacityKwh()
                : DEFAULT_BATTERY_KWH;

        List<TariffRule> rules = tariffRuleMapper.listByTemplateId(templateId);
        BigDecimal maxPrice = BigDecimal.ZERO;
        for (TariffRule rule : rules) {
            BigDecimal unit = rule.getEnergyPrice()
                    .add(rule.getServicePrice() == null ? BigDecimal.ZERO : rule.getServicePrice());
            if (unit.compareTo(maxPrice) > 0) {
                maxPrice = unit;
            }
        }
        if (maxPrice.signum() <= 0) {
            // 规则集为空或全为 0 —— 不是「不用冻钱」，是数据有问题。
            // 让结算去报「某个时刻没有规则覆盖」，这里先给一个保守值
            maxPrice = BigDecimal.ONE;
        }

        BigDecimal estimate = capacity.multiply(maxPrice).setScale(2, BillingEngine.ROUNDING);
        return estimate.min(MAX_FREEZE_AMOUNT);
    }

    /**
     * 选一份可用权益并锁定它，返回锁到的 {@code member_package.id}；一份都没锁到
     * 返回 {@code null}，订单按余额直充走 —— 这不是失败。
     *
     * <p>必须「查询 → 逐个试锁 → 失败取下一份」，不能「查询 → 取第一份 → 锁」：
     * 查询和锁定之间别人可能把第一份抢走了，那时该取下一份重试而不是直接失败。
     *
     * <p>遍历所有类型、每种都试，抢不到就换下一个。中途 {@code break} 会让只有
     * 时长包的车主因为「电量包这一类空着」而被跳过，且日志里什么都看不到。
     */
    private Long tryLockPackage(Long memberId, Long orderId, LocalDateTime now) {
        for (PackageType type : PackageType.values()) {
            List<MemberPackage> candidates = memberPackageMapper.listUsable(
                    memberId, type.getCode(), now);
            for (MemberPackage candidate : candidates) {
                if (memberPackageMapper.lockForOrder(candidate.getId(), orderId, now) > 0) {
                    log.info("订单锁定权益：orderId={}, packageId={}, type={}",
                            orderId, candidate.getId(), type.getLabel());
                    // 锁到就返回 —— 订单只有一个 member_package_id 列，
                    //   不许叠加两种权益，那会让「用户实际被优惠了多少」
                    //   变成一个需要按顺序解释的计算过程
                    return candidate.getId();
                }
                // 被别人抢走了，试下一份
            }
            // 这里不能 break。曾经写过一个无条件的 break，本意是「一类里锁到
            //   一份就够了」，但它在「这一类一份都没锁到」时也会跳出 —— 于是只持有
            //   时长包 / 次卡 / 折扣包的用户，权益被静默忽略（枚举顺序把电量包排在最前）。
            //   用户看到的是「我的套餐没用上」，而日志里什么都没有。
            //   真正的「只锁一份」由上面的 return 保证。
        }
        return null;
    }

    // ---------- 结算 ----------

    /**
     * 跑计费引擎
     *
     * <p>用的是订单上冻结的 {@code tariff_template_id} 去取规则，不是
     * 「现在这个站点用哪个模板」—— 这条是 {@code station_tariff} 整张表存在的意义
     * （见那个接口的类注释）。
     */
    private BillingResult bill(ChargingOrder order) {
        if (order.getTotalEnergyKwh() == null) {
            throw new DataConflictException(MessageConstant.ORDER_METER_READING_MISSING
                    + "：orderId=" + order.getId());
        }
        if (order.getStartTime() == null || order.getEndTime() == null) {
            throw new DataConflictException("订单缺少起止时间，无法计费：orderId=" + order.getId());
        }

        List<TariffRuleSnapshot> rules = tariffRuleMapper
                .listByTemplateId(order.getTariffTemplateId())
                .stream()
                .map(rule -> TariffRuleSnapshot.of(rule.getStartTime(), rule.getEndTime(),
                        rule.getPeriodType(), rule.getEnergyPrice(), rule.getServicePrice()))
                .toList();

        return BillingEngine.calculate(new BillingRequest(
                order.getStartTime(), order.getEndTime(), order.getTotalEnergyKwh(), rules));
    }

    /**
     * 权益抵扣，返回抵扣金额。
     *
     * <p>权益抵的是电费，服务费照收。这条规则是实现时定下来的产品假设，没有上游文档
     * 规定过；要改成「权益覆盖全部费用」的话，只改这一个方法。
     *
     * <pre>
     *   电量包 type=1  覆盖率 = min(实际电量, 剩余电量) / 实际电量
     *   时长包 type=2  覆盖率 = min(实际时长, 剩余时长) / 实际时长
     *   次卡   type=4  覆盖率 = 1        （一次充电就是一整笔，这是次卡的定义）
     *   折扣包 type=3  不按覆盖率：抵扣 = round2(服务费 × (1 − 折扣系数))
     * </pre>
     *
     * <p>抵扣 = {@code round2(电费 × 覆盖率)}。用覆盖率乘电费合计，而不是把覆盖的电量
     * 逐段分摊：逐段分摊要在每段各舍入一次，误差会累加，而账单上只显示一个优惠金额，
     * 用户核不出那个数是哪来的。覆盖率封顶 1，否则 pay_amount 会变成负数。
     *
     * <p>{@code covered = min(实际用量, 剩余量)} 必须同时决定抵扣金额和扣减的余量。
     * 曾经抵扣按 min 算、扣减却扣全部用量，于是一份只剩 4 度的包在用户充了 10 度之后
     * {@code remaining_kwh} 变成 -6：用户没多付，但那一行不再满足
     * {@code remaining_kwh > 0}，会被 listUsable 永久跳过，而且全程不报错。
     */
    private BigDecimal applyPackage(ChargingOrder order, BillingResult result, LocalDateTime now) {
        MemberPackage mp = memberPackageMapper.getById(order.getMemberPackageId());
        if (mp == null || !StatusConstant.ENABLE.equals(mp.getStatus())) {
            // 权益在充电期间被作废了（退款 / 客服操作）。不抵扣，
            // 但也不失败 —— 用户确实充了电，该付的钱还是要付
            log.warn("订单的权益不可用，按无优惠结算：orderId={}, packageId={}",
                    order.getId(), order.getMemberPackageId());
            return ZERO;
        }

        PackageType type = PackageType.of(mp.getPackageType());

        // 折扣包不消耗余量，没有「用掉多少」可言
        if (type == PackageType.SERVICE_DISCOUNT) {
            BigDecimal factor = mp.getServiceDiscount() == null
                    ? BigDecimal.ONE : mp.getServiceDiscount();
            BigDecimal discount = result.totalServiceFee()
                    .multiply(BigDecimal.ONE.subtract(factor))
                    .setScale(2, BillingEngine.ROUNDING);
            return discount.min(result.totalAmount());
        }

        BigDecimal used = usedAmountOf(type, order);
        BigDecimal covered = coveredAmountOf(type, mp, used);

        // 抵扣 = 电费 × covered/used。因为 covered <= used，覆盖率天然 <= 1
        BigDecimal discount = ZERO;
        if (used != null && used.signum() > 0 && covered.signum() > 0) {
            BigDecimal coverage = covered.divide(used, 6, RoundingMode.HALF_UP);
            discount = result.totalEnergyFee()
                    .multiply(coverage)
                    .setScale(2, BillingEngine.ROUNDING);
        }

        // 抵扣不能超过应付。多出来的部分不退还、不结转 ——
        //   那是产品规则，不是代码问题
        if (discount.compareTo(result.totalAmount()) > 0) {
            discount = result.totalAmount();
        }

        consumePackage(type, mp, order, covered, now);
        return discount;
    }

    /** 这次消耗了多少（电量包=度，时长包=秒，次卡=次）。算不出返回 {@code null} */
    private BigDecimal usedAmountOf(PackageType type, ChargingOrder order) {
        return switch (type) {
            case ENERGY -> order.getTotalEnergyKwh();
            case DURATION -> order.getDurationSeconds() == null
                    ? null : BigDecimal.valueOf(order.getDurationSeconds());
            // 一次充电就用掉一次，这是次卡的定义 —— 没有「用了几次」这个输入
            case TIMES -> BigDecimal.ONE;
            default -> throw new ParamErrorException("该权益类型不按用量抵扣：" + type.getLabel());
        };
    }

    /** 还能用多少，单位和 {@link #usedAmountOf} 一致 */
    private BigDecimal remainingAmountOf(PackageType type, MemberPackage mp) {
        return switch (type) {
            case ENERGY -> mp.getRemainingKwh();
            // 库里存的是分钟，这里换算成秒 —— 分母（实际时长）是秒
            case DURATION -> mp.getRemainingMinutes() == null
                    ? null : BigDecimal.valueOf(mp.getRemainingMinutes())
                    .multiply(BigDecimal.valueOf(60));
            case TIMES -> mp.getRemainingTimes() == null
                    ? null : BigDecimal.valueOf(mp.getRemainingTimes());
            default -> throw new ParamErrorException("该权益类型没有余量：" + type.getLabel());
        };
    }

    /**
     * 这次能抵扣多少 —— 恒在 {@code [0, used]}，且不超过剩余量
     *
     * <p>两个封顶都是必需的：不超过 {@code used}（否则抵扣会超过电费本身，
     * {@code pay_amount} 变负数）、不超过 {@code remain}（否则余量被扣成负数）。
     * 一个 {@code min} 同时满足两条，因为抵扣的量不可能既是「用掉的」又是「没有的」。
     */
    private BigDecimal coveredAmountOf(PackageType type, MemberPackage mp, BigDecimal used) {
        if (used == null || used.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal remain = remainingAmountOf(type, mp);
        if (remain == null || remain.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal covered = used.min(remain);

        // 时长包按整分钟扣减（settleDuration 的参数就是分钟），
        //   所以这里先向下取整到分钟。不取整的话抵扣按 90 秒算、
        //   扣减只扣 1 分钟，两个数又对不上了 —— 差额是平台白送
        if (type == PackageType.DURATION) {
            covered = covered.divide(BigDecimal.valueOf(60), 0, RoundingMode.DOWN)
                    .multiply(BigDecimal.valueOf(60));
        }
        return covered;
    }

    /**
     * 扣减权益。扣的必须是 {@link #applyPackage} 算出来的那个 {@code covered}。
     *
     * <p>扣量、释放锁、用尽则改状态三件事在同一条 SQL 里完成，拆开写的话中间任何
     * 一步失败都会留下不一致。
     *
     * <p>影响 0 行不抛异常：钱已经按抵扣后的价收过了，这时抛会让整笔结算回滚，
     * 而用户已经充完电走了。记告警由对账发现 —— 那是权益少扣了，平台吃亏。
     */
    private void consumePackage(PackageType type, MemberPackage mp,
                                ChargingOrder order, BigDecimal covered, LocalDateTime now) {
        int rows = switch (type) {
            case ENERGY -> memberPackageMapper.settleEnergy(
                    mp.getId(), order.getId(), covered, now);
            case DURATION -> memberPackageMapper.settleDuration(
                    mp.getId(), order.getId(), covered.intValue() / 60, now);
            case TIMES -> memberPackageMapper.settleTimes(mp.getId(), order.getId(), now);
            // 折扣包在上面就返回了，走不到这里；写 1 只是为了 switch 穷尽
            case SERVICE_DISCOUNT -> 1;
        };
        if (rows == 0 && type != PackageType.SERVICE_DISCOUNT) {
            log.warn("权益扣减未命中：orderId={}, packageId={}, type={} —— 可能已被释放或抢走，需对账",
                    order.getId(), mp.getId(), type.getLabel());
        }
    }

    private List<ChargingSegment> toSegments(Long orderId, BillingResult result, LocalDateTime now) {
        return result.segments().stream()
                .map(seg -> toSegment(orderId, seg, now))
                .toList();
    }

    private ChargingSegment toSegment(Long orderId, BillingSegment seg, LocalDateTime now) {
        return ChargingSegment.builder()
                .orderId(orderId)
                .seq(seg.seq())
                .periodType(seg.periodType().getCode())
                .startTime(seg.startTime())
                .endTime(seg.endTime())
                .durationSeconds(seg.durationSeconds())
                .energyKwh(seg.energyKwh())
                .energyPrice(seg.energyPrice())
                .servicePrice(seg.servicePrice())
                .energyFee(seg.energyFee())
                .serviceFee(seg.serviceFee())
                .amount(seg.amount())
                .createTime(now)
                .build();
    }

    // ---------- 释放与挂账 ----------

    /**
     * 释放订单占用的资源：钱包冻结 + 权益锁。两样都要放，且和状态变更同事务 ——
     * 少任何一样都会留下「钱 / 权益冻着但订单已经结束」的状态，没有东西会去清理它。
     */
    private void releaseOrderResources(ChargingOrder order, LocalDateTime now) {
        // ① 解冻。冻结额可能为 0（全额权益抵扣），那时当初就没冻过，
        //    这里也什么都不做 —— 解冻 0 元会写出一条 0 元流水，给对账添噪音
        if (order.getFrozenAmount() != null && order.getFrozenAmount().signum() > 0) {
            walletService.unfreeze(order.getMemberId(), order.getId(), order.getFrozenAmount());
        }

        // ② 释放权益锁。余量不动 —— 取消订单不该消耗任何权益
        if (order.getMemberPackageId() != null) {
            memberPackageMapper.releaseLock(order.getMemberPackageId(), order.getId(), now);
        }
    }

    /**
     * 释放订单占用的充电枪。
     *
     * <p>刻意和 {@link #releaseOrderResources} 分开，不合并：取消订单两样都要做，
     * 而结算只做这一样 —— 结算时钱绝不能解冻，它马上就要被扣掉。合并成一个
     * 「释放所有资源」之后，必然有人在结算路径上顺手调它，把那笔钱放走。
     *
     * <p>谓词只有 {@code current_order_id = orderId}，所以幂等：重复调、对一把从未被
     * 本单占用的枪调，都是 0 行无副作用。正因如此 cancel / settle / markAbnormal
     * 三处可以直接调，不必先判断「还占着枪吗」—— 那个判断在三处会写成三种样子。
     */
    private void releaseConnector(Long orderId, LocalDateTime now) {
        connectorMapper.release(orderId, now);
    }

    /**
     * 释放这笔订单占着的权益锁（如果有）。谓词是 {@code locked_order_id = orderId}，
     * 幂等、自守卫，重复调或对一笔从未锁过权益的订单调都是 0 行。
     *
     * <p>不复用 {@link #releaseOrderResources}：那个还会解冻，而挂账的语义恰恰是
     * 「钱还得冻着等人工」，解冻会把一笔本该被扣掉或退回的钱提前放走。
     *
     * <p>也不能换成 releaseLock(packageId, ...)：那个要先知道是哪一份权益，也就是
     * 要先把订单整行读出来拿 member_package_id。「哪一行被这笔订单锁着」这个问题，
     * 权益表那一侧就能回答。
     *
     * <p>不碰余量、不碰状态：挂账的订单没结算过，那份权益一点没被消耗。
     *
     * @see com.chargeops.mapper.MemberPackageMapper#releaseLockByOrder
     */
    private void releasePackageLock(Long orderId, LocalDateTime now) {
        memberPackageMapper.releaseLockByOrder(orderId, now);
    }

    /**
     * 转异常挂账。带原因，没有原因的挂账单只能靠人翻日志。
     *
     * <p>来源集合是 {@link OrderStatus#holdingMoneyCodes()}（钱还冻着但账结不掉）。
     * 不能用 holdingConnectorCodes()：那个只有 {1,2,3}，而挂账最常见的两个场景
     * （4 余额不够、6 重试仍失败）恰恰在集合外，挂不上去而且静默失败。
     */
    private void markAbnormal(Long orderId, String reason, LocalDateTime now) {
        int rows = orderMapper.markAbnormal(
                orderId, OrderStatus.holdingMoneyCodes(), reason, now);
        if (rows == 0) {
            // 状态已经不在进行中了（比如被并发地取消了）——
            // 那不是错误，挂账本来就不该覆盖一个已经终结的订单
            log.info("转异常挂账时订单已不在进行中状态，跳过：orderId={}", orderId);
            return;
        }

        // 释放枪口和权益锁，两样都不做分支判断：来源 {1,2,3,4,6} 里 {4,6} 的枪和锁
        // 早在结算时就放过了，而 release 的谓词带 orderId，对它们影响 0 行。
        //
        // 权益锁这一行曾经漏了（end() 的电表异常路径 3 → 9 是真实可达的），后果不是
        // 报错而是那一行 locked_order_id 永久留着：listUsable 从此永久跳过这份权益，
        // voidPackage 的预检也把它挡在外面 —— 一份一点没用过的权益永远退不了款。
        releaseConnector(orderId, now);
        releasePackageLock(orderId, now);

        recordEvent(orderId, null, OrderStatus.ABNORMAL,
                OrderEventType.ABNORMAL, EventSource.SCHEDULED, null, reason, null, now);
    }

    // ---------- 读写订单 + 事件 ----------

    private ChargingOrder loadOrder(Long orderId) {
        ChargingOrder order = orderMapper.getById(orderId);
        if (order == null) {
            throw new NotFoundException(MessageConstant.ORDER_NOT_FOUND);
        }
        return order;
    }

    private ChargingOrder loadOwnedOrder(Long orderId, Long memberId) {
        ChargingOrder order = orderMapper.getByIdAndMember(orderId, memberId);
        if (order == null) {
            throw new NotFoundException(MessageConstant.ORDER_NOT_FOUND);
        }
        return order;
    }

    private boolean markCancelledOrConflict(ChargingOrder order, LocalDateTime now) {
        if (orderMapper.markCancelled(order.getId(), now) == 0) {
            throw statusConflict(order, OrderStatus.CREATED, OrderStatus.CANCELLED);
        }
        return true;
    }

    /**
     * 状态条件更新返回 0 行时抛的异常。消息里带上当前状态和期望状态：只写
     * 「操作失败」的话，用户和客服分不清这是并发（刷新重试即可）还是用错了流程
     * （比如想取消一笔已经在充电的订单），而那两件事要说的话完全不同。
     */
    private DataConflictException statusConflict(ChargingOrder order, OrderStatus expected,
                                                 OrderStatus target) {
        OrderStatus actual = OrderStatus.of(order.getStatus());
        return new DataConflictException(MessageConstant.ORDER_STATUS_CONFLICT
                + "：订单 " + order.getOrderNo() + " 当前是「" + actual.getLabel()
                + "」，该操作要求「" + expected.getLabel() + "」并转入「" + target.getLabel()
                + "」。若刚刚重复提交过，刷新后重试即可");
    }

    private void recordEvent(Long orderId, OrderStatus from, OrderStatus to,
                             OrderEventType type, EventSource source, Long operatorId,
                             String detail, String idempotentKey, LocalDateTime now) {
        eventMapper.insert(ChargingOrderEvent.builder()
                .orderId(orderId)
                .fromStatus(from == null ? null : from.getCode())
                .toStatus(to.getCode())
                .eventType(type.getCode())
                .source(source.getCode())
                .operatorId(operatorId)
                .detail(detail)
                .idempotentKey(idempotentKey)
                .createTime(now)
                .build());
    }

    /** 电表读数能不能算出电量。算得出返回 null，算不出返回原因 */
    private String checkMeterReadings(ChargingOrder order, OrderEndDTO dto) {
        if (order.getStartMeterKwh() == null) {
            return MessageConstant.ORDER_METER_READING_MISSING
                    + "：订单缺少起始读数，结束读数 " + dto.getEndMeterKwh();
        }
        if (dto.getEndMeterKwh().compareTo(order.getStartMeterKwh()) < 0) {
            return MessageConstant.ORDER_METER_READING_REGRESSED
                    + "：起始 " + order.getStartMeterKwh() + "，结束 " + dto.getEndMeterKwh();
        }
        return null;
    }

    private String describeStopReason(Integer code) {
        return code == null ? "未上报" : StopReason.of(code).getLabel();
    }
}
