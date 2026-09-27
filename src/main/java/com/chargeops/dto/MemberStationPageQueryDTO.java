package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 站点分页查询条件（车主端）
 *
 * <p>分页参数的两条约定（字段初始值给默认、{@code pageSize} 必须有上限）
 * 见 {@code StationPageQueryDTO} 的类注释，三种匹配方式的对照表见
 * {@code MemberPageQueryDTO} 的类注释。
 *
 * <h2>★★ 一、它是补上一个真实缺口的 —— 车主端此前<b>看不到任何站点</b> ★★</h2>
 *
 * <p>在它之前，{@code /api/member/**} 下唯一和设备有关的东西是
 * {@code POST /api/member/orders}，而那个接口要三个 id：
 * {@code stationId} / {@code chargerId} / {@code connectorId}。
 *
 * <p>★ <b>三个 id 在车主端没有任何合法来源</b> —— 车主只能靠扫二维码
 * 拿到它们。没有二维码的场景（运维临时指一把枪、在手机上先看看哪把枪空着）
 * 完全走不通，前端也只能让用户手输三个数字。
 *
 * <h2>★ 二、为什么<b>没有</b> {@code status} 条件</h2>
 *
 * <p>车主端只能看到「1 启用」的站点，而这个约束<b>做进 SQL</b>（见
 * {@code StationMapper.pageForMember}），不做成 DTO 上的一个可传字段。
 *
 * <p>★ 理由是「停用」这个状态对车主<b>没有第二种含义</b>：
 * 它就是不展示。如果做成可传参数，那么「不传时展示什么」就要有人回答，
 * 而任何一个答案都是错的 —— 不传展示全部等于泄露了还在维护中的站点，
 * 不传默认 1 又和「传了就覆盖」的直觉打架。既然只可能有一个值，
 * 它就不该是一个参数。
 *
 * <p>★ 管理端有 {@code status}（{@code StationPageQueryDTO}），
 * 那里的运维<b>要</b>看到停用的站 —— 这正是两侧 DTO 不共用的原因。
 *
 * <h2>★ 三、{@code city} 精确而 {@code name} 模糊</h2>
 *
 * <p>和 {@code StationPageQueryDTO} 保持一致：城市是<b>从列表里选的</b>
 * （值域有限、拼写统一），站名是<b>手敲的</b>（记不全，只能模糊找）。
 * 两者的区别不在「哪个更常见」，而在<b>输入是从哪来的</b>。
 */
@Data
@Schema(description = "站点分页查询条件（车主端）")
public class MemberStationPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /** ★ {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "站点名称（模糊匹配）", example = "科技园")
    private String name;

    @Schema(description = "城市（精确匹配）", example = "深圳市")
    private String city;
}
