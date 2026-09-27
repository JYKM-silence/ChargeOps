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
 * <p>方法本身的契约见 {@link MemberStationService}。这个类的注释只记
 * <b>三处「写错了也不会报错、只会静默给出错误答案」</b>的地方 ——
 * 它们是本实现里全部的风险点。
 *
 * <h2>★★ 一、{@code PageHelper.startPage} 和 {@code pageForMember} 之间<b>不许有别的查询</b> ★★</h2>
 *
 * <p>本类是最容易踩到这条的地方：分页查询后面紧跟着一次
 * {@code countByStationIds} 富化。<b>那一次必须排在 {@code pageForMember} 之后</b> ——
 * 放到前面（或者被内联进来），它就<b>把分页参数消费掉了</b>：
 * 富化查询拿到 {@code LIMIT 10} 而主查询退化成全表扫描。
 * 症状是第 1 页看起来完全正常（页大小本来就是 10）、站点少于 10 个时永不出现、
 * 出现了像是前端的问题。完整论证见 {@code StationMapper.page} 与
 * {@code ChargerServiceImpl.page}。
 *
 * <h2>★★ 二、{@code countByStationIds} 的两条边界：空集合不许传、缺行必须当 0 ★★</h2>
 *
 * <p>两条都在 {@code ConnectorMapper.countByStationIds} 的 javadoc 里写明了，
 * 而它们<b>方向相反</b>，所以两条都得单独处理：
 *
 * <ul>
 *   <li><b>{@code ids} 不能是空集合</b> —— {@code <foreach>} 会展开成
 *       {@code IN ()}，那是<b>SQL 语法错误</b>而不是「返回空列表」。
 *       所以 {@link #toStationVos} 第一行就是 {@code isEmpty()} 短路；</li>
 *   <li><b>站下没有枪时整行都不出现</b> —— {@code GROUP BY} 只给有匹配行的组，
 *       没有 {@code LEFT JOIN} 那种「给个 0 行」的保证。
 *       所以取值用的是<b>「查不到就是 0」</b>，而不是
 *       {@code map.get(id)} 直接拆箱 —— 那是 NPE，而它会在
 *       <b>「这个站刚建好、还没装枪」这个完全正常的场景</b>上炸掉整个列表接口。</li>
 * </ul>
 *
 * <h2>★★ 三、枪列表<b>不排序</b>，桩信息<b>批量</b>补 ★★</h2>
 *
 * <p>不排序是刻意的（{@code gun_no} 是字符串，排出来是 {@code 1, 10, 2}，
 * 重排的职责在前端），见 {@code ConnectorMapper.listByStation}。
 * 本类只做 {@code stream().map(...)}，<b>顺带保序</b> ——
 * 将来谁想「顺手加个 sorted()」，那是把展示逻辑搬进了后端。
 *
 * <p>★ 桩信息是<b>一次 {@code listByIds}</code> 补上的，不是逐个 {@code getById}：
 * 一个站下十几根桩就是十几次查询。这也正是 {@code connector} 表冗余
 * {@code station_id} 之外，{@code ChargerMapper} 需要 {@code listByIds} 的原因。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MemberStationServiceImpl implements MemberStationService {

    private final StationMapper stationMapper;
    private final ConnectorMapper connectorMapper;
    private final ChargerMapper chargerMapper;

    /**
     * ★ {@code @Transactional(readOnly = true)} 不影响 PageHelper：
     * {@code startPage} 把参数放在 ThreadLocal 里，而 ThreadLocal 跟着线程走，
     * 和事务边界无关。同 {@code ChargingOrderServiceImpl.pageMine}。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult page(MemberStationPageQueryDTO query) {
        // ★★ 顺序就是一切：startPage 必须紧挨着 pageForMember，中间不能插任何别的查询。
        //    见类注释第一节 —— 那个陷阱的失败方式是不报错的。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Station> rows = stationMapper.pageForMember(query);

        // ★ 强转只为了拿 total：PageHelper 返回的 List 实际就是 Page（继承自 ArrayList）。
        //   抛 ClassCastException 说明 startPage 没生效 —— 那才是这条语句的用途。
        long total = ((Page<Station>) rows).getTotal();

        return new PageResult(total, toStationVos(rows));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberConnectorVO> listByStation(Long stationId) {
        // ★ 没有 stationId 判空，是刻意的：它是【路径变量】，
        //   `/by-station/` 这个空段根本匹配不到本映射，null 到不了这里。
        //   对照 MemberPackageServiceImpl.listAvailable 里那个判空 ——
        //   它拦的 stationId 是 query 参数，「不传」是一个合法的请求。
        return toConnectorVos(connectorMapper.listByStation(stationId));
    }

    // ==================== 私有：实体 → VO ====================

    /**
     * 批量转站点 VO，并补上两个枪数
     *
     * <p>★★ 这个方法是本类最容易写错的地方，两个错误都不会报错、只会给出错误答案：
     * <ol>
     *   <li>把空集合传给 {@code countByStationIds} —— 那是 SQL 语法错误
     *       （{@code IN ()}），整页 500；</li>
     *   <li>{@code map.get(stationId)} 之后直接拆箱 —— 站下一把枪都没有时那一行
     *       <b>根本不在 map 里</b>，NPE。而「刚建好、还没装枪」的站点在列表页上
     *       是常见状态，不是边界。见类注释第二节。</li>
     * </ol>
     */
    private List<MemberStationVO> toStationVos(List<Station> stations) {
        if (stations.isEmpty()) {
            // ★ 这一行同时挡住「空集合传进 countByStationIds」和「没有站点还去查一次枪数」。
            //   它是【承重】的，不是提前返回的优化。
            return List.of();
        }

        Set<Long> stationIds = stations.stream()
                .map(Station::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ★ 用 Set 是因为返回的是 id 的【集合】，而这里其实去重不了什么
        //   （分页结果里每个站点只出现一次）。真正需要去重的是下面枪那边的
        //   chargerIds —— 同一个桩下好几把枪是常态，不去重 IN 列表会白白变长。
        //   ★ isEmpty() 那个分支是给「有站点行但 id 全为 null」留的 —— 主键不可能为 null，
        //     所以它实际到不了；留着是因为「不能传空集合」这条约定不能靠
        //     「上面那条 isEmpty 已经挡过了」来保证：那是跨方法的推理。
        Map<Long, StationConnectorCountVO> counts = stationIds.isEmpty()
                ? Map.of()
                : connectorMapper.countByStationIds(List.copyOf(stationIds)).stream()
                        .collect(Collectors.toMap(
                                StationConnectorCountVO::getStationId, Function.identity()));

        return stations.stream()
                .map(station -> {
                    StationConnectorCountVO count = counts.get(station.getId());
                    if (count == null) {
                        // ★★ 整行缺失 = 这个站下一把枪都没有 = 0 把 / 0 把空闲。
                        //    MemberStationVO.from(station) 就是那个 (0, 0) 的默认形态 ——
                        //    不要在这里写 map.get(id) 拆箱，那是 NPE。
                        return MemberStationVO.from(station);
                    }
                    return MemberStationVO.from(station,
                            zeroIfNull(count.getConnectorCount()),
                            zeroIfNull(count.getIdleConnectorCount()));
                })
                .toList();
    }

    /**
     * 批量转枪 VO，并补上所属桩的编号 / 名称 / 类型文案
     *
     * <p>★ 顺序<b>原样透传</b>：{@code stream().map(...)} 保序，
     * 而 {@code listByStation} 的 {@code ORDER BY id} 是唯一的排序依据。
     * 别在这里加 {@code sorted(...)}，见类注释第三节。
     */
    private List<MemberConnectorVO> toConnectorVos(List<Connector> connectors) {
        if (connectors.isEmpty()) {
            return List.of();
        }

        Set<Long> chargerIds = connectors.stream()
                .map(Connector::getChargerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ★ 同 countByStationIds：listByIds 也不能传空集合，先判空。
        Map<Long, Charger> chargers = chargerIds.isEmpty()
                ? Map.of()
                : chargerMapper.listByIds(List.copyOf(chargerIds)).stream()
                        .collect(Collectors.toMap(Charger::getId, Function.identity()));

        return connectors.stream()
                .map(connector -> {
                    // ★ 桩查不到（被并发删掉了）时<b>不报错</b>，三个字段给 null：
                    //   只读路径上的派生字段失败要降级 —— 「这一行的桩名显示不出来」
                    //   不该让整个列表接口 500。同 ChargerServiceImpl.toVos 补站名的取舍。
                    Charger charger = chargers.get(connector.getChargerId());
                    return MemberConnectorVO.from(connector,
                            charger == null ? null : charger.getDeviceNo(),
                            charger == null ? null : charger.getName(),
                            charger == null ? null : labelOfType(charger.getType()));
                })
                .toList();
    }

    /**
     * 枪数默认值：{@code null} 当 0
     *
     * <p>★ 存在的意义只有一个：<b>把「拆箱」和「判空」合成一句话</b>。
     * {@code from(station, int, int)} 收的是基本类型，直接把
     * {@code Integer} 传进去就是一次隐式拆箱 —— 编译期完全合法，
     * 运行时 null 才炸。这个方法让「null 怎么办」必须在代码里被写出来。
     */
    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 桩类型码 → 文案（{@code 1直流快充 / 2交流慢充}）
     *
     * <p>★ {@code ChargerType.of} 对未知的 code 会抛异常，这里不能让它抛：
     * 一个数据库里存在、代码不认识的值（有人手工 UPDATE 了一个 9）
     * 不该让整个枪列表 500 —— 那种情况下返回 null，界面上显示空白。
     * <b>「这一行的类型显示不出来」和「整个页面打不开」差得很远。</b>
     * 通用取舍：<b>只读路径上的派生字段失败要降级，写路径上的校验失败要抛。</b>
     * 同 {@code ChargerServiceImpl.labelOfType} / {@code StationServiceImpl.labelOf}。
     *
     * <p>★★ <b>这一份是刻意的重复。</b>★★ 同样的逻辑在
     * {@code ChargerServiceImpl} 里已经有一份（私有方法）。本次改动只新增文件、
     * 不动任何已存在的类，所以不能把它提升成一个公共工具类 ——
     * 那要改两个已存在的文件。★ 代价要记住：两处<b>逐字一致</b>，
     * 将来其中一处改了（比如加了「3 无线充电」），另一处不会跟着变。
     * 真要合并，是一次单独的重构，不是顺手改一行。
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
