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
 * 各自都有明确的失败模式，注释按这个顺序展开。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectorServiceImpl implements ConnectorService {

    private final ConnectorMapper connectorMapper;
    private final ChargerMapper chargerMapper;
    private final ChargingOrderMapper orderMapper;

    // ==================== 新增 ====================

    @Override
    @Transactional
    public Long create(ConnectorDTO connectorDTO) {
        Charger charger = requireCharger(connectorDTO.getChargerId());

        Connector connector = new Connector();
        BeanUtils.copyProperties(connectorDTO, connector);

        // ★★ 一、冗余列 station_id 由【所属桩】派生，客户端传不了。
        //
        //   如果让客户端传，「挂在 A 站的桩下、station_id 却写着 B 站」这种
        //   数据会被 idx_connector_station_status 上的「某站所有枪」查询真的
        //   查出来，而没有任何东西会报错。用接口里没有的入参，让它在协议上
        //   就不可能产生。
        //   注意这里用的就是【同一次】chargerMapper.getById 的结果，
        //   所以「校验桩存在」不是额外成本。
        connector.setStationId(charger.getStationId());

        // ★★ 二、字段初始值只能挡「没传这个键」，挡不住显式的 JSON null。
        //
        //   Jackson 对 {"status": null} 会照常调用 setter 写进 null，覆盖掉
        //   字段上的初始值；而 insert 是全列插入，null 会直接撞 NOT NULL
        //   （errno 1048）。所以「不传」和「传 null」必须在这里归一成同一件事。
        //   这个坑在本项目里连踩三次（station.open_time、charger.rated_power_kw、
        //   charger.gun_count），见 ChargerMapper.xml。
        connector.setStatus(ConnectorStatus.OFFLINE.getCode());

        // version 从 0 开始（和 DDL 的 DEFAULT 0 对齐）。
        //   ★ 不让客户端指定：能指定的话，客户端传一个比库里大的值，
        //   乐观锁从此永远不冲突。
        connector.setVersion(0);

        // current_order_id 留 null（空闲），total_charge_count 给 0。
        //   ★ total_charge_count 是 NOT NULL DEFAULT 0，null 会撞 1048，必须给值。
        //   这两个字段在 ConnectorDTO 里根本不存在，显式写一遍是为了让
        //   「新建的枪是空闲的、没有充过电」这句话在代码里看得见。
        connector.setCurrentOrderId(null);
        connector.setTotalChargeCount(0);

        // ★★ 三、审计时间必须【在这里】显式写，因为本表没有 @AutoFill。
        //
        //   connector 表只有 create_time / update_time，没有 user 两列，而
        //   AutoFillInterceptor 是反射找 setCreateUser(Long) 的，找不到会直接抛
        //   IllegalStateException。所以这两行是这个字段唯一的时间来源
        //   （DDL 的 DEFAULT CURRENT_TIMESTAMP 在全列插入下同样不生效）。
        //
        //   这条约定有一个测试守着：ConnectorMapperTest 用反射断言
        //   Connector.class.getMethod("setCreateUser", Long.class) 抛
        //   NoSuchMethodException —— 谁哪天给实体加了 user 字段而没加列，它会立刻失败。
        LocalDateTime now = LocalDateTime.now();
        connector.setCreateTime(now);
        connector.setUpdateTime(now);

        connectorMapper.insert(connector);

        // ★★ 四、冗余列对账：必须在【同一个事务】里做。
        //
        //   charger.gun_count 是 DDL 明写的冗余列，不在这里重算的话它会从第一把
        //   枪开始就漂移，而且漂到有人手工数枪才会发现。
        //
        //   用 COUNT(*) 重算而不是 gun_count + 1：自增在「有人手工删过一行枪」
        //   或「上一次事务回滚过」时会错得无法恢复，重算则永远是精确值，
        //   代价只是一次走 idx_connector_charger 的索引计数。
        //   （ChargerMapperTest 里有一条断言专门验证它是【重算】而不是自增。）
        connectorMapper.refreshGunCount(charger.getId());

        log.info("新增充电枪成功：id={}, chargerId={}, gunNo={}",
                connector.getId(), connector.getChargerId(), connector.getGunNo());
        return connector.getId();
    }

    // ==================== 修改 ====================

    @Override
    @Transactional
    public void update(Long id, ConnectorUpdateDTO connectorUpdateDTO) {
        // ★ 状态白名单在【进 SQL 之前】判。
        //
        //   不用 @Min/@Max：白名单是「0/1/4/5」这四个散的值，而全集 0~5 里
        //   排掉的是中间两个，写成 @Min(0) @Max(5) 等于放行。用
        //   ConnectorStatus.isAdminSettable() —— 它是一个方法而不是一个常量集合，
        //   加状态值的时候这个方法会跟着一起被想起来。
        Integer status = connectorUpdateDTO.getStatus();
        if (status != null && !ConnectorStatus.of(status).isAdminSettable()) {
            // 消息要说清楚为什么，否则管理员只会觉得是 bug —— 见 MessageConstant
            throw new DataConflictException(MessageConstant.CONNECTOR_STATUS_NOT_EDITABLE);
        }

        Connector connector = new Connector();
        BeanUtils.copyProperties(connectorUpdateDTO, connector);
        connector.setId(id);

        // ★ 时间同样要显式写，见 create 里的说明（本表没有 @AutoFill）。
        connector.setUpdateTime(LocalDateTime.now());

        // ★★ 乐观锁：{@code WHERE id = ? AND version = ?}，
        //   影响行数就是全部的判断依据 —— 0 行有「版本冲突」和「行没了」
        //   两种可能，必须再查一次区分。
        int affected = connectorMapper.update(connector);
        if (affected == 0) {
            // ★ 这两条消息给用户的意义完全不同：「枪不存在」→ 他打开的页面
            //   已经失效了，要退回列表；「被他人修改」→ 刷新一下就能看到新值
            //   再改一遍。合并成一句「保存失败」的话，用户只能靠猜。
            Connector existing = connectorMapper.getById(id);
            if (existing == null) {
                throw new NotFoundException(MessageConstant.CONNECTOR_NOT_FOUND);
            }
            throw new DataConflictException(MessageConstant.CONCURRENT_MODIFIED);
        }

        // ★ 不重算 gun_count：这个接口改不了 chargerId，也改不了「有几把枪」，
        //   枪的数量没变，冗余列自然没变。唯一可能影响它的操作是「把这把枪挪到
        //   别的桩」，而那在 DTO 层面就被排除了，见 ConnectorUpdateDTO 类注释第三节。

        log.info("修改充电枪：id={}, version={}", id, connectorUpdateDTO.getVersion());
    }

    // ==================== 删除 ====================

    @Override
    @Transactional
    public void delete(Long id) {
        Connector connector = requireConnector(id);
        assertDeletable(connector);

        connectorMapper.deleteById(id);

        // ★ 和 create 对称：同一个事务里重算 gun_count。删掉最后一把枪时它会
        //   变成 0，而 0 是正确的值 —— 不是「没枪了所以清空」，是「真的没有枪」。
        connectorMapper.refreshGunCount(connector.getChargerId());

        log.info("删除充电枪：id={}, chargerId={}", id, connector.getChargerId());
    }

    // ==================== 查询 ====================

    @Override
    public ConnectorVO getById(Long id) {
        return toVo(requireConnector(id));
    }

    @Override
    public PageResult page(ConnectorPageQueryDTO query) {
        // ★ PageHelper 的调用顺序见 ChargerServiceImpl.page 里那段长注释。
        //   这里的特别之处是：枪【不需要】富化任何字段，
        //   所以这个方法的顺序天然是对的 —— 而这正是它危险的地方。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Connector> rows = connectorMapper.page(query);
        long total = ((Page<Connector>) rows).getTotal();

        return new PageResult(total, rows.stream().map(this::toVo).toList());
    }

    // ==================== 私有 ====================

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
     * <p><b>★ 判据是三道，后两道都各有原因：</b>
     *
     * <ol>
     *   <li><b>{@code current_order_id != null}</b> —— 最直接的一道。
     *       订单状态机在<b>建单</b>时写它（{@code ConnectorMapper.occupy}），
     *       非空就意味着「这把枪正被一个订单占着」。</li>
     *
     *   <li><b>{@code status} 是 {@code 2已占用} 或 {@code 3充电中}</b> ——
     *       <b>冗余判据</b>，防的是「状态和 {@code current_order_id} 不一致」
     *       的历史数据。理论上不该出现（管理员改不了这两个状态，见
     *       {@code ConnectorStatus.isAdminSettable}），但这个项目里已经有过
     *       数据被手工改过的先例，而漏判的代价是<b>删掉一把正在充电的枪</b>。</li>
     *
     *   <li><b>{@code charging_order} 里有没有未完结的行指向这把枪</b> ——
     *       前两道读的都是 {@code connector} 自己那两列，而<b>订单才是真相，
     *       枪上的状态是它的投影</b>。这一道反过来问订单表，能发现
     *       「{@code current_order_id} 因为某个 bug 被清空了、但订单其实还开着」。</li>
     * </ol>
     *
     * <p>★ 为什么删枪比删桩危险：订单里存的是 {@code connector_id}，
     * {@code charging_segment} 又是按订单关联的。枪被删掉之后，一笔正在进行的
     * 订单会失去「电流往哪里送」这个事实，而结算仍然会按订单冻结的模板算出
     * 金额 —— 钱照扣，但没有任何办法验证那笔电量是不是真的充进去了。
     *
     * <p>常规的退役手段是 {@code status = 5 维护中} 或 {@code 0 离线}；
     * 硬删只用于「刚建好就发现枪口号填错了」，而那正是需要它的场景：
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

        // ★ 前两道判据都在读 connector 自己写的两列；这一道问的是订单表。
        //   用 holdingConnectorCodes()（{1,2,3}）而不是更宽的集合：4/6/9 的订单
        //   早就把枪放了，拿它们拦删除会让一把枪在历史上充过一次电之后就再也删不掉。
        //   走 idx_order_connector_status (connector_id, status)，零新增索引。
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

    /**
     * ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} ——
     * 只读路径上的派生字段失败返回 null，不抛。
     */
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
