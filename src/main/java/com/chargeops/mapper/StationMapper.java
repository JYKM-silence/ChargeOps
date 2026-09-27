package com.chargeops.mapper;

import com.chargeops.annotation.AutoFill;
import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 充电站数据访问。注解还是 XML：固定单条 SQL 写注解，带动态标签或复用列清单的写 XML。
 * 不写「先查再插」的预检：重复 {@code code} 交给 {@code uk_station_code}，由 {@code GlobalExceptionHandler} 翻译。
 */
@Mapper
public interface StationMapper {

    Station getById(@Param("id") Long id);

    /** 消除 N+1。{@code ids} 不能是空集合：{@code <foreach>} 展开成 {@code IN ()} 是语法错误，调用方必须先判空 */
    List<Station> listByIds(@Param("ids") List<Long> ids);

    /**
     * {@code openTime} / {@code closeTime} 必须非空：DDL 的 {@code DEFAULT} 只在列不出现在 INSERT 里时生效，
     * 这条是全列插入，传 null 就是往 NOT NULL 列写 NULL（errno 1048，第一次跑测试时踩到过）。
     */
    @AutoFill(OperationType.INSERT)
    void insert(Station station);

    /** 局部更新：{@code <set>} + {@code <if>} 下传 null 的字段直接不出现在 SQL 里，所以「清空某个字段」做不到 */
    @AutoFill(OperationType.UPDATE)
    void update(Station station);

    /** 必须紧挨着写 {@code PageHelper.startPage(...)}：分页参数在 ThreadLocal 里，会被该线程上的下一条查询消费掉 */
    List<Station> page(StationPageQueryDTO query);

    /** 车主端只返回「1 启用」：不复用 {@link #page} + setStatus，规则写死在 WHERE 里才是结构性的 */
    List<Station> pageForMember(MemberStationPageQueryDTO query);

    /** 裸删除，守卫在 {@code StationServiceImpl.assertDeletable}（它要读 charger 表，不属于「一条 SQL」） */
    @Delete("DELETE FROM station WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
