package com.chargeops.vo;

import com.chargeops.entity.Station;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 站点信息（车主端）
 *
 * 一、和 {@link StationVO} 的区别只有一处：两个枪数 
 *
 * <p>其余字段两边完全一样（地址、经纬度、营业时间、停车费……都是车主
 * 真的会看的），所以「为什么不复用 {@code StationVO} 加两个字段」这个问题
 * 需要正面回答 —— 答案是反向的：
 *
 * <ul>
 * <li>{@code StationVO} 带 {@code status} 和 {@code statusLabel}，
 * 并且用的是「1启用 / 0停用」那套 {@code StatusConstant} 文案。
 * 车主端不该出现这两个字段：这个接口只返回启用中的站点，
 * 一个恒为「启用」的状态列是纯噪音，而它的存在会让人以为
 * 「有可能返回停用的」；</li>
 * <li>{@code StationVO} 带 {@code createTime} / {@code updateTime}，
 * 那是运维审计用的，对车主没有意义。</li>
 * </ul>
 *
 * <p> 两条加起来就是「同一张表、给两种人看、需要的字段相反」——
 * 和 {@code AdminOrderVO} 对 {@code ChargingOrderVO} 是同一个判断。
 *
 * 二、那两个枪数是这个 VO 存在的理由 
 *
 * <p>没有它们，车主列表上只能看到一串站名，看不出哪个站现在有枪可用 ——
 * 于是只能一个个点进去，这是「选站」这个动作里最没价值的一步。
 *
 * <p> {@code idleConnectorCount} 的口径是 {@code status = 1 空闲}，
 * 不等于「能下单」：真正的裁决在建单那一刻的
 * {@code ConnectorMapper.occupy} 条件更新上。中间这几秒里枪可能被别人抢走，
 * 那是正常的竞争，不是这个字段算错了 —— 见 {@link MemberConnectorVO#available}。
 *
 * <p> 数据来源是 {@code connector.station_id} 这个冗余列，
 * 不是 {@code connector JOIN charger}。建表时把 {@code station_id}
 * 冗余到 {@code connector} 上正是为了这个：一次
 * {@code GROUP BY station_id} 就够了，不用为了数一把枪去 join 整张
 * {@code charger} 表。走的是 {@code idx_connector_station_status (station_id, status)}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberStationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 站点编码，对外可见 */
    private String code;

    private String name;

    /** 运营商 */
    private String operator;

    private String province;

    private String city;

    private String district;

    private String address;

    /**  {@code decimal(10,6)}，绝不用 {@code double} —— 见 {@code Station} 类注释第三节 */
    private BigDecimal longitude;

    private BigDecimal latitude;

    private String servicePhone;

    /** 营业开始，形如 {@code "08:00:00"}。 是 String，见 {@code Station} 类注释第一节 */
    private String openTime;

    /** 营业结束。 {@code "24:00:00"} 表示营业到当天结束，不是脏数据 */
    private String closeTime;

    /** 停车费，元/小时 */
    private BigDecimal parkingFee;

    /**
     * 该站下充电枪总数
     *
     * <p> 含离线、故障、维护中的枪 —— 它是「这个站有多少把枪」，
     * 不是「有多少把能用」。用 {@link #idleConnectorCount} 回答后者。
     *
     * <p> 站下一把枪都没有时是 0（不是 null）：{@code LEFT JOIN}
     * 侧没有匹配行，聚合函数 {@code COUNT} 返回 0。见
     * {@code ConnectorMapper.countByStationIds} —— 那里刻意用了
     * {@code SUM(status = 1)} 而不是 {@code COUNT(...)}，就是为了让这个 0
     * 落在同一个形状上。
     */
    private Integer connectorCount;

    /**
     * 其中「1 空闲」的枪数
     *
     * <p> 它是给排序和一眼扫过用的，不是可用性的保证。
     * 真正决定能不能下单的是建单时的条件更新，
     * 完整的说明见 {@link MemberConnectorVO#available}。
     */
    private Integer idleConnectorCount;

    public static MemberStationVO from(Station station) {
        return from(station, 0, 0);
    }

    public static MemberStationVO from(Station station, int connectorCount, int idleConnectorCount) {
        return MemberStationVO.builder()
                .id(station.getId())
                .code(station.getCode())
                .name(station.getName())
                .operator(station.getOperator())
                .province(station.getProvince())
                .city(station.getCity())
                .district(station.getDistrict())
                .address(station.getAddress())
                .longitude(station.getLongitude())
                .latitude(station.getLatitude())
                .servicePhone(station.getServicePhone())
                .openTime(station.getOpenTime())
                .closeTime(station.getCloseTime())
                .parkingFee(station.getParkingFee())
                .connectorCount(connectorCount)
                .idleConnectorCount(idleConnectorCount)
                .build();
    }
}
