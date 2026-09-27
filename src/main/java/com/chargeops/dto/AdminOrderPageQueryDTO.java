package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单分页查询条件（管理端）
 *
 * <p>分页参数的两条约定（字段初始值给默认、{@code pageSize} 必须有上限）
 * 见 {@code StationPageQueryDTO} 的类注释 —— 那里是这套写法的正本，这里不重复。
 * 三种匹配方式（标识走前缀 / 名字走模糊 / 枚举走精确）的对照表见
 * {@code MemberPageQueryDTO} 的类注释。
 *
 * <h2>★★ 一、这个 DTO 是补上一个真实缺口的 —— 它不是「顺手加的查询」★★</h2>
 *
 * <p>在它之前，{@code /api/admin/**} 下<b>没有任何订单查询接口</b>：
 * {@code OrderAdminController} 只有 {@code start} / {@code end} / {@code settle} /
 * {@code refund} 四个<b>操作</b>接口，而它们全都要求一个 {@code {id}}。
 * 于是管理端出现了一个闭环的死角 —— <b>四个按钮都在，但没有一个页面能列出订单，
 * 因而拿不到那个 id</b>。运维只能靠车主截图报单号，或者直接查库。
 *
 * <p>★ 这一条和「少个筛选条件」不是一回事：它让充电链路在管理端<b>整条不可达</b>。
 * 前端第一次做订单管理页时撞上的就是这个。
 *
 * <h2>★ 二、{@code orderNo} 用前缀匹配，而不是精确匹配</h2>
 *
 * <p>运维手上通常是一个<b>完整</b>的订单号（从车主截图、客服工单、日志里来），
 * 这时候 {@code LIKE 'SO-20260920-abc%'} 和 {@code = } 等价，不多一次全表扫
 * （{@code uk_order_no} 前缀可用）。但反过来，客服拿着半截单号
 * （{@code SO-2026092}）来问「今天这批单子怎么样了」时，精确匹配<b>返回空</b>，
 * 而前缀匹配能列出来。<b>一个更宽松的匹配在完整输入下不多花任何代价</b>，
 * 所以选它。
 *
 * <p>★ 不加两侧 {@code %}：订单号是<b>从左往右敲</b>的标识，理由同
 * {@code MemberPageQueryDTO.phone}。中间匹配在这里除了让索引失效之外，
 * 没有任何真实的使用场景。
 *
 * <h2>★★ 三、时间范围是可选的，而「什么都不传」是一次全表扫描 ★★</h2>
 *
 * <p>排序是 {@code create_time DESC}。这个排序只在<b>带了 {@code status} 时</b>
 * 能由 {@code idx_order_status_create (status, create_time)} 直接给出；
 * <b>不带任何条件时</b>，MySQL 必须扫全表再 filesort。
 *
 * <p>★ <b>这个取舍是有意的，但使用方要知道</b>：订单表是全项目唯一会持续增长的
 * 大表，百万行时「不带条件的管理端订单列表」会变慢。所以
 * <b>{@code createTimeFrom} / {@code createTimeTo} 不是装饰</b> ——
 * 前端应当默认帮运维带上「最近 7 天」，而不是打开页面就裸查。
 *
 * <p>★ <b>不为此加索引</b>：{@code charging_order} 已经有
 * {@code idx_order_status_create} / {@code idx_order_member_create} /
 * {@code idx_order_station_create} 三棵以 {@code create_time} 收尾的索引，
 * 再加一棵纯 {@code create_time} 的 B+ 树，代价是每次订单写入都多维护一棵 ——
 * 而它服务的只是「运维忘了带条件」这一种情况。
 */
@Data
@Schema(description = "订单分页查询条件（管理端）")
public class AdminOrderPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /** ★ {@code @Max(100)} 是唯一挡住 {@code ?pageSize=1000000} 的东西 */
    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "订单号（前缀匹配）", example = "SO-20260920")
    private String orderNo;

    @Schema(description = "状态：1待启动 2充电中 3充电结束待结算 4待支付 5已支付 6支付失败 7已退款 8已取消 9异常挂账",
            example = "9")
    private Integer status;

    @Schema(description = "站点 id（精确）", example = "1")
    private Long stationId;

    /**
     * ★ 车主手机号，<b>前缀匹配</b>，理由同 {@code MemberPageQueryDTO.phone}。
     *
     * <p>★★ 它<b>不</b>参与订单表的 SQL —— Service 会先拿它去 {@code member} 表
     * 查出 id 列表，再让订单查询按 {@code member_id IN (...)} 收窄。
     * 把 {@code LIKE} 直接写进订单查询的 {@code JOIN} 里也能出结果，
     * 但那样「按手机号搜订单」会变成扫全表订单再逐行回表比对手机号；
     * 先查 member 走的是 {@code uk_member_phone} 的前缀，是另一个量级。
     *
     * <p>★ 代价是<b>前缀命中的车主过多时 {@code IN} 列表会很长</b>。
     * 这不是问题：手机号前缀 {@code 138} 也就命中几千行，而运维真正会敲的
     * {@code 1380000} 通常只命中个位数 —— 一个「按手机号搜」的入口，
     * 用户给的本就是足以定位的前缀。
     */
    @Schema(description = "车主手机号（前缀匹配）", example = "138")
    private String memberPhone;

    /**
     * ★ 格式必须显式声明：这个参数走 query string，而 Spring 默认只认 ISO 8601
     * （{@code 2026-09-20T00:00:00}），和本项目全局对外的
     * {@code yyyy-MM-dd HH:mm:ss} 不是一套。不写 {@code @DateTimeFormat}
     * 的话，前端按项目惯例传 {@code 2026-09-20 00:00:00} 会得到一个
     * <b>参数类型不匹配</b> —— 而它会被兜底成「未知错误」，
     * 排查成本高（见 README 缺口表里那条 4xx 兜底）。
     */
    @Schema(description = "建单时间起（含），格式 yyyy-MM-dd HH:mm:ss", example = "2026-09-01 00:00:00")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeFrom;

    @Schema(description = "建单时间止（含），格式 yyyy-MM-dd HH:mm:ss", example = "2026-09-30 23:59:59")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeTo;
}
