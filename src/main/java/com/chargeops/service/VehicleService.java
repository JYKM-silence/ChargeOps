package com.chargeops.service;

import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.vo.VehicleVO;

import java.util.List;

/**
 * 车辆服务（纯车主端 —— 这个域没有管理端接口）
 *
 * <p> 六个方法没有一个接收 {@code memberId}，身份全部来自 {@code BaseContext}
 * （拦截器解 token 后写入）。
 *
 * <p> 所有按 id 操作的方法（{@link #getMine} / {@link #update} / {@link #delete} /
 * {@link #setDefault}）在目标车辆不存在或不属于当前车主时，抛的都是
 * {@code NotFoundException(VEHICLE_NOT_FOUND)}，不抛「无权访问」：那句话会免费
 * 告诉攻击者「这个 id 是存在的，只是不属于你」，拿一个 id 从 1 数到 10000
 * 就能把系统里所有有效的车辆 id 都标出来。
 *
 * <p> 不变量：有车的车主一定有且只有一辆默认车。它落在 {@link #create}、
 * {@link #setDefault}、{@link #delete} 三处，{@link #update} 不参与 ——
 * {@code VehicleUpdateDTO} 里根本没有 {@code isDefault} 字段。它不是数据库约束，
 * 所以只在这三个方法里成立。
 */
public interface VehicleService {

    /**
     * 查当前车主的全部车辆（不分页：一个车主的车辆数在个位）
     *
     * <p> 返回顺序是 {@code is_default DESC, id DESC}，默认车在最前。需要「默认车」
     * 时取第一条，不要另写「找 isDefault == 1 的那条」：数据万一坏了（手工 SQL
     * 造出两辆默认车），取第一条只是多显示一个标记，而「找那条」要么结果不确定、
     * 要么在没有默认车时拿到 null。
     */
    List<VehicleVO> listMine();

    /** 查当前车主的某一辆车 */
    VehicleVO getMine(Long id);

    /**
     * 新增车辆
     *
     * <p> 车牌会被规范化（trim + 转大写），见 {@code VehicleServiceImpl.normalizePlateNo}。
     *
     * <p> 这是车主的第一辆车时 {@code isDefault} 被强制置 1，不管请求里传了什么：
     * 只有一个选择时「设为默认」这个动作没有意义。
     *
     * @return 新车辆的 id
     * @throws org.springframework.dao.DuplicateKeyException 该车主名下已有同一车牌
     */
    Long create(VehicleDTO vehicleDTO);

    /**
     * 修改车辆（车牌 / 颜色 / 品牌 / 型号 / 电池容量 / 备注）
     *
     * <p> 改不了 {@code isDefault}（那只走 {@link #setDefault}），也改不了归属。
     *
     * @throws org.springframework.dao.DuplicateKeyException 改成的新车牌在该车主名下已存在
     */
    void update(Long id, VehicleUpdateDTO vehicleUpdateDTO);

    /**
     * 删除车辆（硬删，没有守卫）
     *
     * <p> 「有订单就不能删」这种守卫不该加：{@code charging_order.plate_no} 是
     * 车牌快照，那一列存在的理由就是允许车辆被删；{@code vehicle} 也没有状态列，
     * 「卖车之后把它从列表里去掉」只有硬删这一条路。
     *
     * <p> 删掉的如果是默认车，剩下的第一辆会被提升为默认（按
     * {@code is_default DESC, id DESC} 取）：不这么做「有车的车主一定有且只有一辆
     * 默认车」这条不变量就断了，而那正是 {@code VehicleVO} 让前端「取列表第一条」
     * 所依赖的前提。
     */
    void delete(Long id);

    /**
     * 把某一辆设为默认车
     *
     * <p> 这是唯一能改 {@code isDefault} 的入口，而且幂等：已经是默认车时再调一次也成功。
     *
     * <p> 并发下两个「设为默认」不会都成功 —— {@code WHERE member_id = ?} 会走
     * {@code uk_vehicle_member_plate} 的最左前缀，把该车主的车辆行全部锁住，
     * 两个事务在第一条语句上就串行化了。
     */
    void setDefault(Long id);
}
