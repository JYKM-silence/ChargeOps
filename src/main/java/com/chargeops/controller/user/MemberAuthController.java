package com.chargeops.controller.user;

import com.chargeops.dto.MemberLoginDTO;
import com.chargeops.dto.MemberRegisterDTO;
import com.chargeops.result.Result;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车主端认证（注册 / 登录）
 *
 * <p>路径 {@code /api/auth/member/**}，<b>故意落在 JWT 拦截器范围之外</b>。
 * 「为什么认证接口放在 {@code /api/auth/} 而不是用 {@code excludePathPatterns}」的正本在
 * {@code controller/admin/AuthController} 的类注释里，一句话概括：
 * <b>能让规则变成结构的，就不要让它变成配置。</b>下面只写会员侧特有的四件事。
 *
 * <h2>★ 一、包名 {@code controller.user} 和 URL 前缀 {@code /api/auth/member} 不一致，是有意的</h2>
 *
 * <p>README 的结构说明里写死了「{@code controller/} — admin（管理端，6 个域）/ user（车主端）」，
 * 而 URL 上车主端统一用 {@code member}（{@code member} 是表名，也是 {@code BaseContext} 里那个
 * id 的语义）。同一个不匹配在管理端也存在：{@code controller/admin/AuthController} 映射在
 * {@code /api/auth/staff}，包名 {@code admin} 而路径段是 {@code staff}。
 *
 * <p>★ 所以<b>不要「顺手统一」</b>：两处不一致是同一个历史约定，
 * 只改一处会让「哪个名字指哪个东西」变得更难猜。
 *
 * <h2>★ 二、类名是 {@code MemberAuthController}，不是 {@code AuthController}</h2>
 *
 * <p>★ 这一条是<b>踩过一次才写下的</b>，而且踩的是一个应用<b>根本起不来</b>的坑。
 * Spring 的 {@code AnnotationBeanNameGenerator} 给 {@code @RestController} 的默认 bean 名是
 * <b>类的简单名首字母小写</b> —— 它<b>不看包名</b>。所以这个类若叫 {@code AuthController}，
 * 它会和 {@code controller/admin/AuthController} 抢同一个 bean 名，启动时直接抛：
 *
 * <pre>
 *   ConflictingBeanDefinitionException: Annotation-specified bean name 'authController'
 *   for bean class [...] conflicts with existing, non-compatible bean definition of same name
 * </pre>
 *
 * <p>★ <b>这个错误的性质值得记下来 —— 它躲过了所有静态检查</b>：{@code mvn compile} <b>成功</b>
 * （两个同名类在不同包里，Java 层面完全合法），IDE 不报错也不警告，它<b>只在 Spring 容器启动
 * 那一刻</b>才出现。本项目的 {@code *MapperTest} 那组 {@code @SpringBootTest} 就是抓到它的地方 ——
 * 一个很具体的例子，说明<b>「编译通过」和「能跑起来」之间的距离</b>。
 *
 * <p>★ <b>两种修法，这里选了改类名：</b>给两个类写显式 bean 名能解决，但把「不许重名」变成一条
 * <b>要记得的规则</b>，下一个写 {@code AuthController} 的人还会再踩一次；<b>改类名让冲突在结构上
 * 不可能发生</b>。这和 {@code MemberAdminController} 是<b>同一个决定</b>（那个类也是因为和
 * {@code controller/user/MemberController} 重名而改的），两处合起来是一句话：
 * <b>同名不同包在 Java 里合法，在 Spring 里不合法 —— 所以类名必须自带作用域。</b>
 *
 * <h2>★ 三、注册和登录返回<b>同一个</b> VO，而且注册就发 token</h2>
 *
 * <p>注册即登录 —— 用户刚证明了他知道密码（是他自己设的），让他注册完再发一次登录请求，
 * 是<b>最常见的新用户路径</b>上的一次白跑。两者共用 {@code MemberLoginVO} 不是图省事，
 * 而是让「客户端拿到凭据」这件事只有一种形状：前端处理「注册成功」和「登录成功」的后续逻辑
 * （存 token、跳首页）会是同一段代码，不需要分叉。
 *
 * <p>★ 更要紧的一点藏在 Service 层：这两个入口共用同一个私有的 {@code issueToken(Member)} ——
 * 各写一遍就有两处可能把 admin 密钥或错的 claim 常量写进去，而<b>那是编译期拦不住的</b>
 * （两个 getter 都返回 {@code String}）。完整论证见 {@code MemberServiceImpl} 类注释第一节。
 *
 * <h2>★ 四、{@code @Valid} 在注册上比在登录上更关键</h2>
 *
 * <p>两者的 {@code @Valid} 都不能少（理由见 {@code controller/admin/AuthController#login}）。
 * 但注册那边多一层：{@code MemberRegisterDTO} 上的 {@code @Pattern("^1[3-9]\\d{9}$")}
 * <b>是承重的</b> —— 手机号是登录账号并且有 {@code uk_member_phone}，一个打错的号码会<b>永久占用</b>
 * 那个错的号，真正的机主来注册时会撞「已存在」。少了 {@code @Valid}，这个正则一次都不会执行。
 */
@RestController
@RequestMapping("/api/auth/member")
@Tag(name = "车主端-认证")
@Slf4j
public class MemberAuthController {

    private final MemberService memberService;

    public MemberAuthController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * ★ 手机号重复不预检，由 {@code uk_member_phone} 挡，撞键后由 {@code GlobalExceptionHandler}
     * 翻成<b>「13800000001已存在」</b>。这是全项目所有唯一索引共享的同一个翻译点 ——
     * 完整论证（为什么预检既多余又在并发下不可靠）见 {@code StationMapper} 的类注释。
     *
     * <p>★ <b>响应里没有密码字段，这是结构上的保证</b>：{@code MemberLoginVO} 里根本没有那个字段。
     * 明文密码在 Service 里第一件事就被 BCrypt 加密，之后没有任何地方见过它。
     */
    @PostMapping("/register")
    @Operation(summary = "车主注册", description = "注册成功即登录，直接返回 JWT 令牌")
    public Result<MemberLoginVO> register(@Valid @RequestBody MemberRegisterDTO memberRegisterDTO) {
        return Result.success(memberService.register(memberRegisterDTO));
    }

    /**
     * ★ <b>「手机号没注册」和「密码错误」返回同一句</b>（「手机号或密码错误」），
     * 而管理端是分开的两句。这个差异是刻意的 —— 但注意它<b>不是</b>一个防用户枚举的安全措施：
     * 注册接口按设计就会告诉调用方「这个手机号已存在」。完整论证见
     * {@code MessageConstant.MEMBER_LOGIN_FAILED} 的注释，那里也解释了为什么
     * 「把假的安全收益说成真的」比不做这个防护更糟。
     *
     * <p>★ <b>「该账号未设置密码」是单独一句</b>：那类用户（{@code member.password IS NULL}，
     * DDL 里的合法状态）拿密码登录<b>永远</b>失败，混进模糊文案里他会一直重试密码，
     * 得到的提示不指向任何能解决问题的行动。
     *
     * <p>★ 登录接口<b>目前没有限流</b>，而它每次失败都要跑一遍故意设计得很慢的 BCrypt。
     * 已记入 README 的已知缺口表 —— 正确修法是<b>在登录和注册两个口上都加限流 / 人机校验</b>，
     * 而不是靠合并文案。
     */
    @PostMapping("/login")
    @Operation(summary = "车主登录", description = "返回 JWT 令牌，后续请求放在请求头 authentication 中")
    public Result<MemberLoginVO> login(@Valid @RequestBody MemberLoginDTO memberLoginDTO) {
        return Result.success(memberService.login(memberLoginDTO));
    }
}
