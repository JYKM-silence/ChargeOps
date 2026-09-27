package com.chargeops.constant;

/**
 * 提示信息常量
 *
 * <p><b>为什么提示语要集中在一个类里，而不是随手写在抛异常的地方：</b>
 * <ol>
 *   <li><b>同一句话在多处出现必须字字一致。</b>比如「账号不存在」，登录接口和管理端
 *       重置密码接口都会抛；两处各写一遍字面量，改了一处忘了另一处，
 *       前端按 msg 做分支判断的逻辑就会在某条路径上失配</li>
 *   <li><b>便于统一审阅「哪些信息会透给前端」。</b>真实原因（SQL 报错、堆栈）绝不能
 *       出现在这里 —— 这个类就是一个白名单，凡是没进这个类的信息，
 *       GlobalExceptionHandler 一律翻成"未知错误"</li>
 * </ol>
 *
 * <p><b>命名按业务域分组</b>，不做无根据的预埋：一条从没被引用过的常量，
 * 半年后没人敢删它，因为不知道当初为什么加的。
 */
public class MessageConstant {

    // ==================== 账号与登录 ====================

    public static final String ACCOUNT_NOT_FOUND = "账号不存在";
    public static final String PASSWORD_ERROR = "密码错误";
    public static final String ACCOUNT_LOCKED = "账号被禁用";
    public static final String LOGIN_FAILED = "登录失败";
    public static final String USER_NOT_LOGIN = "登录状态已失效，请重新登录";
    public static final String PASSWORD_EDIT_FAILED = "密码修改失败";

    // ==================== 通用 ====================

    public static final String UNKNOWN_ERROR = "未知错误";
    public static final String ALREADY_EXISTS = "已存在";
    public static final String UPLOAD_FAILED = "文件上传失败";
    public static final String PARAM_ERROR = "参数不合法";
    public static final String NOT_FOUND = "数据不存在";
    public static final String OPERATION_FAILED = "操作失败";

    // ==================== 充电业务 ====================

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
     * {@code %d} 是<b>未完结</b>订单数量。站点的第二道删除守卫
     *
     * <p>★ <b>这一条和另外两条「还有 %d 个下级」是同一句话，但解法完全不同，
     * 所以文案也必须不同。</b>{@link #STATION_HAS_CHARGER} 的后半句是「请先删除或转移」——
     * 桩可以被挪到别的站去。而这里的对象是<b>订单</b>：订单是 append-only 的
     * 历史事实，没有「转移」这个动作，也不该被删除。唯一的出路是把它推到终态
     * （结算完、付掉、取消掉、或者人工处理掉），所以这里只能写「请先处理」。
     * 抄成「请先删除」会让管理员去找一个不存在的删除订单按钮。
     *
     * <p>★ <b>不带「或转移」，也不带「联系运维」：</b>前者是做不到，后者要看情况 ——
     * 大部分情形（待启动、充电中、待支付、支付失败）用户自己就能推着走完，
     * 只有 {@code 9 异常挂账}才真的需要运维。一句笼统的「联系运维」会让
     * 能自己解决的人先去提工单。
     *
     * <p>★ 判据是 {@code OrderStatus.blockingCodes()}（「钱还冻着」∪「挂账等人工」），
     * <b>不是</b>另外两个集合 —— 尤其是 {@code 5 已支付}特意<b>不在</b>里面，否则
     * 任何完成过一次充电的站点都会变成永久不可删。论证见 {@code OrderStatus}。
     * ★ 也别把它记成「非终态」：那个口径会把 {@code 5 已支付} 算进来，
     * 正是上面这句话要避免的结果（这个 bug 真实存在过，见 {@code blocksDeviceDeletion}）。
     */
    public static final String STATION_HAS_ORDER = "该站点下还有 %d 笔未完结的充电订单，请先处理";

    /** {@code %d} 是<b>未完结</b>订单数量。桩的第二道删除守卫，理由同 {@link #STATION_HAS_ORDER} */
    public static final String CHARGER_HAS_ORDER = "该充电桩下还有 %d 笔未完结的充电订单，请先处理";

    /**
     * 和 {@link #CONNECTOR_OCCUPIED} 的区别：那个是「用户想启动充电但枪被别人占了」
     * （业务正常流转），这个是「管理员想删枪但它正被用着」（管理操作被拒）。
     *
     * <p>★ <b>为什么这条不像 {@link #STATION_HAS_ORDER} / {@link #CHARGER_HAS_ORDER}
     * 那样带数量：</b>它的触发条件不是「有几笔订单」而是「订单表里还有指向这把枪的行」，
     * 而正常情况下一把枪上<b>最多只有一笔</b>进行中的订单 —— 报「还有 1 笔」比
     * 报「有订单占用」多不出任何信息。真的出现两笔时（并发建单、历史脏数据）
     * 那是需要查数据异常，而不是需要管理员知道数字 2；
     * 那种情况由 {@link #ORDER_CONNECTOR_STATE_INVALID} 负责，它说的是另一件事。
     */
    public static final String CONNECTOR_IN_USE = "该充电枪当前有订单占用，不能删除";

    /**
     * 管理员试图把充电枪设成「已占用」或「充电中」
     *
     * <p>这两个状态由订单状态机写入，不是可编辑的字段 —— 详见 {@code ConnectorStatus}。
     */
    public static final String CONNECTOR_STATUS_NOT_EDITABLE = "「已占用」和「充电中」由充电流程自动维护，不能手工设置";

    // ---------------- 套餐 ----------------

    public static final String PACKAGE_NOT_FOUND = "套餐不存在";

    /**
     * 两种触发方式：类型要求的字段没给，或者不该有值的字段还留着旧值。
     * 这是一条<b>前缀</b>，抛出的完整消息会拼上具体是哪个字段
     * （见 {@code ChargePackageServiceImpl.assertTypeFields}），例如
     * {@code 套餐的类型专属字段不匹配：时长包不能设置 times（这些字段属于别的套餐类型，残留值会被计费逻辑读走）}。
     *
     * <p>★ 前缀固定、后缀逐字段变化，是为了让前端至少有一个稳定的匹配点 ——
     * 后缀里带的字段名是给人看的，不是给代码分支用的。
     */
    public static final String PACKAGE_TYPE_FIELD_MISMATCH = "套餐的类型专属字段不匹配";

    /**
     * 车主想买的套餐已下架
     *
     * <p>★ 它和 {@link #PACKAGE_NOT_FOUND} 是<b>刻意分开</b>的两句：
     * 「没有这个套餐」（id 错了 / 被硬删了）和「这个套餐现在不卖了」
     * 对车主是两件不同的事 —— 后者意味着「你稍后再来或者换个套餐」，
     * 前者意味着「你的链接是旧的」。合并成一句会让第二种情况看起来像数据错误。
     *
     * <p>★ 这里<b>不区分</b>「下架」和「被硬删」，前者是常态后者是异常，
     * 但对车主都不改变他要做的事：换一个套餐。
     */
    public static final String PACKAGE_OFF_SHELF = "该套餐已下架";

    /**
     * 套餐的<b>类型专属列</b>在库里是空的，卖不出去 —— 前缀，完整消息会拼上列名和类型
     *
     * <p>★ 触发条件：{@code charge_package} 里某一行 {@code package_type = 1}
     * 而 {@code energy_kwh IS NULL} 之类。<b>正常路径下到不了这里</b>
     * （{@code ChargePackageServiceImpl.assertTypeFields} 在写入时就拦住了），
     * 能走到说明有人<b>直接改过库</b>，或者校验上线之前留下了数据。
     *
     * <p>★★ <b>为什么宁可在这里抛异常，也不「跳过这个套餐」：</b>★★
     * 一个 {@code energy_kwh} 为空的电量包如果被卖掉，会产生一份
     * {@code remaining_kwh IS NULL} 的权益，而「这份权益还能用吗」的判据是
     * {@code remaining_kwh > 0} —— <b>NULL 参与比较恒为 false</b>，
     * 于是它永远不会被结算选中，也永远不会被标成「已用尽」。
     * 用户看到的是「我买了套餐，但充电时一直按余额扣钱」，
     * 而库里那行数据看起来完全正常。这是一条<b>全程静默</b>的失败路径。
     *
     * <p>★ 消息会拼上具体是哪个字段，因为修它的人需要知道去改哪一列。
     */
    public static final String PACKAGE_FIELD_MISSING = "该套餐的数据不完整，无法购买";

    /**
     * 购买请求太频繁 —— {@code CacheClient.tryLock} 没抢到锁
     *
     * <p>★ <b>措辞是「正在处理」而不是「购买失败」</b>，这是一个真实的区别：
     * 没抢到锁意味着<b>上一次请求还在跑</b>（正常情况几十毫秒就结束了），
     * 它的结果大概率是成功的。说「失败」会让车主以为要重来一遍。
     *
     * <p>★ 文案里<b>不提示「请稍后重试」之外的做法</b>：
     * 这条路径下用户唯一该做的就是等一下再点，而它几乎总是会成功。
     */
    public static final String PACKAGE_PURCHASE_DUPLICATE = "购买请求正在处理中，请勿重复提交";

    // ---------------- 计费模板 ----------------

    public static final String TEMPLATE_NOT_FOUND = "计费模板不存在";

    /** 计费时段没有完整覆盖 24 小时（消息里会列出具体缺口） */
    public static final String TARIFF_NOT_COVER_24H = "计费时段未完整覆盖 24 小时";

    /** 计费时段之间存在重叠（消息里会列出具体冲突的两条规则） */
    public static final String TARIFF_OVERLAP = "计费时段存在重叠";

    /**
     * 模板已被站点绑定，不能修改它的计费规则
     *
     * <p>这是 {@code station_tariff} 存在的全部意义所在：订单在创建时把
     * {@code tariff_template_id} 冻结进去，结算时按那个模板计价。如果模板的规则还能改，
     * 已冻结的 id 就会指向被改过的规则 ——「电价调整不影响在途订单」这条保证就成了空话。
     * 要调价就新建一个模板版本，用 {@code station_tariff.effective_from} 绑定生效期；
     * 消息里要说清这一点，否则管理员只会觉得「为什么不让我改」。
     */
    public static final String TEMPLATE_IN_USE = "该模板已被站点绑定，不能修改计费规则；请新建一个模板版本";

    public static final String TEMPLATE_DISABLED = "该计费模板已停用，不能用于新建绑定";

    // ---------------- 站点计费绑定 ----------------

    public static final String STATION_TARIFF_NOT_FOUND = "站点计费绑定不存在";

    /**
     * 该站点在当前时刻没有生效的计费方案
     *
     * <p>{@code StationTariffService.resolveTemplateIdAt} 在「解析不出模板」时抛这条。
     * <b>它必须是「不能充电」而不是「用默认价」</b> —— 回退到一个没人批准过的默认价
     * 是一个静默的、真实的资金错误：订单会正常完成、正常结算，只有对账时那笔钱对不上。
     * 消息里还要说清「找谁」：这是需要运维介入的配置问题，不是用户自己的操作问题。
     */
    public static final String STATION_TARIFF_NOT_BOUND = "该站点当前没有配置计费方案，请联系运营";

    // ---------------- 并发 ----------------

    /**
     * 乐观锁冲突：数据在你打开页面之后被改过
     *
     * <p>和 {@code @Version} 那种自动乐观锁不同，这里是手写的条件更新
     * （{@code WHERE id=? AND version=?}），影响行数为 0 时抛出。
     */
    public static final String CONCURRENT_MODIFIED = "数据已被他人修改，请刷新后重试";

    // ==================== 车主端 ====================
    // ★ 这一组刻意和上面「账号与登录」那组<b>不共用</b>。看起来
    //   ACCOUNT_NOT_FOUND / PASSWORD_ERROR 可以复用，但会员侧和 staff 侧的
    //   登录失败语义是<b>刻意不同</b>的，复用会让「文案为什么不一样」失去落点。

    /**
     * 用在两处，语义都是「查无此人」：{@code /me} 拿到了有效 token 但那条 member
     * 记录已经不在了（token 只能证明签发时它存在）、管理端按 id 查车主详情。
     */
    public static final String MEMBER_NOT_FOUND = "车主不存在";

    public static final String MEMBER_DISABLED = "该账号已被禁用，请联系客服";

    /**
     * 手机号前缀太短，一次匹配到的车主过多
     *
     * <p>触发条件是「按手机号搜订单」时前缀命中的 {@code member} 行数
     * 达到了上限（见 {@code ChargingOrderServiceImpl.MEMBER_PREFIX_LIMIT}）。
     *
     * <p>★★ <b>为什么是抛异常，而不是「截断到前 N 个然后照常返回」。</b>★★
     * 截断的返回是一个<b>看起来正常的结果</b> —— 运维搜 {@code 138}
     * 拿到 500 个车主的订单，列表不会告诉他「还有 8 万条没显示」，
     * 于是他得出的结论是「这个前缀就这么多单」。<b>一个错误答案比一个错误提示危险得多</b>。
     *
     * <p>★ 这条文案必须<b>告诉用户怎么办</b>（多输入几位），否则它就是一个死胡同：
     * 管理员会去提工单，而不是把手上的号码多敲三位。判据和
     * {@link #CATEGORY_HAS_PACKAGE} 一致 —— 拒绝的时候要给出路。
     */
    public static final String MEMBER_PREFIX_TOO_BROAD = "该手机号前缀匹配到的车主过多，请多输入几位数字后重试";

    /**
     * 密码登录失败的<b>合并</b>文案
     *
     * <p>「手机号没注册」和「密码错了」在车主端合并成这一句，而在管理端是分开的两句
     * （{@link #ACCOUNT_NOT_FOUND} / {@link #PASSWORD_ERROR}）。这个差异是
     * {@code StaffServiceImpl.login} 的 javadoc 亲口要求的：
     * 「如果这是面向公众的车主端，就该合并成一句模糊提示」。
     *
     * <p>★ <b>但不要在代码里把它说成「防用户枚举」——那是假的。</b>
     * 注册接口按设计就会返回「这个手机号已存在」（不告诉用户就没法注册），
     * 所以「某个手机号有没有账号」这条信息在这套 API 里本来就拿得到，成本一样低。
     * 合并登录文案换来的是<b>一致性</b>（用户不会因为「没注册」和「密码错」而困惑于
     * 该去注册还是该找回密码），<b>不是安全</b>。声称一个不存在的安全收益比不做这个防护
     * 更糟 —— 后来的人会相信它，然后在真正需要防枚举的地方（比如验证码接口）
     * 以「登录已经防过了」为由跳过限流。真正的修法是在登录和注册两个口上都加
     * 限流 / 人机校验，那是改动而不是文案，已记入 README 的已知缺口表。
     */
    public static final String MEMBER_LOGIN_FAILED = "手机号或密码错误";

    /**
     * 账号没有设置密码，不能用密码登录
     *
     * <p>触发条件是 {@code member.password IS NULL}，而那是 DDL 里
     * <b>合法且有意的状态</b>（「验证码登录的用户为 NULL」）。
     *
     * <p>★ 为什么它<b>不</b>并进上面那句模糊文案：那类用户拿密码登录<b>永远</b>失败，
     * 只告诉他「手机号或密码错误」的话他会一直重试密码，而提示不指向任何能解决问题的行动。
     * 文案里也<b>不提「请使用验证码登录」</b>：验证码流程根本不存在，
     * 一个指向不存在功能的提示比一句陈述事实更糟。
     *
     * <p>★ 这<b>不是</b>用户枚举的泄漏口：它要求攻击者<b>已经知道</b>这个手机号存在，
     * 且该账号恰好没设密码 —— 而「手机号存不存在」本来就由注册接口给出（见上一条）。
     */
    public static final String MEMBER_PASSWORD_NOT_SET = "该账号未设置密码，无法使用密码登录";

    public static final String OLD_PASSWORD_ERROR = "原密码错误";

    /** 车辆不存在。★ 不属于当前车主时也返回这一句，见 {@code VehicleMapper} */
    public static final String VEHICLE_NOT_FOUND = "车辆不存在";

    public static final String CATEGORY_NOT_FOUND = "分类不存在";

    /**
     * 分类类型不匹配：套餐只能挂在「充电套餐分类」下
     *
     * <p>触发条件是 {@code category.type != 1}。实际抛出时会在后面拼上具体信息，例如
     * {@code "分类类型不匹配：充电套餐只能挂在「充电套餐分类」下，当前分类是「增值服务分类」"}。
     *
     * <p>★ 它守的是一件 DDL 守不住的事：{@code charge_package.category_id} 是裸的
     * {@code bigint}，全项目没有外键，指错 type 不撞任何约束。论证见 {@code CategoryType}。
     */
    public static final String CATEGORY_TYPE_MISMATCH = "分类类型不匹配";

    /**
     * {@code %d} 是套餐数量
     *
     * <p>★ <b>这是一个 {@link String#format} 模板，不是一个可以直接抛出去的句子。</b>
     * 用法是
     * {@code throw new DataConflictException(String.format(MessageConstant.CATEGORY_HAS_PACKAGE, count))}。
     * 本类里带 {@code %d} 的<b>五条</b>（还有 {@link #STATION_HAS_CHARGER} /
     * {@link #CHARGER_HAS_CONNECTOR} / {@link #STATION_HAS_ORDER} /
     * {@link #CHARGER_HAS_ORDER}）都是这个用法。
     *
     * <p>★ 这五条是<b>全部</b>「删除守卫」类消息，可以拿来当清单用：
     * 每给一个资源加一道删除前置检查，就该在这个列表里多一条 ——
     * 不加的后果是把「有几笔/几个」这个信息丢掉，而那句话本身
     * 就是给管理员的行动指引（见下面那段）。
     *
     * <p>为什么句中要带数量：只说「不允许删除」在管理员看到的是一个无解的限制，
     * 他会去提工单，而不是去把那个套餐挪走。
     */
    public static final String CATEGORY_HAS_PACKAGE = "该分类下还有 %d 个充电套餐，请先删除或调整分类";

    // ==================== P1c-3 订单 ====================

    /** 订单不存在。★ 车主端查别人的订单时也报这一句 —— 见 {@code ChargingOrderMapper.getByIdAndMember} */
    public static final String ORDER_NOT_FOUND = "订单不存在";

    /**
     * 订单状态不允许这次操作
     *
     * <p>★ 触发条件是「状态条件更新影响了 0 行」，也就是<b>别人先动了这一行</b>（并发）
     * 或者<b>当前状态根本不该做这件事</b>（重复请求、顺序错乱）。两者对用户是同一件事：
     * 你现在做不了这个操作，刷新看看。
     *
     * <p>★ 消息里带上了当前状态和期望状态，见 {@code ChargingOrderServiceImpl} 的调用处 ——
     * 只有「操作失败」三个字的话，用户和客服都无法判断这是并发还是用错了流程。
     */
    public static final String ORDER_STATUS_CONFLICT = "订单当前状态不允许该操作";

    /**
     * 该车辆在别的订单上正在充电
     *
     * <p>★ 同一时刻一辆车只能有一笔进行中的订单。拦不住的话，用户会拿到两笔都算满额的
     * 订单，而设备那边只充了一次电。
     */
    public static final String ORDER_VEHICLE_BUSY = "该车辆有进行中的充电订单，请先结束";

    /**
     * 该充电枪上的订单状态异常，需要人工处理
     *
     * <p>★ 触发条件是「一把枪上有多于一笔进行中的订单」。正常不该发生，真发生了说明
     * 有并发建单或历史脏数据 —— 那时候<b>必须停下来</b>：随便挑一笔继续，
     * 另一笔就会永远停在充电中，而它的钱还冻着。
     */
    public static final String ORDER_CONNECTOR_STATE_INVALID = "该充电枪上的订单状态异常，请联系运维";

    /** 权益已被占用（并发下没抢到，且没有下一份可试） */
    public static final String MEMBER_PACKAGE_LOCKED = "所选权益已被其他订单占用，请重试";

    public static final String ORDER_METER_READING_MISSING = "缺少电表读数，无法计算用电量，已转入异常挂账";

    public static final String ORDER_METER_READING_REGRESSED = "结束电表读数小于起始读数，已转入异常挂账";

    /**
     * 该支付方式暂不支持
     *
     * <p>★ 见 {@code PayMethod} 的类注释：{@code 2微信} / {@code 3支付宝} 会走到这里。
     * 这是刻意的 —— 一个「微信支付」按钮点了之后从余额扣钱，比没有这个按钮严重得多。
     */
    public static final String ORDER_PAY_METHOD_UNSUPPORTED = "该支付方式暂未支持，请使用余额支付";

    // ==================== 管理端：权益作废/退款 ====================

    /**
     * 权益不存在
     *
     * <p>★ 和 {@link #PACKAGE_NOT_FOUND}（「套餐不存在」，说的是 SKU）<b>刻意分成两句</b>：
     * 管理端在这个接口上同时会用到两个 id —— 路径里的「持有权益 id」和这一行指向的
     * {@code package_id}。共用一句的话，运维看到「套餐不存在」时无法判断
     * 是自己把权益 id 填错了，还是这份权益指向的 SKU 被删了。
     */
    public static final String MEMBER_PACKAGE_NOT_FOUND = "权益不存在";

    /**
     * 权益当前状态不允许该操作
     *
     * <p>★ 触发条件和 {@link #ORDER_STATUS_CONFLICT} 一样，是「条件更新影响了 0 行」。
     * 这里的实际含义几乎总是<b>已经作废过了</b>（重复点击、或者两个人同时点了），
     * 但<b>不要</b>把文案写成「该权益已作废」—— 影响 0 行还可能是状态被别的路径改成了
     * {@code 2 已用尽 / 3 已过期}，那时说「已作废」是一句错误的话。
     * 具体的当前状态由调用方拼在后面（见 {@code MemberPackageServiceImpl.voidPackage}）。
     */
    public static final String MEMBER_PACKAGE_STATUS_CONFLICT = "权益当前状态不允许该操作";

    /**
     * 该权益已经被若干笔订单用过，不能退款。{@code %d} 是订单笔数
     *
     * <p>★ 这是本轮定下的退款口径：<b>只退完全没用过的，全额退</b>。
     * 一旦这份权益被任何一笔<b>非取消</b>的订单引用过，就整份不退 ——
     * 包括那些还没付钱的（{@code 4 待支付} / {@code 6 支付失败}）
     * 和已经挂账的（{@code 9}）。判据的完整论证见
     * {@code ChargingOrderMapper#countByPackage}。
     *
     * <p>★ <b>带笔数是为了让这句话可核对。</b>运营看到「被 2 笔订单用过」之后
     * 能自己去查那两笔，而不是和客服来回扯「到底用没用过」。
     * 和 {@link #CATEGORY_HAS_PACKAGE} 那几条带 {@code %d} 的是同一条理由。
     *
     * <p>★ 不用「按剩余量折算退款」的理由：{@code remaining_*} 是<b>结算时</b>才知道的数字，
     * 而结算价、折扣、服务费的组合口径是一套产品假设（README 已记）。
     * 从「还剩 120 度」反推「该退多少钱」需要重算一遍当时的价格，
     * 而那个价格已经不可复现 —— 一笔错的钱比一句「不能退」严重得多。
     *
     * <p>★★ <b>措辞说的是「被订单用过」，不是「产生过消费」。</b>★★
     * 后者会让人以为「只有结算过才算」—— 而 {@code 4 待支付} 的订单
     * <b>余量已经扣了、钱还没收到</b>，它同样挡退款。
     * 日志里那句更细的话（「包括正在进行的和已结算的」）留在这里，不上面向前端。
     */
    public static final String MEMBER_PACKAGE_ALREADY_USED = "该权益已经被 %d 笔订单用过，不能退款";

    /**
     * 该权益被一笔进行中的订单占着，不能退款
     *
     * <p>★ 不复用 {@link #MEMBER_PACKAGE_LOCKED}：那一句的落点是「请重试」，
     * 属于充电建单路径上的并发提示。这里的情况完全不同 ——
     * 有一笔订单<b>正拿着这份权益在充电</b>，管理员该做的是等它结束或者先处理那笔订单，
     * 而不是重试。{@code locked_order_id} 非空就是它的判据。
     */
    public static final String MEMBER_PACKAGE_IN_USE = "该权益被一笔进行中的订单占用，请先处理那笔订单";

    /**
     * 这份权益没有可退的金额
     *
     * <p>★ 触发条件是 {@code member_package.pay_amount IS NULL}。那一列是
     * {@code V3__member_package_pay_amount.sql} 新加的，<b>对建于 V3 之前的行不回溯</b>。
     *
     * <p>★★ <b>为什么不拿 {@code charge_package.price} 现算一个：</b>那个价格是可编辑的，
     * 用它等于「按今天的售价退去年的钱」。{@code ChargingOrderSweepJob.STALE_REASON}
     * 那一段已经为同一类问题立过规矩 —— 不要为了填一个空而把可变的配置
     * 固化成一个看起来像事实的数字。<b>宁可拒绝，也不要编一个金额出来。</b>
     */
    public static final String MEMBER_PACKAGE_PAY_AMOUNT_MISSING =
            "该权益没有购买金额快照（建于本功能上线之前），无法计算退款金额，请人工处理";

    // ★ 这里曾经有一条 MEMBER_PACKAGE_FREE（「该权益实付 0 元，没有可退金额」），
    //   在落地时<b>被删掉了</b>，因为找不到一个诚实的调用点：
    //   赠送套餐（pay_amount = 0）**是可以作废的**，只是没有钱退 ——
    //   「作废」讲的是让这一行失效，「退款」是它的副作用，两者不该绑在一起。
    //   运营想清掉一份发错的赠送套餐是正当需求，不该被一句
    //   「没有可退金额」挡住（那句话答的不是他问的问题）。
    //   所以 0 元走的是**正常作废 + 退款 0.00**，不是异常。
    //   ★ 留这段注释是因为「明明见过那条消息」的记忆会让人以为它被误删了。

    // ==================== 管理端：钱包调账 ====================

    /**
     * 调账金额不能为 0
     *
     * <p>★ 0 元的调账在账面上什么都不改变，但它会写下一条
     * {@code wallet_transaction}。不拦的话，一个把「金额」输入框留空的运营
     * 每次点提交都会往车主流水里塞一条 0 元记录，而对账时无法区分
     * 「他本来就想记一笔 0 元」和「他填漏了」。拒掉它，让意图明确。
     */
    public static final String WALLET_ADJUST_AMOUNT_ZERO = "调账金额不能为 0";

    /**
     * 调账必须填写原因
     *
     * <p>★ {@code WalletTransactionType.ADMIN_ADJUST} 的 javadoc 写着
     * 「唯一一个不由订单流程驱动的类型，必须留 remark 说明原因」。
     * 这条流水上没有 {@code order_id} 可以反查 —— {@code remark} <b>是</b>
     * 这笔钱的唯一解释。半年后对账的人只能看到这一列。
     */
    public static final String WALLET_ADJUST_REMARK_REQUIRED = "调账必须填写原因";

    /**
     * 调账扣款时可用余额不足
     *
     * <p>★ 和 {@link #BALANCE_NOT_ENOUGH} 分开：那一句是给<b>车主自己</b>看的
     * （「余额不足」，下一步是去充值），而这一句是给<b>运营</b>看的 ——
     * 该做的是改小金额或者换一笔，充值解决不了问题。
     */
    public static final String WALLET_ADJUST_BALANCE_NOT_ENOUGH =
            "该车主可用余额不足，无法扣除这笔金额";

    // ==================== 文件上传 ====================

    /** 上传的文件为空。★ 表单里选了一个 0 字节的文件，或者压根没选 */
    public static final String UPLOAD_FILE_EMPTY = "上传文件为空";

    /**
     * 不支持的文件类型。{@code %s} 是取到的后缀
     *
     * <p>★ 白名单而不是黑名单：只放行几个图片后缀，其余一律拒绝。
     * 黑名单（禁 {@code jsp/phtml/...}）永远漏，而漏掉的那个会被写进
     * 静态资源目录、由 {@code /img/**} 直接对外提供。
     */
    public static final String UPLOAD_TYPE_UNSUPPORTED = "不支持的文件类型：%s";

    /** 文件名里没有后缀，无法判断类型。★ 和 {@link #UPLOAD_TYPE_UNSUPPORTED} 分开，因为下一步不同 */
    public static final String UPLOAD_FILE_NAME_INVALID = "文件名不合法，缺少扩展名";
}
