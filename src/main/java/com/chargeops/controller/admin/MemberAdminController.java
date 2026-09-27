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
 * <p>★ 类名带 {@code Admin} 是因为车主端已经占用了 {@code controller/user/MemberController}。
 * 两个同名类合法，但会让 import、日志、Swagger 里「这是哪个 MemberController」都要多看一步。
 *
 * <h2>★ 一、只有三个端点（{@code page} / {@code {id}} / {@code {id}/status}），故意没有新建和硬删</h2>
 *
 * <p><b>不做新建</b>：账号来源已定为<b>自助注册</b>（{@code POST /api/auth/member/register}）。
 * 管理端建号会引入「初始密码怎么安全送达」这个问题，而它<b>没法在接口层解决</b> ——
 * 发短信没有设施，口头告知则没有任何审计记录。
 *
 * <p><b>不做硬删</b>：车主有订单、钱包、车辆，硬删会让它们全变成孤儿。禁用就是停用手段。
 *
 * <h2>★ 二、这和 {@code CategoryController} 的「硬删必须存在」是同一个项目里相反的两个决定</h2>
 *
 * <p>差别在唯一键：category 的 {@code (type, name)} 是<b>人造的</b>，建错一次就永久占用
 * （唯一键不看 {@code status}），没有硬删只能去生产库手敲 DELETE；member 的 {@code phone}
 * 是<b>真实世界的标识</b>，不存在「建错了」，而硬删的代价是订单 / 钱包 / 车辆全变孤儿。
 * 两个相反决定各自的理由都写在这两个类的注释里，半年后被问起不用翻文档。
 *
 * <h2>★ 三、已知缺口：禁用车主不会撤销已经签发的 token</h2>
 *
 * <p>拦截器<b>只验签名、从不查库</b>，而 member token 的有效期是 <b>7 天</b>
 * （{@code chargeops.jwt.member-ttl}）。{@code PUT /{id}/status} 把 {@code status} 改成 0 之后，
 * 那个车主手上的 token <b>最长还能用 7 天</b>，包括继续调 {@code /api/member/**} 下的所有接口。
 *
 * <p><b>处置：不引入黑名单</b> —— 那等于每个请求一次 Redis 往返。
 * 接受的是<b>token 无法提前失效</b>这件事本身，不是「禁用没有作用」：
 * 有资金影响的端点见下文，它们每次都重新查一次状态。
 *
 * <p>★★ <b>但当初支持这个处置的那个理由已经过期了。</b>原话是
 * 「{@code /api/member/**} 只有我的信息 / 我的车辆这类<b>无资金影响</b>的读写」——
 * 那是 P1c-2 / P1c-3 之前的事实。现在这条前缀下多了<b>充值、建单、支付</b>，
 * 三个都有资金影响。所以下面那条缓解措施从「锦上添花」变成了<b>必须兑现</b>。
 *
 * <p>★ 两条已有的缓解措施，都不是靠服务端强制：{@code GET /api/member/me} 的响应里带 {@code status}，
 * 它是前端的启动探针，所以<b>行为正常的</b>客户端会看到 {@code status = 0} 并主动登出，
 * 这把例外从「所有客户端」缩小到「从不调 {@code /me} 的客户端」；
 * {@code member_token_name} 用 {@code authentication} 而管理端用 {@code token}，
 * 但这只防<b>误用</b>，不防伪造（防伪造靠密钥隔离）。
 *
 * <p>★★ <b>真正要紧的是钱包、订单这类有资金影响的端点：它们必须在自己的 Service 里
 * 重新查一次 {@code member.status}</b>，不能依赖「拦截器已经验过了」——
 * 拦截器验的是<b>签名</b>，它证明「这个 token 是我们在 7 天内签发的」，
 * 而不是「这个人现在还能用」。
 *
 * <p>★★ <b>这件事已经落地</b>，落点是 {@code MemberService.assertActive}，共 7 处调用：
 * {@code WalletServiceImpl} 3 处（{@code getMine} / {@code recharge} / {@code pageTransactions}）、
 * {@code ChargingOrderServiceImpl} 4 处（{@code create} / {@code pageMine} / {@code getMine} / {@code cancel}）。
 *
 * <p>★ 唯一<b>刻意不查</b>的是 {@code pay}：禁用后 {@code cancel} 已被挡住，而
 * {@code refund} 的 CAS 只认 {@code 5 已支付} —— 在这里补一次 {@code assertActive}
 * 会让一笔 {@code 4 待支付} 订单的冻结资金<b>再也解不开</b>。理由写在
 * {@code ChargingOrderServiceImpl.pay} 上。别照着其余方法「顺手补齐」。
 *
 * <p>★ <b>而且这不该修在拦截器里</b>：状态校验属于 Service，因为
 * <b>「该不该查状态」取决于这个接口有没有资金影响</b>，而那是每个 Service 自己知道的事，
 * 拦截器不知道（{@code JwtTokenMemberInterceptor} 的类注释里也预留了这句话）。
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
     * ★ 三种条件的匹配方式<b>刻意不同</b>：{@code phone} <b>前缀</b>匹配 —— 手机号是从左往右
     * 敲的，两侧 {@code %} 会让 {@code uk_member_phone} 完全用不上；{@code nickname} <b>模糊</b>
     * 匹配 —— 给人看的名字，客服记不全；{@code status} <b>精确</b>匹配。
     * 完整说明在 {@code MemberMapper.xml} 里 {@code page} 上方的注释。
     *
     * <p>★ 这里的 {@code @Valid} 是承重的：{@code MemberPageQueryDTO} 的 {@code @Max(100)}
     * 是唯一挡住 {@code ?pageSize=1000000} 的东西。完整说明见 {@code StationController#page}。
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询车主")
    public Result<PageResult> page(@Valid MemberPageQueryDTO query) {
        return Result.success(memberService.page(query));
    }

    /**
     * ★ 返回的 {@code MemberVO} 和车主端 {@code GET /api/member/me} 是<b>同一个类</b>
     * （两侧可见字段集合相同），但<b>没有密码</b> —— 这是结构上的保证，不是「记得别加」。
     * 完整论证见 {@code MemberVO} 的类注释第一节。
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询车主详情")
    public Result<MemberVO> getById(@PathVariable Long id) {
        return Result.success(memberService.getById(id));
    }

    /**
     * ★ <b>禁用不会撤销已签发的 token</b>（完整论证见类注释第三节）——
     * <b>调用这个接口的人应该知道：它不是「立刻踢下线」。</b>
     *
     * <p>★ {@code status} 走请求体而不是路径 / query 参数 —— 理由（{@code @Valid}
     * 在哪两种写法下会静默失效）见 {@code MemberStatusDTO} 的类注释第一节。
     *
     * <p>★ 这是管理端唯一一个改车主<b>行为</b>（而不只是资料）的接口，所以日志级别高一档：
     * {@code MemberServiceImpl.updateStatus} 会记下「启用/禁用 + memberId + phone」——
     * 事后追查「谁把这个人封了」时，{@code member.update_user} 里有操作人（staff.id），
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
