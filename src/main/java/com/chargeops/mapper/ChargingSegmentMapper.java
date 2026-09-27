package com.chargeops.mapper;

import com.chargeops.entity.ChargingSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 计费分段数据访问：只有写入和查询。分段是账单的原始凭据，能被修改的凭据不是凭据；
 * 结算幂等靠 {@code uk_segment_order_start}，不靠先查一遍。
 */
@Mapper
public interface ChargingSegmentMapper {

    /** 一条多值 INSERT，不是循环单插。列表不能为空：{@code <foreach>} 展开后是 SQL 语法错误 */
    void batchInsert(@Param("segments") List<ChargingSegment> segments);

    /** 按 {@code seq} 而不是 {@code start_time} 排：展示顺序由引擎给的序号决定 */
    List<ChargingSegment> listByOrderId(@Param("orderId") Long orderId);
}
