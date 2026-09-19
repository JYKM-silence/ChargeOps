package com.chargeops.controller.user;

import com.chargeops.dto.MemberPasswordDTO;
import com.chargeops.dto.MemberUpdateDTO;
import com.chargeops.result.Result;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 车主端 - 我的信息
 *
 * <p>路径 {@code /api/member/**}，由 {@code JwtTokenMemberInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。
 *
 * <h2>★★ 一、三个端点全都<b>没有 id 参数</b> —— 这是本类最重要的性质</h2>
 *
 * <pre>
 *   GET /api/member/me            →  Result&lt;MemberVO&gt;
 *   PUT /api/member/me            →  Result&lt;Void&gt;
 *   PUT /api/member/me/password   →  Result&lt;Void&gt;
 * </pre>
 *
 * <p>注意路径段是字面量 {@code me}，而不是 {@code {id}}。这个选择意味着
 * <b>「修改任意一个车主」这件事在这个 Controller 上的接口形状里无法表达</b> ——
 * 身份唯一的来源是 token（拦截器解出 claim 放进 {@code BaseContext}），
 * 而 Service 层的方法签名里也没有 id 可传。
 *
 * <p>★ 对照一下另一种设计：{@code PUT /api/member/{id}}。
 * 那样写的话，这个接口<b>同时是「改自己」和「改任意人」两个接口</b>，
 * 区别只在调用方传了什么 —— 服务端必须靠一句
 * {@code if (id != currentId) throw} 来区分。
 * 那句话漏了、写反了、或者将来加了个新分支忘了带，就是一个越权漏洞。
 * <b>让「改自己」在形状上不可能表达「改别人」，比在方法体里拦住它可靠。</b>
 *
 * <p>★ 这条约定和 {@code MemberUpdateDTO} 里没有 {@code phone} / {@code status}
 * 是同一类做法：<b>用「字段/参数不存在」代替「记得校验」</b>。
 * 完整论证见那个 DTO 的类注释第一节。
 *
 * <h2>★ 二、{@code GET /me} 是前端的启动探针，所以它的响应是<b>有职责</b>的</h2>
 *
 * <p>小程序打开时会拿本地 token 调一次它。而 {@code MemberVO} 里带了
 * {@code status}，所以一个行为正常的客户端能在这里看到「我被禁用了」
 * 并主动强制登出。
 *
 * <p>★ 这不能替代服务端强制（<b>禁用不撤销已签发的 token</b>，
 * member token 的 TTL 是 7 天），但它把「我们要接受 7 天窗口」缩小成
 * 「我们要接受 7 天窗口，<b>只有那些从不调 /me 的客户端例外</b>」——
 * 后者的范围小得多，而且原因是客户端的问题而不是服务端的。
 * 完整论证见 {@code MemberVO} 的类注释第二节。
 *
 * <h2>★ 三、改密码是<b>独立端点</b>，不是 {@code PUT /me} 的一个字段</h2>
 *
 * <p>{@code PUT /me/password} 而不是给资料更新 DTO 加一个 {@code password} 字段 ——
 * 完整论证见 {@code MemberPasswordDTO} 的类注释第一节，
 * 一句话概括：混在一起会让「改密码」变成一条<b>可以绕过的入口</b>，
 * 而且那条路径上<b>没有地方可以插进「验旧密码」这一步</b>。
 */
@RestController
@RequestMapping("/api/member")
@Tag(name = "车主端-我的信息")
@Slf4j
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 查询当前登录车主（<b>前端的启动探针</b>）
     *
     * <p>★ 响应里的 {@code status} 是给<b>车主端自己</b>看的，
     * 不只是给管理端看的 —— 见类注释第二节。
     *
     * <p>★ <b>token 有效 ≠ 车主还在。</b>这个方法会拿 {@code BaseContext}
     * 里的 id 重新查一次库，查不到就抛
     * {@code AccountNotFoundException}（「车主不存在」）。
     * 这是 JWT 相比 session 的固有代价：服务端不保存会话，
     * 「账号在两次请求之间被删了」这个中间状态必须在每个读接口里处理。
     */
    @GetMapping("/me")
    @Operation(summary = "查询当前登录车主")
    public Result<MemberVO> me() {
        return Result.success(memberService.getCurrentMember());
    }

    /**
     * 修改当前登录车主的资料（昵称 / 头像 / 性别）
     *
     * <p>★ <b>改不了手机号和状态</b> —— 不是「服务端拒绝」，而是
     * {@code MemberUpdateDTO} 里根本没有那两个字段，客户端传了会被
     * Jackson 直接丢掉。为什么不能改，见那个 DTO 的类注释第一节。
     *
     * <p>★ 局部更新的已知限制：<b>传 null 表示「不改」，不是「清空」</b>。
     * 所以「把昵称删掉」目前办不到 —— 这是全项目统一的取舍，
     * 见 {@code StationMapper.update} 的完整论证。
     */
    @PutMapping("/me")
    @Operation(summary = "修改当前登录车主的资料")
    public Result<Void> updateProfile(@Valid @RequestBody MemberUpdateDTO memberUpdateDTO) {
        memberService.updateProfile(memberUpdateDTO);
        return Result.success();
    }

    /**
     * 修改当前登录车主的密码（<b>必须验旧密码</b>）
     *
     * <p>★ 不验旧密码的话，一个被偷走的 token 就等于<b>永久账号接管</b>：
     * 攻击者改掉密码，原机主再也登不进来，而 token 还有 7 天才过期。
     * 完整论证见 {@code MemberPasswordDTO} 的类注释第二节。
     *
     * <p>★ <b>改密码不吊销已签发的 token</b>（和禁用车主一样，
     * 服务端不保存会话）。所以「改密码之后把别的设备踢下线」这件事
     * <b>本接口做不到</b>。这不是本轮新增的缺口，
     * 是 JWT 无状态这个选择的固有代价 —— 已连同禁用车主一起
     * 记在 README 的已知缺口表里。
     */
    @PutMapping("/me/password")
    @Operation(summary = "修改当前登录车主的密码")
    public Result<Void> changePassword(@Valid @RequestBody MemberPasswordDTO memberPasswordDTO) {
        memberService.changePassword(memberPasswordDTO);
        return Result.success();
    }
}
