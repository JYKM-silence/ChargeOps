package com.chargeops.constant;

/**
 * 提示信息常量
 *
 * <p><b>为什么提示语要集中在一个类里，而不是随手写在抛异常的地方：</b>
 * 有两个实际原因，都不是「整洁」这种审美问题：
 * <ol>
 *   <li><b>同一句话在多处出现必须字字一致。</b>比如「账号不存在」，
 *       登录接口和管理端重置密码接口都会抛。如果两处各写一遍字面量，
 *       改了一处忘了另一处，前端按 msg 做分支判断的逻辑就会在某条路径上失配。
 *       前端按文案分支本来就是脆的，至少保证后端只提供一个版本。</li>
 *   <li><b>便于统一审阅「哪些信息会透给前端」。</b>
 *       真实原因（SQL 报错、堆栈）绝不能出现在这里 —— 这个类就是一个白名单，
 *       凡是没进这个类的信息，GlobalExceptionHandler 一律翻成"未知错误"。</li>
 * </ol>
 *
 * <p><b>命名按业务域分组</b>，P1 往后会继续往充电业务那几组里加。
 * 现在只放了 P0 登录链路真正用得到的几条，不做无根据的预埋 ——
 * 一条从没被引用过的常量，半年后没人敢删它，因为不知道当初为什么加的。
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

    /** 充电枪不存在 */
    public static final String CONNECTOR_NOT_FOUND = "充电枪不存在";

    /** 充电枪已被占用（并发抢同一把枪时返回） */
    public static final String CONNECTOR_OCCUPIED = "该充电枪正在使用中，请换一把";

    /** 钱包余额不足（冻结失败时返回） */
    public static final String BALANCE_NOT_ENOUGH = "余额不足";

    // ---------------- 站点 / 充电桩 / 充电枪 ----------------

    /** 充电站不存在 */
    public static final String STATION_NOT_FOUND = "充电站不存在";

    /** 充电桩不存在 */
    public static final String CHARGER_NOT_FOUND = "充电桩不存在";

    /**
     * 站点下还有充电桩，不能删除
     *
     * <p>实际抛出时会在前面拼上具体数量，例如
     * {@code "该站点下还有 3 个充电桩，请先删除或转移"} ——
     * 光说「不允许」会让人以为是无解的限制。
     */
    public static final String STATION_HAS_CHARGER = "该站点下还有充电桩，请先删除或转移";

    /** 充电桩下还有充电枪，不能删除 */
    public static final String CHARGER_HAS_CONNECTOR = "该充电桩下还有充电枪，请先删除";

    /**
     * 充电枪当前有订单占用，不能删除
     *
     * <p>和 {@link #CONNECTOR_OCCUPIED} 的区别：那个是「用户想启动充电但枪被别人占了」
     * （业务正常流转），这个是「管理员想删枪但它正被用着」（管理操作被拒）。
     */
    public static final String CONNECTOR_IN_USE = "该充电枪当前有订单占用，不能删除";

    /**
     * 管理员试图把充电枪设成「已占用」或「充电中」
     *
     * <p>这两个状态由订单状态机写入，不是可编辑的字段 —— 详见
     * {@code ConnectorStatus} 的类注释。
     */
    public static final String CONNECTOR_STATUS_NOT_EDITABLE = "「已占用」和「充电中」由充电流程自动维护，不能手工设置";

    // ---------------- 套餐 ----------------

    /** 套餐不存在 */
    public static final String PACKAGE_NOT_FOUND = "套餐不存在";

    /**
     * 套餐的类型专属字段不匹配
     *
     * <p>两种触发方式：类型要求的字段没给，或者不该有值的字段还留着旧值。
     * 这是一条<b>前缀</b>，抛出的完整消息会拼上具体是哪个字段
     * （见 {@code ChargePackageServiceImpl.assertTypeFields}），例如：
     * <ul>
     *   <li>{@code 套餐的类型专属字段不匹配：电量包必须填写 energyKwh}</li>
     *   <li>{@code 套餐的类型专属字段不匹配：时长包不能设置 times（这些字段属于别的套餐类型，残留值会被计费逻辑读走）}</li>
     * </ul>
     *
     * <p>★ 前缀固定、后缀逐字段变化，是为了让前端至少有一个稳定的匹配点 ——
     * 后缀里带的字段名是给人看的，不是给代码分支用的。
     */
    public static final String PACKAGE_TYPE_FIELD_MISMATCH = "套餐的类型专属字段不匹配";

    // ---------------- 计费模板 ----------------

    /** 计费模板不存在 */
    public static final String TEMPLATE_NOT_FOUND = "计费模板不存在";

    /** 计费时段没有完整覆盖 24 小时（消息里会列出具体缺口） */
    public static final String TARIFF_NOT_COVER_24H = "计费时段未完整覆盖 24 小时";

    /** 计费时段之间存在重叠（消息里会列出具体冲突的两条规则） */
    public static final String TARIFF_OVERLAP = "计费时段存在重叠";

    /**
     * 模板已被站点绑定，不能修改它的计费规则
     *
     * <p>这是 {@code station_tariff} 存在的全部意义所在：订单在创建时把
     * {@code tariff_template_id} 冻结进去，结算时按那个模板计价。
     * 如果模板的规则还能改，已冻结的 id 就会指向被改过的规则 ——
     * 「电价调整不影响在途订单」这条保证就成了空话。
     *
     * <p>要调价就新建一个模板版本，用 {@code station_tariff.effective_from}
     * 绑定生效期。消息里要说清楚这一点，否则管理员只会觉得「为什么不让我改」。
     */
    public static final String TEMPLATE_IN_USE = "该模板已被站点绑定，不能修改计费规则；请新建一个模板版本";

    /** 模板已停用，不能再被新绑定 */
    public static final String TEMPLATE_DISABLED = "该计费模板已停用，不能用于新建绑定";

    // ---------------- 站点计费绑定 ----------------

    /** 站点计费绑定不存在 */
    public static final String STATION_TARIFF_NOT_FOUND = "站点计费绑定不存在";

    /**
     * 该站点在当前时刻没有生效的计费方案
     *
     * <p>{@code StationTariffService.resolveTemplateIdAt} 在
     * 「解析不出模板」时抛这条。<b>它必须是「不能充电」而不是「用默认价」</b> ——
     * 回退到一个没人批准过的默认价是一个静默的、真实的资金错误：
     * 订单会正常完成、正常结算，只有对账时那笔钱对不上。
     *
     * <p>消息里要说清楚「找谁」：这是一个需要运维介入的配置问题，
     * 而不是用户自己的操作问题。
     */
    public static final String STATION_TARIFF_NOT_BOUND = "该站点当前没有配置计费方案，请联系运营";

    // ---------------- 并发 ----------------

    /**
     * 乐观锁冲突：数据在你打开页面之后被改过
     *
     * <p>和 {@code @Version} 注解那种自动乐观锁不同，这里是手写的条件更新
     * （{@code WHERE id=? AND version=?}），影响行数为 0 时抛出。
     */
    public static final String CONCURRENT_MODIFIED = "数据已被他人修改，请刷新后重试";

    // ==================== 车主端 ====================
    // ★ 这一组刻意和上面「账号与登录」那组<b>不共用</b>。
    //   看起来 ACCOUNT_NOT_FOUND / PASSWORD_ERROR 可以复用，
    //   但会员侧和 staff 侧的登录失败语义是<b>刻意不同</b>的
    //   （见 MemberServiceImpl.login 的完整论证），
    //   复用同一个常量会让「为什么这里文案不一样」失去落点 ——
    //   改动一处文案会同时改掉另一侧的语义。

    /**
     * 车主不存在
     *
     * <p>用在两个地方，语义都是「查无此人」：
     * <ul>
     *   <li>{@code /me} 拿到了有效 token、但那条 member 记录已经不在了
     *       （token 只能证明签发时它存在）</li>
     *   <li>管理端按 id 查车主详情</li>
     * </ul>
     */
    public static final String MEMBER_NOT_FOUND = "车主不存在";

    /** 车主账号被禁用，无法登录 */
    public static final String MEMBER_DISABLED = "该账号已被禁用，请联系客服";

    /**
     * 密码登录失败的<b>合并</b>文案
     *
     * <p>「手机号没注册」和「密码错了」在车主端合并成这一句，
     * 而在管理端是分开的两句（{@link #ACCOUNT_NOT_FOUND} / {@link #PASSWORD_ERROR}）。
     *
     * <p>★ 这个差异是 {@code StaffServiceImpl.login} 的 javadoc
     * 亲口要求的：「如果这是面向公众的车主端，就该合并成一句模糊提示」。
     *
     * <p>★★ <b>但不要在代码里把它说成「防用户枚举」——那是假的。</b>
     * 注册接口按设计就会返回「这个手机号已存在」（不告诉用户就没法注册），
     * 所以「某个手机号有没有账号」这条信息在这套 API 里本来就拿得到，
     * 而且成本一样低。合并登录文案换来的是<b>一致性</b>（用户不会因为
     * 「没注册」和「密码错」而困惑于自己该去注册还是该找回密码），<b>不是安全</b>。
     * 声称一个不存在的安全收益，比不做这个防护更糟 ——
     * 后来的人会相信它，然后在真正需要防枚举的地方（比如验证码接口）
     * 以「登录已经防过了」为由跳过限流。
     *
     * <p>真正的修法是<b>在登录和注册两个口上都加限流 / 人机校验</b>，
     * 那是改动而不是文案。已记入 README 的已知缺口表。
     */
    public static final String MEMBER_LOGIN_FAILED = "手机号或密码错误";

    /**
     * 账号没有设置密码，不能用密码登录
     *
     * <p>触发条件是 {@code member.password IS NULL}，而那是 DDL 里
     * <b>合法且有意的状态</b>（「验证码登录的用户为 NULL」）。
     *
     * <p>★ 为什么它<b>不</b>并进上面那句模糊文案：那类用户拿密码登录
     * <b>永远</b>失败，如果只告诉他「手机号或密码错误」，
     * 他会一直重试密码 —— 得到的提示不指向任何能解决问题的行动。
     * 单独一句「该账号未设置密码」至少让他知道自己该走另一条路。
     *
     * <p>★ 文案里<b>不提「请使用验证码登录」</b>：验证码流程本轮根本不存在，
     * 一个指向不存在功能的提示比一句陈述事实更糟。
     * 等验证码登录落地后，在这句话后面补上引导。
     *
     * <p>★ 这<b>不是</b>用户枚举的泄漏口：它要求攻击者
     * <b>已经知道</b>这个手机号存在，且该账号恰好没设密码 ——
     * 而「手机号存不存在」本来就由注册接口给出（见上一条）。
     */
    public static final String MEMBER_PASSWORD_NOT_SET = "该账号未设置密码，无法使用密码登录";

    /** 修改密码时原密码不对 */
    public static final String OLD_PASSWORD_ERROR = "原密码错误";

    /** 车辆不存在（或不属于当前车主 —— 两种情况刻意返回同一句，见 VehicleMapper） */
    public static final String VEHICLE_NOT_FOUND = "车辆不存在";

    /** 分类不存在 */
    public static final String CATEGORY_NOT_FOUND = "分类不存在";

    /**
     * 分类类型不匹配：套餐只能挂在「充电套餐分类」下
     *
     * <p>触发条件是 {@code category.type != 1}。实际抛出时会在后面拼上
     * 具体信息，例如
     * {@code "分类类型不匹配：充电套餐只能挂在「充电套餐分类」下，当前分类是「增值服务分类」"}。
     *
     * <p>★ 它守的是一件 DDL 守不住的事：{@code charge_package.category_id}
     * 是裸的 {@code bigint}，全项目没有外键，指错 type 不撞任何约束。
     * 完整论证见 {@code CategoryType} 的类注释。
     */
    public static final String CATEGORY_TYPE_MISMATCH = "分类类型不匹配";

    /**
     * 分类下还有充电套餐，不能删除（{@code %d} 是套餐数量）
     *
     * <p>★ <b>这是一个 {@link String#format} 模板，不是一个可以直接抛出去的句子。</b>
     * 用法固定是
     * {@code throw new DataConflictException(String.format(MessageConstant.CATEGORY_HAS_PACKAGE, count))}，
     * 见 {@code CategoryServiceImpl.assertDeletable}。
     *
     * <p>为什么要带数量：消息里没有数字的话，管理员看到的是「不允许删除」——
     * 一个看起来无解的限制，他会转头去提工单，而不是去把那个套餐挪走。
     * 带上「还有 3 个」，下一步该做什么就是明摆着的。
     *
     * <p>★★ <b>为什么不照 {@link #STATION_HAS_CHARGER} 那样写一个不带数字的常量、
     * 让调用方自己拼。</b>因为那两个常量（还有 {@link #CHARGER_HAS_CONNECTOR}）
     * <b>现在就是一个反例</b>：{@code StationServiceImpl} /
     * {@code ChargerServiceImpl} 实际拼出来的是「该站点下还有 3 个充电桩，请先删除或转移」，
     * 而常量里写的是「该站点下还有充电桩，请先删除或转移」——
     * <b>两句不一样</b>，而且常量从来没被引用过，所以没有任何东西会发现这件事。
     *
     * <p>这个类的注释说得很清楚，它存在的两个理由之一是
     * 「同一句话在多处出现必须字字一致」。一个<b>和真实文案不一致且无人引用</b>的常量
     * 比没有常量更糟：它是这个类里唯一一处会让人读错用户实际看到什么的地方。
     *
     * <p>★ 所以这条<b>不复制那个写法</b>。模板 + {@code String.format}
     * 让常量保持是唯一的真本（single source of truth），而不是一个平行描述。
     * 那两条旧的常量本轮<b>不动</b>（改它们要动 {@code StationServiceImpl} /
     * {@code ChargerServiceImpl} 的已测代码，不属于 P1c-1 的范围），
     * 但它们属于「该收口的历史遗留」，已记在 README 的已知缺口表里。
     */
    public static final String CATEGORY_HAS_PACKAGE = "该分类下还有 %d 个充电套餐，请先删除或调整分类";
}
