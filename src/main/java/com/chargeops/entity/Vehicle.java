package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆（对应 {@code vehicle} 表）；字段类型的两条约定见 {@link Staff} 的类注释。
 *
 * <p>{@code plate_no} 唯一索引 {@code uk_vehicle_member_plate (member_id, plate_no)}，
 * 入库统一转大写 + 去首尾空格：{@code utf8mb4_0900_ai_ci} 已经让唯一键大小写不敏感，
 * 转大写是让规则不依赖排序规则；而它 NO PAD，尾随空格只能靠 trim 拦。不做格式正则。
 *
 * <p>「每个车主最多一辆默认车」没有唯一索引可用：非默认行的 {@code is_default} 全是 0，
 * {@code (member_id, is_default)} 会连「一个车主有两辆非默认车」一起禁掉。只有应用层守得住，
 * 落地在 {@code VehicleMapper.clearDefaultExcept} + {@code markDefault}。
 *
 * <p>没有 {@code status} 列，硬删是唯一手段；订单侧读 {@code charging_order.plate_no} 快照列，
 * 不许 JOIN {@code vehicle} 取车牌。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属车主 id，绝不允许来自请求体（DTO 里没有它）；{@code VehicleMapper} 的每条 SQL 都带它 */
    private Long memberId;

    private String plateNo;

    /** {@code 1蓝 2黄 3绿 4渐变绿}，可空 —— 见 {@link com.chargeops.enumeration.PlateColor} */
    private Integer plateColor;

    private String brand;

    private String model;

    /** 电池容量 kWh，只用于估算充满需要多少电，不参与计费，所以可空 */
    private BigDecimal batteryCapacityKwh;

    /**
     * {@code 1默认 0非默认}，{@code NOT NULL}，传 null 会直接 errno 1048。
     * 读侧不要写 {@code WHERE is_default = 1} 期望一行，按 {@code ORDER BY is_default DESC, id DESC} 取第一条。
     */
    private Integer isDefault;

    private String remark;

    // ---------- 审计四列 ----------
    // 由 AutoFillInterceptor 反射填充，四列齐全所以可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
