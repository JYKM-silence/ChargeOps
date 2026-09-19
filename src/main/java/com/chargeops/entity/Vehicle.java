package com.chargeops.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆（对应 {@code vehicle} 表）
 *
 * <p>字段类型的两条约定见 {@link Staff} 的类注释。
 *
 * <h2>★★ 一、没有 {@code is_default} 唯一索引 —— 这不是疏漏，是<b>做不到</b></h2>
 *
 * <p>业务规则是「每个车主最多一辆默认车」。直觉是给
 * {@code (member_id, is_default)} 加个唯一索引。★ <b>那个索引是错的</b>：
 *
 * <pre>
 *   member_id=1, plate='粤BD12345', is_default=1   ← 默认车
 *   member_id=1, plate='粤BD54321', is_default=0   ← 第二辆，非默认
 *   member_id=1, plate='粤BD00001', is_default=0   ← ★ 撞唯一键，插不进去
 * </pre>
 *
 * <p>非默认行的 {@code is_default} <b>全都是 0</b>，所以
 * 「一个车主有两辆非默认车」会被那个索引直接禁止 ——
 * 而那是一件完全合法的事。
 *
 * <p>MySQL 8.0 <b>没有部分索引</b>（写不出 {@code WHERE is_default = 1}）。
 * 业界绕法是让非默认行存 <b>NULL</b>（唯一索引不约束 NULL），
 * 但 DDL 写的是 {@code is_default tinyint NOT NULL DEFAULT 0}，
 * 这条路也已经封了。
 *
 * <p>★ <b>所以这个不变量只能靠应用层守</b>，落地在
 * {@code VehicleMapper.clearDefaultExcept} + {@code markDefault}
 * 两条条件更新上，完整的论证与并发分析写在 {@code VehicleMapper} 的类注释里。
 *
 * <p>★★ <b>把这段写在这里的目的只有一个：让将来那个「顺手补上缺失的索引」
 * 的人先看到它。</b>补上之后现象是「一个车主只能有一辆车」——
 * 一个看起来像别的 bug、查起来毫无线索的现象。
 *
 * <h2>★★ 二、{@code plate_no} 的「入库转大写」<b>不是为了唯一性</b></h2>
 *
 * <p>库和这张表的排序规则都是 {@code utf8mb4_0900_ai_ci}，
 * <b>大小写不敏感</b> —— 所以 {@code uk_vehicle_member_plate} 本来就把
 * {@code 粤BD12345} 和 {@code 粤bd12345} 当成同一个值（已真机验证：
 * {@code SELECT '粤BD12345' = '粤bd12345'} 返回 1）。
 *
 * <p>转大写的理由有三条，<b>一条都不是唯一性</b>：
 * <ol>
 *   <li><b>唯一索引的保护依赖列排序规则，业务层的规则不依赖。</b>
 *       把列改成 {@code utf8mb4_bin}「让车牌精确匹配」是一个
 *       <b>看起来很有道理</b>的改动，改了之后同一个车牌加两次会变成两行 ——
 *       而在写入侧统一转大写能让这条不变量在<b>两种排序规则下都成立</b></li>
 *   <li>{@code utf8mb4_0900_ai_ci} 是 <b>NO PAD</b> 的，
 *       所以 {@code '粤BD12345 '}（尾随一个空格）和 {@code '粤BD12345'}
 *       是<b>两个不同的值</b>，唯一索引<b>拦不住它</b>。
 *       手机键盘很容易带出尾随空格，所以 {@code trim} 是正确性要求，
 *       而且这一条<b>没有任何排序规则改动能救</b></li>
 *   <li>{@code plate_no} 会被快照进 {@code charging_order.plate_no}，
 *       而订单是 append-only 的 —— 存进去什么写法就永远是那个写法。
 *       在唯一的写入路径上规范化，等于让所有未来的订单快照自动统一</li>
 * </ol>
 *
 * <p>★ 规范化<b>只在写入侧做</b>，读侧照样 `WHERE plate_no = ?` 靠排序规则匹配。
 * 这个不对称是刻意的：读侧加 {@code COLLATE utf8mb4_bin} 或 {@code BINARY}
 * 去「追求精确」会<b>静默地查不到行</b>。
 *
 * <h2>★ 三、没有 {@code status} 列 → 硬删是唯一手段，而这是 DDL 同意的</h2>
 *
 * <p>{@code charging_order.plate_no} 的列注释：
 * 「车牌快照：<b>车辆可被删除/改牌</b>，订单必须保留当时值」。
 * 一眼可见 DDL 是<b>假定车辆可删</b>来设计的：
 * 它专门留了一个快照列，还在 {@code charging_order.vehicle_id} 上
 * 允许了 NULL（悬挂引用是被表达得出来的）。
 *
 * <p>★ 所以 {@code VehicleService.delete} <b>没有守卫</b>，而且刻意不加。
 * 要写进 P1c 的两句话在这里：
 * <ul>
 *   <li>「车被删了」的检查属于<b>下单路径</b>（按 id + member_id 取车，取不到就 404），
 *       不属于删除路径</li>
 *   <li>订单查询<b>不许 JOIN vehicle 取车牌</b>，必须读 {@code charging_order.plate_no}
 *       快照列 —— 否则删车之后历史订单的车牌就消失了</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * 归属车主 id
     *
     * <p>★ <b>它绝不允许来自客户端请求体。</b>
     * {@code VehicleDTO} 里没有这个字段，{@code VehicleUpdateDTO} 里也没有 ——
     * 「这辆车是谁的」是身份的一部分，只能从 {@code BaseContext} 读。
     * 让它出现在请求体里，就等于把「改别人的车」变成一个可表达的操作。
     *
     * <p>★ 相应地，{@code VehicleMapper} 的<b>每一条 SQL 都带 {@code member_id}</b>：
     * 读是 {@code WHERE id = ? AND member_id = ?}，
     * 写也是 {@code WHERE id = ? AND member_id = ?}。
     * 作用域是<b>文件名级的性质</b>，不是某个调用方的自觉。
     */
    private Long memberId;

    /**
     * 车牌号，唯一索引 {@code uk_vehicle_member_plate (member_id, plate_no)}
     *
     * <p>★ <b>入库统一转大写 + 去首尾空格</b>（{@code VehicleServiceImpl.normalizePlateNo}），
     * 理由见类注释第二节 —— 提醒一句，转大写<b>不是</b>为了唯一性。
     *
     * <p>★ 不做格式正则校验。真正的约束是唯一性，那由数据库守；
     * 车牌格式有新能源 / 使馆 / 港澳 / 教练 / 警车等多种，写死正则会挡住合法输入。
     * 这个取舍和 {@code StationDTO.code} 一致。
     */
    private String plateNo;

    /** {@code 1蓝 2黄 3绿 4渐变绿}，可空 —— 见 {@link com.chargeops.enumeration.PlateColor} */
    private Integer plateColor;

    private String brand;

    private String model;

    /**
     * 电池容量，单位 kWh
     *
     * <p>用途是<b>估算</b>充满需要多少电（下单页那个「预计需要 60 度」），
     * 不参与计费 —— 实际计费按充电枪上报的度数走。
     * 所以它是可空的，缺了只是估算不出来，不影响任何金额。
     */
    private BigDecimal batteryCapacityKwh;

    /**
     * {@code 1默认 0非默认}，{@code NOT NULL}
     *
     * <p>★★ <b>不要给它加 {@code (member_id, is_default)} 唯一索引</b>，
     * 原因见类注释第一节 —— 那个索引会禁止「一个车主有两辆非默认车」。
     *
     * <p>★ 因为 {@code NOT NULL} + 全列插入，传 null 会直接 errno 1048。
     * 默认值 {@code 0} 由 {@code VehicleDTO.DEFAULT_IS_DEFAULT} 提供，
     * Service 再兜一次（字段初始值挡不住显式的 JSON {@code null}）。
     *
     * <p>★ <b>读侧不要写 {@code WHERE is_default = 1} 然后期望一行。</b>
     * 「我的车辆」列表按 {@code ORDER BY is_default DESC, id DESC} 排序，
     * 需要默认车时取第一条 —— 这样即使数据因为手工 SQL 出了两辆默认车，
     * 页面也只是多显示一个标记，而不是抛 {@code TooManyResultsException}。
     */
    private Integer isDefault;

    private String remark;

    // ==================== 审计四列 ====================
    // 由 AutoFillInterceptor 反射填充，四列齐全所以可以标 @AutoFill

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;
}
