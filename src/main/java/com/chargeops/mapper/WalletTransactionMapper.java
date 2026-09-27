package com.chargeops.mapper;

import com.chargeops.entity.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 钱包流水数据访问。
 *
 * <p>★★ <b>只追加，没有 update / delete。</b>流水是会计凭证 ——
 * 可以被红冲（再记一笔反向的），不能被原地修改。
 *
 * <p>★ {@link #insert} 是本项目资金幂等的落点：
 * {@code uk_txn_order_type (order_id, type)} 让「同一订单的同一种流水只有一条」。
 * 支付回调、结算重试、定时补偿三条路径都会尝试写同一条流水，
 * 这条唯一索引让「扣两次钱」在<b>数据库层</b>不可能发生 ——
 * 不管那三条路径的代码写得对不对、有没有并发。
 *
 * <p>调用方拿到 {@code DuplicateKeyException} 时的正确反应是
 * <b>当作成功</b>（说明这笔已经做过了），而不是报错给用户。
 */
@Mapper
public interface WalletTransactionMapper {

    /**
     * 记一笔流水
     *
     * <p>★ 全列插入，{@code createTime} 由调用方给（本表没有 {@code create_user}，
     * 用不了 {@code @AutoFill}，DDL 的 {@code DEFAULT} 也不会生效）。
     */
    int insert(WalletTransaction transaction);

    /**
     * 我的流水，按时间倒序
     *
     * <p>★ 末尾补 {@code id DESC} 是为了<b>全序</b>：同一毫秒的两笔流水
     * 在两次请求里的相对顺序必须一致，否则翻页时会重复或漏行。
     */
    List<WalletTransaction> listByMember(@Param("memberId") Long memberId);

    /**
     * 按「订单 + 类型」查一条
     *
     * <p>★ 只用于排查和对账，<b>不要用它做幂等判断</b> ——
     * 「先查有没有，没有就插」是 TOCTOU，并发下两个线程都会查到「没有」。
     * 幂等必须靠 insert 撞唯一键。
     */
    WalletTransaction getByOrderAndType(@Param("orderId") Long orderId,
                                        @Param("type") Integer type);
}
