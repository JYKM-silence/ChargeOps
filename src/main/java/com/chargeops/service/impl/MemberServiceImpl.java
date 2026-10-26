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
 * <p>形状沿用 {@code StaffServiceImpl}，这里只写会员侧特有的部分。
 *
 * <p> {@link #issueToken} 是整个会员侧唯一的 token 签发点：{@code login} 和 {@code register}
 * 各抄一遍的话，就有两处可能把 {@code getAdminSecretKey()} / {@code STAFF_ID} 写进去，
 * 而这是编译期拦不住的（两个 getter 都返回 {@code String}）。后果很具体：用 admin 密钥签发
 * 的 token 发给车主端，验签会失败 → 所有车主端请求 401，而登录接口返回 200。
 *
 * <p> {@link #login} 刻意不加 {@code @Transactional}：它只有一条写语句，而且那条写的语义是
 * 记账、不是鉴权 —— 「最后登录时间没记上」不该让一次密码正确的登录失败。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final MemberMapper memberMapper;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    // ---------- 车主端（无 id 参数） ----------

    /**
     * 车主自助注册（注册即登录）
     *
     * <p> 不预检手机号是否重复，直接插，让 {@code uk_member_phone} 去挡 —— 撞键抛出的
     * {@code DuplicateKeyException} 由 {@code GlobalExceptionHandler} 统一翻译。
     *
     * <p> {@code status} 和 {@code lastLoginTime} 必须显式给：{@code member.status} 是
     * {@code NOT NULL DEFAULT 1}，而 insert 是全列插入，传 null 就是往 NOT NULL 列里显式
     * 写 NULL（errno 1048）；注册即登录，留到「下次真的登录时再写」的话，新注册的用户在
     * 管理端看到的「最后登录时间」会是空的。
     */
    @Override
    @Transactional
    public MemberLoginVO register(MemberRegisterDTO memberRegisterDTO) {
        Member member = new Member();
        member.setPhone(memberRegisterDTO.getPhone());

        // 明文密码在这里就消失了 —— 下面再也没有地方见过它
        // （BCrypt 每次 encode 的 salt 都是随机的，同一个密码两次注册的密文不一样是正常的）
        member.setPassword(passwordEncoder.encode(memberRegisterDTO.getPassword()));

        member.setNickname(memberRegisterDTO.getNickname());
        member.setStatus(StatusConstant.ENABLE);
        member.setLastLoginTime(LocalDateTime.now());

        memberMapper.insert(member);

        // 日志里只有 id 和手机号，没有密码 —— 不要顺手把 DTO 整个打出来，它的 toString() 带明文密码
        log.info("车主注册成功：id={}, phone={}", member.getId(), member.getPhone());

        return toLoginVo(member, issueToken(member));
    }

    /**
     * 车主登录
     *
     * <p> 四个检查的顺序有讲究：手机号存在吗 → 账号被禁用了吗 → 设过密码吗 → 密码对吗
     * （BCrypt 校验，故意设计得慢，约 100ms）。把最慢的一步放最后，前三种失败就不会消耗
     * BCrypt 的计算量；Controller 外层还有 Redis IP 限流，账号维度累计认证失败并触发冷却。
     *
     * <p> 第 3 步必须显式判空，不能依赖 {@code matches(raw, null)} 的返回值：它现在确实
     * 返回 false，但那是实现细节不是契约，而且那个分支还会打一行 WARN。
     * 这一支用 {@code LoginFailedException} 而不是 {@code ParamErrorException}：参数完全合法、
     * 账号也真实存在，只是这个账号整体不支持密码登录。
     */
    @Override
    public MemberLoginVO login(MemberLoginDTO memberLoginDTO) {
        String phone = memberLoginDTO.getPhone();

        // 1. 手机号是否注册过
        Member member = memberMapper.getByPhone(phone);
        if (member == null) {
            // 日志只记手机号，不记整个 DTO —— 它的 toString() 带明文密码
            log.info("车主登录失败：手机号未注册，phone={}", phone);
            throw new AccountNotFoundException(MessageConstant.MEMBER_LOGIN_FAILED);
        }

        // 2. 账号是否被禁用（便宜的判断放前面，避免为禁用账号浪费 BCrypt 的算力）。
        // 不要写 status == StatusConstant.DISABLE —— 那是 Integer 的引用比较，
        // 只因 Integer 缓存了 -128~127 才恰好正确。
        if (StatusConstant.DISABLE.equals(member.getStatus())) {
            log.info("车主登录失败：账号被禁用，phone={}, memberId={}", phone, member.getId());
            throw new AccountLockedException(MessageConstant.MEMBER_DISABLED);
        }

        // 3. 账号有没有设置过密码（必须显式判空，见方法注释）
        if (member.getPassword() == null) {
            log.info("车主登录失败：账号未设置密码，phone={}, memberId={}", phone, member.getId());
            throw new LoginFailedException(MessageConstant.MEMBER_PASSWORD_NOT_SET);
        }

        // 4. 密码校验 —— matches 的参数顺序是 (明文, 密文)，写反了不会报错、只会一直返回 false
        if (!passwordEncoder.matches(memberLoginDTO.getPassword(), member.getPassword())) {
            log.info("车主登录失败：密码错误，phone={}, memberId={}", phone, member.getId());
            throw new PasswordErrorException(MessageConstant.MEMBER_LOGIN_FAILED);
        }

        // 5. 签发 token
        String token = issueToken(member);

        // 6. 更新最后登录时间 —— 记账，不是鉴权，失败不影响这次登录，所以用 try-catch。
        // 单独一条窄 UPDATE：走 MemberMapper.update 会让「这条记录最后一次被编辑是什么时候」
        // 因为一次登录而失真，而且登录成功之前 BaseContext 是空的，@AutoFill 会把 update_user
        // 写成 NULL。
        try {
            memberMapper.updateLastLoginTime(member.getId(), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("更新最后登录时间失败（不影响本次登录）：memberId={}", member.getId(), e);
        }

        log.info("车主登录成功：phone={}, memberId={}", phone, member.getId());

        return toLoginVo(member, token);
    }

    /** 查询当前登录车主（车主端的启动探针）。形状照 {@code StaffServiceImpl.getCurrentStaff} */
    @Override
    public MemberVO getCurrentMember() {
        // 这个 if 在正常路径下永远不会成立 —— 「永远不会成立」只对当前的拦截器配置成立：
        // 哪天有人给这个接口加了放行规则，这里会静默地拿到 null，然后变成「查 id 为 null 的记录
        // → 返回 null → 前端拿到空对象」，一个不报错但明显不对的结果。
        Long memberId = BaseContext.getCurrentId();
        if (memberId == null) {
            throw new UserNotLoginException(MessageConstant.USER_NOT_LOGIN);
        }

        // token 有效 ≠ 用户还在：token 只能证明「签发的那一刻这个 id 是存在的」，而它的 TTL 是 7 天
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
     * <p> 判空是 SQL 层的事，Service 只负责把非空值合并到实体上 —— 三个可改字段在 XML 里
     * 的判空方式不是同一种，混用会出静默 bug，见 {@code MemberMapper.xml}。
     */
    @Override
    @Transactional
    public void updateProfile(MemberUpdateDTO memberUpdateDTO) {
        Member member = requireCurrentMember();

        // 不用 BeanUtils.copyProperties —— 那是全量覆盖，会把 DTO 里的 null 也写进去，
        // 于是「不传」变成「清空」。gender 不用在这里校验值域：DTO 上的 @Min/@Max 已经把它
        // 卡在 0-2，而且那条提示比 Gender.of() 抛出的「未知的性别：7」有用得多。
        if (memberUpdateDTO.getNickname() != null) {
            member.setNickname(memberUpdateDTO.getNickname());
        }
        if (memberUpdateDTO.getAvatar() != null) {
            member.setAvatar(memberUpdateDTO.getAvatar());
        }
        if (memberUpdateDTO.getGender() != null) {
            member.setGender(memberUpdateDTO.getGender());
        }

        // 用合并后的实体去更新，这样 @AutoFill 才有对象可填，而且 id 一定带上
        memberMapper.update(member);

        // 日志只记 id，不记 DTO —— 见 StaffServiceImpl.login 里那条记录
        log.info("修改车主资料：memberId={}", member.getId());
    }

    /**
     * 修改当前登录车主的密码（验旧密码）
     *
     * <p> 未设密码的账号抛的是 {@code OLD_PASSWORD_ERROR} 而不是 {@code MEMBER_PASSWORD_NOT_SET}：
     * 客户端已经提交了一个 oldPassword，服务端要回答的是「你给的旧密码对不对」，
     * 对一个没有密码的账号答案是「不对」。
     *
     * <p> 对这类账号不「跳过旧密码校验直接设新密码」：能走到这里的唯一凭证是一个 token，
     * 而这个账号从来没设过密码，没有任何东西能证明来的人是机主 —— 跳过等于永久接管。
     */
    @Override
    @Transactional
    public void changePassword(MemberPasswordDTO memberPasswordDTO) {
        Member member = requireCurrentMemberWithPassword();

        // 显式判空，不依赖 passwordEncoder.matches(raw, null) 恒为 false 这个实现细节。
        // 这里额外记一条 warn：「这个账号在改密但它没有密码」对运营是一个信号。
        if (member.getPassword() == null) {
            log.warn("未设置密码的账号尝试修改密码，memberId={}", member.getId());
            throw new ParamErrorException(MessageConstant.OLD_PASSWORD_ERROR);
        }

        if (!passwordEncoder.matches(memberPasswordDTO.getOldPassword(), member.getPassword())) {
            log.info("修改密码失败：原密码错误，memberId={}", member.getId());
            throw new ParamErrorException(MessageConstant.OLD_PASSWORD_ERROR);
        }

        // 窄 UPDATE：MemberMapper.update 那条 SQL 里根本没有 password 列，这是刻意的（三道防线之一）。
        // 走 @AutoFill 是因为这是一次数据编辑，不是鉴权事件，此时 BaseContext 里有当前车主。
        member.setPassword(passwordEncoder.encode(memberPasswordDTO.getNewPassword()));
        memberMapper.updatePassword(member);

        log.info("修改密码成功：memberId={}", member.getId());
    }

    // ---------- 管理端（显式 id） ----------

    @Override
    public PageResult page(MemberPageQueryDTO query) {
        // 本方法没有富化步骤，顺序天然是对的 —— 危险之处也正在于此。
        // PageHelper 的调用顺序见 StationServiceImpl.page 里那段长注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Member> rows = memberMapper.page(query);

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

    /** 启用 / 禁用车主。禁用不会撤销已签发的 token，见 {@link MemberService#updateStatus} */
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

        // 构造一个只带 id + status 的对象交给 @AutoFill，不复用上面那个 existing：
        // 那条 SQL 只写 status 一列，传字段更少的对象能让「这次动了什么」一眼看清。
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
            // 这是「禁用不撤销 token」那个缺口的补救点：token 还能验签通过，
            // 但账号已经不能产生资金行为了
            throw new AccountLockedException(MessageConstant.MEMBER_DISABLED);
        }
        return member;
    }

    // ---------- 私有 ----------

    /**
     * 整个会员侧唯一的 token 签发点
     *
     * <p> 两个「唯一来源」都在这里：{@code MEMBER_ID} 与 {@code getMemberSecretKey()} 和
     * 拦截器的解析侧是同一份常量 / 同一个配置；TTL 用 {@code getMemberTtl()} 而不是
     * {@code getAdminTtl()}（两者都是 {@code long}，写错编译通过）。
     *
     * <p> claims 里只放身份标识，不放昵称/状态这些会变的属性 —— 管理员禁用了这个车主，
     * 旧 token 里还是 {@code status=1}。
     */
    private String issueToken(Member member) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.MEMBER_ID, member.getId());

        return JwtUtil.createJWT(
                jwtProperties.getMemberSecretKey(),
                jwtProperties.getMemberTtl(),
                claims);
    }

    /** 取当前登录车主（不带密码）。和 {@link #getCurrentMember} 分开写：那个方法要先判空 BaseContext（那是它公开契约的一部分），这里被调用时拦截器必然已经放行过 */
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
     * 取当前登录车主（带密码），只服务 {@link #changePassword}
     *
     * <p> 单独一个方法而不是给 {@link #requireCurrentMember} 加个布尔参数：布尔参数会得到一个
     * 危险的默认值 —— 写错的后果是「password 恒为 null」，表现成「所有账号改密码都报原密码错误」。
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
     * <p> {@code BeanUtils.copyProperties} 在这里安全，因为目标是白名单：{@code MemberVO} 里
     * 根本没有 {@code password} 字段 —— 安全性由目标类型保证。
     *
     * <p> 两个派生标签都走降级：一行脏数据不该让整个列表 500，而且恰恰是出了脏数据的时候，
     * 管理员才最需要打开这个页面去修它。
     */
    private MemberVO toVo(Member member) {
        MemberVO vo = new MemberVO();
        BeanUtils.copyProperties(member, vo);
        vo.setGenderLabel(labelOfGender(member.getGender()));
        vo.setStatusLabel(labelOfStatus(member.getStatus()));
        return vo;
    }

    /**  派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 失败返回 null，不抛 */
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
     * <p> 常量写在左边：{@code status == StatusConstant.DISABLE} 比较的是 Integer 引用，
     * 只因 Integer 缓存了 -128~127 才恰好正确。
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
