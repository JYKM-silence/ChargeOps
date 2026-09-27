package com.chargeops.service;

import com.chargeops.dto.MemberLoginDTO;
import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.dto.MemberPasswordDTO;
import com.chargeops.dto.MemberRegisterDTO;
import com.chargeops.dto.MemberUpdateDTO;
import com.chargeops.entity.Member;
import com.chargeops.result.PageResult;
import com.chargeops.vo.MemberLoginVO;
import com.chargeops.vo.MemberVO;

/**
 * 车主服务
 *
 * <p>同时服务<b>车主端</b>（{@link #getCurrentMember} / {@link #updateProfile} /
 * {@link #changePassword}）和<b>管理端</b>（{@link #page} / {@link #getById} /
 * {@link #updateStatus}），外加两个免登录入口（{@link #register} / {@link #login}）。
 *
 * <h2>★★ 一、车主端方法<b>一个 id 参数都没有</b>，这是这个接口最重要的形状</h2>
 *
 * <pre>
 *   getCurrentMember()                  ← 没有参数
 *   updateProfile(MemberUpdateDTO)      ← 没有 id，DTO 里也没有
 *   changePassword(MemberPasswordDTO)   ← 同上
 * </pre>
 *
 * <p>管理端的三个方法<b>都有 id</b>：车主端的「改谁」来自 <b>token</b>
 * （{@code BaseContext}），管理端的来自<b>路径参数</b>（管理员显式指定）。
 *
 * <p>★ 把「改谁」做成路径参数，是越权漏洞最常见的来源：接口写成
 * {@code PUT /api/member/{id}}，它同时就是「改自己」和「改任意人」两个接口，
 * 区别只在调用方传了什么 —— 服务端必须靠一句 {@code if (id != currentId) throw}
 * 来区分，那句话漏了、写反了、或者加了个新分支忘了带，漏洞就出现了。
 * <b>让「改自己」在接口形状上不可能表达「改别人」，比在方法体里拦住它可靠</b> ——
 * 这和 {@code MemberUpdateDTO} 里没有 {@code phone} / {@code status} 是同一类做法：
 * <b>用「字段/参数不存在」代替「记得校验」</b>。
 *
 * <h2>★★ 二、{@link #register} 和 {@link #login} 都返回 token</h2>
 *
 * <p>注册即登录 —— 用户刚证明了他知道密码（是他自己设的），让他再发一次登录请求
 * 是新用户路径上的一次白跑。★ 两者<b>共用同一个私有的签发方法</b>
 * （{@code MemberServiceImpl.issueToken}），不是为少写几行，而是让「用哪个密钥、
 * 放哪个 claim 名」在会员侧只出现一次。
 *
 * <h2>★ 三、管理端不做新建、不做硬删</h2>
 *
 * <p>{@link #page} / {@link #getById} / {@link #updateStatus} 没有 {@code create}
 * 也没有 {@code delete}，这是<b>刻意的</b>：<b>不新建</b>（账号来源已定为自助注册；
 * 管理端建号会引入「初始密码怎么安全送达」这个接口层解决不了的问题 ——
 * 发短信没设施、口头告知就没有审计）；<b>不硬删</b>（车主有订单、钱包、车辆，
 * 硬删会让它们全变成孤儿，禁用就是停用手段）。
 *
 * <p>★★ 这和 {@code StationService} / {@code CategoryService} 的「硬删必须存在」是
 * <b>同一个项目里相反的两个决定</b>：那两个域的唯一键（{@code code} / {@code name}）
 * 是<b>人造的</b>，建错了不硬删就得去生产库手敲 DELETE；而 {@code phone} 是
 * <b>真实世界的标识</b>，不存在「建错了」。
 */
public interface MemberService {

    /**
     * 车主自助注册（<b>注册即登录</b>，返回的 token 可直接用）
     *
     * <p>★ 密码在这里被 BCrypt 加密，明文不会被存下来、也不会进日志。
     *
     * <p>★ <b>不预检手机号是否重复</b>，直接插，让 {@code uk_member_phone} 去挡 ——
     * 预检既有多线程下的 TOCTOU，又是在应用层重复数据库已经提供的保证。撞键抛出的
     * {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler} 翻成
     * <b>「13800000001已存在」</b>，所有唯一索引共享同一个翻译点。
     *
     * @return 车主 id + 手机号 + 昵称 + token
     * @throws org.springframework.dao.DuplicateKeyException 手机号已注册
     */
    MemberLoginVO register(MemberRegisterDTO memberRegisterDTO);

    /**
     * 车主登录
     *
     * <p>★ <b>「手机号没注册」和「密码错误」返回同一句模糊文案</b>
     * （{@code MEMBER_LOGIN_FAILED}），和管理端<b>刻意不同</b>。
     * 但<b>这不是一个安全收益</b>：注册接口按设计就会泄露「这个手机号
     * 有没有账号」，换来的是<b>一致性</b>。见
     * {@code MessageConstant.MEMBER_LOGIN_FAILED} 的注释。
     *
     * <p>★ <b>「该账号未设置密码」是单独一句</b>（{@code MEMBER_PASSWORD_NOT_SET}）：
     * 那类用户拿密码登录<b>永远</b>失败，混进模糊文案里他会一直重试密码。
     *
     * @throws com.chargeops.exception.AccountNotFoundException 手机号或密码错误（合并文案）
     * @throws com.chargeops.exception.PasswordErrorException    手机号或密码错误（合并文案）
     * @throws com.chargeops.exception.AccountLockedException    账号已被禁用
     * @throws com.chargeops.exception.ParamErrorException       账号未设置密码
     */
    MemberLoginVO login(MemberLoginDTO memberLoginDTO);

    /**
     * 查询<b>当前登录车主</b>（车主端的启动探针）
     *
     * <p>★ 返回的 {@code MemberVO} 里带 {@code status}，所以行为正常的客户端能在启动时
     * 看到「我被禁用了」并主动登出。这不能替代服务端强制（禁用不撤销已签发的 token，
     * 窗口最长 7 天），但把例外的范围从「所有客户端」缩小到了「从不调 /me 的客户端」。
     *
     * @throws com.chargeops.exception.UserNotLoginException   没有登录上下文
     * @throws com.chargeops.exception.AccountNotFoundException  token 有效但账号已不存在
     */
    MemberVO getCurrentMember();

    /**
     * 修改<b>当前登录车主</b>的资料（昵称 / 头像 / 性别）
     *
     * <p>★ 改不了 {@code phone} 和 {@code status}，见 {@link MemberUpdateDTO}。
     *
     * @throws com.chargeops.exception.UserNotLoginException   没有登录上下文
     * @throws com.chargeops.exception.AccountNotFoundException  token 有效但账号已不存在
     * @throws com.chargeops.exception.ParamErrorException       性别取值非法
     */
    void updateProfile(MemberUpdateDTO memberUpdateDTO);

    /**
     * 修改<b>当前登录车主</b>的密码（必须验旧密码）
     *
     * <p>★ 不验旧密码的话，一个被偷走的 token 就等于永久账号接管，
     * 见 {@link MemberPasswordDTO} 的类注释第二节。
     *
     * <p>★ <b>未设密码的账号（{@code password IS NULL}）改不了密码</b>，而且这是对的：
     * 能走到这个接口的唯一凭证是一个 token，而那个账号从来没设过密码，所以
     * <b>没有任何东西能证明来的人是机主</b> —— 跳过旧密码校验会把「持有 token」
     * 升级成「设置一个永久密码」，即把一个 7 天的窗口变成永久接管。
     *
     * @throws com.chargeops.exception.UserNotLoginException   没有登录上下文
     * @throws com.chargeops.exception.AccountNotFoundException  token 有效但账号已不存在
     * @throws com.chargeops.exception.ParamErrorException       原密码错误
     */
    void changePassword(MemberPasswordDTO memberPasswordDTO);

    /**
     * 分页查询车主（<b>管理端</b>）
     *
     * <p>★ 手机号是<b>前缀</b>匹配、昵称是<b>模糊</b>匹配、状态是精确匹配 ——
     * 为什么这样分，见 {@code MemberMapper.xml} 里 {@code page} 上方的注释。
     */
    PageResult page(MemberPageQueryDTO query);

    /**
     * 按 id 查车主详情（<b>管理端</b>）
     *
     * @throws com.chargeops.exception.NotFoundException 车主不存在
     */
    MemberVO getById(Long id);

    /**
     * 启用 / 禁用车主（<b>管理端</b>）
     *
     * <p>★★ <b>禁用不会撤销已经签发的 token。</b>拦截器只验签名、从不查库，而
     * member token 的有效期是 7 天 —— 所以一个刚被禁用的车主，手上的 token
     * 最长还能用 7 天。
     *
     * <p><b>接受并记录</b>这个窗口，不引入黑名单：那等于每个请求一次 Redis 往返。
     * ★ <b>真正要紧的是钱包和订单端点 —— 那些必须在 Service 里重新查一次
     * {@code member.status}</b>（见 {@link #assertActive}，已落地）。
     *
     * <p>★ 这<b>不是</b>「拦截器该做的事」：拦截器是<b>每个请求</b>都跑的，而该不该
     * 查状态取决于<b>这个接口有没有资金影响</b>，那是每个 Service 自己知道的事。
     *
     * @throws com.chargeops.exception.NotFoundException 车主不存在
     * @throws com.chargeops.exception.ParamErrorException 状态取值不是 0/1
     */
    void updateStatus(Long id, Integer status);

    /**
     * 确认车主存在且未被禁用，返回该车主
     *
     * <p>★ 这就是上面那条缺口要求的落点：<b>有资金影响的端点每次都要调它</b> ——
     * token 有效 ≠ 账号可用。
     *
     * <p>★ <b>按「这个接口有没有资金影响」判断，不是按读写判断</b>：有资金影响的一律要调，
     * 哪怕只读 —— {@code pageMine} / {@code getMine} 都是 {@code readOnly} 但仍然查。
     * 反例是 {@code GET /api/member/me} 和「我的车辆」：被禁用的车主看看自己的资料
     * 不产生任何后果，调它只是多一次查库。不放在拦截器里，理由同上。
     *
     * <p>★ <b>唯一的例外是 {@code pay}</b>，那里<b>不能</b>查 —— 理由见方法上方的注释。
     *
     * @return 车主实体（调用方通常还要用它的其他字段）
     * @throws com.chargeops.exception.AccountLockedException 账号已禁用
     * @throws com.chargeops.exception.NotFoundException      车主不存在
     */
    Member assertActive(Long memberId);
}
