package com.chargeops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充电桩信息
 *
 * <h2>★ {@code stationName} 是 VO 存在的最直接理由</h2>
 *
 * <p>{@code GET /api/admin/charger/page?stationId=…} 返回的是<b>跨站点</b>的桩列表，
 * 而桩表里只有 {@code station_id}。一行数据显示成
 * 「{@code DEV-2026-0001} / A区-01」而不带站名，管理员根本不知道它在哪 ——
 * 设备编号是全平台唯一的，而「A区-01」每个站都有。
 *
 * <p>所以站名必须补上。补的方式是 Service 查完这一页之后，收集去重后的
 * {@code stationId}，调一次 {@code StationMapper.listByIds} 批量取名：
 *
 * <pre>
 *   PageHelper.startPage(q.getPageNum(), q.getPageSize());
 *   List&lt;Charger&gt; rows = chargerMapper.page(q);   // ★ 必须紧挨着 startPage
 *   long total = ((Page&lt;Charger&gt;) rows).getTotal();
 *   List&lt;ChargerVO&gt; vos = toVos(rows);            // ★ 富化在这一步之后
 * </pre>
 *
 * <p><b>★★ 顺序不能变。</b>{@code PageHelper.startPage} 把分页参数放进一个
 * <b>ThreadLocal</b>，由<b>该线程上的下一条 MyBatis 查询</b>消费掉。补站名的
 * {@code listByIds} 要是插在中间，<b>它会拿到 {@code LIMIT 10}</b> —— 于是第 1 页
 * 看起来正常（正好 10 个站点），第 2 页开始站名全空，而列表里桩的数量依然是对的。
 * 这个 bug 的三个特征是<b>静默</b>（不报错、只是字段为 null）、<b>依赖数据量</b>
 * （站点数 ≤ 10 时永远不出现）、<b>一旦出现就看起来像前端的问题</b>。
 *
 * <p>★ 所以枪的列表<b>不需要</b>补枪的名字 —— 枪的查询永远带 {@code chargerId}
 * 或 {@code stationId}，上下文里已经有它属于谁了。这是原则，不是随意取舍：
 * <b>要在列表里补充的那一列，必须是这一页数据自己无法回答的问题。</b>
 */
@Data
@Schema(description = "充电桩信息")
public class ChargerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "充电桩 id")
    private Long id;

    @Schema(description = "所属站点 id")
    private Long stationId;

    @Schema(description = "所属站点名称，由 Service 批量补齐")
    private String stationName;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "充电桩名称")
    private String name;

    @Schema(description = "类型：1直流快充 2交流慢充")
    private Integer type;

    @Schema(description = "类型文案，如「直流快充」")
    private String typeLabel;

    @Schema(description = "型号")
    private String model;

    @Schema(description = "额定功率，kW")
    private BigDecimal ratedPowerKw;

    /**
     * 枪数量
     *
     * <p>★ 这一列是 DDL 明写的<b>冗余字段</b>，事实来源是 {@code connector} 表，
     * 由 {@code ConnectorService} 在枪增删时调 {@code refreshGunCount} 重算；
     * 它的<b>写入</b>路径只有一条，见 {@code ChargerUpdateDTO} 类注释。
     */
    @Schema(description = "充电枪数量（冗余自 connector 表，由枪的增删维护）")
    private Integer gunCount;

    @Schema(description = "状态：0离线 1在线空闲 2充电中 3故障 4维护中")
    private Integer status;

    @Schema(description = "状态文案，如「在线空闲」")
    private String statusLabel;

    @Schema(description = "最后心跳时间；null 表示从未上线")
    private LocalDateTime lastHeartbeatTime;

    @Schema(description = "固件版本")
    private String firmwareVersion;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
