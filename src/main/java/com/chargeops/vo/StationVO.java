package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电站信息
 *
 * <h2>★ 为什么不直接返回 {@code Station} 实体</h2>
 *
 * <p>同 {@link StaffVO} 里说明的那条贯穿全局的规则：实体的默认状态是
 * <b>全字段暴露</b>，而 VO 的默认状态是<b>什么都不返回</b>。这里具体省掉的是
 * {@code createUser} / {@code updateUser} 两个审计列 —— <b>「谁改的」通常不该
 * 跟着业务数据一起返回</b>，它是内部审计信息；将来真要做操作日志，那是另一个接口，
 * 不是把这个字段加回来。{@code createTime} / {@code updateTime} 保留
 * （「这个站最近什么时候被改过」有用，而且不涉及谁操作的）。
 *
 * <h2>★ {@code statusLabel} 是 VO 的第二个价值</h2>
 *
 * <p>VO 的第一层价值是「少返回东西」，第二层是<b>「多返回一点已经算好的东西」</b>：
 * {@code statusLabel} 把 {@code 1} 变成「启用」。不做的话前端要维护一份
 * code → label 的映射表，而后端枚举里也有一份 —— 加状态值时后端加了、前端忘了，
 * 界面上就会出现一个空白的状态列。让后端算好，映射表就只有一份。
 *
 * <p>★ 注意 label 是<b>派生字段</b>：它<b>不出现在任何 DTO 里</b>，也不接受客户端输入，
 * 由 Service 在转 VO 时从枚举取。
 */
@Data
@Schema(description = "充电站信息")
public class StationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "站点 id")
    private Long id;

    @Schema(description = "站点编码")
    private String code;

    @Schema(description = "站点名称")
    private String name;

    @Schema(description = "运营商")
    private String operator;

    @Schema(description = "省")
    private String province;

    @Schema(description = "市")
    private String city;

    @Schema(description = "区/县")
    private String district;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "经度")
    private BigDecimal longitude;

    @Schema(description = "纬度")
    private BigDecimal latitude;

    @Schema(description = "服务电话")
    private String servicePhone;

    /**
     * 开始营业时间
     *
     * <p>★ 原样返回字符串，不做任何格式化 —— {@code "24:00:00"} 必须能原样出去。这里
     * 没有任何转换，正是为了让那个值在整条链路上保持字节级不变（{@code StationMapperTest}
     * 里有一条断言专门钉住这件事）。
     */
    @Schema(description = "开始营业时间，HH:mm:ss")
    private String openTime;

    @Schema(description = "结束营业时间（不含），24:00:00 表示到当天结束")
    private String closeTime;

    @Schema(description = "停车费，元/小时；null 表示未设置，0 表示免费")
    private BigDecimal parkingFee;

    @Schema(description = "状态：0停用 1启用 2维护中")
    private Integer status;

    @Schema(description = "状态文案，如「启用」")
    private String statusLabel;

    /**
     * ★ 格式 {@code yyyy-MM-dd HH:mm:ss} 由 {@code JacksonConfiguration} 统一配置，
     * 不要在字段上加 {@code @JsonFormat} —— 那会让同一个响应里出现两种时间格式。
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
