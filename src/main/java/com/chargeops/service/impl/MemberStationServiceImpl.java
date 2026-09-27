package com.chargeops.service.impl;

import com.chargeops.dto.MemberStationPageQueryDTO;
import com.chargeops.entity.Charger;
import com.chargeops.entity.Connector;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.ChargerType;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ConnectorMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
import com.chargeops.service.MemberStationService;
import com.chargeops.vo.MemberConnectorVO;
import com.chargeops.vo.MemberStationVO;
import com.chargeops.vo.StationConnectorCountVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 车主端站点 / 充电枪查询实现
 *
 * <p> 这里记三处「写错了也不会报错、只会静默给出错误答案」的地方。
 *
 * <p> {@code PageHelper.startPage} 和 {@code pageForMember} 之间不许有别的查询：
 * 本类最容易踩到这条（分页查询后面紧跟着一次 {@code countByStationIds} 富化），
 * 富化放到前面就把分页参数消费掉了 —— 富化查询拿到 {@code LIMIT 10}，主查询退化成
 * 全表扫描。症状是第 1 页看起来完全正常（页大小本来就是 10）、站点少于 10 个时永不出现。
 *
 * <p> {@code countByStationIds} 有两条方向相反的边界：{@code ids} 不能是空集合
 * （{@code <foreach>} 会展开成 {@code IN ()}，那是 SQL 语法错误）；站下没有枪时整行
 * 都不出现（{@code GROUP BY} 只给有匹配行的组），所以取值必须是「查不到就是 0」，
 * 而不是 {@code map.get(id)} 直接拆箱 —— 那是 NPE。
 *
 * <p> 枪列表不排序是刻意的：{@code gun_no} 是字符串，排出来是 {@code 1, 10, 2}。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberStationServiceImpl implements MemberStationService {

    private final StationMapper stationMapper;
    private final ConnectorMapper connectorMapper;
    private final ChargerMapper chargerMapper;

    /** {@code @Transactional(readOnly = true)} 不影响 PageHelper：startPage 的参数在 ThreadLocal 里，和事务边界无关 */
    @Override
    @Transactional(readOnly = true)
    public PageResult page(MemberStationPageQueryDTO query) {
        // startPage 必须紧挨着 pageForMember，中间不能插任何别的查询 —— 那个陷阱不报错
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Station> rows = stationMapper.pageForMember(query);

        long total = ((Page<Station>) rows).getTotal();

        return new PageResult(total, toStationVos(rows));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberConnectorVO> listByStation(Long stationId) {
        // 没有 stationId 判空是刻意的：它是路径变量，`/by-station/` 空段匹配不到本映射。
        // 对照 listAvailable 里那个判空 —— 它拦的 query 参数「不传」是一个合法的请求。
        return toConnectorVos(connectorMapper.listByStation(stationId));
    }

    // ---------- 私有：实体 → VO ----------

    /**
     * 批量转站点 VO，并补上两个枪数
     *
     * <p> 两个容易写错、且都不报错的地方：把空集合传给 {@code countByStationIds}（整页 500），
     * 以及 {@code map.get(stationId)} 之后直接拆箱（站下一把枪都没有时 NPE）。
     */
    private List<MemberStationVO> toStationVos(List<Station> stations) {
        if (stations.isEmpty()) {
            // 承重不是提前返回的优化：它同时挡住「空集合传进 countByStationIds」
            // 和「没有站点还去查一次枪数」。
            return List.of();
        }

        Set<Long> stationIds = stations.stream()
                .map(Station::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 这里的 isEmpty() 分支实际到不了（分页结果里每个站点只出现一次，主键也不为 null），
        // 留着是因为「不能传空集合」这条约定不能靠「上面那条 isEmpty 已经挡过了」来保证 ——
        // 那是跨方法的推理。
        Map<Long, StationConnectorCountVO> counts = stationIds.isEmpty()
                ? Map.of()
                : connectorMapper.countByStationIds(List.copyOf(stationIds)).stream()
                        .collect(Collectors.toMap(
                                StationConnectorCountVO::getStationId, Function.identity()));

        return stations.stream()
                .map(station -> {
                    StationConnectorCountVO count = counts.get(station.getId());
                    if (count == null) {
                        // 整行缺失 = 这个站下一把枪都没有，就是那个 (0, 0) 的默认形态
                        return MemberStationVO.from(station);
                    }
                    return MemberStationVO.from(station,
                            zeroIfNull(count.getConnectorCount()),
                            zeroIfNull(count.getIdleConnectorCount()));
                })
                .toList();
    }

    /** 批量转枪 VO，并补上所属桩的编号 / 名称 / 类型文案。顺序原样透传，排序依据是 SQL 里的 ORDER BY id */
    private List<MemberConnectorVO> toConnectorVos(List<Connector> connectors) {
        if (connectors.isEmpty()) {
            return List.of();
        }

        Set<Long> chargerIds = connectors.stream()
                .map(Connector::getChargerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 同 countByStationIds：listByIds 也不能传空集合，先判空
        Map<Long, Charger> chargers = chargerIds.isEmpty()
                ? Map.of()
                : chargerMapper.listByIds(List.copyOf(chargerIds)).stream()
                        .collect(Collectors.toMap(Charger::getId, Function.identity()));

        return connectors.stream()
                .map(connector -> {
                    // 桩查不到（被并发删掉了）时三个字段给 null：只读路径上的派生字段失败要降级
                    Charger charger = chargers.get(connector.getChargerId());
                    return MemberConnectorVO.from(connector,
                            charger == null ? null : charger.getDeviceNo(),
                            charger == null ? null : charger.getName(),
                            charger == null ? null : labelOfType(charger.getType()));
                })
                .toList();
    }

    /** 枪数默认值：{@code null} 当 0。{@code from(station, int, int)} 收的是基本类型，直接传 {@code Integer} 就是一次隐式拆箱 */
    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 桩类型码 → 文案（{@code 1直流快充 / 2交流慢充}）
     *
     * <p> {@code ChargerType.of} 对未知 code 会抛异常，这里不能让它抛：一个数据库里存在、
     * 代码不认识的值不该让整个枪列表 500，返回 null 即可。
     *
     * <p> 这一份是刻意的重复（{@code ChargerServiceImpl} 里已经有一份）：两处逐字一致，
     * 将来其中一处改了，另一处不会跟着变。
     */
    private String labelOfType(Integer type) {
        if (type == null) {
            return null;
        }
        try {
            return ChargerType.of(type).getLabel();
        } catch (ParamErrorException e) {
            log.warn("充电桩类型无法识别，type={}（数据是手工改过还是枚举漏了一个值？）", type);
            return null;
        }
    }
}
