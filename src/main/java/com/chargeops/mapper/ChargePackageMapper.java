package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.ChargePackagePageQueryDTO;
import com.chargeops.entity.ChargePackage;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充电套餐数据访问。类型专属列的校验在 {@code ChargePackageServiceImpl.assertTypeFields}，不在这一层。
 */
@Mapper
public interface ChargePackageMapper {

    ChargePackage getById(@Param("id") Long id);

    /** 全列插入，漏一个 NOT NULL 列就是 errno 1048（默认值在 {@code ChargePackageDTO} 的字段初始值上）。
     * {@code stationId} 不能漏：{@code uk_pkg_name_station} 里的 {@code station_id} 若是 NULL，
     * 同名套餐可以被无限次创建 —— MySQL 的唯一索引不约束 NULL。 */
    @AutoFill(OperationType.INSERT)
    void insert(ChargePackage chargePackage);

    /** 四个专属列走 {@code <if>}，传 null 只跳过不清空 —— 现在改不了 {@code packageType}，所以不影响正确性 */
    @AutoFill(OperationType.UPDATE)
    void update(ChargePackage chargePackage);

    /** 调用方必须紧挨着写 {@code PageHelper.startPage(...)}，见 {@link StationMapper#page} */
    List<ChargePackage> page(ChargePackagePageQueryDTO query);

    /** 车主端：{@code station_id IN (0, ?)}（全平台套餐也能买），{@code status = 1} 写死不做参数 */
    List<ChargePackage> listAvailableForMember(@Param("stationId") Long stationId);

    /** 分类删除守卫。{@code category_id} 是裸 bigint、没有外键；「先 count 再 delete」
     * 在并发下不是原子的，这是已知且接受的缺口。 */
    @Select("SELECT COUNT(*) FROM charge_package WHERE category_id = #{categoryId}")
    int countByCategoryId(@Param("categoryId") Long categoryId);

    /** 硬删。常规下架用 {@code status = 0}，但 {@code uk_pkg_name_station} 不看 status，建错的名字只能靠它释放 */
    @Delete("DELETE FROM charge_package WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
