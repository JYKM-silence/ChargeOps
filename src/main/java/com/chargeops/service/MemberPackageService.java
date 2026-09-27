package com.chargeops.service;

import com.chargeops.dto.MemberPackageVoidDTO;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import com.chargeops.vo.MemberPackageVoidVO;

import java.util.List;

/**
 * 车主端权益服务：看货架、下单买、看自己有什么
 *
 * <p>购买是「直购」：一个事务，不建订单表。充电链路的订单表是为「设备上报」
 * 这个外部事件准备的，而套餐购买没有外部事件，扣钱和得到权益在同一个事务里，
 * 中间不存在需要被保护的停留态。代价是购买流水没有数据库级的幂等保证：
 * {@code wallet_transaction} 的 {@code uk_txn_order_type (order_id, type)}
 * 要求 {@code order_id} 非空，直购只能传 NULL，而唯一索引不约束 NULL。
 *
 * <p>退款口径：只退完全没用过的，全额退。判据是这份权益有没有被任何一笔
 * 非取消的订单引用过（权威实现 {@code ChargingOrderMapper.countByPackage}）——
 * 只有「8 已取消」的订单意味着权益一点没被消耗，一旦参与过任何一次充电
 * （哪怕那笔订单还没付钱、或者已经挂账）就整份不退。不按剩余量折算：
 * {@code remaining_*} 是结算时才知道的数字，「用了 30 度该退多少钱」要重算
 * 当时的电费 + 服务费 + 折扣组合，而当时的价格已经不可复现。
 *
 * <p>SKU 的站点范围不在购买时校验：{@link #purchase} 不检查「这份套餐是不是
 * 这个站点能买的」，买完之后在哪儿充电都能用（结算路径从来不读
 * {@code member_package} 的站点维度，那份信息也不在那张表里）。
 * 真要落地，得先在 {@code member_package} 上快照一个可空的站点列，那是一次
 * schema 变更。已记入 README 的已知缺口表。
 *
 * <p>管理端的两个（{@link #listByMember} / {@link #voidPackage}）收
 * {@code memberId} / {@code id} 参数，绝不读 {@code BaseContext}：
 * {@code /api/admin/**} 上挂的是管理端拦截器，它往里放的是员工的 id，
 * {@code /api/member/**} 放的才是车主 id。写成 {@code requireMemberId()} 的症状是
 * 运营点了「作废退款」，钱退进了运营自己的钱包，而且全程不报错。它们也不查
 * {@code MemberService.assertActive} —— 「账号被禁用」恰恰是作废并退钱的常见原因。
 */
public interface MemberPackageService {

    /**
     * 我在这个站点能买哪些套餐 —— 车主端的货架
     *
     * @param stationId 必传，没有「不限站点」这个变体。理由见
     * {@code ChargePackageMapper.listAvailableForMember}
     */
    List<ChargePackageVO> listAvailable(Long stationId);

    /**
     * 买下一份套餐
     *
     * <p> 返回的是刚写进 {@code member_package} 的那一行，不是 SKU ——
     * 车主买完之后要看的是「我这份还剩多少、什么时候过期」，那是持有属性。
     *
     * @throws com.chargeops.exception.DataConflictException      套餐已下架
     * @throws com.chargeops.exception.BalanceNotEnoughException  余额不足
     * @throws com.chargeops.exception.ParamErrorException        套餐数据不完整（专属列为空）
     */
    MemberPackageVO purchase(Long packageId);

    /**
     * 我的套餐 —— 不过滤状态
     *
     * <p> 已用尽 / 已过期 / 已作废的也要返回：车主需要看到「我买过、但用完了」，
     * 而不是「我的套餐凭空消失了」。
     */
    List<MemberPackageVO> listMine();

    // ---------- 管理端 ----------

    /**
     * 管理端：某个车主名下的全部权益
     *
     * <p> 没有这个方法，{@link #voidPackage} 就是一个没法用的接口 —— 它要的是
     * 一个 {@code member_package.id}，而车主端的「我的套餐」认的是登录人。
     *
     * <p> 和 {@link #listMine} 走同一条 SQL（不过滤状态、同样的排序），
     * 区别只有两处：车主 id 是参数，以及不查 {@code assertActive}。
     * 不分页：一个车主手里的权益条数是「买过几次套餐」的量级。
     */
    List<MemberPackageVO> listByMember(Long memberId);

    /**
     * 管理端：作废一份权益，并把当初付掉的钱全额退回车主余额
     *
     * <p> 前五步预检（存在 / status=1 / 没被订单锁 / 有 pay_amount / 没用过）
     * 都只是「给出一句能解释的话」；权威判据是
     * {@code memberPackageMapper.voidPackage} 的条件更新，必须检查它的影响行数，
     * 而且必须排在 {@code walletService.refundPackage} 前面。前五步和它之间
     * 有一道真实的并发窗口（两个运营同时点了作废），那时只有条件更新会把其中
     * 一个挡下来。顺序写反或不检查返回值，症状是同一份权益退了两次钱，
     * 而 {@code wallet_transaction} 上留下两条一模一样的 {@code PACKAGE_REFUND}
     * 流水 —— 每条单独看都完全正常。这条流水的 {@code order_id} 是 NULL，
     * 数据库层没有第二道防线。
     *
     * <p> 金额等于 {@code member_package.pay_amount}（购买时的实付快照），
     * 绝不用 {@code charge_package.price} 现算 —— 那个价格是可编辑的。
     * {@code pay_amount IS NULL}（建于 {@code V3} 之前）时拒绝并转人工。
     *
     * @param dto 只带原因，金额不在请求里
     * @throws com.chargeops.exception.DataConflictException 状态不对 / 被订单占着 /
     * 已经用过 / 没有金额快照 / CAS 撞了并发
     */
    MemberPackageVoidVO voidPackage(Long id, MemberPackageVoidDTO dto);
}
