package com.chargeops.service;

import com.chargeops.dto.AdminOrderPageQueryDTO;
import com.chargeops.dto.OrderCreateDTO;
import com.chargeops.dto.OrderEndDTO;
import com.chargeops.dto.OrderPageQueryDTO;
import com.chargeops.dto.OrderStartDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.AdminOrderDetailVO;
import com.chargeops.vo.ChargingOrderDetailVO;
import com.chargeops.vo.ChargingOrderVO;

import java.math.BigDecimal;

/**
 * 充电订单服务 —— 订单状态机
 *
 * <h2>★★ 一、方法按「谁在调」分成两组，这两组的权限边界是硬的 ★★</h2>
 *
 * <pre>
 *   车主端（身份从 token 取）        订单流程（没有登录用户）
 *   ────────────────────────       ──────────────────────────
 *   create    建单                   start   设备上报启动
 *   pageMine  我的订单                end     设备上报结束
 *   getMine   订单详情                settle  结算
 *   cancel    取消（仅待启动）         pay     支付
 *   pay       支付（仅待支付）         refund  退款
 * </pre>
 *
 * <p>★ 右列<b>不能</b>暴露给车主端 —— <b>它能让用户自己决定金额</b>：
 * {@code start} / {@code end} 收的是电表读数，用户能填就等于能决定
 * 自己用了多少电。本项目挂在管理端（{@code /api/admin/orders/**}）
 * 由运维代为录入。{@code pay} 是例外：车主自己点支付和定时任务补偿
 * 支付都是正常的，走同一个方法。
 *
 * <h2>★ 一点五、第三组：管理端查询（只读）</h2>
 *
 * <p>{@code pageForAdmin} / {@code getForAdmin} 是<b>只读</b>的，
 * 但它们的归属和前两组都不同：<b>不做归属校验，且返回的是另一套 VO</b>
 * （{@link com.chargeops.vo.AdminOrderVO} /
 * {@link com.chargeops.vo.AdminOrderDetailVO}，多出车主、站点名、冻结金额、
 * 计费模板等运维字段）。
 *
 * <p>★ <b>为什么不复用车主端的两个方法加一个布尔开关</b>：
 * 「要不要校验归属」和「返回哪套字段」是<b>两个独立的问题</b>，
 * 用一个 {@code boolean admin} 把它们绑在一起，将来任何一边要独立变化
 * （比如加一个「客服只能看自己负责的站点」的中间态）都要拆开它。
 * 两个方法各自只回答一个问题。
 *
 * <p>★★ <b>它同时补上了一个真实缺口</b>：在 {@code pageForAdmin} 之前，
 * {@code /api/admin/**} 下没有任何订单查询接口，而
 * {@link #start} / {@link #end} / {@link #settle} / {@link #refund}
 * 全都要一个 {@code {id}} —— <b>四个按钮都在，却没有页面能拿到那个 id</b>，
 * 充电链路在管理端整条不可达。
 *
 * <h2>★★ 二、每一步的幂等都靠「订单状态的条件更新」★★</h2>
 *
 * <p>{@code UPDATE ... WHERE id = ? AND status = <来源>}，
 * 影响行数 0 = 这一步已经有人做过了，或者现在轮不到你做。
 * 返回 0 时抛 {@code DataConflictException}。
 *
 * <p>★ <b>绝不能 catch 钱包侧的 {@code DuplicateKeyException} 然后当作成功。</b>
 * 那个异常抛出时事务已经被标记回滚了，catch 住它提交时照样失败。
 * 幂等必须在<b>订单侧</b>完成，钱包侧的唯一索引是最后一道防线。
 * 完整论证见 {@link WalletService} 的类注释。
 *
 * <h2>★ 三、结算顺序：先解冻、再扣款，中间不能有别的</h2>
 *
 * <p>{@code settle} 只负责算出金额并写进订单，<b>不动钱</b>；动钱的是 {@code pay} ——
 * 它调 {@link WalletService#settle}，而那一步内部是「先释放本单全部冻结 → 再扣实付金额」。
 *
 * <p>对余额支付来说 {@code settle} 和 {@code pay} 在<b>同一个事务</b>里连着发生
 * （4 待支付是瞬间穿过的）。拆成两步写而不是合并成一个方法，是为了让微信/支付宝那种
 * 「等回调」的支付方式有地方停 —— 那笔订单会在 4 待支付 停住，等一个可能永远不来的回调。
 */
public interface ChargingOrderService {

    // ==================== 车主端 ====================

    /**
     * 建单
     *
     * <p>四件事全在<b>同一个事务</b>里：解析并<b>冻结</b>计费模板 id
     * （{@code resolveTemplateIdAt} —— 冻的是「下单这一刻的价」，不是「结算那一刻的价」）
     * → 选一份可用权益并<b>锁定</b>它（{@code lockForOrder}，查询和锁定之间可能被别人
     * 抢走，那时取下一份重试）→ 冻结预估金额（{@code walletService.freeze}）
     * → 写订单 + 写 {@code create} 事件。
     *
     * <p>★ 订单状态是 {@code 1 待启动}。设备上报启动之前，用户随时可以取消。
     *
     * @throws com.chargeops.exception.NotFoundException           站点 / 桩 / 枪 / 车辆不存在
     * @throws com.chargeops.exception.DataConflictException       枪被占用、车辆有进行中订单
     * @throws com.chargeops.exception.TariffNotCoveredException   该站点此刻没有生效的计费方案
     * @throws com.chargeops.exception.BalanceNotEnoughException   余额不足以支付预估冻结额
     */
    ChargingOrderVO create(OrderCreateDTO dto);

    /** 我的订单（分页，按建单时间倒序） */
    PageResult pageMine(OrderPageQueryDTO query);

    /**
     * 我的订单详情（含分段明细和流转历史）
     *
     * <p>★ 查不到和「不是你的订单」返回<b>同一个</b> {@code NotFoundException} ——
     * 区分它们会让接口变成「这个订单号存在吗」的探测器。
     */
    ChargingOrderDetailVO getMine(Long orderId);

    /**
     * 取消订单：{@code 1 待启动 → 8 已取消}
     *
     * <p>★ <b>只有待启动能取消。</b>已经开始的充电不能取消 —— 那是「结束」，而且已经
     * 产生了电量和费用（见 {@code ChargingOrderMapper.markCancelled}）。
     *
     * <p>★ 取消必须<b>同时</b>做三件事，同一个事务：释放钱包冻结、释放权益锁、改订单
     * 状态。少任何一件都会留下一个「钱冻着但订单已经没了」的状态。
     */
    void cancel(Long orderId);

    /**
     * 支付：{@code 4 待支付 / 6 支付失败 → 5 已支付}，并完成钱包扣款
     *
     * <p>★ 顺序是<b>先改订单状态，再动钱包</b>，且同事务 —— 反过来（先动钱包）会让
     * 重复支付撞 {@code uk(order_id, type)}，而那个异常一旦抛出，整个事务已经回滚了。
     *
     * <p>★ 来源<b>包含 6 支付失败</b>：用户充完值回来点第二次支付时，订单正停在那里
     * （{@link #markPayFailed} 就是把它推到 6 的）。不认 6 的话，「再付一次」那个按钮
     * 会永远返回状态不对，而钱一直冻着。
     *
     * @throws com.chargeops.exception.BalanceNotEnoughException 可用余额不足。
     *         ★ 此时整个事务回滚，订单退回 4 待支付、钱退回冻结 ——
     *         <b>钱没丢</b>，充值后可以再点一次支付。调用方要把它转成
     *         {@code 6 支付失败} 的话，必须在<b>新事务</b>里调 {@link #markPayFailed}
     */
    void pay(Long orderId);

    // ==================== 管理端查询（只读） ====================

    /**
     * 管理端订单分页
     *
     * <p>★ <b>不做归属校验</b>：管理端看的是全平台的订单，
     * 这正是它和 {@link #pageMine} 的全部区别。
     *
     * <p>★★ <b>{@code memberPhone} 是前缀匹配，且匹配不到车主时返回空结果。</b>★★
     * 这个分支必须显式处理：「没有车主匹配这个前缀」的正确答案是
     * <b>没有订单</b>，而如果让它落进「不带车主条件」那一支，
     * 就会变成<b>返回全平台订单</b> —— 一个看起来完全正常的错误答案。
     * 处理方式见 {@code ChargingOrderMapper.pageForAdmin}。
     *
     * @throws com.chargeops.exception.ParamErrorException 手机号前缀匹配到的车主过多，
     *         <b>而不是</b>截断后照常返回。完整理由见
     *         {@code MessageConstant.MEMBER_PREFIX_TOO_BROAD}
     */
    PageResult pageForAdmin(AdminOrderPageQueryDTO query);

    /**
     * 管理端订单详情（含分段明细和流转历史）
     *
     * <p>★ 查不到抛 {@code NotFoundException}。<b>这里不需要「和不是你的订单
     * 返回同一个异常」那层考虑</b> —— 管理端本来就有权看任何一笔订单，
     * 没有可探测的信息。
     *
     * <p>★ 补齐的三个字段（车主手机号/昵称、站点名）可能查不到，
     * 那时返回 {@code null} 而不是报错：车主注销、站点被删都不该让
     * <b>一笔真实的订单</b>变得打不开。
     */
    AdminOrderDetailVO getForAdmin(Long orderId);

    // ==================== 订单流程（设备上报 / 定时任务 / 运维） ====================

    /**
     * 把订单转成 {@code 4 待支付 → 6 支付失败}
     *
     * <p>★★ <b>必须在一个新的、独立的事务里调用</b> —— 也就是
     * {@link #pay} 抛了 {@code BalanceNotEnoughException} 的那个事务
     * <b>已经回滚之后</b>。★★
     *
     * <p>原因：那个异常是 RuntimeException，Spring 的事务拦截器在它抛出的<b>瞬间</b>
     * 就把事务标记成 rollback-only 了。在同一个事务里 catch 住它再写这一行，提交时照样
     * 整体回滚 —— 只是失败的位置从「余额不足那一行」挪到了「提交那里」，
     * 排查时会看到一个和真正原因毫无关系的报错。
     *
     * <p>调用方的正确写法（Controller 没有事务，天然就是正确的位置）：
     *
     * <pre>
     *   try {
     *       orderService.pay(orderId);
     *   } catch (BalanceNotEnoughException e) {
     *       orderService.markPayFailed(orderId, e.getMessage());
     *   }
     * </pre>
     *
     * <p>★ 影响行数为 0 是<b>正常</b>的（订单可能已经被别的线程推到 5 了），
     * 所以本方法<b>不抛异常</b>：它记的是「这一次尝试失败了」，
     * 不是「订单必须变成失败态」。
     */
    void markPayFailed(Long orderId, String reason);

    /**
     * 启动充电：{@code 1 待启动 → 2 充电中}
     *
     * <p>★ 钱在<b>建单时</b>已经冻结了，这一步不动钱。
     */
    void start(Long orderId, OrderStartDTO dto);

    /**
     * 结束充电：{@code 2 充电中 → 3 待结算}
     *
     * <p>★ 只写「充电结束」这个事实，<b>不写金额</b>：结算可能失败（电价有缺口、
     * 电量数据缺失），而失败不应该让「车确实被充过」这件事回滚掉。
     *
     * <p>★ 电量 = {@code end - start}，<b>不是</b> {@code end} 本身 ——
     * 电表是累计读数，不会因为换了辆车就归零。
     */
    void end(Long orderId, OrderEndDTO dto);

    /**
     * 结算：{@code 3 待结算 → 4 待支付}
     *
     * <p>跑 {@code BillingEngine}，写分段明细，定下金额三兄弟。<b>不动钱。</b>
     *
     * <p>★ {@code total_amount} 必须写 {@code BillingResult.totalAmount()}，绝不能写
     * 「总电量 × 综合单价」（见 {@code BillingResult} 的类注释）。
     *
     * <p>★ 结算失败（电价有缺口）时抛异常 → 整个事务回滚，订单留在 {@code 3 待结算}，
     * 可以重试。只有「重试也不会好」的失败才转 {@code 9}。
     */
    void settle(Long orderId);

    /**
     * 退款：{@code 5 已支付 → 7 已退款}
     *
     * <p>★ 只有 {@code 5 已支付} 能退。{@code 4 待支付} 走的是取消/挂账，
     * 那时候钱还没扣。
     *
     * @param amount 退款金额，必须为正。★ 传负数等于从用户账上偷钱，
     *               那属于「管理员调账」，不走退款
     */
    void refund(Long orderId, BigDecimal amount);
}
