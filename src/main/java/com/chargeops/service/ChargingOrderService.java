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
 * <p> 右列不能暴露给车主端 —— 它能让用户自己决定金额：{@code start} / {@code end}
 * 收的是电表读数，用户能填就等于能决定自己用了多少电。本项目把这两个挂在管理端
 * （{@code /api/admin/orders/**}）由运维代为录入。{@code pay} 是例外：车主自己点支付
 * 和定时任务补偿支付都是正常的，走同一个方法。
 *
 * <p> {@code pageForAdmin} / {@code getForAdmin} 是管理端的只读查询，和上面两组都不同：
 * 不做归属校验，返回的是另一套 VO（多出车主、站点名、冻结金额、计费模板等运维字段）。
 *
 * <p> 每一步的幂等都靠「订单状态的条件更新」：{@code UPDATE ... WHERE id = ? AND status = <来源>}，
 * 影响行数 0 = 这一步已经有人做过了，或者现在轮不到你做，返回 0 时抛
 * {@code DataConflictException}。绝不能 catch 钱包侧的 {@code DuplicateKeyException}
 * 然后当作成功 —— 那个异常抛出时事务已经被标记回滚了，catch 住它提交时照样失败。
 *
 * <p> 钱包全程只被碰两次：创建订单时 {@code freeze}，支付时 {@code WalletService.settle}
 * （内部是「先释放本单全部冻结 → 再扣实付金额」）。{@code settle} 只负责算出金额并写进
 * 订单，不动钱。对余额支付来说 {@code settle} 和 {@code pay} 在同一个事务里连着发生
 * （4 待支付是瞬间穿过的），拆成两步写是为了让微信/支付宝那种「等回调」的支付方式
 * 有地方停 —— 那笔订单会在 4 待支付 停住，等一个可能永远不来的回调。
 */
public interface ChargingOrderService {

    // ---------- 车主端 ----------

    /**
     * 建单
     *
     * <p>四件事全在同一个事务里：解析并冻结计费模板 id
     * （{@code resolveTemplateIdAt} —— 冻的是「下单这一刻的价」，不是「结算那一刻的价」）
     * → 选一份可用权益并锁定它（{@code lockForOrder}，查询和锁定之间可能被别人
     * 抢走，那时取下一份重试）→ 冻结预估金额 → 写订单 + 写 {@code create} 事件。
     *
     * <p> 订单状态是 {@code 1 待启动}。设备上报启动之前，用户随时可以取消。
     *
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
     * <p> 查不到和「不是你的订单」返回同一个 {@code NotFoundException} ——
     * 区分它们会让接口变成「这个订单号存在吗」的探测器。
     */
    ChargingOrderDetailVO getMine(Long orderId);

    /**
     * 取消订单：{@code 1 待启动 → 8 已取消}
     *
     * <p> 只有待启动能取消。已经开始的充电不能取消 —— 那是「结束」，而且已经产生了电量
     * 和费用。
     *
     * <p> 取消必须同时做三件事，同一个事务：释放钱包冻结、释放权益锁、改订单状态。
     * 少任何一件都会留下一个「钱冻着但订单已经没了」的状态。
     */
    void cancel(Long orderId);

    /**
     * 支付：{@code 4 待支付 / 6 支付失败 → 5 已支付}，并完成钱包扣款
     *
     * <p> 顺序是先改订单状态，再动钱包，且同事务 —— 反过来（先动钱包）会让重复支付撞
     * {@code uk(order_id, type)}，而那个异常一旦抛出，整个事务已经回滚了。
     *
     * <p> 来源包含 6 支付失败：用户充完值回来点第二次支付时订单正停在那里
     * （{@link #markPayFailed} 就是把它推到 6 的）。不认 6 的话，「再付一次」那个按钮
     * 会永远返回状态不对，而钱一直冻着。
     *
     * @throws com.chargeops.exception.BalanceNotEnoughException 可用余额不足。
     * 此时整个事务回滚，订单退回 4 待支付、钱退回冻结 —— 充值后可以再点一次支付。
     * 调用方要把它转成 {@code 6 支付失败} 的话，必须在新事务里调 {@link #markPayFailed}
     */
    void pay(Long orderId);

    // ---------- 管理端查询（只读） ----------

    /**
     * 管理端订单分页
     *
     * <p> {@code memberPhone} 是前缀匹配，且匹配不到车主时返回空结果。
     * 这个分支必须显式处理：「没有车主匹配这个前缀」的正确答案是没有订单，
     * 而如果让它落进「不带车主条件」那一支，就会变成返回全平台订单 ——
     * 一个看起来完全正常的错误答案。处理方式见 {@code ChargingOrderMapper.pageForAdmin}。
     *
     * @throws com.chargeops.exception.ParamErrorException 手机号前缀匹配到的车主过多，
     * 而不是截断后照常返回
     */
    PageResult pageForAdmin(AdminOrderPageQueryDTO query);

    /**
     * 管理端订单详情（含分段明细和流转历史）
     *
     * <p> 补齐的三个字段（车主手机号/昵称、站点名）可能查不到，那时返回 {@code null}
     * 而不是报错：车主注销、站点被删都不该让一笔真实的订单变得打不开。
     */
    AdminOrderDetailVO getForAdmin(Long orderId);

    // ---------- 订单流程（设备上报 / 定时任务 / 运维） ----------

    /**
     * 把订单转成 {@code 4 待支付 → 6 支付失败}
     *
     * <p> 必须在一个新的、独立的事务里调用 —— 也就是 {@link #pay} 抛了
     * {@code BalanceNotEnoughException} 的那个事务已经回滚之后。那个异常是
     * RuntimeException，Spring 的事务拦截器在它抛出的瞬间就把事务标记成
     * rollback-only 了，在同一个事务里 catch 住它再写这一行，提交时照样整体回滚，
     * 只是失败的位置从「余额不足那一行」挪到了「提交那里」。Controller 没有事务，
     * 天然就是正确的位置。
     *
     * <p> 影响行数为 0 是正常的（订单可能已经被别的线程推到 5 了），所以本方法不抛异常：
     * 它记的是「这一次尝试失败了」，不是「订单必须变成失败态」。
     */
    void markPayFailed(Long orderId, String reason);

    /** 启动充电：{@code 1 待启动 → 2 充电中}。钱在建单时已经冻结了，这一步不动钱 */
    void start(Long orderId, OrderStartDTO dto);

    /**
     * 结束充电：{@code 2 充电中 → 3 待结算}
     *
     * <p> 只写「充电结束」这个事实，不写金额：结算可能失败（电价有缺口、电量数据缺失），
     * 而失败不应该让「车确实被充过」这件事回滚掉。
     *
     * <p> 电量 = {@code end - start}，不是 {@code end} 本身 —— 电表是累计读数。
     */
    void end(Long orderId, OrderEndDTO dto);

    /**
     * 结算：{@code 3 待结算 → 4 待支付}
     *
     * <p>跑 {@code BillingEngine}，写分段明细，定下金额三兄弟。不动钱。
     *
     * <p> {@code total_amount} 必须写 {@code BillingResult.totalAmount()}，绝不能写
     * 「总电量 × 综合单价」。
     *
     * <p> 结算失败（电价有缺口）时抛异常 → 整个事务回滚，订单留在 {@code 3 待结算}，
     * 可以重试。只有「重试也不会好」的失败才转 {@code 9}。
     */
    void settle(Long orderId);

    /**
     * 退款：{@code 5 已支付 → 7 已退款}
     *
     * <p> 只有 {@code 5 已支付} 能退。{@code 4 待支付} 走的是取消/挂账，那时候钱还没扣。
     *
     * @param amount 退款金额，必须为正
     */
    void refund(Long orderId, BigDecimal amount);
}
