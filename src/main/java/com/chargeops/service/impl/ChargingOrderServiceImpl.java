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
 * 订单状态机实现
 *
 * <p>契约与分组见 {@link ChargingOrderService} 的类注释。
 *
 * <h2>★★ 一、每一个流转都是「CAS 先赢，再动别的」★★</h2>
 *
 * <p>所有方法的骨架都一样：
 *
 * <pre>
 *   ① 读到订单
 *   ② 做纯计算（可能抛异常 → 整体回滚，订单留在原状态，可以重试）
 *   ③ 用 status 条件更新**抢占这一步**，返回 0 就抛冲突
 *   ④ 抢占成功之后才去写分段 / 动钱包 / 写事件
 * </pre>
 *
 * <p>★ <b>③ 必须在 ④ 之前。</b>反过来的话，两个并发的结算请求会双双跑完计费引擎、
 * 双双去插分段 —— 其中一个撞唯一键抛 {@code DuplicateKeyException}，
 * 而那个异常是 RuntimeException，<b>抛出的那一刻事务已经被标记回滚</b>，
 * 连「回滚」这件事都发生在了一个和真正原因无关的位置。
 *
 * <h2>★★ 二、余额不足时为什么不在本类里转「支付失败」★★</h2>
 *
 * <p>{@link #pay} 调 {@code walletService.settle}，后者在余额不够时抛
 * {@link BalanceNotEnoughException}。那个异常<b>不能在本方法里 catch</b>：
 * Spring 的事务拦截器在它抛出的瞬间就把事务标记成 rollback-only 了，
 * catch 住再写一个 {@code status = 6} 出去，提交时照样整体回滚 ——
 * 只是失败的位置从「余额不足那一行」挪到了「提交那里」。
 *
 * <p>所以 {@code 6 支付失败} <b>只能在新的事务里写</b>，由调用方在 {@code pay}
 * 的事务回滚<b>之后</b>调 {@link #markPayFailed} —— 调用方（Controller）没有事务，
 * 天然就是正确的位置。★ 这也解释了为什么 {@code 4 待支付} 是一个安全的停留状态：
 * 此刻钱还在<b>冻结</b>里，既没扣掉也没丢，定时任务可以捡起来重试，
 * 重试仍失败由运维转 {@code 9}。
 *
 * <h2>★ 三、{@code now} 只取一次</h2>
 *
 * <p>每个方法入口取一个 {@code LocalDateTime now} 然后一路传下去 —— 否则同一笔订单、
 * 同一条流水、同一个事件上的时间戳会差几毫秒，而对账要按时间对齐两张表，
 * 那几毫秒会让「同一笔操作」看起来像两笔。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingOrderServiceImpl implements ChargingOrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * 没有车辆信息时的默认电池容量，用于估算冻结额
     *
     * <p>★ 取一个偏大的值：<b>多冻是暂时占用，少冻是结算时才发现钱不够</b> ——
     * 前者用户刷新一下就看到，后者要走到支付那一步才失败。
     */
    private static final BigDecimal DEFAULT_BATTERY_KWH = new BigDecimal("60.00");

    /**
     * 单笔订单的冻结上限
     *
     * <p>★ 兜底用。估算公式是「电池容量 × 最贵的一条规则单价」，而容量是用户自己
     * 填的字段 —— 填成 99999 的话会把余额一次冻光。这个上限让那个错误变成「冻了 500」。
     */
    private static final BigDecimal MAX_FREEZE_AMOUNT = new BigDecimal("500.00");

    /**
     * 管理端「按手机号搜订单」时，前缀一次最多匹配多少个车主
     *
     * <p>★ 取满即<b>拒绝这次查询</b>，不截断。理由见
     * {@code MessageConstant.MEMBER_PREFIX_TOO_BROAD}：
     * 截断会给出一个<b>看起来正常的错误答案</b>，而搜订单的运维
     * 不会去数「是不是还有更多」。
     *
     * <p>★ 500 这个数不是算出来的，是「正常使用下永远碰不到」的量级：
     * 一个足以定位到人的前缀（比如 {@code 1380000}）通常只命中个位数，
     * 而 {@code 138} 在一个十万车主的平台上会命中几万 ——
     * 那本来就不该被当成一次查询条件。
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

    /** ★ 只给管理端查询的两个方法用 —— 车主端那条路径带 member_id 查订单，不需要回表 */
    private final MemberMapper memberMapper;

    private final VehicleMapper vehicleMapper;

    private final StationTariffService stationTariffService;

    private final WalletService walletService;

    private final MemberService memberService;

    private final BizNoGenerator bizNoGenerator;

    // ========================================================================
    // 车主端
    // ========================================================================

    /**    (non-Javadoc)
     * 创建订单 create()
     * Long memberId
     */

    @Override
    @Transactional
    public ChargingOrderVO create(OrderCreateDTO dto) {
        Long memberId = requireMemberId();
        memberService.assertActive(memberId);
        LocalDateTime now = LocalDateTime.now();

        PayMethod payMethod = resolvePayMethod(dto.getPayMethod());

        // ① 站点 / 桩 / 枪三层，逐个存在性 + 归属关系 + 可用状态
        Connector connector = assertConnectorUsable(dto);

        // ② 这把枪上不能有进行中的订单。
        //    ★ 这一次查询<只负责提前拒绝>，它给出的是「已有订单 SO-xxx」这种能直接
        //      展示给用户的文案。**它不做裁决** —— 它是一次「先查后插」，
        //      两个并发建单会同时查到「没有进行中的订单」，然后各自往下走。
        //      真正的裁决在 ⑦ 的占用条件更新上，那一步的影响行数是数据库给的。
        assertConnectorFree(dto.getConnectorId());

        // ③ 车辆可选。有车就要查归属、查这辆车有没有在充
        Vehicle vehicle = resolveVehicle(dto.getVehicleId(), memberId);
        
        // ④ ★ 冻结计费模板 —— 冻的是「此刻的价」，不是「结算时的价」。
        //    必须在任何 PageHelper.startPage 之前调，见接口注释。
        Long templateId = stationTariffService.resolveTemplateIdAt(dto.getStationId(), now);

        // ⑤ 估算要冻多少钱
        BigDecimal estimate = estimateFreezeAmount(templateId, vehicle);

        // ⑥ 建单。status=1 待启动，此刻用户随时可以取消
        ChargingOrder order = ChargingOrder.builder()
                .orderNo(bizNoGenerator.orderNo())
                .memberId(memberId)
                .vehicleId(vehicle == null ? null : vehicle.getId())
                // ★ 车牌快照。不是 JOIN vehicle 拿的实时值，见 ChargingOrder.plateNo
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

        // ⑦ ★★ 抢占这把枪 —— 「一把枪同时只能有一个订单」的真正裁决点。★★
        //    位置是三条约束共同决定的，不能挪：
        //      · 必须在 insert <之后> —— current_order_id 要写的就是刚拿到的 orderId；
        //      · 必须在冻钱 <之前> —— 钱一冻上，失败回滚就是白冻一次，还要多解冻一次；
        //      · 和建单在<b>同一个事务</b>里 —— 建单成功但抢枪失败会留下一笔
        //        占着枪却没人认领的订单。
        //    影响 0 行只有一个含义：另一个请求刚刚抢走了它，或者枪被改成了
        //    故障 / 维护（WHERE 里的 status = 1 会挡住）。两者对用户是同一件事。
        if (connectorMapper.occupy(connector.getId(), order.getId(), now) == 0) {
            throw new ConnectorOccupiedException(MessageConstant.CONNECTOR_OCCUPIED);
        }

        // ⑧ 锁权益。必须在拿到 orderId 之后 —— locked_order_id 要写的就是它
        Long packageId = tryLockPackage(memberId, order.getId(), now);
        if (packageId != null && orderMapper.bindMemberPackage(order.getId(), packageId, now) > 0) {
            order.setMemberPackageId(packageId);
        }

        // ⑨ 冻钱。余额不够会抛 BalanceNotEnoughException，整个事务回滚 ——
        //    订单、枪口占用、权益锁一起撤销，不会留下一个「建了单但没冻钱」的订单
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
        // ★ 查不到和「不是你的」返回同一个异常 —— 区分它们会让接口变成
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

        // ★ 只有 1 待启动能取消。让充电中也走这条路等于给了一条
        //   「充完电把订单作废」的路径 —— 那不是取消，是偷电。
        if (markCancelledOrConflict(order, now)) {
            releaseOrderResources(order, now);
            // ★ 枪也要放。取消是「1 待启动 → 8 已取消」，那把枪从头到尾
            //   没充过电，但不放的话它会永远停在「2 已占用」上再也点不动。
            releaseConnector(orderId, now);
            recordEvent(orderId, OrderStatus.CREATED, OrderStatus.CANCELLED,
                    OrderEventType.CANCEL, EventSource.USER, memberId, null, null, now);
        }
        log.info("订单已取消：orderId={}, memberId={}", orderId, memberId);
    }

    /**
     * ★★ 这是本类<b>唯一不调 {@code memberService.assertActive} 的车主端方法</b>，是刻意的。
     *
     * <p>其余四个（{@code create} / {@code pageMine} / {@code getMine} / {@code cancel}）
     * 都查，唯独这里不查，因为<b>补上它会制造一个解不开的死结</b>：
     * 车主被禁用时若手上有一笔 {@code 4 待支付} 的订单，钱正冻在钱包里。
     * <ul>
     *   <li>{@code cancel} 会因为 {@code assertActive} 被挡 —— 用户自己退不掉；</li>
     *   <li>{@code refund} 的 CAS 谓词是 {@code status = 5}，够不着 {@code 4 待支付}；</li>
     *   <li>于是若 {@code pay} 也挡，<b>这笔冻结资金就没有任何一条路能出去</b>。</li>
     * </ul>
     *
     * <p>★ 放行的方向也是对的：支付是<b>了结旧账</b>，不是产生新消费 ——
     * 电已经充进去了，让账号把钱付掉只会让平台收回欠款、把冻结释放掉。
     * 真正要挡住的是「被禁用了还能再建单」，那在 {@link #create} 上。
     *
     * <p>★ <b>所以别照着同类的其余方法「顺手补齐」这一行。</b>
     */
    @Override
    @Transactional
    public void pay(Long orderId) {
        ChargingOrder order = loadOrder(orderId);
        LocalDateTime now = LocalDateTime.now();

        // ① ★ 先在订单侧抢占这一步。这一步成功之后，本单的扣款流水
        //    就不可能再被写第二次 —— uk_txn_order_type 只是最后一道防线。
        int rows = orderMapper.markPaid(orderId, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.PENDING_PAY, OrderStatus.PAID);
        }

        // ② 再动钱：释放本单全部冻结，再扣实付。
        //    余额不够会抛 BalanceNotEnoughException 让整个事务回滚 ——
        //    订单退回 4 待支付，钱退回冻结。见类注释第二节。
        walletService.settle(order.getMemberId(), orderId,
                order.getFrozenAmount(), order.getPayAmount());

        recordEvent(orderId, OrderStatus.PENDING_PAY, OrderStatus.PAID,
                OrderEventType.PAY, EventSource.USER, order.getMemberId(),
                "实付 " + order.getPayAmount() + "，解冻 " + order.getFrozenAmount(),
                null, now);

        log.info("订单已支付：orderId={}, payAmount={}", orderId, order.getPayAmount());
    }

    // ========================================================================
    // 管理端查询（只读）
    // ========================================================================

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
        // ★ 这里用 getById 而不是 getByIdAndMember：管理端看的是全平台订单，
        //   没有归属可校验（接口注释里说明了为什么这不需要「同样的异常」那层考虑）
        ChargingOrder order = orderMapper.getById(orderId);
        if (order == null) {
            throw new NotFoundException(MessageConstant.ORDER_NOT_FOUND);
        }

        List<ChargingSegmentVO> segments = segmentMapper.listByOrderId(orderId)
                .stream().map(ChargingSegmentVO::from).toList();
        List<ChargingOrderEventVO> events = eventMapper.listByOrderId(orderId)
                .stream().map(ChargingOrderEventVO::from).toList();

        // ★ 派生字段查不到就是 null，不报错：车主注销 / 站点被删都不该让
        //   一笔**真实存在**的订单变得打不开。同 ChargerServiceImpl.toVos 的立场。
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
     * 把手机号前缀解析成车主 id 列表
     *
     * <p>★★ 三个返回值，语义<b>刻意不同</b>，调用方和 XML 都必须区分：★★
     * <table border="1">
     *   <caption>返回值 → 订单查询的行为</caption>
     *   <tr><th>返回</th><th>含义</th><th>结果</th></tr>
     *   <tr><td>{@code null}</td><td>没传手机号</td><td><b>不过滤</b>，返回全部订单</td></tr>
     *   <tr><td>空列表</td><td>传了，但一个车主都没匹配到</td><td><b>返回 0 行</b></td></tr>
     *   <tr><td>非空列表</td><td>收窄到这些车主</td><td>正常过滤</td></tr>
     * </table>
     *
     * <p>★ 前两种看起来只差一点，实际<b>结论相反</b>。把「没匹配到」当成
     * 「不过滤」，就会变成一个<b>看起来完全正常</b>的错误：搜一个不存在的
     * 手机号，返回全平台的订单，列表有数据、分页也对、一条错都不报。
     * 正确性由 {@code ChargingOrderMapper.xml} 里那个 {@code AND 1 = 0}
     * 分支保证，本方法只负责把两种「空」区分开。
     *
     * <p>★ {@code trim()} 是必要的：查询串里的空格是看不见的，
     * 而 {@code " 138"} 匹配不到任何手机号 —— 用户会以为「这个人没有订单」。
     *
     * @throws ParamErrorException 前缀匹配到 {@value #MEMBER_PREFIX_LIMIT} 个及以上车主。
     *         ★ <b>取满也拒绝</b>，不区分「正好 N 个」和「超过 N 个」：
     *         两种情况该做的事都是「请多输入几位」
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
     * 批量转 {@link AdminOrderVO}，并补齐车主手机号/昵称与站点名
     *
     * <p>和 {@code ChargerServiceImpl.toVos} 是同一个形状，理由也一样：
     * 一页 10 条订单逐个回表就是 20 次查询，批量 {@code IN} 只要 2 次。
     *
     * <p>★ <b>两个 {@code listByIds} 都不能传空集合</b> —— {@code IN ()} 是
     * SQL 语法错误而不是空结果，而「这一页一条订单都没有」和「一页订单
     * 全是同一个车主」都很常见。所以那两个 {@code isEmpty()} 判断是必经的。
     *
     * <p>★ <b>补不上就是 null，不报错。</b>只读路径上的派生字段失败要降级：
     * 「这行的手机号显示不出来」不该让整个订单列表 500。
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

    // ========================================================================
    // 订单流程（设备上报 / 定时任务 / 运维）
    // ========================================================================

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

        // 枪跟着进「3 充电中」。★ <b>影响 0 行只告警，不抛异常</b> ——
        //   订单才是真相，connector.status 是它的投影（见 ConnectorStatus 类注释）。
        //   这里真的抛出去的话，一笔「枪口没被正确占用」的订单就<b>永远充不了电</b>，
        //   而那是一个可观测量（下面的日志）换一个不可恢复的失败。
        //   历史数据（W0 之前建的订单没有占用记录）走的也是这条路。
        if (connectorMapper.markCharging(order.getConnectorId(), orderId, now) == 0) {
            log.warn("订单启动充电但枪口占用记录不匹配：orderId={}, connectorId={}",
                    orderId, order.getConnectorId());
        }

        // ★ 不动钱：钱在建单时已经冻好了
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

        // ★ 电量 = 电表差，不是结束读数本身。电表是累计值，
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

        // 车已经拔枪 → 枪回到「2 已占用」。★ 订单还要结算，所以<b>不释放</b> ——
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
        //    ★ 两步而不是一步：end_time 已经保住了，异常状态也拿到了，
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

        // ① 纯计算。★ 失败（电价规则有缺口）时抛异常让整个事务回滚 ——
        //    订单留在 3 待结算，可以重试。只有「重试也不会好」的才转 9，
        //    而那是一个需要人来判断的决定，不在这里自动做
        BillingResult result = bill(order);

        // ② 权益抵扣
        BigDecimal discount = ZERO;
        if (order.getMemberPackageId() != null) {
            discount = applyPackage(order, result, now);
        }
        BigDecimal payAmount = result.totalAmount().subtract(discount);

        // ③ ★ 抢占这一步，然后才写分段。顺序不能反 —— 见类注释第一节
        int rows = orderMapper.markSettled(orderId,
                result.totalEnergyFee(), result.totalServiceFee(), result.totalAmount(),
                discount, payAmount, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.SETTLING, OrderStatus.PENDING_PAY);
        }

        // ④ 分段明细。★ 撞 uk_segment_order_start 说明 ③ 漏了，那时整笔失败并告警
        segmentMapper.batchInsert(toSegments(orderId, result, now));

        // ⑤ ★★ 两次对 connector 的写入放在一起，且都在 batchInsert 之后。
        //    两个理由：
        //      · 一次事务里只取一次那一行的 X 锁，且取得尽量晚 —— 早取会拉长
        //        与「管理员改这把枪」的等待窗口（那条 update 也锁同一行）；
        //      · 原子性上放哪儿都一样：本方法整个是一个事务，这两条语句
        //        提交当且仅当 ③ 的 markSettled 提交。
        //
        //    下面是 ⑤ 的两半，各自的「为什么不会重复计数」在注释里。
        //
        //    ⑤a 释放枪口：车充完了、账也结出来了，这把枪不再归本单。
        //        ★ 释放是幂等的（谓词只有 current_order_id = orderId），
        //          所以不需要先判断「我还占着它吗」。
        releaseConnector(orderId, now);
        //
        //    ⑤b 累计充电次数 +1。
        //        ★ <b>一次性的证明</b>：本方法标了 @Transactional，自增是一条普通的
        //          mapper UPDATE —— 它提交当且仅当 ③ 的 CAS 提交。④ 撞唯一键时
        //          整笔回滚（含 ③ 和这一条），重试从状态 3 重新开始，最终恰好加一次。
        //          ★ 因此绝不可把这一条改成 REQUIRES_NEW / @Async / 自注入代理调用，
        //          那三种写法都会让它在 CAS 之外独立提交，变成重复计数。
        //        ★ 它数的是「结算成功的次数」，不是「车充过电的次数」：经 end() 的
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

        // ★ 只有 5 已支付能退。4 待支付的时候钱还没扣，
        //   那时候要「退」的是冻结的钱，走的是取消 / 挂账
        int rows = orderMapper.markRefunded(orderId, amount, now, now);
        if (rows == 0) {
            throw statusConflict(order, OrderStatus.PAID, OrderStatus.REFUNDED);
        }

        // ★ 必须排在 CAS 之后 —— 和 pay 里那条理由一样。
        //   金额为正的校验在 walletService.refund 里，传负数会在那里被拒
        walletService.refund(order.getMemberId(), orderId, amount);

        recordEvent(orderId, OrderStatus.PAID, OrderStatus.REFUNDED,
                OrderEventType.REFUND, EventSource.OPERATOR, currentOperatorId(),
                "退款 " + amount, null, now);

        log.info("订单已退款：orderId={}, amount={}", orderId, amount);
    }

    /**
     * 把订单转成 {@code 6 支付失败}
     *
     * <p>★★ <b>必须在一个<b>新的</b>事务里调用</b> —— 也就是 {@link #pay} 抛了
     * {@link BalanceNotEnoughException} 的那个事务<b>已经回滚之后</b>。理由见类注释
     * 第二节：那个异常抛出时旧事务已经被标记 rollback-only，写这一行是写不进去的。
     *
     * <p>★ 调用方的正确写法（Controller 没有事务，天然合适）：
     * <pre>
     *   try {
     *       orderService.pay(orderId);
     *   } catch (BalanceNotEnoughException e) {
     *       orderService.markPayFailed(orderId, e.getMessage());   // 新事务
     *   }
     * </pre>
     *
     * <p>★ 影响行数 0 是<b>正常</b>的（比如别的线程已经把它推到 5 了），所以本方法
     * 不抛异常：它记的是「这一次尝试失败了」，而不是「订单必须变成失败态」。
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

    // ========================================================================
    // 校验
    // ========================================================================

    private Long requireMemberId() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException();
        }
        return memberId;
    }

    /** 运维 id。★ 设备上报和定时任务没有操作人，返回 null 而不是硬编一个 0 */
    private Long currentOperatorId() {
        return BaseContext.getCurrentId();
    }

    private PayMethod resolvePayMethod(Integer code) {
        PayMethod method = code == null ? PayMethod.BALANCE : PayMethod.of(code);
        if (!PayMethod.isSupported(method)) {
            // ★ 明确拒绝，不是静默降级成余额 —— 见 PayMethod 的类注释
            throw new ParamErrorException(MessageConstant.ORDER_PAY_METHOD_UNSUPPORTED
                    + "：" + method.getLabel());
        }
        return method;
    }

    /**
     * 站点 / 桩 / 枪三层校验
     *
     * <p>★ 三层全查，而且查<B>归属关系</B>：枪挂在桩上、桩挂在站上。只查枪的话，
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

        // ★ 只有「空闲」的枪能建单。已占用 / 充电中由订单流程维护，
        //   离线 / 故障 / 维护是设备侧的状态
        //
        //   ★ 这里用的是 ConnectorStatus 而不是 StatusConstant —— 两者都有一个
        //     值等于 1，但含义完全不同（枪的空闲码是 1，而 StatusConstant.ENABLE
        //     讲的是「这一行有没有被删除」）。混用会写出一个**能跑但读起来是错的**
        //     判断：改天 ConnectorStatus 的码值变了，那个判断不会跟着变。
        ConnectorStatus connectorStatus = ConnectorStatus.of(connector.getStatus());
        if (connectorStatus != ConnectorStatus.IDLE) {
            throw new DataConflictException(MessageConstant.CONNECTOR_OCCUPIED
                    + "（当前：" + connectorStatus.getLabel() + "）");
        }

        // ★ 桩也要查：枪空闲但桩离线时，建出来的单永远等不到设备上报
        ChargerStatus chargerStatus = ChargerStatus.of(charger.getStatus());
        if (chargerStatus != ChargerStatus.IDLE) {
            throw new DataConflictException("充电桩当前不可用（" + chargerStatus.getLabel()
                    + "）：chargerId=" + charger.getId());
        }
        return connector;
    }

    /**
     * 这把枪上不能有进行中的订单
     *
     * <p>★ 用 {@code listHoldingByConnector} 而不是「查一条」：返回超过 1 条时
     * <b>必须停下来</b> —— 随便挑一笔继续的话，另一笔会永远停在充电中，而它的钱还冻着。
     *
     * <p>★ 这里查得到的概率很低（枪的状态已经拦了一道），但它挡的是另一种情况：
     * <b>枪的状态被人工改错了</b>，或者历史脏数据。状态列是缓存，订单才是真相 ——
     * 和 {@code MemberPackageStatus} 的类注释里那条结论是同一个道理。
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
            // ★ 扫码即充：没绑车也能充。订单的 vehicle_id 和 plate_no 都是 NULL，
            //   详情页展示成「未绑定车辆」—— 那是一个合法的状态，不是数据缺失
            return null;
        }
        // ★ getByIdAndMember 而不是 getById：归属校验在 SQL 里完成，
        //   「忘了校验」在这个形状下写不出来（忘传 memberId 是查不到）
        Vehicle vehicle = vehicleMapper.getByIdAndMember(vehicleId, memberId);
        if (vehicle == null) {
            throw new NotFoundException(MessageConstant.VEHICLE_NOT_FOUND);
        }

        // ★ 同一时刻一辆车只能有一笔进行中的订单。拦不住的话，
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
     * 预估要冻结多少钱
     *
     * <p>★★ <b>这是一个上界，不是一个估计值。</b>★★
     *
     * <p>公式是「电池容量 × 该模板下最贵的一条规则（电费 + 服务费）」。取<b>最贵</b>
     * 而不是平均，是因为冻结的意义是「先把可能花掉的钱占住」—— 按最贵算，结算时的
     * 实付必然小于等于它，走「差额从可用余额出」那条路的概率降到最低。
     *
     * <p><b>本项目对这条公式的取舍：</b>真实的预估会结合「当前 SOC + 目标 SOC +
     * 桩的功率 + 预计时长」，而本项目的输入里没有 SOC（那是设备上报的），所以只能按
     * 容量取上界。代价是小额补电也会冻掉一整块余额 —— 那是<b>暂时占用</b>，
     * 结算后会精确按实付扣除，不是扣款。
     *
     * <p>★ 容量来自 {@code vehicle.battery_capacity_kwh}，是<b>用户自己填的</b>，
     * 所以要压 {@link #MAX_FREEZE_AMOUNT} 上限。
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
     * 选一份可用权益并锁定它，返回锁到的 {@code member_package.id}
     *
     * <p>★ <b>必须「查询 → 逐个试锁 → 失败取下一份」</b>，不能「查询 → 取第一份 →
     * 锁」：查询和锁定之间别的线程可能已经把第一份抢走了，那时正确的动作是取下一份
     * 重试，而不是直接失败 —— 见 {@code MemberPackageMapper.listUsable} 的注释。
     *
     * <p>★ 一层套一层的顺序是：<b>先按 {@link PackageType} 的枚举顺序挑类型，
     * 同一类型内再按「先到期的先用」挑那一份</b>（后者由 {@code listUsable} 的
     * {@code ORDER BY expire_time} 给出，不在这里重复实现）。同一类型里的第一份
     * 没抢到就取下一份；整个类型都没抢到就换下一个类型 —— 用户手上只有时长包时，
     * 不该因为「电量包这一类空着」就把他的权益跳过。
     *
     * <p>★ 一份都没锁到时返回 {@code null}，订单按余额直充走 —— <b>这不是失败</b>：
     * 没有权益的用户本来就应该能用余额充电。
     */
    private Long tryLockPackage(Long memberId, Long orderId, LocalDateTime now) {
        for (PackageType type : PackageType.values()) {
            List<MemberPackage> candidates = memberPackageMapper.listUsable(
                    memberId, type.getCode(), now);
            for (MemberPackage candidate : candidates) {
                if (memberPackageMapper.lockForOrder(candidate.getId(), orderId, now) > 0) {
                    log.info("订单锁定权益：orderId={}, packageId={}, type={}",
                            orderId, candidate.getId(), type.getLabel());
                    // ★ 锁到就返回 —— 订单只有一个 member_package_id 列，
                    //   不许叠加两种权益，那会让「用户实际被优惠了多少」
                    //   变成一个需要按顺序解释的计算过程
                    return candidate.getId();
                }
                // 被别人抢走了，试下一份
            }
            // ★ 这里**不能** break。曾经写过一个无条件的 break，本意是「一类里锁到
            //   一份就够了」，但它在「这一类一份都没锁到」时也会跳出 —— 于是只持有
            //   时长包 / 次卡 / 折扣包的用户，权益被静默忽略（枚举顺序把电量包排在最前）。
            //   用户看到的是「我的套餐没用上」，而日志里什么都没有。
            //   真正的「只锁一份」由上面的 return 保证。
        }
        return null;
    }

    // ========================================================================
    // 结算
    // ========================================================================

    /**
     * 跑计费引擎
     *
     * <p>★ 用的是订单上<b>冻结的</b> {@code tariff_template_id} 去取规则，不是
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
     * 权益抵扣，返回<b>抵扣金额</b>
     *
     * <h2>★★ 产品假设：权益抵扣的是「电费」，服务费照收 ★★</h2>
     *
     * <p>这个规则<b>没有被任何上游文档规定过</b>，是实现时定下来的，理由：
     * 电费是平台代收后要付给电网的钱，权益包是平台补贴给用户的部分，
     * 服务费才是平台的收入。所以权益不覆盖服务费。
     * <b>要改成「权益覆盖全部费用」的话，只改这一个方法。</b>
     *
     * <p>四种权益给出一个<b>统一的「覆盖率」</b>，抵扣 = {@code round2(电费 × 覆盖率)}：
     *
     * <pre>
     *   电量包 type=1  覆盖率 = min(实际电量, 剩余电量) / 实际电量
     *   时长包 type=2  覆盖率 = min(实际时长, 剩余时长) / 实际时长
     *   次卡   type=4  覆盖率 = 1            （一次充电就是一整笔，这是次卡的定义）
     *   折扣包 type=3  不按覆盖率：抵扣 = round2(服务费 × (1 − 折扣系数))
     * </pre>
     *
     * <p>★ 为什么用覆盖率乘<b>电费合计</b>，而不是把覆盖的电量逐段分摊：逐段分摊要在
     * 每段各舍入一次，误差会累加，而账单上只显示一个优惠金额 —— 用户核不出那个数是
     * 哪来的。电费合计只有一个数，一次舍入，可核对。
     *
     * <p>★ 覆盖率封顶 1：剩余量大于实际用量时，抵扣不能超过电费本身 —— 否则
     * {@code pay_amount} 会变成负数，也就是<b>充电反而给用户钱</b>。
     * 多出来的余量留在权益里，下次继续用。
     *
     * <h2>★★ 抵扣多少，就扣减多少 —— 两个数必须来自同一个量 ★★</h2>
     *
     * <p>{@code covered} 是「这次真正用掉的权益量」：{@code min(实际用量, 剩余量)}，
     * 它同时决定<b>抵扣金额</b>和<b>扣减的余量</b>。
     *
     * <p>★ 曾经这里是两个独立的数：抵扣按 {@code min(用量, 余量)} 算出覆盖率，
     * 扣减却直接扣<b>全部用量</b> —— 于是一份只剩 4 度电的包在用户充了 10 度之后
     * {@code remaining_kwh} 变成 <b>-6</b>。症状全是静默的：用户没多付（抵扣是按
     * 4 度算的），平台在账面上却「欠用户 6 度」；而且那一行
     * {@code remaining_kwh > 0} 不再成立，会被 {@code listUsable} 永久跳过，
     * 用户永远查不出那 6 度去哪了。
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

        // ★ 折扣包不消耗余量，没有「用掉多少」可言，单独一条
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

        // ★ 抵扣 = 电费 × covered/used。因为 covered <= used，覆盖率天然 <= 1
        BigDecimal discount = ZERO;
        if (used != null && used.signum() > 0 && covered.signum() > 0) {
            BigDecimal coverage = covered.divide(used, 6, RoundingMode.HALF_UP);
            discount = result.totalEnergyFee()
                    .multiply(coverage)
                    .setScale(2, BillingEngine.ROUNDING);
        }

        // ★ 抵扣不能超过应付。多出来的部分不退还、不结转 ——
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
            // ★ 库里存的是分钟，这里换算成秒 —— 分母（实际时长）是秒
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
     * <p>★ 两个封顶都是必需的：<b>不超过 {@code used}</b>（否则抵扣会超过电费本身，
     * {@code pay_amount} 变负数）、<b>不超过 {@code remain}</b>（否则余量被扣成负数）。
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

        // ★ 时长包按【整分钟】扣减（settleDuration 的参数就是分钟），
        //   所以这里先向下取整到分钟。不取整的话抵扣按 90 秒算、
        //   扣减只扣 1 分钟，两个数又对不上了 —— 差额是平台白送
        if (type == PackageType.DURATION) {
            covered = covered.divide(BigDecimal.valueOf(60), 0, RoundingMode.DOWN)
                    .multiply(BigDecimal.valueOf(60));
        }
        return covered;
    }

    /**
     * 扣减权益，扣的必须是 {@link #applyPackage} 算出来的那个 {@code covered}
     *
     * <p>★ 三件事在 {@code settle*} 那一条 SQL 里完成：扣量、释放锁、用尽则改状态 ——
     * 拆开写的话，中间任何一步失败都会留下不一致。
     *
     * <p>★ 影响行数 0 = 权益不在本单锁定的状态下（被别人抢了 / 锁被清了）。这时
     * <b>不抛异常</b>：钱已经按抵扣后的价收过了，现在抛会让整笔结算回滚，而用户已经
     * 充完电走了。记一条告警由对账发现 —— 那是「权益少扣了」，用户没损失，平台有。
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

    // ========================================================================
    // 释放与挂账
    // ========================================================================

    /**
     * 释放订单占用的资源：钱包冻结 + 权益锁
     *
     * <p>★ 两样都要放，而且要在<b>同一个事务</b>里和状态变更一起 —— 少任何一样都会
     * 留下一个「钱/权益冻着但订单已经结束」的状态，而那种状态没有任何东西会去清理它。
     */
    private void releaseOrderResources(ChargingOrder order, LocalDateTime now) {
        // ① 解冻。★ 冻结额可能为 0（全额权益抵扣），那时 walletService.freeze
        //    当初就没冻也没写流水，这里对应地什么都不做 ——
        //    解冻 0 元会写出一条金额为 0 的流水，给对账添噪音
        if (order.getFrozenAmount() != null && order.getFrozenAmount().signum() > 0) {
            walletService.unfreeze(order.getMemberId(), order.getId(), order.getFrozenAmount());
        }

        // ② 释放权益锁。★ 余量不动 —— 取消订单不该消耗任何权益
        if (order.getMemberPackageId() != null) {
            memberPackageMapper.releaseLock(order.getMemberPackageId(), order.getId(), now);
        }
    }

    /**
     * 释放订单占用的充电枪
     *
     * <p>★ <b>刻意和 {@link #releaseOrderResources} 分开，不合并。</b>
     * 取消订单两样都要做，而<b>结算只做这一样</b> —— 结算时钱绝不能解冻，
     * 它马上就要被待支付扣掉。合并成一个「释放所有资源」的方法之后，
     * 必然有人在结算路径上顺手调它，把那笔钱解冻掉。
     *
     * <p>★ 底层 UPDATE 的谓词只有 {@code current_order_id = orderId}，
     * 所以这个方法<b>幂等</b>：重复调、对一把从未被本单占用的枪调，都是 0 行、无副作用。
     * 正因如此，{@code cancel} / {@code settle} / {@link #markAbnormal}
     * 三处可以各自直接调它，不必先判断「这笔订单还占着枪吗」——
     * 而那个判断在三处里会写成三种略有出入的样子。
     */
    private void releaseConnector(Long orderId, LocalDateTime now) {
        connectorMapper.release(orderId, now);
    }

    /**
     * 释放这笔订单占着的权益锁（如果有）
     *
     * <p>★ <b>和 {@link #releaseConnector} 是同一形状的第三样资源释放</b>，
     * 而且它的必要性也是同一个论证：底层 UPDATE 的谓词是
     * {@code locked_order_id = orderId}，<b>幂等、自守卫</b> ——
     * 重复调、对一笔从未锁过权益的订单调，都是 0 行、无副作用。
     *
     * <p>★ <b>为什么需要单独一个方法，而不是复用 {@link #releaseOrderResources}：</b>
     * 那个方法还会<b>解冻</b>，而挂账恰恰是「钱还得冻着等人工」的语义 ——
     * 解冻会把一笔本该被扣掉或退回的钱提前放走。这条边界和
     * {@code releaseConnector} 为什么不合并是同一条。
     *
     * <p>★ 也<b>不能</b>换成 {@code memberPackageMapper.releaseLock(packageId, ...)}：
     * 那个要求先知道是哪一份权益，也就是要先把订单整行读出来拿
     * {@code member_package_id}。「哪一行被这笔订单锁着」这个问题，
     * 权益表那一侧（{@code locked_order_id}，带唯一索引）就能回答。
     *
     * <p>★ <b>不碰余量、不碰状态</b> —— 挂账的订单没有结算过（结算失败才会挂账），
     * 所以那份权益一点没被消耗，释放锁之后应该原样可用。
     *
     * @see com.chargeops.mapper.MemberPackageMapper#releaseLockByOrder
     */
    private void releasePackageLock(Long orderId, LocalDateTime now) {
        memberPackageMapper.releaseLockByOrder(orderId, now);
    }

    /**
     * 转异常挂账。★ 带原因，没有原因的挂账单只能靠人翻日志
     *
     * <p>★ 来源集合是 {@link OrderStatus#holdingMoneyCodes()} ——「钱还冻着但账结不掉」
     * 的那几个状态。<b>不能</b>用 {@code holdingConnectorCodes()}：那个只有 {1,2,3}，
     * 而挂账最常见的两个场景（4 余额不够、6 重试仍失败）恰恰在那个集合外面，
     * 挂不上去，而且静默失败。
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

        // ★ 释放枪口。来源集合是 {1,2,3,4,6} —— 其中 <b>4 和 6 的枪早就释放过了</b>
        //   （结算时就放了），只有 {1,2,3} 还占着。这里不做分支判断：
        //   release 的谓词是 current_order_id = orderId，对已释放的枪影响 0 行。
        //   分成两支写的话，两支里错一支的症状是「枪永远占着」，而且不报错。
        releaseConnector(orderId, now);

        // ★★ 释放权益锁。**和上面那一行是同一件事，第三样资源。**★★
        //
        //   它曾经漏了，而漏的方式和上面那行注释预言的完全一样：
        //   来源 {1,2,3,4,6} 里 {4,6} 的权益锁早被 settle 释放过（settle* 的 SET
        //   里有 locked_order_id = NULL），只有 {1,2,3} 还占着 ——
        //   而 end() 的电表异常路径（3 → 9）是一条真实可达的路。
        //   漏放的后果<b>不是报错，是那一行 locked_order_id 永久留在那里</b>：
        //     · listUsable 的 locked_order_id IS NULL 从此永久跳过这份权益 ——
        //       车主的套餐还在，但再也不会被任何订单选中（「明明有 150 度，
        //       结算却按余额扣钱」）；
        //     · 本轮的退款路径也会被挡在 voidPackage 的 locked_order_id IS NULL 外面
        //       —— 一份**一点都没被用过**的权益永远退不了款。
        //
        //   ★ 这里同样不做分支判断，理由和上面一模一样：谓词是
        //     locked_order_id = orderId，对 {4,6} 那些早已释放的订单影响 0 行。
        //   ★ 用的是 releaseLockByOrder（按订单号找），不是 releaseLock（要先知道
        //     是哪一份）—— 后者要求先把订单整行读出来拿 member_package_id，
        //     而「哪一行被这笔订单锁着」这个问题，权益表那一侧就能回答。
        releasePackageLock(orderId, now);

        recordEvent(orderId, null, OrderStatus.ABNORMAL,
                OrderEventType.ABNORMAL, EventSource.SCHEDULED, null, reason, null, now);
    }

    // ========================================================================
    // 读写订单 + 事件
    // ========================================================================

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
     * 状态条件更新返回 0 行时抛的异常
     *
     * <p>★ 消息里带上<b>当前状态</b>和<b>期望状态</b>：只写「操作失败」的话，用户和
     * 客服都无法判断这是并发（刷新重试即可）还是用错了流程（比如想取消一笔已经在充电
     * 的订单），而那两件事要说的话完全不同。
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
