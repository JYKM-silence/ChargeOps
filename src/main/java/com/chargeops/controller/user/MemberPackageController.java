package com.chargeops.controller.user;

import com.chargeops.result.Result;
import com.chargeops.service.MemberPackageService;
import com.chargeops.vo.ChargePackageVO;
import com.chargeops.vo.MemberPackageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 车主端 - 充电套餐（权益）
 *
 * <p>路径 {@code /api/member/package/**}，由 {@code JwtTokenMemberInterceptor} 保护。
 *
 * <h2>★ 一、三个端点全都不带 memberId</h2>
 *
 * <p>理由同 {@code MemberController} / {@code WalletController}：
 * <b>身份唯一的来源是 token</b>，而 Service 层的方法签名里也没有 id 可传。
 * 「买一份套餐挂在别人名下」这件事在这个接口形状里<b>无法表达</b>。
 *
 * <h2>★ 二、货架和「我的套餐」是两个端点，不是一个带开关的端点</h2>
 *
 * <p>{@code GET /available} 返回的是<b>商品</b>（{@code ChargePackageVO}），
 * {@code GET /mine} 返回的是<b>我买下的那几份</b>（{@code MemberPackageVO}）。
 * 两者的字段、来源表、以及「为什么这一行是这个样子」都不同 ——
 * 合成一个 {@code ?scope=...} 的端点会让返回体有两种互不相容的形状，
 * 而客户端类型系统无法表达「这个字段在这个 scope 下有值」。
 *
 * <h2>★★ 三、{@code stationId} 是 query 参数，而 {@code packageId} 是路径参数</h2>
 *
 * <p>这个区分是有意的，不是随手写的：
 * <ul>
 *   <li>{@code stationId} 是<b>查询条件</b> —— 「看哪个站的货架」。
 *       它不标识一个资源，改它不会让 URL 指向别的东西；</li>
 *   <li>{@code packageId} 是<b>资源标识</b> —— 「就买这一个」。
 *       {@code POST /api/member/package/{packageId}/purchase} 读起来是
 *       「对这个套餐执行购买」，路径本身就是那句谓语的主语。</li>
 * </ul>
 *
 * <p>★ 对照写法 {@code POST /api/member/package/purchase?packageId=1}：
 * 那样 packageId 变成两个平级的 query 参数之一，而<b>它是这个动作的宾语</b>。
 * 更要紧的是「购买」这个动作会有一个不带 packageId 也能构造出来的 URL，
 * 而那个 URL 的语义是「买什么？」—— 一个没有答案的问题。
 */
@RestController
@RequestMapping("/api/member/package")
@Tag(name = "车主端-充电套餐")
@Slf4j
@RequiredArgsConstructor
public class MemberPackageController {

    private final MemberPackageService memberPackageService;

    /**
     * ★ <b>必须传 stationId</b>，没有「不限站点」这个变体。
     *
     * <p>不传时 Service 抛参数错误而不是「返回全平台套餐」——
     * 静默放宽范围是这个接口最坏的失败方式。理由见
     * {@code ChargePackageMapper.listAvailableForMember}。
     */
    @GetMapping("/available")
    @Operation(summary = "我在这个站点能买哪些套餐",
            description = "含全平台套餐（station_id=0）和本站专属套餐。stationId 必传")
    public Result<List<ChargePackageVO>> listAvailable(
            @Parameter(description = "站点 id", example = "1")
            @RequestParam Long stationId) {
        return Result.success(memberPackageService.listAvailable(stationId));
    }

    /**
     * ★ 返回的是<b>刚写进 {@code member_package} 的那一行</b>，不是 SKU ——
     * 车主买完要看的是「我这份还剩多少、什么时候过期」，那是持有属性。
     *
     * <p>★ 这个接口<b>不是幂等的</b>：连点两次会真的买两份。前端必须在请求期间
     * 禁用按钮，而服务端那道削峰锁只挡「几乎同时到达」的那一次
     * （见 {@code RedisConstant.LOCK_MEMBER_PACKAGE_PURCHASE}）。
     * <b>不要把它写成「同一个套餐只允许买一次」</b> —— 买两份同款套餐是合法的
     * （一个车主可能有两辆车，或者就是觉得划算）。
     */
    @PostMapping("/{packageId}/purchase")
    @Operation(summary = "购买套餐", description = "从余额直接扣款，一个事务完成；不做站点范围校验")
    public Result<MemberPackageVO> purchase(
            @Parameter(description = "套餐 id", example = "1")
            @PathVariable Long packageId) {
        return Result.success(memberPackageService.purchase(packageId));
    }

    /**
     * ★ 不过滤状态：已用尽 / 已过期 / 已作废的也会返回，前端渲染成灰态。
     * 过滤掉它们的后果是用户来问客服「我那个 150 度的包去哪了」，
     * 而客服在后台查得到 —— 两边看的是同一个系统，却给出不同的答案。
     */
    @GetMapping("/mine")
    @Operation(summary = "我的套餐", description = "不过滤状态，已用尽/已过期/已作废同样返回")
    public Result<List<MemberPackageVO>> listMine() {
        return Result.success(memberPackageService.listMine());
    }
}
