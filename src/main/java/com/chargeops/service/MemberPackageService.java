package com.chargeops.service;

import com.chargeops.dto.MemberPackageVoidDTO;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import com.chargeops.vo.MemberPackageVoidVO;

import java.util.List;

/**
 * 车主端权益服务：看货架、下单买、看自己有什么
 *
 * <h2>★★ 一、购买是「直购」：一个事务，<b>不建订单表</b> ★★</h2>
 *
 * <p>充电链路需要一张订单表和一个九态状态机，是因为<b>「设备上报」这个外部事件</b>
 * 把流程切成了多段，每段之间都可能失败、重试、乱序 —— 那些都要 CAS 去保护。
 * 而套餐购买<b>没有任何外部事件</b>：扣钱和得到权益是同一个事务里的两件事，
 * 中间不存在需要被保护的停留态。为它养一张订单表，换来的是一个永远只有
 * 「已支付 / 已退款」两个状态的状态机。
 *
 * <p>★★ <b>代价必须说清楚，它有两处：</b>
 * <ol>
 *   <li><b>购买流水没有数据库级的幂等保证。</b>{@code wallet_transaction} 的
 *       {@code uk_txn_order_type (order_id, type)} 是资金侧的幂等闸门，
 *       而直购没有购买单，{@code order_id} 只能是 NULL ——
 *       <b>唯一索引不约束 NULL，那道闸门对这个类型不存在</b>。
 *       极端情况下（连点两次且都通过了削峰锁）会真的产生两条流水、两份权益。
 *       ★ <b>但它不是资金错误</b>：两条流水各自对应一份权益，钱是对得上的，
 *       那是一次真实的重复购买。完整论证见
 *       {@code WalletTransactionType.PACKAGE_PURCHASE}。</li>
 *   <li><b>退款路径只有管理端这一条，而且口径是「只退完全没用过的，全额退」。</b>
 *       见 {@link #voidPackage}。</li>
 * </ol>
 *
 * <h2>★ 二、SKU 的站点范围<b>不</b>在购买时校验</h2>
 *
 * <p>{@link #listAvailable} 按 {@code station_id IN (0, 站点)} 过滤货架，
 * 但 {@link #purchase} <b>不检查</b>「这份套餐是不是这个站点能买的」——
 * 拿另一个站点的套餐 id 直接调购买接口，买得下来，而且买完之后
 * <b>在哪儿充电都能用</b>（结算路径从来不读 {@code member_package} 的站点维度，
 * 那份信息根本不在 {@code member_package} 表里）。
 *
 * <p>★ <b>也就是说「新站首充 50 度·仅限科技园站」这句商品文案目前是一句空话。</b>
 * 这是<b>既有行为</b>，本轮只把它写下来，没有修改：真要落地站点范围，
 * 得先在 {@code member_package} 上快照一个可空的站点列，那是一次 schema 变更，
 * 而且会让已售出的那批权益（没有那个字段）语义不明。
 * 已记入 README 的已知缺口表。
 *
 * <h2>★ 三、涉及资金的入口都调 {@code MemberService.assertActive} ——
 * <b>但管理端的两个不调</b></h2>
 *
 * <p>车主端那四个（{@link #listAvailable} / {@link #purchase} / {@link #listMine}）
 * 和 {@code WalletService} 同一条规则：token 有效不等于账号可用
 * （禁用不撤销 token，这是已记录的缺口）。连只读的查询也查 ——
 * 为的是让「权益域一律要求账号可用」成为一条没有例外的规则，而不是一张清单。
 *
 * <p>★★ <b>{@link #listByMember} / {@link #voidPackage} 是那条规则的例外，
 * 而且必须是。</b>★★ 两层理由，任一层都足够：
 * <ol>
 *   <li>「账号被禁用」恰恰是作废并退钱的<b>常见原因</b>（客服补偿、注销清算）。
 *       要求账号可用，等于让运营在最需要退钱的那一刻退不了；</li>
 *   <li>那个校验查的是「<b>发起请求的那个账号</b>」，而这两个方法是<b>运营</b>调的、
 *       动的是<b>另一个车主</b>的权益。拿运营的账号状态去判断能不能给车主退钱，
 *       从一开始就是答非所问。</li>
 * </ol>
 *
 * <p>★★ <b>所以管理端的这两个方法收 {@code memberId}/{@code id} 参数，
 * 绝不读 {@code BaseContext}。</b>★★
 * {@code /api/admin/**} 上挂的是<b>管理端</b>拦截器，它往 {@code BaseContext} 里
 * 放的是<b>员工</b>的 id —— 而 {@code /api/member/**} 放的才是车主 id。
 * 一个 admin 请求里 {@code BaseContext.getCurrentId()} 返回的是运营的 staffId。
 * 写成 {@code requireMemberId()} 的症状是：<b>运营点了「作废退款」，
 * 钱退进了运营自己的钱包，而且全程不报错</b>。
 * 完整说明见 {@code WalletService} 类注释里「第三组不能读 BaseContext」那一段。
 */
public interface MemberPackageService {

    /**
     * 我在这个站点能买哪些套餐 —— 车主端的货架
     *
     * @param stationId 必传，没有「不限站点」这个变体。理由见
     *                  {@code ChargePackageMapper.listAvailableForMember}
     */
    List<ChargePackageVO> listAvailable(Long stationId);

    /**
     * 买下一份套餐
     *
     * <p>★ 返回的是<b>刚写进 {@code member_package} 的那一行</b>，
     * 不是 SKU。车主买完之后要看的是「我这份还剩多少、什么时候过期」，
     * 那是持有属性，只有这一行知道。
     *
     * @throws com.chargeops.exception.NotFoundException       套餐不存在
     * @throws com.chargeops.exception.DataConflictException   套餐已下架
     * @throws com.chargeops.exception.BalanceNotEnoughException 余额不足
     * @throws com.chargeops.exception.ParamErrorException     套餐数据不完整（专属列为空）
     */
    MemberPackageVO purchase(Long packageId);

    /**
     * 我的套餐 —— <b>不过滤状态</b>
     *
     * <p>★ 已用尽 / 已过期 / 已作废的也要返回：车主需要看到「我买过、
     * 但用完了」，而不是「我的套餐凭空消失了」。过滤掉它们的后果是
     * 用户来问客服「我那个 150 度的包去哪了」，而客服在后台查得到 ——
     * 两边看的是同一个系统，却给出不同的答案。
     */
    List<MemberPackageVO> listMine();

    // ========================================================================
    // 管理端
    // ========================================================================

    /**
     * 管理端：某个车主名下的全部权益
     *
     * <p>★★ <b>没有这个方法，{@link #voidPackage} 就是一个没法用的接口。</b>★★
     * 它要的是一个 {@code member_package.id}，而在本轮之前，
     * <b>全项目没有任何一个接口能让人查到一个权益 id</b> ——
     * 车主端只有「我的套餐」（认的是登录人），管理端一条都没有。
     * 也就是说运营根本无从知道该作废哪一行。
     *
     * <p>★ 和 {@link #listMine} 走同一条 SQL（不过滤状态、同样的排序），
     * 区别只有两处：<b>车主 id 是参数而不是 {@code BaseContext}</b>，
     * 以及<b>不查 {@code assertActive}</b>。两条理由都见类注释第三节。
     *
     * <p>★ <b>不分页</b>：一个车主手里的权益条数是「买过几次套餐」的量级，
     * 而不是订单那种会到几千的量级。分页在这里只会让调用方多一层壳。
     */
    List<MemberPackageVO> listByMember(Long memberId);

    /**
     * 管理端：作废一份权益，并把当初付掉的钱全额退回车主余额
     *
     * <h3>★★ 退款口径：只退<b>完全没用过</b>的，全额退 ★★</h3>
     *
     * <p>「用过」的判据是「这份权益有没有被任何一笔<b>非取消</b>的订单引用过」，
     * 权威实现在 {@code ChargingOrderMapper.countByPackage}。
     * 一句话版本：<b>一旦它参与过任何一次充电（哪怕那笔订单还没付钱、</b>
     * <b>或者已经挂账），就整份不退。</b>
     *
     * <p>★ <b>不按剩余量折算</b>，理由有两条，都很实际：
     * <ol>
     *   <li>{@code remaining_*} 是<b>结算时</b>才知道的数字，而「用了 30 度该退多少钱」
     *       要重算一遍当时的电费 + 服务费 + 折扣组合 —— 那套口径是产品假设
     *       （README 已记），而当时的价格<b>已经不可复现</b>；</li>
     *   <li>一笔算错的钱比一句「不能退」严重得多，而且它<b>不会有人来反馈</b> ——
     *       车主收到一笔偏少的退款，多半不会发现。</li>
     * </ol>
     *
     * <h3>★ 金额是算出来的，不是传进来的</h3>
     *
     * <p>它等于 {@code member_package.pay_amount}（购买时的实付快照）。
     * ★ <b>绝不用 {@code charge_package.price} 现算</b> —— 那个价格是可编辑的。
     * 完整论证见 {@code MemberPackage#payAmount}。
     * {@code pay_amount IS NULL}（建于 {@code V3} 之前）时<b>拒绝并转人工</b>，
     * 不编一个数字出来。
     *
     * <h3>★★ 执行的次序：四个预检 → CAS → 退钱 ★★</h3>
     *
     * <pre>
     *   ① 存在         → 404
     *   ② status = 1   → 已用尽 / 已过期 / 已作废的不能再作废
     *   ③ 没被锁       → locked_order_id IS NULL，有一笔订单正拿着它
     *   ④ pay_amount   → NULL 拒绝（算不出金额）；0.00 照常作废但不退钱
     *   ⑤ 没用过       → ChargingOrderMapper.countByPackage(id) == 0
     *   ───── 以上都是「给出一句能解释的话」的前置检查，不是权威判据 ─────
     *   ⑥ memberPackageMapper.voidPackage(...)  ← ★ 权威判据，必须检查影响行数
     *   ⑦ walletService.refundPackage(...)      ← 只有 ⑥ 影响 1 行才执行
     * </pre>
     *
     * <p>★★ <b>⑥ 必须排在 ⑦ 前面，而且必须检查返回值。</b>★★
     * 前五步和第六步之间有一道真实的并发窗口（两个运营同时点了作废）——
     * 那时前五步两边都会通过，只有 ⑥ 的条件更新会把其中一个挡下来。
     * 把 ⑦ 提到 ⑥ 前面，或者不检查 ⑥ 的返回值，症状是<b>同一份权益退了两次钱</b>，
     * 而 {@code wallet_transaction} 上会留下两条一模一样的
     * {@code PACKAGE_REFUND} 流水 —— 每条单独看都完全正常。
     *
     * <p>★ <b>幂等性靠 ⑥，不靠流水。</b>这条资金流水的 {@code order_id} 是 NULL，
     * {@code uk_txn_order_type} 对它不生效，所以数据库层<b>没有</b>第二道防线。
     * 这和不检查 CAS 的裸调用只差一行，而那一行就是全部。
     *
     * @param id  要作废的 {@code member_package.id}
     * @param dto 只带原因。★ 金额不在请求里 —— 见 {@code MemberPackageVoidDTO}
     * @throws com.chargeops.exception.NotFoundException       权益不存在
     * @throws com.chargeops.exception.DataConflictException   状态不对 / 被订单占着 /
     *         已经用过 / 没有金额快照 / CAS 撞了并发
     */
    MemberPackageVoidVO voidPackage(Long id, MemberPackageVoidDTO dto);
}
