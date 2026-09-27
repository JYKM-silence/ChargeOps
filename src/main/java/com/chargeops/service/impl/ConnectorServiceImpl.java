package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.ConnectorDTO;
import com.chargeops.dto.ConnectorPageQueryDTO;
import com.chargeops.dto.ConnectorUpdateDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.ChargingOrder;
import com.chargeops.entity.Connector;
import com.chargeops.enumeration.ConnectorStatus;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.ConnectorService;
import com.chargeops.vo.ConnectorVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充电枪服务实现
 *
 * <p>本域同时具备「冗余列维护」「乐观锁」「状态白名单」三件事，
 * 各自都有明确的失败模式。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectorServiceImpl implements ConnectorService {

    private final ConnectorMapper connectorMapper;
    private final ChargerMapper chargerMapper;
    private final ChargingOrderMapper orderMapper;

    // ---------- 新增 ----------

    @Override
    @Transactional
    public Long create(ConnectorDTO connectorDTO) {
        Charger charger = requireCharger(connectorDTO.getChargerId());

        Connector connector = new Connector();
        BeanUtils.copyProperties(connectorDTO, connector);

        // 冗余列 station_id 由所属桩派生，客户端传不了：让客户端传的话，
        // 「挂在 A 站的桩下、station_id 却写着 B 站」这种数据会被
        // idx_connector_station_status 上的「某站所有枪」查询真的查出来，
        // 而没有任何东西会报错。这里用的就是上面那次 getById 的结果，没有额外成本。
        connector.setStationId(charger.getStationId());

        // 字段初始值只能挡「没传这个键」，挡不住显式的 JSON null：Jackson 对
        // {"status": null} 会照常调 setter 写进 null，覆盖掉字段上的初始值，
        // 而 insert 是全列插入，null 会直接撞 NOT NULL（errno 1048）。
        connector.setStatus(ConnectorStatus.OFFLINE.getCode());

        // version 从 0 开始，不让客户端指定：能指定的话，客户端传一个比库里大的值，
        // 乐观锁从此永远不冲突。
        connector.setVersion(0);

        // current_order_id 留 null（空闲），total_charge_count 给 0
        // （NOT NULL DEFAULT 0，null 会撞 1048）。显式写一遍是为了让
        // 「新建的枪是空闲的、没有充过电」这句话在代码里看得见。
        connector.setCurrentOrderId(null);
        connector.setTotalChargeCount(0);

        // 审计时间必须在这里显式写：connector 表没有 user 两列，而
        // AutoFillInterceptor 是反射找 setCreateUser(Long) 的，找不到会直接抛
        // IllegalStateException。所以这两行是这个字段唯一的时间来源。
        // 有一条测试守着这个约定，见 ConnectorMapperTest。
        LocalDateTime now = LocalDateTime.now();
        connector.setCreateTime(now);
        connector.setUpdateTime(now);

        connectorMapper.insert(connector);

        // 冗余列对账必须在同一个事务里做：charger.gun_count 是 DDL 明写的
        // 冗余列，不在这里重算的话它会从第一把枪开始就漂移。
        // 用 COUNT(*) 重算而不是 gun_count + 1：自增在「有人手工删过一行枪」或
        // 「上一次事务回滚过」时会错得无法恢复，重算则永远是精确值。
        connectorMapper.refreshGunCount(charger.getId());

        log.info("新增充电枪成功：id={}, chargerId={}, gunNo={}",
                connector.getId(), connector.getChargerId(), connector.getGunNo());
        return connector.getId();
    }

    // ---------- 修改 ----------

    @Override
    @Transactional
    public void update(Long id, ConnectorUpdateDTO connectorUpdateDTO) {
        // 状态白名单在进 SQL 之前判。不用 @Min/@Max：白名单是「0/1/4/5」
        // 这四个散的值，而全集 0~5 里排掉的是中间两个，写成 @Min(0) @Max(5) 等于放行。
        Integer status = connectorUpdateDTO.getStatus();
        if (status != null && !ConnectorStatus.of(status).isAdminSettable()) {
            // 消息要说清楚为什么，否则管理员只会觉得是 bug
            throw new DataConflictException(MessageConstant.CONNECTOR_STATUS_NOT_EDITABLE);
        }

        Connector connector = new Connector();
        BeanUtils.copyProperties(connectorUpdateDTO, connector);
        connector.setId(id);

        // 时间同样要显式写，见 create 里的说明（本表没有 @AutoFill）。
        connector.setUpdateTime(LocalDateTime.now());

        // 乐观锁：{@code WHERE id = ? AND version = ?}，影响行数就是全部的判断依据 ——
        // 0 行有「版本冲突」和「行没了」两种可能，必须再查一次区分。
        int affected = connectorMapper.update(connector);
        if (affected == 0) {
            // 这两条消息给用户的意义完全不同：「枪不存在」→ 页面已经失效，要退回列表；
            // 「被他人修改」→ 刷新一下就能看到新值再改一遍。合并成「保存失败」只能靠猜。
            Connector existing = connectorMapper.getById(id);
            if (existing == null) {
                throw new NotFoundException(MessageConstant.CONNECTOR_NOT_FOUND);
            }
            throw new DataConflictException(MessageConstant.CONCURRENT_MODIFIED);
        }

        // 不重算 gun_count：这个接口改不了 chargerId，枪的数量没变，冗余列自然没变。

        log.info("修改充电枪：id={}, version={}", id, connectorUpdateDTO.getVersion());
    }

    // ---------- 删除 ----------

    @Override
    @Transactional
    public void delete(Long id) {
        Connector connector = requireConnector(id);
        assertDeletable(connector);

        connectorMapper.deleteById(id);

        // 和 create 对称：同一个事务里重算 gun_count。删掉最后一把枪时它会变成 0，
        // 而 0 是正确的值 —— 不是「没枪了所以清空」，是「真的没有枪」。
        connectorMapper.refreshGunCount(connector.getChargerId());

        log.info("删除充电枪：id={}, chargerId={}", id, connector.getChargerId());
    }

    // ---------- 查询 ----------

    @Override
    public ConnectorVO getById(Long id) {
        return toVo(requireConnector(id));
    }

    @Override
    public PageResult page(ConnectorPageQueryDTO query) {
        // 枪不需要富化任何字段，所以这个方法的顺序天然是对的 ——
        // 而这正是它危险的地方。PageHelper 的调用顺序见 ChargerServiceImpl.page。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Connector> rows = connectorMapper.page(query);
        long total = ((Page<Connector>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    // ---------- 私有 ----------

    private Charger requireCharger(Long chargerId) {
        Charger charger = chargerMapper.getById(chargerId);
        if (charger == null) {
            throw new NotFoundException(MessageConstant.CHARGER_NOT_FOUND);
        }
        return charger;
    }

    private Connector requireConnector(Long id) {
        Connector connector = connectorMapper.getById(id);
        if (connector == null) {
            throw new NotFoundException(MessageConstant.CONNECTOR_NOT_FOUND);
        }
        return connector;
    }

    /**
     * 删除守卫：被订单占用或正在充电时不许删
     *
     * <p> 判据三道：{@code current_order_id != null}（订单建单时写它，非空就意味着
     * 这把枪正被占着）；{@code status} 是 2已占用 或 3充电中（冗余判据，防的是状态和
     * {@code current_order_id} 不一致的历史数据，漏判的代价是删掉一把正在充电的枪）；
     * 以及 {@code charging_order} 里有没有未完结的行指向这把枪 —— 订单才是真相，
     * 枪上的状态是它的投影，这一道能发现「{@code current_order_id} 因为某个 bug
     * 被清空了、但订单其实还开着」。
     *
     * <p> 删枪比删桩危险：一笔正在进行的订单会失去「电流往哪里送」这个事实，
     * 而结算仍然会按订单冻结的模板算出金额 —— 钱照扣，但没有任何办法验证那笔电量
     * 是不是真的充进去了。
     *
     * <p> 常规的退役手段是 {@code status = 5 维护中} 或 {@code 0 离线}；硬删只用于
     * 「刚建好就发现枪口号填错了」，而那正是需要它的场景：
     * {@code uk_connector_charger_gun} 不看 status，枪口号建错一次就永久占用。
     */
    private void assertDeletable(Connector connector) {
        if (connector.getCurrentOrderId() != null) {
            throw new DataConflictException(MessageConstant.CONNECTOR_IN_USE);
        }

        ConnectorStatus status = ConnectorStatus.of(connector.getStatus());
        if (status == ConnectorStatus.OCCUPIED || status == ConnectorStatus.CHARGING) {
            log.warn("充电枪状态与 current_order_id 不一致：id={}, status={}, currentOrderId=null",
                    connector.getId(), connector.getStatus());
            throw new DataConflictException(MessageConstant.CONNECTOR_IN_USE);
        }

        // 前两道判据读的都是 connector 自己写的两列；这一道问的是订单表。
        // 用 holdingConnectorCodes()（{1,2,3}）而不是更宽的集合：4/6/9 的订单早就把枪
        // 放了，拿它们拦删除会让一把枪在历史上充过一次电之后就再也删不掉。
        List<ChargingOrder> holding = orderMapper.listHoldingByConnector(
                connector.getId(), OrderStatus.holdingConnectorCodes());
        if (!holding.isEmpty()) {
            log.warn("充电枪未被标记占用，但订单表里还有未完结订单：id={}, orderNo={}",
                    connector.getId(), holding.get(0).getOrderNo());
            throw new DataConflictException(MessageConstant.CONNECTOR_IN_USE);
        }
    }

    private ConnectorVO toVo(Connector connector) {
        ConnectorVO vo = new ConnectorVO();
        BeanUtils.copyProperties(connector, vo);
        vo.setStatusLabel(labelOf(connector.getStatus()));
        return vo;
    }

    /** 派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 只读路径上失败返回 null，不抛 */
    private String labelOf(Integer status) {
        if (status == null) {
            return null;
        }
        try {
            return ConnectorStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            log.warn("充电枪状态无法识别，status={}", status);
            return null;
        }
    }
}
