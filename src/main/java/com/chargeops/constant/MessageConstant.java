package com.chargeops.constant;

/**
 * 提示信息常量
 *
 * <p> 集中在这里是为了同一句话在多处字字一致（前端有按 msg 做分支的逻辑），同时是一份白名单：
 * 真实原因（SQL 报错、堆栈）绝不能进来，没进这里的信息由 GlobalExceptionHandler 统一翻成「未知错误」。
 */
public class MessageConstant {

    // ---------- 账号与登录 ----------

    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_LOCKED = "账号被禁用";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String USER_NOT_LOGIN = "登录状态已失效，请重新登录";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";

    // ---------- 通用 ----------

    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String ALREADY_EXISTS = "已存在";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String PARAM_ERROR = "参数不合法";
    public static final String NOT_FOUND = "数据不存在";
    public static final String OPERATION_FAILED = "操作失败";

    // ---------- 充电业务 ----------

    public static final String CONNECTOR_NOT_FOUND = "充电枪不存在";

    public static final String CONNECTOR_OCCUPIED = "该充电枪正在使用中，请换一把";

    public static final String BALANCE_NOT_ENOUGH = "余额不足";

    // ---------------- 站点 / 充电桩 / 充电枪 ----------------

    public static final String STATION_NOT_FOUND = "充电站不存在";

    public static final String CHARGER_NOT_FOUND = "充电桩不存在";

    /** {@code %d} 是充电桩数量。带数量的理由见 {@link #CATEGORY_HAS_PACKAGE} */
    public static final String STATION_HAS_CHARGER = "该站点下还有 %d 个充电桩，请先删除或转移";

    /** {@code %d} 是充电枪数量 */
    public static final String CHARGER_HAS_CONNECTOR = "该充电桩下还有 %d 把充电枪，请先删除";

    /**
     * {@code %d} 是未完结订单数量。判据是 {@code OrderStatus.blockingCodes()}，不是「非终态」（差一个 5 已支付）。
     * 文案不同于 {@link #STATION_HAS_CHARGER}：订单是 append-only 的历史事实，没有「转移」也没有「删除」。
     */
    public static final String STATION_HAS_ORDER = "该站点下还有 %d 笔未完结的充电订单，请先处理";

    /** {@code %d} 是未完结订单数量。桩的第二道删除守卫，理由同 {@link #STATION_HAS_ORDER} */
    public static final String CHARGER_HAS_ORDER = "该充电桩下还有 %d 笔未完结的充电订单，请先处理";

    /** 和 {@link #CONNECTOR_OCCUPIED}（用户想启动但枪被占）不同，这是管理员想删枪。不带数量：正常最多一笔订单占着 */
    public static final String CONNECTOR_IN_USE = "该充电枪当前有订单占用，不能删除";

    /** 管理员试图把枪设成「已占用」或「充电中」，这两个值由订单状态机写入，不是可编辑字段 */
    public static final String CONNECTOR_STATUS_NOT_EDITABLE = "「已占用」和「充电中」由充电流程自动维护，不能手工设置";

    // ---------------- 套餐 ----------------

    public static final String PACKAGE_NOT_FOUND = "套餐不存在";

    /** 前缀，完整消息会拼上具体字段名（见 {@code ChargePackageServiceImpl.assertTypeFields}）。前缀固定是为了给前端一个稳定的匹配点 */
    public static final String PACKAGE_TYPE_FIELD_MISMATCH = "套餐的类型专属字段不匹配";

    /** 和 {@link #PACKAGE_NOT_FOUND} 分开：「没有这个套餐」和「这个套餐现在不卖了」对车主是两件事 */
    public static final String PACKAGE_OFF_SHELF = "该套餐已下架";

    /**
     * 前缀，完整消息会拼上列名和类型。触发条件是库里 {@code package_type=1} 而 {@code energy_kwh IS NULL}
     * 之类，说明有人直接改过库。宁可抛异常也不跳过这个套餐：它的权益是 {@code remaining_kwh IS NULL}，
     * 而 NULL 参与 {@code > 0} 比较恒为 false，于是永远不被结算选中，用户一直按余额扣钱。
     */
    public static final String PACKAGE_FIELD_MISSING = "该套餐的数据不完整，无法购买";

    /** 措辞是「正在处理」不是「失败」：没抢到锁说明上一次请求还在跑，而它大概率会成功 */
    public static final String PACKAGE_PURCHASE_DUPLICATE = "购买请求正在处理中，请勿重复提交";

    // ---------------- 计费模板 ----------------

    public static final String TEMPLATE_NOT_FOUND = "计费模板不存在";

    /** 计费时段没有完整覆盖 24 小时（消息里会列出具体缺口） */
    public static final String TARIFF_NOT_COVER_24H = "计费时段未完整覆盖 24 小时";

    /** 计费时段之间存在重叠（消息里会列出具体冲突的两条规则） */
    public static final String TARIFF_OVERLAP = "计费时段存在重叠";

    /** 订单创建时冻结了 tariff_template_id，规则还能改就会让「电价调整不影响在途订单」成为空话。调价请新建模板版本 */
    public static final String TEMPLATE_IN_USE = "该模板已被站点绑定，不能修改计费规则；请新建一个模板版本";

    public static final String TEMPLATE_DISABLED = "该计费模板已停用，不能用于新建绑定";

    // ---------------- 站点计费绑定 ----------------

    public static final String STATION_TARIFF_NOT_FOUND = "站点计费绑定不存在";

    /** 解析不出模板时必须不能充电，不能回退到默认价 —— 那是静默的资金错误：订单正常结算，只有对账时对不上 */
    public static final String STATION_TARIFF_NOT_BOUND = "该站点当前没有配置计费方案，请联系运营";

    // ---------------- 并发 ----------------

    /** 手写的条件更新（{@code WHERE id=? AND version=?}）影响行数为 0 时抛 */
    public static final String CONCURRENT_MODIFIED = "数据已被他人修改，请刷新后重试";

    // ---------- 车主端 ----------
    // 刻意不与上面「账号与登录」那组共用：会员侧和 staff 侧的登录失败语义不同。

    /** 两处都用：{@code /me} 拿到了有效 token 但那条 member 已经不在了，以及管理端按 id 查车主详情 */
    public static final String MEMBER_NOT_FOUND = "车主不存在";

    public static final String MEMBER_DISABLED = "该账号已被禁用，请联系客服";

    /** 前缀命中的 member 行数达到上限（见 {@code ChargingOrderServiceImpl.MEMBER_PREFIX_LIMIT}）。抛异常而不是截断返回：截断的结果看起来正常，运维会得出「这个前缀就这么多单」的错误结论 */
    public static final String MEMBER_PREFIX_TOO_BROAD = "该手机号前缀匹配到的车主过多，请多输入几位数字后重试";

    /** 手机号没注册和密码错在车主端合并成一句，管理端是分开的两句（{@link #ACCOUNT_NOT_FOUND} / {@link #PASSWORD_ERROR}）。别把它说成「防用户枚举」——注册接口本来就会告诉你手机号是否存在，合并换来的是一致性不是安全 */
    public static final String MEMBER_LOGIN_FAILED = "手机号或密码错误";

    /** {@code member.password IS NULL} 是 DDL 里合法且有意的状态。不并进上面那句模糊文案：那类用户会一直重试密码，而提示不指向任何能解决问题的行动 */
    public static final String MEMBER_PASSWORD_NOT_SET = "该账号未设置密码，无法使用密码登录";

    public static final String OLD_PASSWORD_ERROR = "原密码错误";

    /** 不属于当前车主时也返回这一句，见 {@code VehicleMapper} */
    public static final String VEHICLE_NOT_FOUND = "车辆不存在";

    public static final String CATEGORY_NOT_FOUND = "分类不存在";

    /** 触发条件是 {@code category.type != 1}，抛出时在后面拼上当前分类。它守的是 {@code charge_package.category_id} 没有外键这件事 */
    public static final String CATEGORY_TYPE_MISMATCH = "分类类型不匹配";

    /** {@code %d} 是套餐数量。这是一个 {@link String#format} 模板，不能直接抛。本类带 {@code %d} 的五条都是这个用法，句中带数量是为了让拒绝的同时给出路 */
    public static final String CATEGORY_HAS_PACKAGE = "该分类下还有 %d 个充电套餐，请先删除或调整分类";

    // ---------- P1c-3 订单 ----------

    /** 车主端查别人的订单时也报这一句 —— 见 {@code ChargingOrderMapper.getByIdAndMember} */
    public static final String ORDER_NOT_FOUND = "订单不存在";

    /** 触发条件是「状态条件更新影响了 0 行」：并发被别人先动，或者顺序错乱。两者对用户是同一件事 */
    public static final String ORDER_STATUS_CONFLICT = "订单当前状态不允许该操作";

    /** 同一时刻一辆车只能有一笔进行中的订单。拦不住的话用户会拿到两笔都算满额的订单，而设备只充了一次电 */
    public static final String ORDER_VEHICLE_BUSY = "该车辆有进行中的充电订单，请先结束";

    /** 一把枪上有多于一笔进行中的订单，说明有并发建单或脏数据。必须停下来：随便挑一笔继续，另一笔会永远停在充电中而它的钱还冻着 */
    public static final String ORDER_CONNECTOR_STATE_INVALID = "该充电枪上的订单状态异常，请联系运维";

    /** 并发下没抢到权益，且没有下一份可试 */
    public static final String MEMBER_PACKAGE_LOCKED = "所选权益已被其他订单占用，请重试";

    public static final String ORDER_METER_READING_MISSING = "缺少电表读数，无法计算用电量，已转入异常挂账";

    public static final String ORDER_METER_READING_REGRESSED = "结束电表读数小于起始读数，已转入异常挂账";

    /** {@code 2微信} / {@code 3支付宝} 会走到这里。刻意拒绝，不能把它当成余额扣了钱 */
    public static final String ORDER_PAY_METHOD_UNSUPPORTED = "该支付方式暂未支持，请使用余额支付";

    // ---------- 管理端：权益作废/退款 ----------

    /** 和 {@link #PACKAGE_NOT_FOUND}（说的是 SKU）分开：这个接口上同时有权益 id 和它指向的 package_id，共用一句就分不清是哪个错了 */
    public static final String MEMBER_PACKAGE_NOT_FOUND = "权益不存在";

    /** 触发条件同 {@link #ORDER_STATUS_CONFLICT}。不要写成「该权益已作废」：影响 0 行也可能是被改成了 {@code 2 已用尽} / {@code 3 已过期} */
    public static final String MEMBER_PACKAGE_STATUS_CONFLICT = "权益当前状态不允许该操作";

    /**
     * 该权益已经被若干笔订单用过，不能退款。{@code %d} 是订单笔数
     *
     * <p> 退款口径是只退完全没用过的、全额退：被任何一笔非取消的订单引用过就整份不退，
     * 包括 {@code 4 待支付} 和 {@code 6 支付失败}（判据见 {@code ChargingOrderMapper#countByPackage}）。
     * 不按剩余量折算 —— 那要重算当时的价格，而那个价格已不可复现。
     */
    public static final String MEMBER_PACKAGE_ALREADY_USED = "该权益已经被 %d 笔订单用过，不能退款";

    /** 不复用 {@link #MEMBER_PACKAGE_LOCKED}：那句的落点是「请重试」，这里是有一笔订单正拿着它在充电，管理员该等它结束。判据是 {@code locked_order_id} 非空 */
    public static final String MEMBER_PACKAGE_IN_USE = "该权益被一笔进行中的订单占用，请先处理那笔订单";

    /** 触发条件是 {@code pay_amount IS NULL}（V3 新加的列，对之前的行不回溯）。不拿 {@code charge_package.price} 现算：那个价格可编辑，等于按今天的售价退去年的钱 */
    public static final String MEMBER_PACKAGE_PAY_AMOUNT_MISSING =
            "该权益没有购买金额快照（建于本功能上线之前），无法计算退款金额，请人工处理";

    // ---------- 管理端：钱包调账 ----------

    /** 0 元的调账在账面上什么都没改变，却会写下一条 {@code wallet_transaction}，对账时无法区分「本来就想记 0 元」和「填漏了」 */
    public static final String WALLET_ADJUST_AMOUNT_ZERO = "调账金额不能为 0";

    /** 这条流水没有 {@code order_id} 可以反查，{@code remark} 是这笔钱的唯一解释 */
    public static final String WALLET_ADJUST_REMARK_REQUIRED = "调账必须填写原因";

    /** 和 {@link #BALANCE_NOT_ENOUGH} 分开：那一句是给车主自己看的（下一步去充值），这一句是给运营看的，充值解决不了问题 */
    public static final String WALLET_ADJUST_BALANCE_NOT_ENOUGH =
            "该车主可用余额不足，无法扣除这笔金额";

    // ---------- 文件上传 ----------

    /** 表单里选了一个 0 字节的文件，或者压根没选 */
    public static final String UPLOAD_FILE_EMPTY = "上传文件为空";

    /** {@code %s} 是取到的后缀。白名单而不是黑名单：黑名单永远漏，而漏掉的那个会被写进静态资源目录、由 {@code /img/**} 直接对外提供 */
    public static final String UPLOAD_TYPE_UNSUPPORTED = "不支持的文件类型：%s";

    /** 文件名里没有后缀，无法判断类型。和 {@link #UPLOAD_TYPE_UNSUPPORTED} 分开，因为下一步不同 */
    public static final String UPLOAD_FILE_NAME_INVALID = "文件名不合法，缺少扩展名";
}
