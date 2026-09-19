package com.chargeops.service;

import com.chargeops.dto.VehicleDTO;
import com.chargeops.dto.VehicleUpdateDTO;
import com.chargeops.vo.VehicleVO;

import java.util.List;

/**
 * 车辆服务（<b>纯车主端</b> —— 这个域没有管理端接口）
 *
 * <p>★ 六个方法<b>没有一个接收 {@code memberId}</b>。身份全部来自
 * {@code BaseContext}（拦截器解 token 后写入）。这不是「懒得传」，
 * 而是本域最重要的一条设计 —— 完整论证见 {@link MemberService}
 * 的类注释第一节，以及 {@code VehicleMapper} 的类注释第一节。
 *
 * <h2>★★ 一、「不存在」和「不是你的」返回<b>同一句话</b></h2>
 *
 * <p>所有按 id 操作的方法（{@link #getMine} / {@link #update} /
 * {@link #delete} / {@link #setDefault}）在目标车辆不存在<b>或</b>
 * 不属于当前车主时，抛的都是
 * {@code NotFoundException(VEHICLE_NOT_FOUND)}。
 *
 * <p>★ <b>不抛「无权访问」。</b>那句话会免费告诉攻击者
 * 「这个 id 是存在的，只是不属于你」—— 于是他可以拿一个 id
 * 从 1 数到 10000，把系统里所有有效的车辆 id 都标出来。
 * 而「不存在」和「不是你的」对<b>合法用户</b>来说是同一个事实：
 * 他要找的那辆车在他的列表里没有，仅此而已。
 *
 * <h2>★★ 二、{@link #listMine} <b>不分页</b>，而且是刻意的</h2>
 *
 * <p>一个车主的车辆数在个位。给车主端套一个 {@code /page} 接口
 * 只会让前端必须处理「我的车还得翻页」这个不存在的问题 ——
 * 和 {@code CategoryService.listEnabled} 不套分页是同一个理由。
 *
 * <p>★ 而且它走 {@code uk_vehicle_member_plate} 的最左前缀
 * （{@code member_id}），所以这个查询是<b>走索引</b>的 ——
 * DDL 里那句「一次解决两件事」说的就是这个。哪怕某个车主
 * 真的登记了一百辆车，全量返回也远好过一次分页往返。
 *
 * <h2>★★ 三、默认车的不变量，以及它和三处写入的关系</h2>
 *
 * <p>规则：<b>有车的车主一定有且只有一辆默认车。</b>
 * 它落在三个地方（{@link #create} / {@link #delete} / {@link #setDefault}），
 * 而 {@link #update} <b>不参与</b> —— 因为 {@code VehicleUpdateDTO} 里
 * 根本没有 {@code isDefault} 字段。见那个 DTO 的类注释第一节：
 * <b>两条写入路径通向同一个不变量，迟早有一条忘了清旧的。</b>
 *
 * <p>★ 这条不变量<b>不是数据库约束</b>（为什么唯一索引表达不了它，
 * 见 {@code Vehicle} 的类注释第一节），所以它只在这三个方法里成立：
 * <ul>
 *   <li>{@link #create} —— 第一辆车强制置 1；显式要求默认时先插入再清旧</li>
 *   <li>{@link #setDefault} —— 先清旧再置新</li>
 *   <li>{@link #delete} —— 删掉的如果是默认车，把剩下的第一辆提升为默认</li>
 * </ul>
 */
public interface VehicleService {

    /**
     * 查当前车主的全部车辆（<b>不分页</b>）
     *
     * <p>★ 返回顺序是 {@code is_default DESC, id DESC} ——
     * 默认车在最前。**需要「默认车」时取第一条**，
     * 不要另写「找 isDefault == 1 的那条」：
     * 数据万一坏了（手工 SQL 造出两辆默认车），
     * 取第一条只是多显示一个标记，而「找那条」要么结果不确定、
     * 要么在没有默认车时拿到 null。
     */
    List<VehicleVO> listMine();

    /**
     * 查当前车主的某一辆车
     *
     * @throws com.chargeops.exception.NotFoundException 车辆不存在<b>或不属于当前车主</b>
     */
    VehicleVO getMine(Long id);

    /**
     * 新增车辆
     *
     * <p>★ <b>车牌会被规范化</b>（trim + 转大写）—— 见
     * {@code VehicleServiceImpl.normalizePlateNo}。
     *
     * <p>★ <b>这是车主的第一辆车时，{@code isDefault} 被强制置 1</b>，
     * 不管请求里传了什么。理由：只有一个选择时「设为默认」这个动作没有意义，
     * 而如果那辆车不是默认车，前端还得引导用户再点一次。
     *
     * @return 新车辆的 id
     * @throws org.springframework.dao.DuplicateKeyException 该车主名下已有同一车牌
     */
    Long create(VehicleDTO vehicleDTO);

    /**
     * 修改车辆（车牌 / 颜色 / 品牌 / 型号 / 电池容量 / 备注）
     *
     * <p>★ <b>改不了 {@code isDefault}</b>（那只走 {@link #setDefault}），
     * 也改不了归属 —— 见 {@code VehicleUpdateDTO} 的类注释第一节。
     *
     * @throws com.chargeops.exception.NotFoundException 车辆不存在<b>或不属于当前车主</b>
     * @throws org.springframework.dao.DuplicateKeyException 改成的新车牌在该车主名下已存在
     */
    void update(Long id, VehicleUpdateDTO vehicleUpdateDTO);

    /**
     * 删除车辆（硬删，<b>没有守卫</b>）
     *
     * <p>★ 加不了「有订单就不能删」这种守卫，而且<b>不该加</b> ——
     * {@code charging_order.plate_no} 的列注释写着
     * 「车牌快照：车辆可被删除/改牌，订单必须保留当时值」。
     * <b>快照列存在的理由就是允许车辆被删。</b>
     * 完整论证见 {@code VehicleMapper} 的类注释第四节。
     *
     * <p>★ {@code vehicle} 没有状态列，所以「卖车之后把它从列表里去掉」
     * 只有硬删这一条路。
     *
     * <p>★ <b>删掉的如果是默认车，剩下的第一辆会被提升为默认</b>
     * （按 {@code is_default DESC, id DESC} 取）。不这么做的话，
     * 「有车的车主一定有且只有一辆默认车」这条不变量就断了 ——
     * 而那正是 {@code VehicleVO} 让前端「取列表第一条」所依赖的前提。
     *
     * @throws com.chargeops.exception.NotFoundException 车辆不存在<b>或不属于当前车主</b>
     */
    void delete(Long id);

    /**
     * 把某一辆设为默认车
     *
     * <p>★ <b>这是唯一能改 {@code isDefault} 的入口。</b>
     *
     * <p>★ 幂等：已经是默认车时再调一次也成功。
     *
     * <p>★ 并发下两个「设为默认」不会都成功 ——
     * {@code WHERE member_id = ?} 会走 {@code uk_vehicle_member_plate}
     * 的最左前缀，把该车主的车辆行全部锁住，两个事务在第一条语句上
     * 就串行化了。完整论证见 {@code VehicleMapper} 的类注释第二节。
     *
     * @throws com.chargeops.exception.NotFoundException 车辆不存在<b>或不属于当前车主</b>
     */
    void setDefault(Long id);
}
