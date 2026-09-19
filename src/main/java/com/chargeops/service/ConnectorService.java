package com.chargeops.service;

import com.chargeops.dto.ConnectorDTO;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.dto.ConnectorUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ConnectorVO;

/**
 * 充电枪服务
 *
 * <p>形状沿用 {@link StationService}，但有两个本域独有的东西，
 * 它们都是 P1a 里最值得读的部分。
 *
 * <h2>★★ 一、枪的写操作<b>没有</b> {@code @AutoFill}，时间由本层显式写</h2>
 *
 * <p>{@code connector} 表<b>只有 create_time / update_time，没有
 * create_user / update_user</b>。而 {@code AutoFillInterceptor} 是<b>反射</b>
 * 找 {@code setCreateUser(Long)} 的，找不到<b>直接抛 {@code IllegalStateException}</b>——
 * 标错了不是「填不上就不填」，是所有写操作当场炸掉。
 *
 * <p>所以实现类里会看到两行很突兀的 {@code setCreateTime/setUpdateTime}。
 * 它们不是冗余，是<b>唯一</b>的时间来源 —— 数据库那两列的 DDL 默认值
 * 在全列插入下同样不生效。
 *
 * <h2>★★ 二、{@code version} 是客户端回传的，服务端不自己读</h2>
 *
 * <p>{@code UPDATE connector SET ... WHERE id = ? AND version = ?}，
 * 影响行数为 0 时再查一次，区分「这行没了」和「别人先改了」。
 *
 * <p><b>服务端自己读一遍 version 再写的话，检查永远通过</b> ——
 * 读到的值紧接着被写进 WHERE，中间没有并发窗口，
 * 而真正的窗口在「客户端读到数据」和「客户端提交修改」之间。
 * 那是装饰性的乐观锁：代码看起来一模一样，只是从来不生效。
 */
public interface ConnectorService {

    /**
     * 新增充电枪
     *
     * <p>★ 与 {@code charger.gun_count} 的重算在<b>同一个事务</b>里完成。
     *
     * @return 新充电枪的 id
     * @throws com.chargeops.exception.NotFoundException     所属充电桩不存在
     * @throws org.springframework.dao.DuplicateKeyException 同一充电桩下枪口号已存在
     */
    Long create(ConnectorDTO connectorDTO);

    /**
     * 修改充电枪
     *
     * <p>★ 必须回传 {@code version}，冲突时抛 {@code DataConflictException}。
     * ★ 不能改所属桩，也不能改 {@code currentOrderId} / {@code totalChargeCount}。
     *
     * @throws com.chargeops.exception.NotFoundException     充电枪不存在
     * @throws com.chargeops.exception.DataConflictException 版本冲突，或状态不在管理员可设的白名单内
     */
    void update(Long id, ConnectorUpdateDTO connectorUpdateDTO);

    /**
     * 删除充电枪（硬删）
     *
     * <p>★ 被订单占用或正在充电时拒绝，并在同一事务里重算 {@code gun_count}。
     *
     * @throws com.chargeops.exception.NotFoundException     充电枪不存在
     * @throws com.chargeops.exception.DataConflictException 充电枪被在途订单占用
     */
    void delete(Long id);

    /**
     * 按 id 查详情
     *
     * <p>★ 返回值里的 {@code version} 是客户端更新时<b>必须回传</b>的值 ——
     * 这两个字段是成对的，改一个必须同时改另一个。
     *
     * @throws com.chargeops.exception.NotFoundException 充电枪不存在
     */
    ConnectorVO getById(Long id);

    /**
     * 分页查询
     *
     * <p>★ 和充电桩不同，这里<b>不需要</b>富化任何字段 ——
     * 枪的查询永远带 {@code chargerId} 或 {@code stationId}，
     * 调用方已经知道自己在看哪个站。这是原则，不是随意取舍。
     */
    PageResult page(ConnectorPageQueryDTO query);
}
