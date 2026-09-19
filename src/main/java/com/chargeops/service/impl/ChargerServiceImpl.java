package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.ChargerDTO;
import com.chargeops.dto.ChargerPageQueryDTO;
import com.chargeops.dto.ChargerUpdateDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.ChargerStatus;
import com.chargeops.enumeration.ChargerType;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
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
 * <p>比 {@code StationServiceImpl} 多两件事：<b>它依赖别人（站点）</b>，
 * 而且<b>它的列表需要富化</b>。这两件事都不是这个类独有的 ——
 * 后面的枪、套餐、模板都会遇到，所以它们的写法在这里定型。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChargerServiceImpl implements ChargerService {

    private final ChargerMapper chargerMapper;
    private final StationMapper stationMapper;
    private final ConnectorMapper connectorMapper;

    @Override
    @Transactional
    public Long create(ChargerDTO chargerDTO) {
        // ★ 先确认站点存在。
        //
        //   这不是「为了保住完整性」—— MySQL 的外键在本项目里是关掉的
        //   （见 DDL 的说明：外键会让删站点的顺序变成约束）。
        //   查它是为了给出一句【能解释的消息】：「站点不存在」。
        //   不查的话，一个建在不存在的站点下的桩会被安静地插进去，
        //   然后永远不出现在任何按站点过滤的列表里 ——
        //   管理员看到的是一句「创建成功」和一块空白的列表。
        requireStation(chargerDTO.getStationId());

        Charger charger = new Charger();
        BeanUtils.copyProperties(chargerDTO, charger);

        // ★★ 两个字段必须在这里显式给值，因为 DDL 的 DEFAULT 救不了它们。
        //
        //   ChargerMapper.xml 的 insert 是全列插入，
        //   而 SQL 的 DEFAULT 只在列【没有出现在语句里】时生效 ——
        //   传 null 就是往 NOT NULL 列里显式写 NULL，MySQL 报 errno 1048。
        //   这个坑在本项目里连踩三次，这是第三次。

        // gun_count：★ 给 0，【不是】DDL 默认的 1。
        //   这一列的事实来源是 connector 表（DDL 明写「冗余自 connector」），
        //   而刚建好的桩一把枪都没有，真实值是 0。
        //   默认值 1 是给「一桩一枪」这个常见情况省事的近似，
        //   代价是列表页会显示一个不存在的枪，一直到有人加枪或数枪才发现。
        //   「近似但错的值」不如「暂时不完整但正确」。
        charger.setGunCount(0);

        // status：新建的桩只能是 0 离线。
        //   它还没有和任何硬件建立过连接，说它「在线空闲」需要一个从未发生过的握手。
        //   之后由 P1d 的心跳上报，或者管理员在更新接口上手工设成故障/维护中。
        //   所以 ChargerDTO 里根本没有 status 字段，这里固定写离线。
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
        // ★ 注意这里 copy 的是 ChargerUpdateDTO —— 它没有 stationId、
        //   没有 gunCount、没有 lastHeartbeatTime。
        //   所以哪怕 ChargerMapper.xml 的 <set> 里哪天多了一行
        //   station_id = #{stationId}，复制出来的对象上那个字段也是 null，
        //   <if> 会跳过它。<b>两层独立生效。</b>
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
        // ★ 详情也要补站名 —— 前端拿到一个 ChargerVO 时，
        //   不应该需要根据「这是从列表来的还是从详情来的」来决定
        //   stationName 有没有值。同一个类型，同样的字段，就应该总是有值。
        return toVos(List.of(charger)).get(0);
    }

    @Override
    public PageResult page(ChargerPageQueryDTO query) {
        // ★★ 顺序就是一切，见下面三段注释。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Charger> rows = chargerMapper.page(query);
        long total = ((Page<Charger>) rows).getTotal();

        // ★★ 富化【必须】在第 1 条查询之后。
        //
        //   PageHelper.startPage 把分页参数放进一个 ThreadLocal，
        //   由【该线程上的下一条 MyBatis 查询】消费掉。
        //
        //   如果 toVos 被挪到 chargerMapper.page 之前（或者被内联进
        //   page 查询和 getTotal 之间），那么【它会拿到 LIMIT 10】：
        //   stationMapper.listByIds 只返回前 10 个站点，
        //   第 11 个之后的站点查不到名字，而 chargerMapper.page
        //   则退化成全表扫描。
        //
        //   表现有多难查：第 1 页看起来完全正常（页大小就是 10）、
        //   站点数不超过 10 个时永远不出现、一旦出现看起来像前端的问题
        //   （后端确实返回了 stationName 这个键，只是值是 null）。
        //   —— 静默、依赖数据量、且指向错误的方向。
        List<ChargerVO> records = toVos(rows);

        return new PageResult(total, records);
    }

    // ==================== 私有：校验与转换 ====================

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
     * 删除守卫：桩下还有枪时不许删
     *
     * <p>★ 和站点那条守卫是同一个判断的另一半，但后果更隐蔽：
     * 桩被删掉之后，它的枪<b>不会被级联删除</b>（没有外键），
     * 于是那些枪会带着一个指向不存在桩的 {@code charger_id} 留在库里。
     * 它们仍然能被查出来（{@code connector} 上没有外键约束），
     * 只是再也找不到自己属于哪个桩 —— 而 {@code gun_count} 的冗余对账
     * 也再没有依据（桩都没了，跟谁对）。
     *
     * <p>消息里带上具体数量，理由同 {@code StationServiceImpl.assertDeletable}。
     *
     * <p>★★ <b>P1c 要在这里加第二个守卫</b>：查这些枪上有没有在充的订单。
     * 一根桩在有人充电时被删掉，那笔订单会失去它的设备归属，
     * 而订单是 append-only 的，悬空引用会永久留在历史里。
     */
    private void assertDeletable(Long id) {
        int connectorCount = connectorMapper.countByChargerId(id);
        if (connectorCount > 0) {
            throw new DataConflictException(
                    "该充电桩下还有 " + connectorCount + " 把充电枪，请先删除");
        }
    }

    /**
     * 批量转 VO，并补齐 {@code stationName}
     *
     * <p>★ 批量而不是逐个查，是为了消除 N+1：
     * 一页 10 个桩如果逐个 {@code stationMapper.getById}，就是 10 次查询，
     * 而 {@code listByIds} 只要 1 次。这个差别在数据量小的时候看不出来，
     * 但它是<b>形状</b>上的差别，改起来要动整个方法 —— 所以一开始就写对。
     *
     * <p>★ <b>{@code listByIds} 不能传空集合</b>：{@code <foreach>} 会展开成
     * {@code IN ()}，那是 SQL 语法错误（不是返回空列表）。
     * 而空集合在业务上很常见 —— 第一页恰好没有数据的时候。
     * 所以下面那个 {@code if} 不是防御性代码，是必经的一步。
     */
    private List<ChargerVO> toVos(List<Charger> chargers) {
        if (chargers.isEmpty()) {
            return List.of();
        }

        // ★ 去重：一页 10 个桩很可能只属于 2 个站点。
        //   不去重的话 listByIds 会拿到一堆重复 id，
        //   虽然结果一样（IN 本来就去重），但白白让 IN 列表变长。
        Set<Long> stationIds = chargers.stream()
                .map(Charger::getStationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ★ 站点查不到时（被并发删掉了）不报错，只是名字为 null。
        //   理由和 StationServiceImpl.labelOf 那条一样：
        //   只读路径上的派生字段失败要降级 ——
        //   「这一行的站名显示不出来」不该让整个列表接口 500。
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

    /**
     * ★ 派生字段的降级策略见 {@code StationServiceImpl.labelOf} ——
     * 只读路径上的派生字段失败返回 null，不抛。
     */
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
