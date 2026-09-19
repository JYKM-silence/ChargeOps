package com.chargeops.controller.admin;

import com.chargeops.dto.MemberPageQueryDTO;
import com.chargeops.dto.MemberStatusDTO;
import com.chargeops.result.PageResult;
import com.chargeops.result.Result;
import com.chargeops.service.MemberService;
import com.chargeops.vo.MemberVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 车主管理
 *
 * <p>路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 统一保护，
 * <b>本类不需要写任何鉴权代码</b>。
 *
 * <p>★ <b>类名是 {@code MemberAdminController} 而不是 {@code MemberController}</b>，
 * 因为后者已经被车主端的 {@code controller/user/MemberController}
 * 占用了。两个类同名不同包在 Java 里是合法的，但
 * 「两个 MemberController」会让「这个 MemberController 是哪个」
 * 在 import、日志、Swagger 里都要多看一步。
 *
 * <h2>★★ 一、只有三个端点，<b>故意没有新建、没有删除</b></h2>
 *
 * <pre>
 *   GET /api/admin/member/page       →  Result&lt;PageResult&gt;
 *   GET /api/admin/member/{id}       →  Result&lt;MemberVO&gt;
 *   PUT /api/admin/member/{id}/status →  Result&lt;Void&gt;
 * </pre>
 *
 * <p><b>不做新建</b>：账号来源已定为<b>自助注册</b>
 * （{@code POST /api/auth/member/register}）。管理端建号会引入
 * 「初始密码怎么安全送达」这个问题，而它<b>没法在接口层解决</b> ——
 * 发短信没有设施，口头告知则没有任何审计记录。
 *
 * <p><b>不做硬删</b>：车主有订单、钱包、车辆，硬删会让它们全变成孤儿。
 * 禁用就是停用手段。
 *
 * <h2>★★ 二、这和 {@code CategoryController} 的「硬删必须存在」是
 * <b>同一个项目里相反的两个决定</b></h2>
 *
 * <table border="1">
 *   <caption>为什么分类能硬删，车主不能</caption>
 *   <tr><th></th><th>category</th><th>member</th></tr>
 *   <tr><td>唯一键</td><td>{@code (type, name)} —— <b>人造的</b></td>
 *       <td>{@code phone} —— <b>真实世界的标识</b></td></tr>
 *   <tr><td>建错了怎么办</td>
 *       <td>唯一键永久占用（不看 status），没有硬删只能去生产库手敲 DELETE</td>
 *       <td><b>不存在「建错了」</b>——手机号是真实存在的那个人的</td></tr>
 *   <tr><td>硬删的代价</td><td>子记录是配置，且已有守卫拦着</td>
 *       <td><b>订单 / 钱包 / 车辆全变孤儿</b></td></tr>
 * </table>
 *
 * <p>★ <b>比统一规则更有价值的是两个相反决定各自的理由都写在代码里。</b>
 * 半年后有人问「为什么这里能删那里不能」，答案不用去翻文档 ——
 * 就在这两个类的注释里。
 *
 * <h2>★★★ 三、已知缺口：<b>禁用车主不会撤销已经签发的 token</b></h2>
 *
 * <p>这是本轮最需要在代码里说清楚的一件事。
 *
 * <p>拦截器<b>只验签名、从不查库</b>，而 member token 的有效期是
 * <b>7 天</b>（{@code chargeops.jwt.member-ttl}）。所以
 * {@code PUT /{id}/status} 把 {@code status} 改成 0 之后，
 * 那个车主手上的 token <b>最长还能用 7 天</b> ——
 * 包括继续调 {@code /api/member/**} 下的所有接口。
 *
 * <p><b>本轮的处置：接受并记录。</b>不引入黑名单
 * （那等于每个请求一次 Redis 往返，而本轮 {@code /api/member/**}
 * 只有「我的信息 / 我的车辆」这类<b>无资金影响</b>的读写）。
 *
 * <p>★ <b>两条缓解措施，都不是靠服务端强制：</b>
 * <ol>
 *   <li>{@code GET /api/member/me} 的响应里带 {@code status} ——
 *       它是前端的启动探针，所以<b>行为正常的</b>客户端会在启动时
 *       看到 {@code status = 0} 并主动登出。
 *       这把例外的范围从「所有客户端」缩小到「从不调 /me 的客户端」</li>
 *   <li>{@code member_token_name} 是 {@code authentication}，
 *       和管理端的 {@code token} 不是同一个头名 ——
 *       但这只防止<b>误用</b>，不防止伪造（密钥隔离才是防伪造的）</li>
 * </ol>
 *
 * <p>★★ <b>真正要紧的是 P1c-2 / P1c-3 的钱包和订单端点。</b>
 * 那些<b>必须</b>在自己的 Service 里重新查一次 {@code member.status}，
 * 不能依赖「拦截器已经验过了」。拦截器验的是<b>签名</b>，
 * 它证明的是「这个 token 是我们在 7 天内签发的」，
 * 而不是「这个人现在还能用」。
 *
 * <p>★ <b>而且这不该修在拦截器里。</b>{@code JwtTokenMemberInterceptor}
 * 的类注释里预留了这句话：「如果这两个拦截器各自开始长出独立的业务逻辑
 * （比如车主端要额外校验账号状态），那就更不该合并了」——
 * 状态校验属于 Service，不属于拦截器，因为
 * <b>「该不该查状态」取决于这个接口有没有资金影响</b>，
 * 而那是每个 Service 自己知道的事，拦截器不知道。
 *
 * <p>已记入 README 的已知缺口表。
 */
@RestController
@RequestMapping("/api/admin/member")
@Tag(name = "管理端-车主管理")
@Slf4j
public class MemberAdminController {

    private final MemberService memberService;

    public MemberAdminController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 分页查询车主
     *
     * <p>★ 三种条件的匹配方式<b>刻意不同</b>，不是随手写的：
     * <ul>
     *   <li>{@code phone} <b>前缀</b>匹配 —— 手机号是从左往右敲的，
     *       两侧 {@code %} 会让 {@code uk_member_phone} 完全用不上</li>
     *   <li>{@code nickname} <b>模糊</b>匹配 —— 它是给人看的名字，
     *       客服记不全，只能模糊找</li>
     *   <li>{@code status} <b>精确</b>匹配</li>
     * </ul>
     * 完整说明在 {@code MemberMapper.xml} 里 {@code page} 上方的注释。
     *
     * <p>★ 这里的 {@code @Valid} 是承重的：{@code MemberPageQueryDTO}
     * 的 {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西。
     * 完整说明见 {@code StationController#page}。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询车主")
    public Result<PageResult> page(@Valid MemberPageQueryDTO query) {
        return Result.success(memberService.page(query));
    }

    /**
     * 查车主详情
     *
     * <p>★ 返回的 {@code MemberVO} 和车主端 {@code GET /api/member/me}
     * 是<b>同一个类</b>（管理端和车主能看到的字段集合相同），
     * 但<b>没有密码</b> —— 这是结构上的保证，不是「记得别加」。
     * 完整论证见 {@code MemberVO} 的类注释第一节。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询车主详情")
    public Result<MemberVO> getById(@PathVariable Long id) {
        return Result.success(memberService.getById(id));
    }

    /**
     * 启用 / 禁用车主
     *
     * <p>★★ <b>禁用不会撤销已签发的 token</b> —— 这是本轮明确接受的缺口，
     * 有两条不可靠的缓解措施，完整论证见类注释第三节。
     * <b>调用这个接口的人应该知道这一点：它不是「立刻踢下线」。</b>
     *
     * <p>★ {@code status} 走请求体而不是路径 / query 参数 ——
     * 理由（{@code @Valid} 在哪两种写法下会静默失效）见
     * {@code MemberStatusDTO} 的类注释第一节。
     *
     * <p>★ 这是管理端唯一一个改车主<b>行为</b>（而不只是资料）的接口，
     * 所以它的日志级别比别的写接口高一档：
     * {@code MemberServiceImpl.updateStatus} 会记下
     * 「启用/禁用 + memberId + phone」—— 事后追查「谁把这个人封了」
     * 时，{@code member.update_user} 里有操作人（那是一个 staff.id），
     * 日志里有时间点和对象。
     */
    @PutMapping("/{id}/status")
    @Operation(summary = "启用/禁用车主",
            description = "注意：禁用不会撤销已签发的 token，最长 7 天内旧 token 仍可用")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody MemberStatusDTO memberStatusDTO) {
        memberService.updateStatus(id, memberStatusDTO.getStatus());
        return Result.success();
    }
}
