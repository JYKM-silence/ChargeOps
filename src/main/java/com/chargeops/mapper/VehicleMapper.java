package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.entity.Vehicle;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 车辆数据访问，每条 SQL 都带 {@code member_id}（读、写、删都是）——
 * 作用域做进 SQL 之后，写不出水平越权的代码。
 *
 * <p>「每个车主最多一辆默认车」无法用唯一索引表达，落在 {@link #clearDefaultExcept} /
 * {@link #markDefault} 两条条件更新上。
 *
 * <p>没有「有订单就不能删」的守卫，也不要加：{@code charging_order.plate_no} 存的是车牌快照。
 */
@Mapper
public interface VehicleMapper {

    /** 返回 null 表示「不存在 或 不是你的」，两种情况 Service 返回同一句话 —— 分开提示等于免费探测 id 是否有效 */
    Vehicle getByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /** 不分页（一个车主的车辆数个位）。排序 {@code is_default DESC, id DESC} 是全序，刷新不会跳 */
    List<Vehicle> listByMemberId(@Param("memberId") Long memberId);

    /** 用途只有一个：判断「这是不是他的第一辆车」，是的话强制 {@code is_default = 1} */
    @Select("SELECT COUNT(*) FROM vehicle WHERE member_id = #{memberId}")
    int countByMemberId(@Param("memberId") Long memberId);

    /** 全列插入：{@code is_default} 是 NOT NULL，传 null 报 errno 1048，默认值在 {@code VehicleDTO} 上 */
    @AutoFill(OperationType.INSERT)
    void insert(Vehicle vehicle);

    /** {@code is_default} 不在这条 update 的范围里 —— 改默认车只有一个入口，两条写入路径迟早有一条忘了清旧的 */
    @AutoFill(OperationType.UPDATE)
    void update(Vehicle vehicle);

    /** 裸删除，不做守卫（刻意如此，见类注释）。返回 0 = 不存在或不是你的 */
    @Delete("DELETE FROM vehicle WHERE id = #{id} AND member_id = #{memberId}")
    int deleteByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    /** {@code id <> #{id}} 是正确性要求：新建第二辆车时新行已可见且 is_default 已置 1，去掉它会把刚设的标记立刻清掉。返回 0 行是正常情况 */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE vehicle SET is_default = 0, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE member_id = #{memberId} AND is_default = 1 AND id <> #{id}")
    int clearDefaultExcept(Vehicle defaultVehicle);

    /** {@code member_id} 在 WHERE 里，归属校验就是写入条件本身；已经是默认车时重复调用也成功 */
    @AutoFill(OperationType.UPDATE)
    @Update("UPDATE vehicle SET is_default = 1, update_time = #{updateTime}, update_user = #{updateUser} "
            + "WHERE id = #{id} AND member_id = #{memberId}")
    int markDefault(Vehicle vehicle);
}
