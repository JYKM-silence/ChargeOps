package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 车主分页查询条件（管理端）
 *
 * <p>分页参数的两条约定（字段初始值给默认、{@code pageSize} 必须有上限）
 * 见 {@code StationPageQueryDTO} 的类注释 —— 那里是这套写法的正本，这里不重复。
 *
 * <h2>★ 一、{@code phone} 是<b>前缀</b>匹配，{@code nickname} 是模糊 —— 为什么不一样</h2>
 *
 * <p><b>{@code phone} 前缀匹配</b>：手机号是<b>从左往右敲</b>的，客服拿着半截号码
 * （{@code 1380000}）来查，给的就是前缀；而且前缀匹配<b>有机会</b>用上
 * {@code uk_member_phone}，两侧加 {@code %} 则一定用不上 ——
 * 一个「按手机号搜」的入口却注定全表扫描，是不必要的。
 *
 * <p><b>{@code nickname} 模糊匹配</b>：它是给人看的名字，客服记不全
 * （「那个叫什么…测试的？」），只能中间匹配。它上面没有索引，所以注定全表扫 ——
 * 在这个量级下无所谓，真到需要优化时该做的是搜索引擎，而不是给 nickname
 * 加索引（前缀索引对「中间匹配」无效）。
 *
 * <h2>★ 二、这一轮<b>不</b>按 {@code lastLoginTime} 排序或过滤</h2>
 *
 * <p>它是能查的，但含义容易被误解：它只在<b>签发 token 成功</b>那一刻更新，
 * 而 token 有 7 天有效期 —— 一个天天用小程序充电的车主，这个值可能停在 7 天前。
 * 把「最近活跃」做成一个基于它的筛选项，会得到一个
 * <b>看起来对、实际上漏掉大量活跃用户</b>的列表。要判断活跃得看订单或钱包流水。
 */
@Data
@Schema(description = "车主分页查询条件")
public class MemberPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /** ★ {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "手机号（前缀匹配）", example = "138")
    private String phone;

    @Schema(description = "昵称（模糊）", example = "测试")
    private String nickname;

    @Schema(description = "状态：1正常 0禁用", example = "1")
    private Integer status;
}
