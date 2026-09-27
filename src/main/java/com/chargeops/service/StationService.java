package com.chargeops.service;

import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.StationVO;

/**
 * 充电站服务
 *
 * <p>分层约定见 {@link StaffService} 的类注释。五个方法的形状是后面四个域
 * （桩/枪/套餐/模板）的模板：{@code create} 返回新 id 而不是 {@code void}，
 * {@code update} 的 id 是独立的方法参数而不进 DTO —— 客户端无法通过改请求体
 * 来改「改的是哪一行」。
 *
 * <p> 每个写方法都要写清它抛什么：{@code GlobalExceptionHandler} 只有一个
 * {@code BaseException} 分支，业务异常的分类完全由抛出的类型决定，所以接口的
 * javadoc 是唯一能让调用方知道「哪些情况要处理」的地方。
 */
public interface StationService {

    /**
     * 新增充电站
     *
     * @return 新站点的 id
     * @throws com.chargeops.exception.ParamErrorException 结束时间不晚于开始时间
     * （格式问题由 DTO 的 {@code @Pattern} 挡在更前面）
     * @throws org.springframework.dao.DuplicateKeyException 站点编码已存在
     */
    Long create(StationDTO stationDTO);

    /** 修改充电站。只有非空字段会被更新，所以无法通过这个方法清空任何字段 */
    void update(Long id, StationUpdateDTO stationUpdateDTO);

    /**
     * 删除充电站（硬删）
     *
     * <p> 站点下还有充电桩时拒绝，守卫的理由和替代做法见实现类的 {@code assertDeletable}。
     *
     * @throws com.chargeops.exception.DataConflictException 站点下还有充电桩
     */
    void delete(Long id);

    /** 按 id 查详情。查不到抛 {@code NotFoundException} */
    StationVO getById(Long id);

    /**
     * 分页查询
     *
     * <p> 用的是 PageHelper。实现类里「{@code startPage} 必须紧挨着查询」那段值得读一遍 ——
     * 它是本项目里最容易犯且最难发现的错误。
     */
    PageResult page(StationPageQueryDTO query);
}
