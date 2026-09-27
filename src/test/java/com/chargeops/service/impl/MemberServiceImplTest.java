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
import com.chargeops.exception.AccountLockedException;
import com.chargeops.exception.AccountNotFoundException;
import com.chargeops.exception.LoginFailedException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.PasswordErrorException;
import com.chargeops.exception.UserNotLoginException;
import com.chargeops.mapper.MemberMapper;
import com.chargeops.properties.JwtProperties;
import com.chargeops.utils.JwtUtil;
import com.chargeops.vo.MemberLoginVO;
import com.chargeops.vo.MemberVO;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code MemberServiceImpl} 的测试
 *
 * <h2>★★ 一、这个类是「不启动 Spring 也测得出真东西」的样本</h2>
 *
 * <p>写法、理由和「为什么用手写桩类而不用 Mockito」见
 * {@code ChargePackageServiceImplTest} 的类注释，这里不重复。
 * 本类只说明为什么<b>这一个</b> Service 值得写单测：
 *
 * <p>登录有<b>四条失败分支</b>，而它们的顺序和文案都是设计决策：
 * <pre>
 *   1. 手机号未注册       → AccountNotFoundException（模糊文案）
 *   2. 账号被禁用         → AccountLockedException（明确文案）
 *   3. 没设过密码         → LoginFailedException（★ 单独一句，不是模糊文案）
 *   4. 密码错             → PasswordErrorException（和 1 共用同一句）
 * </pre>
 *
 * <p>★ 分开测这四条的价值不在于「异常类对不对」—— 那看代码就知道。
 * 价值在于它们是<b>互斥且有序</b>的：把 3 挪到 4 后面、或者把 2 的文案
 * 改成模糊的，单看代码都像是无害的调整，而后果分别是
 * 「验证码用户永远登不进来」和「被禁用的人不知道自己被禁用了」。
 * 这些断言就是那两条规则的守门人。
 *
 * <h2>★★ 二、最有价值的一条：用 admin 密钥验不过车主 token</h2>
 *
 * <p>{@code MemberServiceImpl} 的类注释第一节花了整整一段讲
 * {@code issueToken} 是「会员侧唯一的签发点」，防的是
 * 「把 {@code getAdminSecretKey()} 写进去」这个<b>编译期拦不住</b>的错误。
 *
 * <p>★ 那段论证在这里被变成一条可执行的断言：
 * 用 member 密钥能验过、<b>用 admin 密钥验不过</b>。
 * 它同时钉住了三件事：密钥选对了、claim 名选对了、TTL 用的是 member 的。
 *
 * <h2>★ 三、{@code PasswordEncoder} 用真的，只包一层计数器</h2>
 *
 * <p>BCrypt 只花几毫秒，而 mock 掉它会让「编码」和「比对」两边都能随便写。
 * 特别是 {@code matches(明文, 密文)} 的<b>参数顺序</b> ——
 * 写反了不报错、只是恒返回 false，而 mock 过的 encoder 会愉快地返回
 * 你让它返回的值，把这个问题完美地藏起来。
 *
 * <p>★ 外面包一层 {@link CountingPasswordEncoder} 是为了断言
 * 「前三种失败<b>一次比对都没发生</b>」—— 那是 {@code login} 里
 * 「把最慢的一步放最后」那段论证的<b>唯一</b>可执行证据。
 * 只看「抛了哪个异常」证明不了校验发生在跑 BCrypt 之前。
 */
@DisplayName("MemberServiceImpl 登录分支与 token 签发")
class MemberServiceImplTest {

    /** ★ 真 BCrypt，不是 mock —— 理由见类注释第三节 */
    private final CountingPasswordEncoder passwordEncoder =
            new CountingPasswordEncoder(new BCryptPasswordEncoder());

    private final StubMemberMapper memberMapper = new StubMemberMapper();

    private final JwtProperties jwtProperties = jwtProperties();

    private final MemberServiceImpl service =
            new MemberServiceImpl(memberMapper, jwtProperties, passwordEncoder);

    /**
     * 造一份和管理端<b>完全不同</b>的两套配置
     *
     * <p>★ 两把密钥必须真的不一样，否则下面那条
     * 「用 admin 密钥验不过」的断言永远通过 —— 它会在一个
     * 「两套配置被合并成一套」的实现下变成一条什么也没测的测试。
     * {@code JwtProperties} 的类注释里那段「为什么不用一套密钥」
     * 正是这条断言要守的东西。所以测试里还有一条
     * 「两把密钥不相等」的元断言，防止这个前提被无声地改掉。
     */
    private static JwtProperties jwtProperties() {
        JwtProperties props = new JwtProperties();
        props.setAdminSecretKey("admin-secret-key-for-test-at-least-32-bytes-long!!");
        props.setAdminTtl(7_200_000L);      // 2 小时
        props.setAdminTokenName("token");

        props.setMemberSecretKey("member-secret-key-for-test-at-least-32-bytes-long!");
        props.setMemberTtl(604_800_000L);   // 7 天，和 application.yml 一致
        props.setMemberTokenName("authentication");
        return props;
    }

    @AfterEach
    void cleanUp() {
        // ★ BaseContext 是一个 ThreadLocal，JUnit 默认同线程跑用例 ——
        //   不清的话上一个用例留下的 id 会让下一个用例的
        //   「未登录」分支静默消失。症状是「单独跑通过、一起跑失败」。
        BaseContext.removeCurrentId();
    }

    // ==================== 工具方法 ====================

    private static final String PHONE = "13900000001";
    private static final String RAW_PASSWORD = "correct-horse";

    /** 一行「正常」的车主：启用、有密码 */
    private Member activeMemberWithPassword() {
        return Member.builder()
                .id(7L)
                .phone(PHONE)
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .nickname("张三")
                .status(StatusConstant.ENABLE)
                .build();
    }

    private MemberLoginDTO loginDto(String rawPassword) {
        MemberLoginDTO dto = new MemberLoginDTO();
        dto.setPhone(PHONE);
        dto.setPassword(rawPassword);
        return dto;
    }

    /** ★ 从 claim 里取 id 的统一写法：JWT 的数字可能反序列化成 Integer 或 Long */
    private static long memberIdOf(String token, String secretKey) {
        Object raw = JwtUtil.parseJWT(secretKey, token).get(JwtClaimsConstant.MEMBER_ID);
        assertThat(raw).isInstanceOf(Number.class);
        return ((Number) raw).longValue();
    }

    // ==================== ★★ 一、登录的四条失败分支 ====================

    @Test
    @DisplayName("★ 分支 1：手机号未注册 → 模糊文案（和「密码错」是同一句）")
    void loginWithUnknownPhoneShouldFailWithMergedMessage() {
        memberMapper.storedByPhone = null;   // 查不到

        assertThatThrownBy(() -> service.login(loginDto(RAW_PASSWORD)))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage(MessageConstant.MEMBER_LOGIN_FAILED);

        // ★ 前三种失败都不该走到 BCrypt —— 把最慢的一步放最后，
        //   意味着攻击者用大量不存在的手机号打这个接口时，
        //   服务端不会白烧 CPU（登录接口本轮没有限流）。
        //   ★ 断言的是「一次比对都没有发生」，而不只是「抛了异常」。
        assertThat(passwordEncoder.matchesCallCount).isZero();
    }

    @Test
    @DisplayName("★ 分支 2：账号被禁用 → 明确文案，而且【密码对也照样拒】")
    void loginWithDisabledAccountShouldFailEvenWithCorrectPassword() {
        Member disabled = activeMemberWithPassword();
        disabled.setStatus(StatusConstant.DISABLE);
        memberMapper.storedByPhone = disabled;

        // ★★ 关键：这里传的是【正确的】密码。
        //   如果禁用的判断被挪到密码校验之后，这条测试会失败 ——
        //   而那个改动看起来完全无害（「先验密码再验状态，逻辑一样」）。
        //   后果是「被禁用的人拿对密码能登进来」，也就是禁用一个空操作。
        assertThatThrownBy(() -> service.login(loginDto(RAW_PASSWORD)))
                .isInstanceOf(AccountLockedException.class)
                .hasMessage(MessageConstant.MEMBER_DISABLED);

        assertThat(passwordEncoder.matchesCallCount).isZero();
    }

    @Test
    @DisplayName("★★ 分支 3：账号没设过密码 → 【单独一句】，不能混进模糊文案")
    void loginWithoutPasswordShouldFailWithItsOwnMessage() {
        Member noPassword = activeMemberWithPassword();
        // ★ member.password 可空（DDL：「验证码登录的用户为 NULL」），
        //   这是【合法的设计状态】，不是数据坏了。
        noPassword.setPassword(null);
        memberMapper.storedByPhone = noPassword;

        assertThatThrownBy(() -> service.login(loginDto(RAW_PASSWORD)))
                .isInstanceOf(LoginFailedException.class)
                .hasMessage(MessageConstant.MEMBER_PASSWORD_NOT_SET);

        // ★★ 这条断言是本节的核心：它明确【不是】模糊文案。
        //   混进「手机号或密码错误」的话，这类用户会一直重试密码 ——
        //   而他无论试多少次都不可能成功（这个账号整体不支持密码登录），
        //   得到的提示不指向任何能解决问题的行动。
        assertThat(MessageConstant.MEMBER_PASSWORD_NOT_SET)
                .isNotEqualTo(MessageConstant.MEMBER_LOGIN_FAILED);

        // ★ 而且这一步【不能】依赖 passwordEncoder.matches(raw, null) 返回 false ——
        //   那是实现细节不是契约（Member 的类注释第一节写了完整理由）。
        //   这里断言「压根没调 matches」，把「必须显式判空」这条规则钉死。
        assertThat(passwordEncoder.matchesCallCount).isZero();
    }

    @Test
    @DisplayName("★ 分支 4：密码错 → 和「手机号未注册」完全同一句")
    void loginWithWrongPasswordShouldFailWithTheSameMessageAsUnknownPhone() {
        memberMapper.storedByPhone = activeMemberWithPassword();

        assertThatThrownBy(() -> service.login(loginDto("wrong-password")))
                .isInstanceOf(PasswordErrorException.class)
                // ★★ 同一句。合并它们的收益是【一致性】而不是安全 ——
                //   注册接口按设计就会返回「该手机号已存在」，
                //   所以用户枚举在这套 API 里本来就可得，
                //   这段文案换不来任何防护（见 MessageConstant 的注释）。
                .hasMessage(MessageConstant.MEMBER_LOGIN_FAILED);

        // ★ 这一次 BCrypt 真的跑了 —— 和上面三条分支形成对照。
        assertThat(passwordEncoder.matchesCallCount).isEqualTo(1);
    }

    // ==================== ★★ 二、token 签发 ====================

    @Test
    @DisplayName("★★ 登录签发的 token：member 密钥验得过，admin 密钥【验不过】")
    void issuedTokenShouldBeVerifiableByMemberKeyOnly() {
        // ★ 元断言：先确认这条测试的前提还成立。
        //   如果哪天有人把两把密钥「统一」成一个值，
        //   下面的 isInstanceOf(JwtException.class) 会变成永真式 ——
        //   一条还在跑、还在通过、但已经不测任何东西的测试。
        assertThat(jwtProperties.getAdminSecretKey())
                .isNotEqualTo(jwtProperties.getMemberSecretKey());

        memberMapper.storedByPhone = activeMemberWithPassword();

        MemberLoginVO vo = service.login(loginDto(RAW_PASSWORD));

        assertThat(vo.getToken()).isNotBlank();

        // ---- 用 admin 密钥验：必须失败 ----
        //   ★★ 那条「两套密钥不能合并」的论证在这里被执行。
        //   用 admin 密钥签发的 token 放进 authentication 头发给车主端，
        //   会被 JwtTokenMemberInterceptor 拒掉 —— 症状是
        //   「登录接口返回 200 且带着一个看起来完全正常的 token，
        //    但之后所有车主端请求全部 401」。
        assertThatThrownBy(() -> JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), vo.getToken()))
                .as("★ 用 admin 密钥验过了车主 token —— 说明 issueToken 里取错了密钥。"
                        + "症状会是「登录成功但所有 /api/member/** 请求 401」，"
                        + "而登录接口本身返回 200，排查方向很容易跑偏")
                .isInstanceOf(JwtException.class);

        // ---- 用 member 密钥验，两个 claim 都要对 ----
        var claims = JwtUtil.parseJWT(jwtProperties.getMemberSecretKey(), vo.getToken());

        // ★ claim 名必须是 memberId，不是 staffId —— 这也是编译期拦不住的：
        //   两个常量都是 String，写错了 token 照样签得出来，
        //   只是拦截器 setCurrentId 的时候取到 null。
        assertThat(memberIdOf(vo.getToken(), jwtProperties.getMemberSecretKey())).isEqualTo(7L);
        assertThat(claims.get(JwtClaimsConstant.STAFF_ID)).isNull();

        // ★ 有效期用的是 memberTtl（7 天），不是 adminTtl（2 小时）。
        //   两者都是 long，写错编译通过 —— 表现是车主端 token
        //   提早 7 天失效，用户天天要重新登录。
        long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMillis).isEqualTo(jwtProperties.getMemberTtl());
        assertThat(ttlMillis).isNotEqualTo(jwtProperties.getAdminTtl());

        // ---- 返回值里没有密码 ----
        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getPhone()).isEqualTo(PHONE);
        assertThat(vo.getNickname()).isEqualTo("张三");
        // ★ MemberLoginVO 里根本没有 password 字段，这是【结构上的】保证。
        //   它的类注释第一节说明了为什么：BCrypt 哈希可以离线无限次爆破。
    }

    @Test
    @DisplayName("★★ 注册和登录签发的是【同一种】token（唯一签发点的证据）")
    void registerAndLoginShouldIssueTheSameKindOfToken() {
        // ---- 注册 ----
        memberMapper.storedByPhone = null;
        memberMapper.idToAssign = 42L;   // 桩类模拟 useGeneratedKeys 回填 id

        MemberRegisterDTO registerDTO = new MemberRegisterDTO();
        registerDTO.setPhone(PHONE);
        registerDTO.setPassword(RAW_PASSWORD);
        registerDTO.setNickname("新用户");

        MemberLoginVO fromRegister = service.register(registerDTO);

        // ---- 登录（用刚注册的那个账号）----
        // ★ 注册写入的对象要能被随后的登录查到 —— 桩类里手动接上。
        //   真库里 insert 之后 getByPhone 自然查得到，
        //   桩类里必须显式做这件事，否则第二个断言测的是别的账号。
        memberMapper.storedByPhone = memberMapper.inserted;

        MemberLoginVO fromLogin = service.login(loginDto(RAW_PASSWORD));

        // ★★ 两个入口必须产出【同构】的 token：同一个密钥、同一个 claim 名。
        //   这正是 issueToken 作为唯一签发点要保证的事 ——
        //   如果 register 里内联了一份自己的 claims 构建，
        //   两边的 token 就可能不一样，而这是编译期看不出来的。
        assertThat(memberIdOf(fromRegister.getToken(), jwtProperties.getMemberSecretKey()))
                .isEqualTo(42L);
        assertThat(memberIdOf(fromLogin.getToken(), jwtProperties.getMemberSecretKey()))
                .isEqualTo(42L);

        // ★ 反向：注册的 token 同样验不过 admin 密钥
        assertThatThrownBy(() -> JwtUtil.parseJWT(
                jwtProperties.getAdminSecretKey(), fromRegister.getToken()))
                .isInstanceOf(JwtException.class);

        assertThat(fromRegister.getToken()).isNotBlank();
        assertThat(fromLogin.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("★ 注册：明文密码绝不出现在写入的对象里，status / lastLoginTime 必须显式给")
    void registerShouldEncodePasswordAndFillNotNullColumns() {
        memberMapper.storedByPhone = null;
        BaseContext.removeCurrentId();   // 注册时没有登录上下文

        MemberRegisterDTO dto = new MemberRegisterDTO();
        dto.setPhone(PHONE);
        dto.setPassword(RAW_PASSWORD);
        dto.setNickname("新用户");

        service.register(dto);

        Member written = memberMapper.inserted;
        assertThat(written).isNotNull();

        // ★★ 库里不能是明文。这条断言看着像废话，但它是「注册时忘了 encode」
        //   唯一的抓取点 —— 那个 bug 的症状是「登录永远失败」，
        //   而失败提示是合并后的「手机号或密码错误」，指向完全错误的方向。
        assertThat(written.getPassword())
                .isNotEqualTo(RAW_PASSWORD)
                .startsWith("$2");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, written.getPassword())).isTrue();

        // ★ status 是 NOT NULL 且走全列插入 —— DDL 的 DEFAULT 救不了它。
        //   传 null 会是 errno 1048（500 +「未知错误」）。
        assertThat(written.getStatus()).isEqualTo(StatusConstant.ENABLE);

        // ★ 注册即登录，所以「最后登录时间」在这一刻就该写上。
        //   留到「下次真的登录时再写」的话，新注册的用户在管理端
        //   看到的最后登录时间是空的 —— 而他刚刚才登录过。
        assertThat(written.getLastLoginTime()).isNotNull();

        // ★ create_user 保持 null 是对的：自助注册本来就没有登录者。
        //   Member 的类注释第二节引了 AutoFillInterceptor 的原话
        //   「谁创建的本来就该是 NULL，而不是 0 或某个假的 id」。
        assertThat(written.getCreateUser()).isNull();
    }

    @Test
    @DisplayName("★★ 更新最后登录时间失败时，登录【仍然成功】")
    void loginShouldSucceedEvenIfUpdatingLastLoginTimeFails() {
        memberMapper.storedByPhone = activeMemberWithPassword();
        memberMapper.failOnUpdateLastLoginTime = true;

        // ★★ 这是 login 方法注释里那个 try-catch 的可执行版本：
        //   更新最后登录时间是【记账】，不是鉴权。
        //   用户在这一步没有任何可采取的行动，把异常抛出去只会得到
        //   一个 500 —— 而他其实已经通过验证了。
        MemberLoginVO vo = service.login(loginDto(RAW_PASSWORD));

        assertThat(vo.getToken()).isNotBlank();
        assertThat(vo.getId()).isEqualTo(7L);

        // ★ 而那次写入确实【尝试过】—— 不是「压根没调」。
        //   没有这条，一个把整段写入删掉的实现也能通过上面的断言，
        //   而那样「最后登录时间」会永远不更新，且没有任何提示。
        assertThat(memberMapper.updateLastLoginTimeCallCount).isEqualTo(1);
    }

    // ==================== 改密码 ====================

    @Test
    @DisplayName("★★ 改密码：未设密码的账号报的也是「原密码错误」，【不是】「未设置密码」")
    void changePasswordOnAccountWithoutPasswordShouldReportOldPasswordError() {
        BaseContext.setCurrentId(7L);
        Member noPassword = activeMemberWithPassword();
        noPassword.setPassword(null);
        memberMapper.storedByIdWithPassword = noPassword;

        MemberPasswordDTO dto = new MemberPasswordDTO();
        dto.setOldPassword("anything");
        dto.setNewPassword("new-password");

        // ★★ 和登录那条分支【故意不同】：
        //   登录说「该账号未设置密码」（那是「整体不支持密码登录」，信息准确）；
        //   改密码说「原密码错误」—— 因为客户端已经提交了一个 oldPassword，
        //   服务端要回答的问题是「你给的旧密码对不对」，
        //   而对一个没密码的账号，答案是「不对」。
        //   完整论证见 changePassword 的注释。
        assertThatThrownBy(() -> service.changePassword(dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MessageConstant.OLD_PASSWORD_ERROR);

        // ★ 一行都没写
        assertThat(memberMapper.updatePasswordCallCount).isZero();
    }

    @Test
    @DisplayName("★ 改密码：旧密码错 → OLD_PASSWORD_ERROR，一行都不写")
    void changePasswordWithWrongOldPasswordShouldFail() {
        BaseContext.setCurrentId(7L);
        memberMapper.storedByIdWithPassword = activeMemberWithPassword();

        MemberPasswordDTO dto = new MemberPasswordDTO();
        dto.setOldPassword("not-the-old-one");
        dto.setNewPassword("new-password");

        assertThatThrownBy(() -> service.changePassword(dto))
                .isInstanceOf(ParamErrorException.class)
                .hasMessage(MessageConstant.OLD_PASSWORD_ERROR);

        assertThat(memberMapper.updatePasswordCallCount).isZero();
    }

    @Test
    @DisplayName("★ 改密码成功：写进去的是【新密码的密文】，明文不出现在任何地方")
    void changePasswordShouldWriteTheNewHash() {
        BaseContext.setCurrentId(7L);
        memberMapper.storedByIdWithPassword = activeMemberWithPassword();

        String newRaw = "new-password-456";
        MemberPasswordDTO dto = new MemberPasswordDTO();
        dto.setOldPassword(RAW_PASSWORD);
        dto.setNewPassword(newRaw);

        service.changePassword(dto);

        Member written = memberMapper.passwordWritten;
        assertThat(written).isNotNull();
        assertThat(written.getId()).isEqualTo(7L);

        // ★ 写进去的必须是密文，而且能和新明文对上
        assertThat(written.getPassword()).isNotEqualTo(newRaw).startsWith("$2");
        assertThat(passwordEncoder.matches(newRaw, written.getPassword())).isTrue();
        // ★ 而旧密码对不上了 —— 这条排除了「只是把同一个值又写了一遍」
        assertThat(passwordEncoder.matches(RAW_PASSWORD, written.getPassword())).isFalse();
    }

    @Test
    @DisplayName("★ 改密码：没登录（BaseContext 为空）→ UserNotLoginException")
    void changePasswordWithoutLoginShouldFail() {
        BaseContext.removeCurrentId();

        MemberPasswordDTO dto = new MemberPasswordDTO();
        dto.setOldPassword("a");
        dto.setNewPassword("bbbbbb");

        // ★ 这个 if 在正常路径下永远不会成立（拦截器必然 setCurrentId 过）。
        //   它的价值在于：哪天有人给 /api/member/** 加了放行规则，
        //   这里会从「静默地拿 null 去查库」变成一句明确的报错。
        assertThatThrownBy(() -> service.changePassword(dto))
                .isInstanceOf(UserNotLoginException.class)
                .hasMessage(MessageConstant.USER_NOT_LOGIN);
    }

    // ==================== 管理端 ====================

    @Test
    @DisplayName("★★ 改状态：非法值被【显式】校验 —— 不能靠 DTO 上的 @Min/@Max 兜底")
    void updateStatusShouldRejectValuesOutsideZeroAndOne() {
        // ★ status = 7。DDL 是 tinyint，未必拦得住（tinyint 的范围是 -128~127，
        //   7 完全合法）；DTO 上的 @Min/@Max 挡的是 HTTP 那条路，
        //   而 Service 是最后一道 —— 两处都校验是刻意的（DTO 给提示，这里兜底）。
        assertThatThrownBy(() -> service.updateStatus(7L, 7))
                .isInstanceOf(ParamErrorException.class)
                .hasMessageContaining("状态只能是");

        // ★ 而且这个校验在【查库之前】—— 一次非法请求不该产生一次数据库查询
        assertThat(memberMapper.getByIdCallCount).isZero();

        // null 同样要拦：它一路走到 SQL 就是
        // UPDATE member SET status = NULL，而那一列是 NOT NULL → errno 1048。
        // ★ 这里不能依赖 StatusConstant.DISABLE.equals(null) 刚好是 false ——
        //   虽然结果对，但那是 equals 的语义，不是「我们校验了它」。
        assertThatThrownBy(() -> service.updateStatus(7L, null))
                .isInstanceOf(ParamErrorException.class);
    }

    @Test
    @DisplayName("★ 改状态：车主不存在 → NotFoundException；存在则只提交 id + status")
    void updateStatusShouldRequireExistingMember() {
        memberMapper.storedById = null;   // 查不到

        assertThatThrownBy(() -> service.updateStatus(7L, StatusConstant.DISABLE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MessageConstant.MEMBER_NOT_FOUND);
        assertThat(memberMapper.updateStatusCallCount).isZero();

        // ---- 存在：提交一个【只带 id + status】的对象 ----
        memberMapper.storedById = activeMemberWithPassword();
        service.updateStatus(7L, StatusConstant.DISABLE);

        Member written = memberMapper.statusWritten;
        assertThat(written.getId()).isEqualTo(7L);
        assertThat(written.getStatus()).isEqualTo(StatusConstant.DISABLE);
        // ★ 其余字段一律为 null —— 那条 SQL 只写 status 一列，
        //   传整个实体进去没有意义，而且会让「这次操作到底动了什么」
        //   在读代码时变模糊。用字段更少的对象是一个自解释的选择。
        assertThat(written.getNickname()).isNull();
        assertThat(written.getPhone()).isNull();
    }

    @Test
    @DisplayName("★ getCurrentMember：未登录 / token 有效但车主已不存在，两个分支分开")
    void getCurrentMemberShouldHandleBothNullCases() {
        BaseContext.removeCurrentId();
        assertThatThrownBy(() -> service.getCurrentMember())
                .isInstanceOf(UserNotLoginException.class);

        // ★ token 有效 ≠ 用户还在。JWT 是无状态的、服务端不保存会话，
        //   所以「签发之后、过期之前账号被删了」这个中间状态
        //   必须在这里处理 —— 这是 JWT 相比 session 的固有代价。
        BaseContext.setCurrentId(7L);
        memberMapper.storedById = null;
        assertThatThrownBy(() -> service.getCurrentMember())
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage(MessageConstant.MEMBER_NOT_FOUND);

        // ★ 正常路径：返回的是 VO，没有 password 字段
        memberMapper.storedById = activeMemberWithPassword();
        MemberVO vo = service.getCurrentMember();
        assertThat(vo.getId()).isEqualTo(7L);
        assertThat(vo.getPhone()).isEqualTo(PHONE);
        assertThat(vo.getStatusLabel()).isEqualTo("正常");
        assertThat(vo.getGenderLabel()).isNull();   // 桩里没设 gender → 降级为 null
    }

    @Test
    @DisplayName("★ updateProfile：只覆盖 DTO 里的非空字段，其余原样保留")
    void updateProfileShouldMergeNonNullFieldsOnly() {
        BaseContext.setCurrentId(7L);
        memberMapper.storedById = activeMemberWithPassword();

        MemberUpdateDTO dto = new MemberUpdateDTO();
        dto.setNickname("改过的昵称");
        // ★ avatar 和 gender 都不传 —— 它们必须是「不改」，不是「清空」。
        //   这是 <set> + <if> 的 SQL 语义，Service 侧必须和它一致：
        //   用 BeanUtils.copyProperties(dto, member) 的话，
        //   DTO 里的 null 会覆盖掉库里的值，「不传」就变成了「清空」。

        service.updateProfile(dto);

        Member written = memberMapper.updated;
        assertThat(written.getId()).isEqualTo(7L);
        assertThat(written.getNickname()).isEqualTo("改过的昵称");
        // ★ 从库里读出来的行带着原来的值，所以这两条能通过
        assertThat(written.getPhone()).isEqualTo(PHONE);
        assertThat(written.getStatus()).isEqualTo(StatusConstant.ENABLE);

        // ★★ 这条断言不是 bug，是两层设计合起来的结果，值得看清：
        //   · getById 走 XML 的 Base_Column_List，那里【没有 password 列】，
        //     所以合并时的原始实体 password 本来就是 null（桩类模拟了这一点）
        //   · 而 update 的 <set> 里也没有 password 列，传 null 进去
        //     也不会把它清掉
        //   这两件事是 MemberMapper 类注释吹的那个「两层独立生效」。
        //   真 SQL 层面的断言在 MemberMapperTest 里，这里只把 Service
        //   侧的事实记下来 —— 免得将来有人看到这句以为是遗留 bug 而去「修」它。
        assertThat(written.getPassword()).isNull();
    }

    // ==================== 桩类 ====================

    /**
     * 计数用的 {@link PasswordEncoder} 装饰器
     *
     * <p>★ 它存在的唯一目的是让「前三种失败一次比对都没发生」这句话
     * <b>可以被断言</b>。桩 Mapper 看不到 encoder 的调用，
     * 而「抛了正确的异常」并不能证明校验顺序是对的 ——
     * 一个先跑 BCrypt 再判手机号的实现，四条分支的异常全都是对的。
     */
    private static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate;
        private int matchesCallCount;

        private CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCallCount++;
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    /**
     * 手写的 {@link MemberMapper} 桩
     *
     * <p>理由见 {@code ChargePackageServiceImplTest} 的类注释第二节：
     * <b>接口以后新加方法时编译器会逼着我实现它</b>，
     * 而 Mockito 的 {@code mock()} 会静默返回 null ——
     * 而在这个类里「返回 null」恰好是「查不到这个账号」这个合法分支，
     * 于是新方法被调用时会以一条看起来完全合理的失败测试收场。
     *
     * <p>★ 本类的桩比 {@code ChargePackageServiceImplTest} 那个复杂一些：
     * 它要记录<b>被写入的对象</b>（用来断言「写进去的是密文不是明文」）
     * 和<b>调用次数</b>（用来断言「校验发生在写库之前」）。
     * 这些记录本身就是断言的一半 —— 只看「抛了哪个异常」证明不了这些。
     */
    private static final class StubMemberMapper implements MemberMapper {

        /** {@code getByPhone} 要返回的那一行；{@code null} 表示「没注册过」 */
        private Member storedByPhone;

        /** {@code getById} 要返回的那一行 */
        private Member storedById;

        /** {@code getByIdWithPassword} 专用；不设就退回 {@link #storedById} */
        private Member storedByIdWithPassword;

        /** {@code insert} 之后要回填的 id（模拟 useGeneratedKeys） */
        private Long idToAssign = 99L;

        /** 被 {@code insert} 写进来的对象 */
        private Member inserted;

        /** 被 {@code updatePassword} 写进来的对象 */
        private Member passwordWritten;

        /** 被 {@code updateStatus} 写进来的对象 */
        private Member statusWritten;

        /** 被 {@code update} 写进来的对象 */
        private Member updated;

        private int getByIdCallCount;
        private int updatePasswordCallCount;
        private int updateStatusCallCount;
        private int updateLastLoginTimeCallCount;

        /** 置 true 时 {@code updateLastLoginTime} 抛异常，用来测那个 try-catch */
        private boolean failOnUpdateLastLoginTime;

        /**
         * ★ 忠实模拟 {@code Base_Column_List} 里没有 {@code password} 这件事
         *
         * <p>不这么做的话，桩类会返回一个<b>生产环境里不可能存在</b>的对象
         * （password 非 null 的 {@code getById} 结果），
         * 而依赖这个差异的断言就会变成在测桩类而不是测被测代码。
         * 这条性质本身由 {@code MemberMapperTest} 对着真库断言。
         */
        @Override
        public Member getById(Long id) {
            getByIdCallCount++;
            return withoutPassword(storedById);
        }

        @Override
        public Member getByPhone(String phone) {
            return storedByPhone;
        }

        @Override
        public Member getByIdWithPassword(Long id) {
            getByIdCallCount++;
            return storedByIdWithPassword != null ? storedByIdWithPassword : storedById;
        }

        @Override
        public void insert(Member member) {
            member.setId(idToAssign);
            this.inserted = member;
        }

        @Override
        public void update(Member member) {
            this.updated = member;
        }

        @Override
        public int updatePassword(Member member) {
            updatePasswordCallCount++;
            this.passwordWritten = member;
            return 1;
        }

        @Override
        public int updateStatus(Member member) {
            updateStatusCallCount++;
            this.statusWritten = member;
            return 1;
        }

        @Override
        public int updateLastLoginTime(Long id, LocalDateTime lastLoginTime) {
            updateLastLoginTimeCallCount++;
            if (failOnUpdateLastLoginTime) {
                throw new IllegalStateException("模拟：更新最后登录时间失败");
            }
            return 1;
        }

        @Override
        public List<Member> page(MemberPageQueryDTO query) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖分页，见 MemberMapperTest 的集成测试");
        }

        /**
         * ★ 补这个是**编译器逼出来的**，正是本桩手写而不 Mockito 的理由
         *   （见类注释）：接口一加方法，这里就编译不过，不会静默返回 null ——
         *   而在这个类里「返回 null」恰好是「查不到这个账号」这个合法分支，
         *   静默漏掉会以一条看起来完全合理的失败测试收场。
         *
         * <p>唯一的调用方是 {@code ChargingOrderServiceImpl} 的
         * 「按手机号前缀查订单」，与车主自身的注册 / 改密码 / 改资料无关。
         */
        @Override
        public List<Long> listIdsByPhonePrefix(String phone, int limit) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖按手机号前缀查 id，那是订单侧的查询");
        }

        /**
         * ★ 同 {@link #listIdsByPhonePrefix}：也是编译器逼出来的。
         *
         * <p>唯一的调用方是 {@code ChargingOrderServiceImpl.pageForAdmin}
         * 给管理端订单列表补车主昵称 / 手机号，和车主自身的注册 / 登录 / 改资料无关。
         * ★ 这里更必须响亮地失败：一个返回 null 的 {@code listByIds} 会被
         * {@code ChargingOrderServiceImpl} 当成「这些车主都查不到」，
         * 于是订单列表安静地少掉一列车主信息 —— 一条不会有人发现的失败。
         */
        @Override
        public List<Member> listByIds(List<Long> ids) {
            throw new UnsupportedOperationException(
                    "本测试类不覆盖按 id 批量查车主，那是管理端订单列表的查询");
        }

        /** 浅拷贝一份并把 password 清掉，不修改原始 fixture */
        private static Member withoutPassword(Member source) {
            if (source == null) {
                return null;
            }
            return Member.builder()
                    .id(source.getId())
                    .phone(source.getPhone())
                    .nickname(source.getNickname())
                    .avatar(source.getAvatar())
                    .gender(source.getGender())
                    .status(source.getStatus())
                    .lastLoginTime(source.getLastLoginTime())
                    .createTime(source.getCreateTime())
                    .updateTime(source.getUpdateTime())
                    .createUser(source.getCreateUser())
                    .updateUser(source.getUpdateUser())
                    .build();
        }
    }
}
