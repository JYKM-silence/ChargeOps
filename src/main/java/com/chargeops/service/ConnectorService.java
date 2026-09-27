package com.chargeops.service;

import com.chargeops.dto.ConnectorDTO;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.dto.ConnectorUpdateDTO;
import com.chargeops.result.PageResult;
import com.chargeops.vo.ConnectorVO;

/**
 * 充电枪服务
 *
 * <p> 枪的写操作没有 {@code @AutoFill}：{@code connector} 表没有
 * create_user / update_user 两列，而 {@code AutoFillInterceptor} 是反射找
 * {@code setCreateUser(Long)} 的，找不到直接抛 {@code IllegalStateException} ——
 * 标错了不是「填不上就不填」，是所有写操作当场炸掉。所以实现类里那两行很突兀的
 * {@code setCreateTime/setUpdateTime} 不是冗余，是唯一的时间来源。
 *
 * <p> {@code version} 是客户端回传的，服务端不自己读 —— 自己读一遍再写的话，
 * 读到的值紧接着被写进 WHERE，中间没有并发窗口，真正的窗口在「客户端读到数据」
 * 和「客户端提交修改」之间，那是装饰性的乐观锁。
 */
public interface ConnectorService {

    /**
     * 新增充电枪
     *
     * <p> 与 {@code charger.gun_count} 的重算在同一个事务里完成。
     *
     * @return 新充电枪的 id
     * @throws com.chargeops.exception.NotFoundException     所属充电桩不存在
     * @throws org.springframework.dao.DuplicateKeyException 同一充电桩下枪口号已存在
     */
    Long create(ConnectorDTO connectorDTO);

    /**
     * 修改充电枪
     *
     * <p> 必须回传 {@code version}。不能改所属桩，也不能改
     * {@code currentOrderId} / {@code totalChargeCount}。
     *
     * @throws com.chargeops.exception.DataConflictException 版本冲突，或状态不在管理员可设的白名单内
     */
    void update(Long id, ConnectorUpdateDTO connectorUpdateDTO);

    /**
     * 删除充电枪（硬删）
     *
     * <p> 被订单占用或正在充电时拒绝，并在同一事务里重算 {@code gun_count}。
     *
     * @throws com.chargeops.exception.DataConflictException 充电枪被在途订单占用
     */
    void delete(Long id);

    /** 按 id 查详情。返回值里的 {@code version} 是客户端更新时必须回传的值 */
    ConnectorVO getById(Long id);

    /** 分页查询。和充电桩不同，这里不需要富化任何字段 */
    PageResult page(ConnectorPageQueryDTO query);
}
