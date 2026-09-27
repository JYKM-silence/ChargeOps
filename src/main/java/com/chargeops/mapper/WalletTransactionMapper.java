package com.chargeops.mapper;

import com.chargeops.entity.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 钱包流水数据访问。只追加。{@code uk_txn_order_type (order_id, type)} 是资金幂等的落点：
 * 调用方拿到 {@code DuplicateKeyException} 的正确反应是当作成功，而不是报错给用户。
 */
@Mapper
public interface WalletTransactionMapper {

    /** 全列插入，{@code createTime} 由调用方给（本表没有 {@code create_user}，用不了 {@code @AutoFill}） */
    int insert(WalletTransaction transaction);

    /** 我的流水，按时间倒序。末尾补 {@code id DESC} 是为了全序，否则翻页会重复或漏行 */
    List<WalletTransaction> listByMember(@Param("memberId") Long memberId);

    /** 只用于排查和对账。不要用它做幂等判断 —— 「先查有没有再插」是 TOCTOU */
    WalletTransaction getByOrderAndType(@Param("orderId") Long orderId,
                                        @Param("type") Integer type);
}
