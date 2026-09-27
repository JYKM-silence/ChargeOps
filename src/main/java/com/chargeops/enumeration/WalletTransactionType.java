package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

/**
 * 钱包流水类型，对应 {@code wallet_transaction.type}。
 *
 * <p>★ 它和 {@code uk_txn_order_type (order_id, type)} 是配套的：唯一索引保证
 * 「同一订单的同一种流水只有一条」，所以这里<b>每一种类型都必须是幂等的</b>——
 * 加新类型前先问一句「它重复执行会怎样」。
 *
 * <p>★★ <b>但那条索引的前提是 {@code order_id} 非空，而唯一索引不约束 NULL。</b>★★
 * 逐条对照，八种类型里有四种落在它外面：
 * <table border="1">
 *   <caption>哪些类型真的受 {@code uk_txn_order_type} 保护</caption>
 *   <tr><th>类型</th><th>{@code order_id}</th><th>受保护吗</th></tr>
 *   <tr><td>{@code FREEZE / UNFREEZE / DEDUCT / REFUND}</td><td>必非空</td>
 *       <td><b>是</b>。它们在 {@code WalletService} 上的形参是<b>原始类型
 *       {@code long}</b>，结构上不可能传 null —— 这不是巧合，是刻意用类型挡住了空值</td></tr>
 *   <tr><td>{@link #RECHARGE}</td><td>NULL</td>
 *       <td>否，<b>而且这是对的</b>：充值本来就可以有任意多笔，它不需要幂等</td></tr>
 *   <tr><td>{@link #PACKAGE_PURCHASE}</td><td>NULL</td>
 *       <td>否，直购模型下没有购买单。<b>这是一个真实的取舍</b>，
 *           说明见那个枚举值的 javadoc</td></tr>
 *   <tr><td>{@link #PACKAGE_REFUND}</td><td>NULL</td>
 *       <td>否，同上。但它的幂等<b>由 {@code member_package.status} 的条件更新兜着</b>，
 *           不依赖这条索引 —— 见那个枚举值的 javadoc</td></tr>
 *   <tr><td>{@link #ADMIN_ADJUST}</td><td>NULL（永远）</td>
 *       <td>否。<b>它连业务对象都没有</b> —— 既然是人凭一句话记一笔账，
 *           就没有任何东西能证明「这两条是同一笔」，也就不该假装有</td></tr>
 * </table>
 *
 * <p>★ <b>所以别把「本枚举受那条唯一索引保护」当成一句通用的话。</b>
 * 加新类型时先回答「它的 {@code order_id} 是什么」—— 这个问题的答案
 * 就决定了它有没有那道防线，而<b>没想清楚就写下去的症状是静默的</b>：
 * 一条本该幂等的流水会安静地写下第二条。
 *
 * <p>★★ <b>而 {@code order_id = NULL} 的这四种，必须各自回答「那我的幂等靠什么」：</b>★★
 * <pre>
 *   RECHARGE       不需要 —— 充值本来就可以有任意多笔
 *   PACKAGE_PURCHASE  不需要 —— 重复购买是一笔真实交易，不是资金错误
 *   PACKAGE_REFUND    靠 member_package 的状态条件更新（影响 1 行才退钱）
 *   ADMIN_ADJUST      不需要 —— 它本来就是一个人为的、可以重复发生的动作
 * </pre>
 * ★ 这张表是加新类型时的必填项。写不出来的那一行，就是还没想清楚的那一行。
 */
public enum WalletTransactionType {

    RECHARGE(1, "充值"),
    FREEZE(2, "冻结"),
    UNFREEZE(3, "解冻"),
    DEDUCT(4, "扣款"),
    REFUND(5, "退款"),

    /**
     * 用余额买套餐。★ 它扣的是 {@code balance}，和充电无关
     *
     * <p>★★ <b>{@code order_id} 恒为 {@code NULL} —— 这里曾经写的是
     * 「order_id 指向购买单」，那是一个从未实现、也不打算实现的模型。</b>★★
     *
     * <p>现在的模型是<b>直购</b>：一个事务里「扣钱 + 插一行 member_package」，
     * <b>不建购买单</b>。理由是充电链路需要状态机，是因为「设备上报」这个外部事件
     * 把流程切成了多段、每段之间都可能失败重试；而套餐购买<b>没有外部事件</b> ——
     * 扣钱和得到权益是同一个事务里的两件事，不存在需要 CAS 保护的中间态。
     * 为它养一张订单表，换来的是一个永远只有两个状态（已支付 / 已退款）的状态机。
     *
     * <p>★★ <b>代价要说清楚：{@code uk_txn_order_type (order_id, type)} 这条唯一索引
     * 对本类型不生效</b>（唯一索引不约束 NULL）。也就是说这条流水<b>没有数据库级的
     * 幂等保证</b>：真正的重复提交（不是「用户连点两次」那种，那个由购买接口上
     * 那把 Redis 锁削峰）会真的产生两条流水、两份权益。
     * 好在两条流水各自对应一份权益，<b>钱是对得上的</b> —— 它是一次真实的重复购买，
     * 不是一次资金错误。这是本条与 {@code FREEZE} / {@code DEDUCT} 等的关键区别，
     * 也是它敢这么做的唯一理由。
     *
     * <p>★ 需要反查「这笔扣款买的是哪一份权益」时，目前<b>没有 join 键</b>，
     * 只能靠 {@code remark}（购买接口会写进套餐名）加时间对齐。
     * 要真正解决得加一列 {@code biz_id}，那是一次 schema 变更。
     */
    PACKAGE_PURCHASE(6, "套餐购买"),

    /**
     * 管理端作废一份套餐权益时退回去的钱
     *
     * <p>★★ <b>它和 {@link #REFUND} 是两回事，不要合并。</b>★★
     * {@code REFUND} 的 {@code order_id} <b>必非空</b>（{@code WalletService.refund}
     * 的形参是原始类型 {@code long}，结构上挡着空值），它回答的是
     * 「这笔充电订单退了多少」；而本类型 {@code order_id} <b>恒为 NULL</b> ——
     * 套餐直购没有购买单，被退的那一行在 {@code member_package} 表里。
     *
     * <p>★ <b>金额的来源是 {@code member_package.pay_amount}</b>，不是 SKU 的现价。
     * 完整论证见 {@code MemberPackage#payAmount} 和 {@code V3} 迁移 ——
     * 一句话版本：<b>已经卖出去的东西，条款不能被售后修改。</b>
     *
     * <p>★ 口径是「<b>只退完全没用过的，全额退</b>」：一旦这份权益参与过任何一笔
     * 订单（除已取消之外的状态），就整份不退。不按剩余量折算 ——
     * 那个折算需要重算当时的价格，而当时的价格已经不可复现。
     *
     * <p>★ <b>{@code order_id} 为 NULL 的后果和 {@link #PACKAGE_PURCHASE} 完全一样</b>：
     * {@code uk_txn_order_type} 对它不生效。但这里的幂等<b>不依赖流水</b> ——
     * 它由 {@code MemberPackageMapper.voidPackage} 的条件更新兜着
     * （{@code WHERE status = 1 AND locked_order_id IS NULL}），
     * 只有影响 1 行的那个调用会走到这里来退钱。
     */
    PACKAGE_REFUND(8, "套餐退款"),

    /**
     * ★ 人工调账。唯一一个<b>背后没有一个业务对象</b>的类型，必须留 remark 说明原因
     *
     * <p>★ 措辞在加 {@link #PACKAGE_REFUND} 时改过一次：原来是「唯一一个不由订单流程
     * 驱动的类型」，而套餐退款同样不由订单流程驱动，那句话就成了一句错话。
     * 现在的判据是「<b>有没有一个能指的单据</b>」—— 退款指向一行
     * {@code member_package}（或一笔订单），调账指向<b>空</b>。
     *
     * <p>金额<b>可正可负</b>：往里加钱和往外扣钱是同一个动作的两个方向，
     * 而 {@link #RECHARGE} / {@link #DEDUCT} 各只能走一个方向。
     * 0 被拒绝 —— 见 {@code MessageConstant.WALLET_ADJUST_AMOUNT_ZERO}。
     */
    ADMIN_ADJUST(7, "管理员调账");

    private final int code;

    private final String label;

    WalletTransactionType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static WalletTransactionType of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("流水类型为空");
        }
        for (WalletTransactionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ParamErrorException("未知的流水类型：" + code);
    }
}
