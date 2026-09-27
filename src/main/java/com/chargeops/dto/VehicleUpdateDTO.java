package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 修改车辆的请求体
 *
 * 一、这里没有 {@code isDefault}、没有 {@code memberId}
 *
 * <p>没有 {@code isDefault}：改默认车只有一个入口 ——
 * {@code PUT /api/member/vehicle/{id}/default}。让这个 DTO 也能改它，就会产生
 * 两条通向同一个不变量的写入路径，而那个不变量需要「先清掉其它车的标记、
 * 再把这辆置 1」两个动作，只做后一半的结果是两辆默认车 —— 不报错、
 * 不冲突，只是列表页上出现两个「默认」标签。 两条路径通向一个不变量，迟早有
 * 一条忘了清旧的；删掉字段比在两处都记得清旧划算得多。
 *
 * <p>没有 {@code memberId}：归属是身份的一部分，不是资料。改车的
 * SQL（{@code VehicleMapper.xml} 的 {@code update}）里，{@code member_id} 是
 * {@code WHERE} 条件而不是 {@code SET} 目标。 这和 {@code ConnectorUpdateDTO}
 * 里没有 {@code chargerId} 是同一个理由：能改「这行属于谁」的接口，就是一个
 * 能改归属的越权入口 —— 让它根本不存在。
 *
 * 二、{@code plateNo} 允许修改 —— 这是 DDL 明确支持的
 *
 * <p>{@code charging_order.plate_no} 的列注释：「车牌快照：车辆可被删除/改牌，
 * 订单必须保留当时值」。改牌之所以安全，正是因为订单存的是当时的快照，
 * 而不是每次去 JOIN {@code vehicle} 取当前值 —— 所以改牌之后历史订单显示旧牌
 * （正确，当时确实是那块牌）、这辆车显示新牌（正确），两件事都对。
 *
 * <p> 改牌撞 {@code uk_vehicle_member_plate} 时由数据库拦、Service 不预检，
 * 理由见 {@code StationMapper} 的类注释。
 *
 * 三、局部更新的已知限制：没法把字段清空回 NULL
 *
 * <p>和 {@code MemberUpdateDTO} 一样，见那个类的注释第二节。
 * 具体到这里：「清掉备注」办不到，传 null 是「不改」。
 */
@Data
@Schema(description = "修改车辆")
public class VehicleUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 传了就会重新规范化（trim + 转大写），和新增共用
     * {@code normalizePlateNo} —— 否则改牌就成了一条绕过它的旁路：
     * 车主能把车牌改成小写的，于是同一个车主名下出现两种写法。
     */
    @Schema(description = "车牌号，入库统一转大写", example = "粤BD12345")
    @Size(max = 16, message = "车牌号不能超过 16 个字符")
    private String plateNo;

    /** 取值见 {@code PlateColor} */
    @Schema(description = "车牌颜色：1蓝 2黄 3绿 4渐变绿", example = "3")
    @Min(value = 1, message = "车牌颜色只能是 1蓝、2黄、3绿、4渐变绿")
    @Max(value = 4, message = "车牌颜色只能是 1蓝、2黄、3绿、4渐变绿")
    private Integer plateColor;

    @Schema(description = "品牌", example = "比亚迪")
    @Size(max = 50, message = "品牌不能超过 50 个字符")
    private String brand;

    @Schema(description = "型号", example = "汉 EV")
    @Size(max = 50, message = "型号不能超过 50 个字符")
    private String model;

    /** 电池容量，kWh。 不参与计费，只用于估算 */
    @Schema(description = "电池容量，kWh", example = "85.00")
    @DecimalMin(value = "0", message = "电池容量不能为负数")
    private BigDecimal batteryCapacityKwh;

    @Schema(description = "备注", example = "家用车")
    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
