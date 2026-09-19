package com.chargeops.service;

import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ChargerVO;

/**
 * 充电桩服务
 *
 * <p>形状沿用 {@link StationService}，两个方法有本域特有的约定。
 *
 * <h2>★★ 一、{@code page} 是【唯一】需要富化的列表接口</h2>
 *
 * <p>桩列表返回 {@code ChargerVO}，而 {@code ChargerVO} 比实体多一个
 * {@code stationName} —— 它必须由 Service 批量补齐。
 *
 * <p>这一条把实现类里「{@code PageHelper.startPage} → 查 → 富化」
 * 的顺序变成<b>承重结构</b>，而不是风格问题。
 * 顺序写错的表现是：第 1 页正常、第 2 页开始站名全空，
 * 而且站点数不超过 10 个时永远不出现。见实现类的 {@code page} 方法。
 *
 * <h2>★ 二、{@code gunCount} 在这条链路上只读不写</h2>
 *
 * <p>{@code create} 时 Service 固定写 <b>0</b>（不是 DDL 默认的 1），
 * {@code update} 完全不碰它。它的唯一写入路径是
 * {@link ConnectorService} 在枪增删时调 {@code refreshGunCount} 重算。
 *
 * <p>所以 {@link ChargerDTO} 和 {@link ChargerUpdateDTO} 里都没有这个字段 ——
 * <b>「不能改」是编译期事实，不是运行期的一句检查。</b>
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
     * <p>★ 不能改所属站点，也不能改枪数量。理由见 {@code ChargerUpdateDTO}
     * 类注释第一节 —— 两个字段的原因<b>不同</b>，不要记混。
     *
     * @throws com.chargeops.exception.NotFoundException 充电桩不存在
     */
    void update(Long id, ChargerUpdateDTO chargerUpdateDTO);

    /**
     * 删除充电桩（硬删）
     *
     * @throws com.chargeops.exception.NotFoundException    充电桩不存在
     * @throws com.chargeops.exception.DataConflictException 充电桩下还有充电枪
     */
    void delete(Long id);

    /**
     * 按 id 查详情（含站点名）
     *
     * @throws com.chargeops.exception.NotFoundException 充电桩不存在
     */
    ChargerVO getById(Long id);

    /**
     * 分页查询（含站点名）
     *
     * <p>★ 实现里 {@code startPage} 和富化查询的先后顺序是<b>承重</b>的。
     */
    PageResult page(ChargerPageQueryDTO query);
}
