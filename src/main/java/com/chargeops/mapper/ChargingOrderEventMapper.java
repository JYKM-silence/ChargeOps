package com.chargeops.mapper;

import com.chargeops.entity.ChargingOrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单事件数据访问：只追加。去重不靠 catch {@code DuplicateKeyException}（抛出时事务已被标记回滚），
 * 调用方先用订单状态的条件更新判断这一步做没做过。
 */
@Mapper
public interface ChargingOrderEventMapper {

    /** 不标 {@code @AutoFill}：本表没有 {@code create_user} / {@code update_user} 列，标了插件反射会抛异常 */
    int insert(ChargingOrderEvent event);

    /** 按时间升序（排查要读的是先发生什么），末尾补 {@code id ASC} 保证同一毫秒的两条顺序一致 */
    List<ChargingOrderEvent> listByOrderId(@Param("orderId") Long orderId);
}
