package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.ChargerStatus;
import com.chargeops.enumeration.ChargerType;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.ChargerService;
import com.chargeops.vo.ChargerVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 充电桩服务实现
 *
 * <p>比 {@code StationServiceImpl} 多两件事：它依赖别人（站点），而且它的列表
 * 需要富化 —— 后面的枪、套餐、模板都按这里的写法走。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargerServiceImpl implements ChargerService {

    private final ChargerMapper chargerMapper;
    private final StationMapper stationMapper;
    private final ConnectorMapper connectorMapper;

    /** 只被 {@link #assertDeletable} 用，只读一个 COUNT：「这根桩能不能删」的答案不在桩表里，在引用它的那些订单行里 */
    private final ChargingOrderMapper orderMapper;

    @Override
    @Transactional
    public Long create(ChargerDTO chargerDTO) {
        // 先确认站点存在，不是为了保住完整性（外键在本项目里是关掉的），
        // 而是为了给出一句能解释的消息：不查的话，一个建在不存在的站点下的桩
        // 会被安静地插进去，然后永远不出现在任何按站点过滤的列表里。
        requireStation(chargerDTO.getStationId());

        Charger charger = new Charger();
        BeanUtils.copyProperties(chargerDTO, charger);

        // 两个字段必须在这里显式给值：insert 是全列插入，而 SQL 的 DEFAULT 只在列
        // 没有出现在语句里时生效 —— 传 null 就是往 NOT NULL 列里显式写 NULL。

        // gun_count 给 0，不是DDL 默认的 1。这一列的事实来源是 connector 表
        // （DDL 明写「冗余自 connector」），而刚建好的桩一把枪都没有。
        charger.setGunCount(0);

        // status 只能是 0 离线：它还没和任何硬件建立过连接，说它「在线空闲」
        // 需要一个从未发生过的握手。所以 ChargerDTO 里根本没有这个字段。
        charger.setStatus(ChargerStatus.OFFLINE.getCode());

        chargerMapper.insert(charger);

        log.info("新增充电桩成功：id={}, deviceNo={}, stationId={}",
                charger.getId(), charger.getDeviceNo(), charger.getStationId());
        return charger.getId();
    }

    @Override
    @Transactional
    public void update(Long id, ChargerUpdateDTO chargerUpdateDTO) {
        Charger charger = new Charger();
        // 这里 copy 的是 ChargerUpdateDTO —— 它没有 stationId、没有 gunCount、
        // 没有 lastHeartbeatTime：哪怕 ChargerMapper.xml 的 <set> 里哪天多了一行
        // station_id = #{stationId}，复制出来的对象上那个字段也是 null，<if> 会跳过它。
        BeanUtils.copyProperties(chargerUpdateDTO, charger);
        charger.setId(id);

        chargerMapper.update(charger);

        log.info("修改充电桩：id={}", id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireCharger(id);
        assertDeletable(id);
        chargerMapper.deleteById(id);
        log.info("删除充电桩：id={}", id);
    }

    @Override
    public ChargerVO getById(Long id) {
        Charger charger = requireCharger(id);
        // 详情也要补站名：前端拿到一个 ChargerVO 时，不应该需要根据「这是从列表来的
        // 还是从详情来的」来决定 stationName 有没有值。
        return toVos(List.of(charger)).get(0);
    }

    @Override
    public PageResult page(ChargerPageQueryDTO query) {
        // 顺序就是一切，见下面那段注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Charger> rows = chargerMapper.page(query);
        long total = ((Page<Charger>) rows).getTotal();

        // 富化必须在第 1 条查询之后：startPage 把分页参数放进 ThreadLocal，
        // 由该线程上的下一条 MyBatis 查询消费掉。把 toVos 挪到 chargerMapper.page
        // 之前的话，listByIds 会拿到 LIMIT 10 —— 第 11 个之后的站点查不到名字，
        // 而 chargerMapper.page 退化成全表扫描。
        // 表现有多难查：第 1 页看起来完全正常（页大小就是 10）、站点数不超过 10 个时
        // 永远不出现、一旦出现看起来像前端的问题（后端确实返回了 stationName，
        // 只是值是 null）。
        List<ChargerVO> records = toVos(rows);

        return new PageResult(total, records);
    }

    // ---------- 私有：校验与转换 ----------

    private Station requireStation(Long stationId) {
        Station station = stationMapper.getById(stationId);
        if (station == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        return station;
    }

    private Charger requireCharger(Long id) {
        Charger charger = chargerMapper.getById(id);
        if (charger == null) {
            throw new NotFoundException(MessageConstant.CHARGER_NOT_FOUND);
        }
        return charger;
    }

    /**
     * 删除守卫：桩下还有枪、或者还有未完结的订单时，不许删
     *
     * <p> 桩被删掉之后，它的枪不会被级联删除（没有外键），那些枪会带着一个指向
     * 不存在桩的 {@code charger_id} 留在库里 —— 仍然能被查出来，但再也找不到
     * 自己属于哪个桩，而 {@code gun_count} 的冗余对账也再没有依据。
     *
     * <p> 第二道（订单）不能省：第一道挡不住「桩上的枪已经被全部删掉，但订单还在」，
     * 那笔订单的 {@code charger_id} 会指向一根不存在的桩，而订单是 append-only 的，
     * 悬空引用永久留在历史里。判据是 {@code OrderStatus.blockingCodes()}
     * （「钱还冻着」∪「挂账等人工」，不是「非终态」）—— 让已支付的历史订单也挡住
     * 删除的话，任何出过工单的桩都会变成不可删。
     *
     * <p> 两道守卫都要留，因为两个入口各自独立：{@code DELETE /api/admin/charger/{id}}
     * 这条路径完全不经过 {@code StationService}，那根桩上的订单守卫只能由本方法回答。
     */
    private void assertDeletable(Long id) {
        int connectorCount = connectorMapper.countByChargerId(id);
        if (connectorCount > 0) {
            throw new DataConflictException(
                    String.format(MessageConstant.CHARGER_HAS_CONNECTOR, connectorCount));
        }

        int orderCount = orderMapper.countByCharger(id, OrderStatus.blockingCodes());
        if (orderCount > 0) {
            throw new DataConflictException(
                    String.format(MessageConstant.CHARGER_HAS_ORDER, orderCount));
        }
    }

    /**
     * 批量转 VO，并补齐 {@code stationName}
     *
     * <p> 批量而不是逐个查，是为了消除 N+1：一页 10 个桩逐个 {@code getById}
     * 就是 10 次查询，而 {@code listByIds} 只要 1 次。
     *
     * <p> {@code listByIds} 不能传空集合：{@code <foreach>} 会展开成 {@code IN ()}，
     * 那是 SQL 语法错误（不是返回空列表），而第一页恰好没有数据的时候很常见。
     */
    private List<ChargerVO> toVos(List<Charger> chargers) {
        if (chargers.isEmpty()) {
            return List.of();
        }

        // 去重：一页 10 个桩很可能只属于 2 个站点，不去重会让 IN 列表白白变长。
        Set<Long> stationIds = chargers.stream()
                .map(Charger::getStationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 站点查不到时（被并发删掉了）不报错，只是名字为 null：只读路径上的派生字段
        // 失败要降级，「这一行的站名显示不出来」不该让整个列表接口 500。
        Map<Long, String> stationNames = stationIds.isEmpty()
                ? Map.of()
                : stationMapper.listByIds(List.copyOf(stationIds)).stream()
                        .collect(Collectors.toMap(Station::getId, Station::getName));

        return chargers.stream()
                .map(charger -> toVo(charger, stationNames.get(charger.getStationId())))
                .toList();
    }

    private ChargerVO toVo(Charger charger, String stationName) {
        ChargerVO vo = new ChargerVO();
        BeanUtils.copyProperties(charger, vo);
        vo.setStationName(stationName);
        vo.setTypeLabel(labelOfType(charger.getType()));
        vo.setStatusLabel(labelOfStatus(charger.getStatus()));
        return vo;
    }

    /** 派生字段的降级策略见 {@code StationServiceImpl.labelOf} —— 只读路径上失败返回 null，不抛 */
    private String labelOfType(Integer type) {
        if (type == null) {
            return null;
        }
        try {
            return ChargerType.of(type).getLabel();
        } catch (ParamErrorException e) {
            log.warn("充电桩类型无法识别，type={}", type);
            return null;
        }
    }

    private String labelOfStatus(Integer status) {
        if (status == null) {
            return null;
        }
        try {
            return ChargerStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            log.warn("充电桩状态无法识别，status={}", status);
            return null;
        }
    }
}
