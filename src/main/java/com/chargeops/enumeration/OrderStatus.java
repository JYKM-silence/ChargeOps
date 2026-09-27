package com.chargeops.enumeration;

import com.chargeops.exception.ParamErrorException;

import java.util.List;

/**
 * 充电订单状态，对应 {@code charging_order.status}
 *
 * <h2>★ 一、状态流转靠「带 from 的条件更新」，不靠先查后改</h2>
 *
 * <p>每一次流转都是同一种形状：{@code UPDATE ... SET status = #{to}
 * WHERE id = #{id} AND status = #{from}}。影响行数为 0 就意味着
 * <b>别人先动了这一行</b>，调用方据此拒绝本次操作。
 * <b>绝不写成「先 SELECT 查 status，判一下，再 UPDATE」</b>：
 * 两个并发的启动请求会同时读到 1，然后都更新成功，冻结两次钱。
 *
 * <p>用同一种形状而不是每种流转各写一套判据（比如结算用
 * {@code settle_time IS NULL}），是因为<b>状态列能同时挡住「重复」和「乱序」</b>：
 * 「取消」和「启动」并发时，两者都只在 {@code status=1} 时成功、只有一个能赢；
 * 只判 {@code settle_time} 的话，取消和启动会双双通过。
 *
 * <h2>★ 二、终态与「可重试」是两回事</h2>
 *
 * <pre>
 *   终态：7 已退款 / 8 已取消
 *   非终态：1 待启动 → 2 或 8     2 充电中 → 3       3 待结算 → 4 或 9
 *           4 待支付 → 5/6/9     5 已支付 → 7       6 支付失败 → 5 或 9
 *           9 异常挂账 → 人工处理
 * </pre>
 *
 * <p>★ {@code 6 支付失败 → 5} <b>直接</b>过去、不先回 {@code 4}：用户充值之后
 * 再点一次支付走的就是它，{@code pay} 的 CAS 同时接受 4 和 6 两个来源。
 * 「先回 4 再扣款」要多一条 UPDATE，而中间那个 4 对用户不可见，
 * 多出来的只是一次「回到了 4 但扣款又失败了」的中间状态。
 *
 * <p>★ {@link #ABNORMAL} 不是终态也不是失败态，它是<b>挂起</b>：系统在那一刻
 * 算不出正确结果（余额不够、设备数据缺失、第三方超时），于是把订单原样停住、
 * 把线索留在 {@code charging_order_event} 里，等人工处理。
 * <b>它绝不会自动重试</b> —— 自动重试一笔算不清的账，只会把错误放大到没人能还原。
 *
 * <h2>★ 三、{@link #PAID} 之后再没有「正向」的流转</h2>
 *
 * <p>已支付的订单只能退款，想再次收钱只能新建一笔订单。这条约束让
 * 「一个订单最多产生一条 {@code type=4 扣款} 流水」成立，而它是资金对账的基础。
 */
public enum OrderStatus {

    /** 待启动。★ 已冻结预估金额，但还没开始充电 —— 取消要解冻 */
    CREATED(1, "待启动"),

    /** 充电中。★ 设备上报「启动成功」后进入 */
    CHARGING(2, "充电中"),

    /**
     * 充电结束待结算。
     *
     * <p>★ 这是一个<b>真实存在、会停留</b>的状态，不是过渡态：设备上报「结束」
     * 只说明枪停了，账单还没算；结算可能由用户端触发、由定时任务扫，
     * 也可能因为电价规则有缺口而失败。
     */
    SETTLING(3, "充电结束待结算"),

    /**
     * 待支付。
     *
     * <p>★ 对余额支付来说它是<b>瞬间穿过</b>的（结算和扣款在同一事务里），
     * 但状态本身必须存在：微信/支付宝支付在这里等回调，而回调可能永远不来 ——
     * 那就得有地方停着，让超时任务能扫到它。
     */
    PENDING_PAY(4, "待支付"),

    /** 已支付。★ 见类注释第三节：从这里只能去退款 */
    PAID(5, "已支付"),

    /** 支付失败。★ 钱还冻着。可重试 → 5（充值后再点一次），重试仍失败 → 9 */
    PAY_FAILED(6, "支付失败"),

    /** 已退款。终态 */
    REFUNDED(7, "已退款"),

    /** 已取消。终态。★ 取消前必须把冻结的钱释放回去 */
    CANCELLED(8, "已取消"),

    /**
     * 异常挂账。人工处理。★ 见类注释第二节：它是挂起不是失败，<b>绝不自动重试</b>；
     * 进这个状态时 {@code charging_order_event.detail} 里必须有原因。
     */
    ABNORMAL(9, "异常挂账");

    private final int code;

    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否是终态 —— 不会再有任何流转
     *
     * <p>★ 用它而不是到处写 {@code status == 7 || status == 8}：
     * 那种写法在加了新的终态之后会被漏掉一处，
     * 而漏掉的那处表现为「已退款的订单还能被取消」。
     */
    public boolean isTerminal() {
        return this == REFUNDED || this == CANCELLED;
    }

    /**
     * 是否<b>占着</b>一把枪（设备侧资源）
     *
     * <p>★ 设备重复上报「结束」时，第一件事就是问「这把枪上还有占着它的订单吗」。
     * 它和 {@code idx_order_connector_status (connector_id, status)} 配套 ——
     * 索引的第二列就是这个集合。
     */
    public boolean holdsConnector() {
        return this == CREATED || this == CHARGING || this == SETTLING;
    }

    /**
     * 是否<b>占着</b>车主的钱（冻结中，或者已经扣掉但还能退）
     *
     * <p>★ {@code 6 支付失败} <b>在里面</b>：扣款失败之后那笔钱<b>仍然冻着</b>，
     * 既没扣掉也没退回。漏掉它的话，「钱还冻着的订单」和「这个集合」就对不上了 ——
     * 而挂账（{@link #holdingMoneyCodes()}）正是冲着这类订单去的。
     */
    public boolean holdsMoney() {
        return this == CREATED || this == CHARGING || this == SETTLING
                || this == PENDING_PAY || this == PAY_FAILED;
    }

    /** {@code holdsConnector()} 的取值集，给 SQL 的 {@code IN} 用 */
    public static List<Integer> holdingConnectorCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::holdsConnector)
                .map(OrderStatus::getCode)
                .toList();
    }

    /**
     * {@code holdsMoney()} 的取值集 —— <b>转入异常挂账的合法来源</b>
     *
     * <p>★ <b>不能拿 {@link #holdingConnectorCodes()} 当挂账的来源集合</b>：
     * 那个集合是 {1,2,3}「还占着一把枪」，而挂账要停的是「钱还冻着但账算不下去」
     * 的订单 —— 后者包含 {@code 4 待支付}（余额不够，用户充值之前结不掉）
     * 和 {@code 6 支付失败}（重试还是不够，运维放弃）。
     *
     * <p>用错集合的症状是静默的：那两种情况下的订单<b>挂不上去</b>，
     * {@code markAbnormal} 影响 0 行、按「状态已经变了」跳过，于是一笔钱冻着、
     * 账结不掉的订单卡在 4 或 6 上，没有任何东西会再去碰它。
     * 反过来说，已支付（5）的订单<b>不</b>该挂账：它的钱已经结清，要退就退款。
     */
    public static List<Integer> holdingMoneyCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::holdsMoney)
                .map(OrderStatus::getCode)
                .toList();
    }

    /**
     * 这个状态的订单是否<b>妨碍删除它所属的站点 / 桩</b>
     *
     * <p>★★ <b>这是本类的第三个集合，和前两个都不一样，用错会静默失败。</b>
     *
     * <pre>
     *   holdingConnectorCodes()  {1,2,3}        占着<b>一把枪</b>
     *   holdingMoneyCodes()      {1,2,3,4,6}    占着<b>车主的钱</b>
     *   blockingCodes()          {1,2,3,4,6,9}  还有<b>没了结的事</b>
     * </pre>
     *
     * <p>★ 判据是<b>两个条件的并集</b>：{@link #holdsMoney()}（钱还冻着）
     * 或者 {@link #ABNORMAL}（挂起来等人工核销，见下）。<b>不是「非终态」</b> ——
     * 那个说法听起来等价（{@code 9} 确实也不是终态），实际差一个 {@code 5 已支付}。
     *
     * <p>★★ <b>这里曾经写的就是 {@code return !isTerminal();}，是一个真实的 bug。</b>★★
     * {@link #isTerminal()} 只认 {@code {7,8}}，所以取反得到的集合是
     * {@code {1,2,3,4,5,6,9}} —— <b>把 {@code 5 已支付} 悄悄算了进去</b>，
     * 而同一个 javadoc 的上一段正写着「{@code 5 已支付}特意不在里面」。
     * 症状不是报错，而是：<b>任何完成过一次充电的站点 / 桩从此永久不可删</b>，
     * 唯一的出路是把那笔已支付订单<u>退款</u>（一笔真实资金操作）或者改库。
     * —— 正好就是下面这段警告的「把可恢复的运维操作变成不可恢复的」。
     * 抓住它的是 {@code StationServiceImplTest} / {@code ChargerServiceImplTest}
     * 里那两个「只有已支付订单 → 应该能删」的用例，它们在本类修好之前是<b>红的</b>。
     *
     * <p>★ <b>为什么不是「非终态」这个口径</b>：{@code 5} 和 {@code 7/8} 一样，
     * 都只是「历史里留下一条引用」，唯一的区别是 {@code 5} 还<b>可以</b>被退一次款。
     * 而「可以」不等于「有待办」—— 判据必须是<b>此刻有没有悬着的东西</b>，
     * 否则每一条能引发后续动作的历史订单都会让设备永久不可删。
     * {@code charging_order.station_id} 是历史快照的入口，不是一个必须能 JOIN 的活引用；
     * 已支付订单因为站点被删而查不出站名，是<b>可接受的</b>。
     *
     * <p>★ {@code 9 异常挂账}在里面，因为它是<b>挂起不是终结</b>
     * （见类注释第二节：它的出边只有「人工处理」）：钱还冻着、账没核销，
     * 这时把站点删掉会让处理它的人失去现场。
     * 这一条无法从 {@link #isTerminal()} 推出来，只能显式写 ——
     * 它和 {@code holdsMoney()} 是<b>两个不相交的理由</b>，所以这里是「或」不是「加」。
     */
    public boolean blocksDeviceDeletion() {
        return holdsMoney() || this == ABNORMAL;
    }

    /**
     * {@code blocksDeviceDeletion()} 的取值集，给 SQL 的 {@code IN} 用
     *
     * <p>★ 和另外两个集合一样，<b>SQL 里必须把它写在设备列（station_id /
     * charger_id）前面</b>：在途订单按构造就是 {@code charging_order} 的极小比例，
     * 选择性来自 {@code status}，走的是 {@code idx_order_status_create}。
     * 反过来写会走 {@code idx_order_station_create}，把一个站的全部历史都扫一遍。
     */
    public static List<Integer> blockingCodes() {
        return java.util.Arrays.stream(values())
                .filter(OrderStatus::blocksDeviceDeletion)
                .map(OrderStatus::getCode)
                .toList();
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            throw new ParamErrorException("订单状态为空");
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new ParamErrorException("未知的订单状态：" + code);
    }
}
