package com.chargeops.service;

import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ChargerVO;

/**
 * 充电桩服务
 *
 * <p> {@code ChargerVO} 比实体多一个 {@code stationName}，必须由 Service 批量补齐，
 * 所以实现类里「{@code PageHelper.startPage} → 查 → 富化」的顺序是承重结构，
 * 不是风格问题 —— 顺序写错的表现是第 1 页正常、第 2 页开始站名全空，
 * 而且站点数不超过 10 个时永远不出现。
 *
 * <p> {@code gunCount} 唯一写入路径是 {@link ConnectorService} 在枪增删时调
 * {@code refreshGunCount} 重算：{@code create} 固定写 0，{@code update} 完全不碰它。
 * 两个 DTO 里都没有这个字段，所以「不能改」是编译期事实。
 */
public interface ChargerService {

    /**
     * 新增充电桩
     *
     * @return 新充电桩的 id
     * @throws com.chargeops.exception.NotFoundException     所属站点不存在
     * @throws org.springframework.dao.DuplicateKeyException 设备编号已存在
     */
    Long create(ChargerDTO chargerDTO);

    /**
     * 修改充电桩
     *
     * <p> 不能改所属站点，也不能改枪数量。理由见 {@code ChargerUpdateDTO}
     * 类注释第一节 —— 两个字段的原因不同，不要记混。
     */
    void update(Long id, ChargerUpdateDTO chargerUpdateDTO);

    /**
     * 删除充电桩（硬删）
     *
     * @throws com.chargeops.exception.DataConflictException 充电桩下还有充电枪
     */
    void delete(Long id);

    /** 按 id 查详情（含站点名）。查不到抛 {@code NotFoundException} */
    ChargerVO getById(Long id);

    /** 分页查询（含站点名）。实现里 {@code startPage} 必须先于富化查询 */
    PageResult page(ChargerPageQueryDTO query);
}
