package com.chargeops.service.impl;

import com.chargeops.constant.JwtClaimsConstant;
import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.context.BaseContext;
import com.chargeops.dto.MemberLoginDTO;
import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.dto.MemberPasswordDTO;
import com.chargeops.dto.MemberRegisterDTO;
import com.chargeops.dto.MemberUpdateDTO;
import com.chargeops.entity.Member;
import com.chargeops.enumeration.Gender;
import com.chargeops.exception.AccountLockedException;
import com.chargeops.exception.AccountNotFoundException;
import com.chargeops.exception.LoginFailedException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.PasswordErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.MemberMapper;
import com.chargeops.properties.JwtProperties;
import com.chargeops.result.PageResult;
import com.chargeops.service.MemberService;
import com.chargeops.utils.JwtUtil;
import com.chargeops.vo.MemberLoginVO;
import com.chargeops.vo.MemberVO;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车主服务实现
 *
 * <p>形状沿用 {@code StaffServiceImpl} —— 那三个检查的顺序、构造器注入、
 * {@code BaseContext} 的用法都是照搬的。这里只写<b>会员侧特有的部分</b>。
 *
 * <h2>★★ 一、{@link #issueToken} 是整个会员侧<b>唯一</b>的 token 签发点</h2>
 *
 * <p>{@code StaffServiceImpl} 是在 {@code login} 方法体里<b>内联</b>构建 claims 的。
 * 会员侧的 {@code login} 和 {@code register} 如果各抄一遍，就有<b>两处</b>可能把
 * {@code getAdminSecretKey()} / {@code JwtClaimsConstant.STAFF_ID} 写进去 ——
 * 这<b>是编译期拦不住的</b>：两个 getter 返回的都是 {@code String}，两个常量也是。
 *
 * <p>★ 写错的后果很具体：用 admin 密钥签发的 token 发给车主端，
 * {@code JwtTokenMemberInterceptor} 用 member 密钥验签会失败 →
 * <b>所有车主端请求 401</b>，而登录接口返回 200 且带着一个看起来完全正常的 token。
 * 所以：让密钥和 claim 名在会员侧只出现一次，两个入口都调这个方法。
 *
 * <h2>★ 二、{@link #login} 刻意<b>不加</b> {@code @Transactional}</h2>
 *
 * <p>它只有一条写语句（更新最后登录时间），没有需要原子性的多条写 ——
 * 加 {@code @Transactional} 只会让人以为这里有什么一致性要求。
 *
 * <p>★ 更要紧的是那条写的<b>语义</b>：它是记账，不是鉴权。「最后登录时间没记上」
 * 不该让一次密码正确的登录失败，所以它被 try-catch 包住，失败只记 warn。
 *
 * <h2>★ 三、车主端三个方法的第一件事都是 {@link BaseContext#getCurrentId()}</h2>
 *
 * <p>而管理端的三个方法都接收一个显式的 id。完整论证见 {@link MemberService}
 * 的类注释，一句话：<b>「改自己」不该有能力表达「改别人」。</b>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberMapper memberMapper;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    /**
     * 构造器注入。前三个理由和 {@code StaffServiceImpl} 完全相同（构造期发现循环依赖、
     * 依赖不可变、必填性由编译器保证）。第四个在这里尤其要紧：<b>这个类要被单元测试</b> ——
     * 登录的三条失败分支（账号不存在 / 未设密码 / 密码错）用
     * {@code new MemberServiceImpl(mockMapper, props, encoder)} 就能测，
     * 不必起一个真容器 + 一个真 MySQL。
     *
     * <p>★ 注入的是 {@code PasswordEncoder} 接口而不是 {@code BCryptPasswordEncoder}
     * 实现类 —— 理由见 {@code StaffServiceImpl} 里那段更长的说明。
     */

    // ==================== 车主端（无 id 参数） ====================

    /**
     * 车主自助注册（注册即登录）
     *
     * <p>★ <b>不预检手机号是否重复。</b>直接插，让 {@code uk_member_phone} 去挡 ——
     * 全项目统一的取舍（完整论证见 {@code StationMapper} 的类注释）。
     * 撞键抛出的 {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler}
     * 用正则翻成「13800000001已存在」，所有唯一索引共享那一个翻译点。
     *
     * <p>★ <b>{@code status} 必须显式给。</b>{@code member.status} 是
     * {@code NOT NULL DEFAULT 1}，而 insert 是全列插入 —— DDL 的 DEFAULT
     * 只在列没出现在语句里时生效，传 null 就是往 NOT NULL 列里显式写 NULL，
     * errno 1048。这是本项目连踩四次的坑。
     *
     * <p>★ <b>{@code lastLoginTime} 在注册时就写上当前时间</b>，因为注册即登录 ——
     * 留到「下次真的登录时再写」的话，新注册的用户在管理端看到的
     * 「最后登录时间」会是空的，而他刚刚才登录过。
     *
     * <p>★ <b>{@code createUser} 保持 null，而且这是对的：</b>自助注册<b>本来就没有
     * 登录者</b>，而 {@code AutoFillInterceptor} 填的是「当前登录者」，没有登录上下文时
     * 就该是 NULL（见那个类的注释）。
     */
    @Override
    @Transactional
    public MemberLoginVO register(MemberRegisterDTO memberRegisterDTO) {
        Member member = new Member();
        member.setPhone(memberRegisterDTO.getPhone());

        // ★ 明文密码在这里就消失了 —— 下面再也没有地方见过它。
        //   （BCrypt 每次 encode 的 salt 都是随机的，所以「同一个密码两次注册，
        //   库里密文不一样」是正常的。）
        member.setPassword(passwordEncoder.encode(memberRegisterDTO.getPassword()));

        member.setNickname(memberRegisterDTO.getNickname());
        member.setStatus(StatusConstant.ENABLE);
        member.setLastLoginTime(LocalDateTime.now());

        memberMapper.insert(member);

        // ★ 日志里只有 id 和手机号，没有密码 —— 不要顺手把 DTO 整个打出来，
        //   它的 toString() 由 @Data 生成，会带出明文密码（同 StaffServiceImpl.login）。
        log.info("车主注册成功：id={}, phone={}", member.getId(), member.getPhone());

        return toLoginVo(member, issueToken(member));
    }

    /**
     * 车主登录
     *
     * <p><b>★ 四个检查的顺序是有讲究的，和 {@code StaffServiceImpl.login} 同形：</b>
     * <pre>
     *   1. 手机号存在吗      —— 一次唯一索引查询，很快
     *   2. 账号被禁用了吗    —— 纯内存判断，几乎不花时间
     *   3. 设过密码吗        —— 纯内存判空
     *   4. 密码对吗          —— ★ BCrypt 校验，故意设计得慢（约 100ms）
     * </pre>
     *
     * <p>把最慢的一步放最后，意味着前三种失败<b>不会消耗 BCrypt 的计算量</b>。
     * 这不是省那 100ms：BCrypt 的设计目标就是让每次校验都昂贵，而登录接口
     * <b>没有做限流</b>（已记入 README 的已知缺口表），攻击者可以用大量不存在的
     * 手机号打这个接口 —— 每个请求都先跑一遍 BCrypt 的话，服务端 CPU 会被白烧掉。
     * <b>让便宜且能确定拒绝的条件先执行。</b>
     *
     * <p>★ <b>第 3 步必须显式判空，不能依赖 {@code matches(raw, null)} 的返回值。</b>
     * 它现在确实返回 false（Spring Security 里有一个 {@code encodedPassword == null}
     * 的分支），但那是<b>实现细节，不是契约</b>，换个实现就可能变成抛异常；
     * 而且那个分支还会打一行 {@code "Empty encoded password"} 的 WARN，
     * 让日志里出现一条指向「数据有问题」但其实是正常状态的记录。
     *
     * <p>★ <b>这个分支用的是 {@code LoginFailedException} 而不是
     * {@code ParamErrorException}：</b>这里参数完全合法、账号也真实存在，
     * 只是这个账号<b>整体就不支持密码登录</b>。用错异常类的代价不在响应上
     * （两者都走 {@code BaseException} 那一个 handler），在<b>日志的可读性</b>上。
     */
    @Override
    public MemberLoginVO login(MemberLoginDTO memberLoginDTO) {
        String phone = memberLoginDTO.getPhone();

        // 1. 手机号是否注册过
        Member member = memberMapper.getByPhone(phone);
        if (member == null) {
            // ★ 日志只记手机号，不记整个 DTO —— 它的 toString() 带明文密码。
            log.info("车主登录失败：手机号未注册，phone={}", phone);
            throw new AccountNotFoundException(MessageConstant.MEMBER_LOGIN_FAILED);
        }

        // 2. 账号是否被禁用（便宜的判断放前面，避免为禁用账号浪费 BCrypt 的算力）
        //    ★ 不要写 status == StatusConstant.DISABLE —— 那是 Integer 的引用比较，
        //      只因 Integer 缓存了 -128~127 才恰好正确；常量写左边还顺带免掉 NPE。
        //      同一个理由见 StaffServiceImpl.login 里那段更长的说明。
        if (StatusConstant.DISABLE.equals(member.getStatus())) {
            log.info("车主登录失败：账号被禁用，phone={}, memberId={}", phone, member.getId());
            throw new AccountLockedException(MessageConstant.MEMBER_DISABLED);
        }

        // 3. 账号有没有设置过密码（见方法注释：必须显式判空）
        if (member.getPassword() == null) {
            log.info("车主登录失败：账号未设置密码，phone={}, memberId={}", phone, member.getId());
            throw new LoginFailedException(MessageConstant.MEMBER_PASSWORD_NOT_SET);
        }

        // 4. 密码校验
        //    ★ matches 的参数顺序是 (明文, 密文)，写反了不会报错、只会一直返回 false。
        if (!passwordEncoder.matches(memberLoginDTO.getPassword(), member.getPassword())) {
            log.info("车主登录失败：密码错误，phone={}, memberId={}", phone, member.getId());
            throw new PasswordErrorException(MessageConstant.MEMBER_LOGIN_FAILED);
        }

        // 5. 签发 token
        String token = issueToken(member);

        // 6. 更新最后登录时间 —— ★ 记账，不是鉴权，失败不影响这次登录。
        //
        //    单独一条窄 UPDATE，不走 MemberMapper.update：
        //      · 登录是【鉴权事件】，不是数据编辑。让它推进 update_time 会让
        //        「这条记录最后一次被编辑是什么时候」永久失真。
        //      · 登录成功【之前】BaseContext 是空的，走 @AutoFill(UPDATE)
        //        会把 update_user 写成 NULL。
        //    完整论证见 MemberMapper.updateLastLoginTime 的注释。
        //
        //    ★ 为什么用 try-catch 包住：更新失败不该让一次【密码正确】的登录失败 ——
        //      用户在这一步没有任何可采取的行动，抛出去只会得到一个 500。
        //      代价是「最后登录时间可能不准」，但它是纯统计字段，
        //      而且失败会在日志里留下一行 warn，不会静默。
        try {
            memberMapper.updateLastLoginTime(member.getId(), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("更新最后登录时间失败（不影响本次登录）：memberId={}", member.getId(), e);
        }

        log.info("车主登录成功：phone={}, memberId={}", phone, member.getId());

        return toLoginVo(member, token);
    }

    /**
     * 查询当前登录车主（车主端的启动探针）
     *
     * <p>★ 形状照 {@code StaffServiceImpl.getCurrentStaff}：
     * {@code BaseContext.getCurrentId()} → 判空 → <b>重新查一次库</b>。
     */
    @Override
    public MemberVO getCurrentMember() {
        // ★ 这个 if 在正常路径下永远不会成立 —— 请求能走到这里，说明
        //   JwtTokenMemberInterceptor 已经放行过，而它放行前必然 setCurrentId 过。
        //
        //   写它是因为「永远不会成立」只对当前的拦截器配置成立：哪天有人给这个接口
        //   加了放行规则、或者把它挪到 /api/auth/ 下面，这里就会静默地拿到 null，
        //   然后变成「查 id 为 null 的记录 → 返回 null → 前端拿到空对象」——
        //   一个不报错但明显不对的结果。加这三行，把「配置改错了」变成一句明确的报错。
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        // ★ token 有效 ≠ 用户还在。token 只能证明「签发的那一刻这个 id 是存在的」，
        //   而 member token 的 TTL 是 7 天，这个窗口不短。
        //   JWT 是无状态的、服务端不保存会话，所以这个中间状态必须在这里处理 ——
        //   这也是 JWT 相比 session 的固有代价：无法「远程踢人下线」。
        Member member = memberMapper.getById(memberId);
        if (member == null) {
            log.warn("token 有效但车主已不存在，memberId={}", memberId);
            throw new AccountNotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }

        return toVo(member);
    }

    /**
     * 修改当前登录车主的资料
     *
     * <p>★ 三个可改字段在 XML 里的判空方式<b>不是同一种</b>（nickname / avatar 是
     * {@code != null and != ''}，gender 只判 {@code != null}），混用会出静默 bug ——
     * 完整说明见 {@code MemberMapper.xml}。<b>这里不需要任何特殊处理</b>：
     * 判空是 SQL 层的事，Service 只负责把非空值合并到实体上。
     */
    @Override
    @Transactional
    public void updateProfile(MemberUpdateDTO memberUpdateDTO) {
        Member member = requireCurrentMember();

        // ★ 合并方向：DTO 的非空值覆盖到从库里读出的行上。
        //   不用 BeanUtils.copyProperties —— 那是全量覆盖，会把 DTO 里的 null
        //   也写进去，于是「不传」变成「清空」，和 <set> + <if> 的语义直接矛盾
        //   （完整论证见 ChargePackageServiceImpl.mergeNonNull 那段）。
        //
        //   ★ 这里【不需要】校验 gender 的值域：MemberUpdateDTO 上的 @Min/@Max
        //     已经把它卡在 0-2，而且那条提示（「性别只能是 0未知、1男、2女」）
        //     比 Gender.of() 抛出的「未知的性别：7」有用得多。
        //     唯一能绕过 @Valid 的路径是直接调 Service，那条路径不存在于这个应用里。
        if (memberUpdateDTO.getNickname() != null) {
            member.setNickname(memberUpdateDTO.getNickname());
        }
        if (memberUpdateDTO.getAvatar() != null) {
            member.setAvatar(memberUpdateDTO.getAvatar());
        }
        if (memberUpdateDTO.getGender() != null) {
            member.setGender(memberUpdateDTO.getGender());
        }

        // ★ 用【合并后的实体】去更新，而不是只把 DTO 拷进去 ——
        //   这样 @AutoFill 才有对象可填，而且 id 一定带上。
        memberMapper.update(member);

        // ★ 日志只记 id，不记 DTO —— 见 StaffServiceImpl.login 里那条
        //   「dto 能把密码写进日志文件」的记录。
        log.info("修改车主资料：memberId={}", member.getId());
    }

    /**
     * 修改当前登录车主的密码（验旧密码）
     *
     * <p>★ 显式判空和 {@link #login} 里那个是同一件事，但<b>这里的处理不同</b>：
     * 登录时未设密码的账号抛 {@code MEMBER_PASSWORD_NOT_SET}（「这个账号整体不支持
     * 密码登录」）；而改密码时客户端<b>已经提交了一个 oldPassword</b>，服务端要回答的是
     * 「你给的旧密码对不对」—— 对一个没有密码的账号答案是「不对」，
     * 所以抛 {@code OLD_PASSWORD_ERROR}。
     *
     * <p>★ 这<b>不是</b>信息泄露：能走到这个方法的前提是持有一个有效 token，
     * 也就是<b>已经登录过</b>的人，他对这个账号的了解不会因为这句话增加。
     *
     * <p>★ <b>为什么对这类账号不「跳过旧密码校验直接设新密码」</b>——见
     * {@link MemberPasswordDTO} 第四节：能走到这里的唯一凭证是一个 token，
     * 而这个账号从来没设过密码，所以没有任何东西能证明来的人是机主。
     * <b>跳过等于把 7 天窗口变成永久接管。</b>
     */
    @Override
    @Transactional
    public void changePassword(MemberPasswordDTO memberPasswordDTO) {
        Member member = requireCurrentMemberWithPassword();

        // ★ 显式判空，不依赖 passwordEncoder.matches(raw, null) 恒为 false 这个
        //   实现细节 —— 理由见 login 里那段。这里额外记一条 warn：它区别于
        //   「用户就是想错了密码」，「这个账号在改密但它没有密码」对运营是一个信号。
        if (member.getPassword() == null) {
            log.warn("未设置密码的账号尝试修改密码，memberId={}", member.getId());
            throw new ParamErrorException(MessageConstant.OLD_PASSWORD_ERROR);
        }

        if (!passwordEncoder.matches(memberPasswordDTO.getOldPassword(), member.getPassword())) {
            log.info("修改密码失败：原密码错误，memberId={}", member.getId());
            throw new ParamErrorException(MessageConstant.OLD_PASSWORD_ERROR);
        }

        // ★ 只改 password 一列 + 两个审计列，用的是窄 UPDATE。不用
        //   MemberMapper.update —— 那条 SQL 里根本没有 password 列，这是刻意的
        //   （三道防线之一，见 MemberMapper 的类注释）。
        //   ★ 走 @AutoFill：改密码是一次【数据编辑】，不是鉴权事件，而且此时
        //     BaseContext 里有当前车主，update_user 能填对。这和 updateLastLoginTime
        //     正好构成对照 —— 判断标准是「这是一次编辑，还是一次记账」。
        member.setPassword(passwordEncoder.encode(memberPasswordDTO.getNewPassword()));
        memberMapper.updatePassword(member);

        log.info("修改密码成功：memberId={}", member.getId());
    }

    // ==================== 管理端（显式 id） ====================

    @Override
    public PageResult page(MemberPageQueryDTO query) {
        // ★ PageHelper 的调用顺序见 StationServiceImpl.page 里那段长注释。
        //   本方法没有富化步骤，所以顺序天然是对的 —— 危险之处也正在于此。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Member> rows = memberMapper.page(query);

        // ★ 强转成 Page 只为了拿 total。PageHelper 返回的 List 实际就是 Page
        long total = ((Page<Member>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    @Override
    public MemberVO getById(Long id) {
        Member member = memberMapper.getById(id);
        if (member == null) {
            throw new NotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }
        return toVo(member);
    }

    /**
     * 启用 / 禁用车主
     *
     * <p>★★ <b>禁用不会撤销已经签发的 token</b> —— 完整论证见
     * {@link MemberService#updateStatus} 的注释：那里说明了这个 7 天窗口为什么
     * 本轮接受、以及将来哪些端点必须在 Service 里自己查 status。
     *
     * <p>★ 状态值在这里<b>显式校验</b>，虽然 {@code MemberStatusDTO} 上大概率也有
     * {@code @Min/@Max}。两处都校验是有意的：这里是兜底，DTO 那里是给调用方的提示。
     */
    @Override
    @Transactional
    public void updateStatus(Long id, Integer status) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new ParamErrorException("状态只能是 0禁用、1正常");
        }

        Member existing = memberMapper.getById(id);
        if (existing == null) {
            throw new NotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }

        // ★ 构造一个只带 id + status 的对象交给 @AutoFill，不要复用上面那个
        //   existing 再改它的 status：那条 SQL 只写 status 一列，传一个
        //   【字段更少的对象】能让「这次操作到底动了什么」在读代码时一眼看清。
        Member update = new Member();
        update.setId(id);
        update.setStatus(status);

        memberMapper.updateStatus(update);

        log.info("{}车主：memberId={}, phone={}",
                StatusConstant.ENABLE.equals(status) ? "启用" : "禁用", id, existing.getPhone());
    }

    @Override
    public Member assertActive(Long memberId) {
        if (memberId == null) {
            throw new UserNotLoginException();
        }
        Member member = memberMapper.getById(memberId);
        if (member == null) {
            throw new NotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }
        if (!StatusConstant.ENABLE.equals(member.getStatus())) {
            // ★ 这句话就是「禁用不撤销 token」那个缺口的补救点：
            //   token 还能验签通过，但账号已经不能产生资金行为了
            throw new AccountLockedException(MessageConstant.MEMBER_DISABLED);
        }
        return member;
    }

    // ==================== 私有 ====================

    /**
     * <b>整个会员侧唯一的 token 签发点</b> —— 见类注释第一节。
     *
     * <p>★ 两个「唯一来源」都在这里，各防一类写错：
     * <ul>
     *   <li>{@code JwtClaimsConstant.MEMBER_ID} 与
     *       {@code jwtProperties.getMemberSecretKey()} ——
     *       和 {@code JwtTokenMemberInterceptor} 的解析侧是同一份常量 / 同一个配置</li>
     *   <li>{@code getMemberTtl()} 而不是 {@code getAdminTtl()} ——
     *       两者都是 {@code long}，写错编译通过，表现是车主端 token
     *       按管理端的时长过期（或反过来）</li>
     * </ul>
     *
     * <p>★ claims 里<b>只放身份标识，不放昵称/状态这些业务属性</b>。JWT 的载荷是
     * Base64 编码、不是加密，谁都解得出来；<b>更要紧的是会变的属性不该烙进 token</b>——
     * 管理员禁用了这个车主，旧 token 里还是 {@code status=1}，于是「禁用后仍能以正常
     * 身份操作，直到 token 过期」。这类属性应该每次从数据库读（见 {@link #getCurrentMember}）。
     * 这也解释了为什么登录响应里可以带 {@code nickname} 而 claims 里不带：
     * 响应是这次请求的结果，claims 是<b>未来 7 天里每个请求都会用到的输入</b>。
     */
    private String issueToken(Member member) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.MEMBER_ID, member.getId());

        return JwtUtil.createJWT(
                jwtProperties.getMemberSecretKey(),
                jwtProperties.getMemberTtl(),
                claims);
    }

    /**
     * 取当前登录车主（<b>不带密码</b>）
     *
     * <p>★ 和 {@link #getCurrentMember} 分开写而不是互相调用，是因为它们对
     * 「查不到」的处理<b>不同</b>：{@link #getCurrentMember} 会先判空 BaseContext
     * （那是它公开契约的一部分），而这里被调用时拦截器必然已经放行过 ——
     * 让接口方法去调用一个有更弱前置条件的私有方法，会让那个 if 变得可疑。
     */
    private Member requireCurrentMember() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Member member = memberMapper.getById(memberId);
        if (member == null) {
            log.warn("token 有效但车主已不存在，memberId={}", memberId);
            throw new AccountNotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }
        return member;
    }

    /**
     * 取当前登录车主（<b>带密码</b>），只服务 {@link #changePassword}
     *
     * <p>★ 为什么单独一个方法而不是给 {@link #requireCurrentMember} 加个布尔参数：
     * 「要不要读密码」决定的是调哪个 Mapper 方法，而两个方法返回的 {@code Member}
     * 在<b>字段上就不一样</b> —— {@code getById} 走 XML 的 {@code Base_Column_List}
     * （不含 password），{@code getByIdWithPassword} 是注解 SQL（含 password）。
     * 布尔参数还会得到一个危险的默认值：写错的后果是「password 恒为 null」，
     * 表现成「所有账号改密码都报原密码错误」，且没有任何报错。
     */
    private Member requireCurrentMemberWithPassword() {
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        Member member = memberMapper.getByIdWithPassword(memberId);
        if (member == null) {
            log.warn("token 有效但车主已不存在，memberId={}", memberId);
            throw new AccountNotFoundException(MessageConstant.MEMBER_NOT_FOUND);
        }
        return member;
    }

    private MemberLoginVO toLoginVo(Member member, String token) {
        return MemberLoginVO.builder()
                .id(member.getId())
                .phone(member.getPhone())
                .nickname(member.getNickname())
                .token(token)
                .build();
    }

    /**
     * 实体 → VO
     *
     * <p>★ {@code BeanUtils.copyProperties} 在这里是安全的，因为目标是<b>白名单</b>：
     * {@code MemberVO} 里根本没有 {@code password} 字段，所以无论怎么复制都不可能
     * 泄露它。<b>安全性由目标类型保证，而不是由「我记得它有哪几个字段」保证</b>
     * （判断标准见 {@code StaffServiceImpl.getCurrentStaff}）。
     *
     * <p>★ 两个派生标签都走<b>降级</b>：查不到标签返回 null，不抛 —— 项目统一的
     * 「写路径严格，读路径降级」。一行脏数据不该让整个列表 500，而且恰恰是出了
     * 脏数据的时候，管理员才最需要打开这个页面去修它。
     */
    private MemberVO toVo(Member member) {
        MemberVO vo = new MemberVO();
        BeanUtils.copyProperties(member, vo);
        vo.setGenderLabel(labelOfGender(member.getGender()));
        vo.setStatusLabel(labelOfStatus(member.getStatus()));
        return vo;
    }

    /** ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 失败返回 null，不抛 */
    private String labelOfGender(Integer gender) {
        if (gender == null) {
            return null;
        }
        try {
            return Gender.of(gender).getLabel();
        } catch (ParamErrorException e) {
            log.warn("车主性别无法识别，gender={}", gender);
            return null;
        }
    }

    /**
     * 车主状态是真的二值（{@code 1正常 0禁用}），所以直接映射，不需要枚举。
     *
     * <p>★ 常量写在左边 —— 理由见 {@code ChargePackageServiceImpl.labelOfStatus}
     * 里关于 {@code ==} 与 Integer 缓存的说明。
     */
    private String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (StatusConstant.ENABLE.equals(status)) {
            return "正常";
        }
        if (StatusConstant.DISABLE.equals(status)) {
            return "禁用";
        }
        log.warn("车主状态无法识别，status={}", status);
        return null;
    }
}
