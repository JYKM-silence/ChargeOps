package com.chargeops.mapper;

import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.entity.Connector;
import com.chargeops.vo.StationConnectorCountVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电枪数据访问。写方法绝不能标 {@code @AutoFill}：本表没有 {@code create_user} / {@code update_user} 列，
 * 插件反射找不到 setter 会直接抛 {@code IllegalStateException}。更新走乐观锁，{@code version} 由客户端回传；
 * {@code current_order_id} 只归下面四个 CAS 方法，不在管理端的 {@code insert} / {@code update} 里。
 */
@Mapper
public interface ConnectorMapper {

    Connector getById(@Param("id") Long id);

    /** 桩的删除守卫；也是 {@code charger.gun_count} 的事实来源 —— 两处用同一个 COUNT，不会互相矛盾 */
    @Select("SELECT COUNT(*) FROM connector WHERE charger_id = #{chargerId}")
    int countByChargerId(@Param("chargerId") Long chargerId);

    /** 必须在新增/删除枪的同一事务里调。重新 COUNT 而不是 {@code gun_count + 1}：增量更新并发下会丢更新 */
    @Update("UPDATE charger SET gun_count = "
            + "(SELECT COUNT(*) FROM connector WHERE charger_id = #{chargerId}) "
            + "WHERE id = #{chargerId}")
    int refreshGunCount(@Param("chargerId") Long chargerId);

    void insert(Connector connector);

    /** 返回 0 = 版本冲突或行不存在，调用方必须检查，并靠 {@code getById} 区分这两种 */
    int update(Connector connector);

    /**
     * 建单时抢占这把枪：裁决权在影响行数上，{@code listHoldingByConnector} 只负责给出好看的文案。
     * 同一笔订单去占第二把空闲枪时唯一索引会抛 {@code DuplicateKeyException} 而不是返回 0。
     */
    int occupy(@Param("connectorId") Long connectorId,
               @Param("orderId") Long orderId,
               @Param("updateTime") LocalDateTime updateTime);

    /** 谓词里没有来源状态，所以重报一次仍影响 1 行（不幂等），也会覆盖管理员设的「4 故障」 */
    int markCharging(@Param("connectorId") Long connectorId,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 车已拔枪但订单还要结算，所以回到「2 已占用」而不是放空闲。谓词同 {@link #markCharging} */
    int markOccupied(@Param("connectorId") Long connectorId,
                     @Param("orderId") Long orderId,
                     @Param("updateTime") LocalDateTime updateTime);

    /** 谓词只有 {@code current_order_id = orderId}，所以幂等、自守卫。不要和「解冻钱包」合成一个方法：结算只释放枪，绝不解冻 */
    int release(@Param("orderId") Long orderId,
                @Param("updateTime") LocalDateTime updateTime);

    /** 不推 {@code version}（{@code total_charge_count} 不在管理端 update 里，推了只会产生假冲突）。它数的是结算次数，挂账订单不计入 */
    int incrementChargeCount(@Param("connectorId") Long connectorId);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<Connector> page(ConnectorPageQueryDTO query);

    /** 车主端选枪，不过滤 {@code status} —— 把维护/故障的枪筛掉会让一个正在维护的站点显示成空列表 */
    List<Connector> listByStation(@Param("stationId") Long stationId);

    /**
     * 某台桩下的全部枪。
     *
     * <p> 和 {@link #listByStation} 不合并：那个按站点查是从车主端选枪的入口来的，
     * 一页可能要覆盖多个站点；这个只给 AI 助手查「某号桩现在怎么样」用，参数就是桩 id。
     */
    List<Connector> listByChargerId(@Param("chargerId") Long chargerId);

    /**
     * 一批站点的枪数（{@code GROUP BY} 聚合）。{@code SUM(status = 1)} 没有匹配行时是 NULL 不是 0，
     * 所以 {@code COALESCE} 不是装饰；站下没有枪时整行都不出现，调用方不能直接拆箱。{@code ids} 不能是空集合。
     */
    List<StationConnectorCountVO> countByStationIds(@Param("ids") List<Long> ids);

    /** 裸删除。调用方必须先守两道：charger 还在、且这枪没被占用（{@code current_order_id IS NULL} 且状态不在 2/3） */
    @Delete("DELETE FROM connector WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
