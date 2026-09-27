package com.chargeops.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 「某个站点下有多少把枪」——只作为一次批量统计的载体，不对任何接口暴露
 *
 * 为什么值得为三个字段单开一个类
 *
 * <p>它是 {@code ConnectorMapper.countByStationIds} 的返回类型，
 * 而那个查询是 {@code SELECT station_id, COUNT(*), SUM(status = 1) ... GROUP BY station_id}。
 *
 * <p>看起来可以用 {@link MemberStationVO} 当返回类型（只填这三个字段），
 * 省一个类。 不能这么做：那个方法的返回类型会变成
 * 「一个站点」，而它实际只装了三个字段 —— 后来的人读到
 * {@code List<MemberStationVO> counts} 会以为手里是完整的站点对象，
 * 于是去读 {@code getName()}，拿到的是 {@code null}。
 * 这类「类型说它是什么、实际不是」的偏差不会报错，只会静默地给出空值。
 *
 * <p> 同理也不能返回 {@code List<Map<String, Object>>}：那样每个取值点
 * 都要写一次 {@code (Integer) row.get("idleCount")}，一处拼错列名就是
 * 运行时 NPE，而编译器完全帮不上忙。
 *
 * 没有 {@code Station} 实体字段，只有 id
 *
 * <p>它回答的问题是「这批站点各自有多少把枪」，站点本身的字段由调用方
 * 手上那份 {@code List<Station>} 提供。带上站名只会多一次没人读的拷贝。
 *
 * <p> 字段名必须和 SQL 里的别名逐字对齐（{@code stationId} /
 * {@code connectorCount} / {@code idleConnectorCount}），
 * 靠 {@code map-underscore-to-camel-case} 映射。改一个就要改两处。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StationConnectorCountVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long stationId;

    /**
     * 该站下充电枪总数
     *
     * <p> 站下一把枪都没有时这一行根本不会出现（{@code GROUP BY}
     * 只给有匹配行的组），所以调用方取值时必须用一个「查不到就当 0」的
     * 默认值，不能直接 {@code map.get(id)} 然后拆箱 —— 那是 NPE。
     */
    private Integer connectorCount;

    /**
     * 其中「1 空闲」的枪数
     *
     * <p> 它的 SQL 是 {@code SUM(status = 1)} 而不是带 {@code <if>} 的
     * {@code COUNT}：{@code SUM} 在没有任何行满足时返回 NULL 而不是 0，
     * 而 {@code COUNT} 恒返回 0。所以 SQL 里必须写
     * {@code COALESCE(SUM(...), 0)}，否则「一个站下 3 把枪全是故障」会让
     * 这个字段变成 null，界面上显示成空白而不是 {@code 0}。
     */
    private Integer idleConnectorCount;
}
