package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端启用 / 禁用车主
 *
 * <h2>★ 一、只有一个字段，为什么还值得单开一个 DTO</h2>
 *
 * <p>三个理由，都不是「整洁」：
 *
 * <p><b>1. 用 {@code @RequestParam Integer status} 的话，这个类里的
 * {@code @Min/@Max} 一个都不会生效。</b>{@code @Valid} 校验的是
 * {@code @RequestBody} 的对象，而 {@code @RequestParam} 上的约束要
 * 配合类级别的 {@code @Validated} 才会触发 —— 那个写法抛的是
 * {@code HandlerMethodValidationException}，而
 * {@code GlobalExceptionHandler} 里<b>没有对应的分支</b>
 * （那个类的注释末尾专门标注了这一点），于是它会落到兜底分支，
 * 前端拿到的是「未知错误」。<b>一个 400 变成 500。</b>
 *
 * <p><b>2. 一个裸的 {@code Integer} 没有地方写文档。</b>
 * 这个字段的值域是 {@code 0禁用 / 1正常}，而
 * {@code ?status=7} 光看签名完全看不出是错的。写在
 * {@code @Schema} 上之后，Swagger 里能看见。
 *
 * <p><b>3. 它是「将来会长大」的形状。</b>禁用车主大概率会需要
 * 「禁用原因」这一列（现在 {@code member} 表里没有）——
 * 那时加一个字段就行，不用把路径从 query 参数改成请求体
 * （那是一个会破坏已有客户端的改动）。
 *
 * <h2>★ 二、为什么这个端点是 {@code PUT /{id}/status} 而不是
 * {@code PUT /{id}} 上带一个字段</h2>
 *
 * <p>因为车主的资料更新走的是<b>另一个 Controller</b>
 * （{@code controller/user/MemberController}，路径 {@code /api/member/me}），
 * 而那一边<b>刻意没有</b> {@code status} 字段。
 *
 * <p>★ 两边的差别是身份面：车主改自己的资料时不能碰 {@code status}
 * （否则「禁用」就是一个请求就能绕过的功能），
 * 而管理端改别人的 {@code status} 正是这个端点的<b>全部职责</b>。
 * 把它们做成同一个接口会更省代码，但那会让
 * 「谁能改 status」这件事重新变成一个需要判断的问题 ——
 * 现在它是<b>由路径划分的</b>：{@code /api/member/**} 是车主自己，
 * {@code /api/admin/**} 是管理员，前者没有这个能力，后者有。
 *
 * <h2>★★ 三、用独立端点而不是混进资料更新，还有一个具体的好处</h2>
 *
 * <p>{@code MemberMapper.xml} 的 {@code update} 里<b>没有 {@code status} 列</b>，
 * 而 {@code updateStatus} 是一条只有 {@code status} + 两个审计列的窄 SQL。
 * 两条写入路径分开 ⇒ 管理端禁用车主时<b>不可能顺手改掉他的昵称</b>，
 * 因为那条 SQL 里根本没有 nickname 那一列。
 *
 * <p>★ 这和 {@code VehicleUpdateDTO} 排除 {@code isDefault} 是同一个手法：
 * <b>把「不该一起发生的事」变成「不可能一起发生的事」。</b>
 */
@Data
@Schema(description = "启用/禁用车主")
public class MemberStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * {@code 1正常 0禁用}
     *
     * <p>★ {@code @NotNull} 是承重的：少了它，不传 status 会一路走到
     * {@code UPDATE member SET status = NULL}，而 {@code member.status}
     * 是 {@code NOT NULL} → errno 1048 → HTTP 500 +「未知错误」。
     * 加上它就是一个 400 +「状态不能为空」。
     *
     * <p>★ 值域用 {@code @Min/@Max} 卡死，而不是留给 Service 去判断 ——
     * 这样错误提示是「状态只能是 0禁用、1正常」，
     * 而不是 Service 里那句拼出来的同义话。
     * 两处都校验是有意的：这里是给调用方的提示，Service 那里是兜底
     * （和 {@code MemberUpdateDTO.gender} 同一个形状）。
     */
    @Schema(description = "状态：1正常 0禁用", example = "0")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0禁用、1正常")
    @Max(value = 1, message = "状态只能是 0禁用、1正常")
    private Integer status;
}
