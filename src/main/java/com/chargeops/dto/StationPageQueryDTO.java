package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 站点分页查询条件
 *
 * 所有字段都是可选的，包括分页参数本身
 *
 * <p>这不是「没想好」，是刻意的：{@code GET /api/admin/station/page} 不带任何参数
 * 就应该返回第一页 10 条。要求调用方必须传 {@code pageNum} 只是把「默认值」
 * 这件事推给了每一个前端的每一个列表页。
 *
 * {@code pageNum} / {@code pageSize} 的默认值写在字段初始值里
 *
 * <p>Spring 的参数绑定先用无参构造器创建对象（字段初始值生效），再把请求里
 * 实际存在的参数覆盖上去，所以「不传 pageNum」拿到的是 1 而不是 null。
 * 换个写法（在 Service 里 {@code q.getPageNum() == null ? 1 : ...}）也能跑，
 * 但那意味着每一个用到这个 DTO 的地方都要记得判一次空，
 * 漏掉一处就是一次 NPE 或者一次 {@code LIMIT null}。
 *
 * {@code pageSize} 必须有上限
 *
 * <p>{@code @Max(100)}。少了它，{@code ?pageSize=1000000} 就是一行 DoS ——
 * 一个不需要任何认证之外的技巧就能打垮服务的方式：它会让 MySQL 扫出全表、
 * 让 JVM 分配一个百万行的 {@code ArrayList}、再让 Jackson 把它序列化成几十 MB
 * 的 JSON，而这一切都发生在一次看起来完全正常的、合法的请求里。
 *
 * <p>为什么是 100 而不是 1000：后台管理界面的列表页没有一屏显示超过 100 行的，
 * 而超过 100 的请求几乎只可能来自爬虫或者压测脚本。上限设小一点，
 * 代价是「导出全部」这类需求需要单独做接口 —— 那本来就该单独做，
 * 因为它的性能特征和列表页完全不同。
 *
 * <p>过滤字段是「精确匹配」还是「模糊匹配」由 Mapper 的 XML 决定，
 * 这里不表达。
 */
@Data
@Schema(description = "站点分页查询条件")
public class StationPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数，最大 100", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;

    @Schema(description = "站点名称（模糊）", example = "科技园")
    private String name;

    /**  前缀匹配：运维常按编码前缀找 */
    @Schema(description = "站点编码（前缀匹配）", example = "ST-440300")
    private String code;

    /**  走索引 {@code idx_station_city_status} */
    @Schema(description = "城市（精确）", example = "深圳市")
    private String city;

    @Schema(description = "状态：0停用 1启用 2维护中", example = "1")
    private Integer status;
}
