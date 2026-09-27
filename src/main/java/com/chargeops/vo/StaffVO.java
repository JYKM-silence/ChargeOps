package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 运维人员信息（不含密码）
 *
 * <p>{@code GET /api/admin/staff/me} 返回它。
 *
 * <p><b>★ 为什么不直接返回 {@link com.chargeops.entity.Staff} 实体。</b>
 * 这是本项目一条贯穿始终的规则，值得单独说明一次。
 *
 * <p>返回实体最省事，问题在于它<b>默认是全字段暴露</b>：今天 Staff 里只有
 * {@code password} 一个敏感字段，你会记得在 Service 里写 {@code setPassword(null)}；
 * 明天产品说「加个身份证号」，你加了字段、忘了那行处理 —— 身份证号就跟着所有查询接口
 * 一起出去了，而且<b>没有任何东西会提醒你</b>：编译通过、测试通过、接口返回 200。
 *
 * <p>VO 把这个方向反过来：<b>它的默认状态是「什么都不返回」</b>，要暴露什么必须显式声明。
 * 新加字段时忘了往 VO 加，后果只是「前端拿不到」—— 一个会立刻被发现的、良性的失败。
 *
 * <p>与之配套的还有一层保险：{@code StaffMapper.xml} 里的查询<b>根本不 select
 * password 列</b>，即使哪天误把实体返回出去，那个字段也是 null。两层独立生效，
 * 任何一层单独失效都不会造成泄露。
 */
@Data
@Schema(description = "运维人员信息（不含密码）")
public class StaffVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "运维人员 id")
    private Long id;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "性别：0 女 1 男")
    private String sex;

    @Schema(description = "角色：1 平台管理员 2 站点运维")
    private Integer role;

    @Schema(description = "负责站点 id，0 为平台级")
    private Long stationId;

    @Schema(description = "状态：1 启用 0 禁用")
    private Integer status;

    /**
     * 格式是 {@code yyyy-MM-dd HH:mm:ss}（带秒），由
     * {@code JacksonConfiguration} 里的 customizer 统一配置。不要在字段上加
     * {@code @JsonFormat} —— 那种「每个字段各写一遍格式」的做法迟早会漏掉一个，
     * 然后同一个响应里出现两种时间格式。
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
