package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.RedisConstant;
import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.OrderStatus;
import com.chargeops.enumeration.StationStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.ChargingOrderMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
import com.chargeops.redis.CacheClient;
import com.chargeops.service.StationService;
import com.chargeops.utils.TimeCodec;
import com.chargeops.vo.StationVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 充电站服务实现
 *
 * <p>站点没有外键依赖（它是被别人依赖的那一端），后面几个域要用的三件事在这里定型：
 * PageHelper 的调用顺序、取实体→判空→转 VO、删除守卫，分别见 {@link #page}、
 * {@link #requireStation} 和 {@link #assertDeletable}。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StationServiceImpl implements StationService {

    private final StationMapper stationMapper;
    private final ChargerMapper chargerMapper;

    /**
     * 跨到订单表上看起来越界，但「这个站能不能删」的答案本来就不在站点表里 ——
     * 在引用它的那些行里（桩在 charger、订单在 charging_order）。
     */
    private final ChargingOrderMapper orderMapper;

    private final CacheClient cacheClient;

    @Override
    @Transactional
    public Long create(StationDTO stationDTO) {
        Station station = new Station();
        BeanUtils.copyProperties(stationDTO, station);

        // 这里必须补默认值，不能只靠 DTO 的字段初始值：初始值挡不住
        // {"openTime": null} 这种显式 null —— Jackson 照常调 setter 写进去，
        // 而 insert 是全列插入，null 会撞 station.open_time 的 NOT NULL（errno 1048），
        // 报出来是一个只提列名的 500。集成测试里连踩三次（另两处见 ChargerMapper.xml）。
        station.setOpenTime(normalizeOr(stationDTO.getOpenTime(), StationDTO.DEFAULT_OPEN_TIME));
        station.setCloseTime(normalizeOr(stationDTO.getCloseTime(), StationDTO.DEFAULT_CLOSE_TIME));

        // 状态同理：不传或传 null 都按「启用」处理
        if (station.getStatus() == null) {
            station.setStatus(StationStatus.ENABLED.getCode());
        }

        assertBusinessHours(station.getOpenTime(), station.getCloseTime());

        stationMapper.insert(station);

        // 新 id 通常不会有缓存，但有人可能提前查询过自增 id 并命中空值缓存；
        // 提交后删掉它，避免创建成功后短时间内仍被当成不存在。
        cacheClient.deleteAfterCommit(RedisConstant.CACHE_STATION_PREFIX + station.getId());

        log.info("新增充电站成功：id={}, code={}", station.getId(), station.getCode());
        return station.getId();
    }

    @Override
    @Transactional
    public void update(Long id, StationUpdateDTO stationUpdateDTO) {
        Station station = new Station();
        BeanUtils.copyProperties(stationUpdateDTO, station);
        station.setId(id);

        // 时间字段要归一化，但不能补默认值：这里的 null 是「不修改」，
        // 兜底会让「改个电话」顺便把营业时间重置成全天。
        if (station.getOpenTime() != null) {
            station.setOpenTime(TimeCodec.normalize(station.getOpenTime()));
        }
        if (station.getCloseTime() != null) {
            station.setCloseTime(TimeCodec.normalize(station.getCloseTime()));
        }

        // 跨字段校验必须拿合并后的行来做：「把关门时间改成 06:00」这个请求里
        // 只有一个字段，非法性来自它和库里那个 open_time 的关系。
        if (station.getOpenTime() != null || station.getCloseTime() != null) {
            Station existing = requireStation(id);
            String openTime = station.getOpenTime() != null
                    ? station.getOpenTime() : existing.getOpenTime();
            String closeTime = station.getCloseTime() != null
                    ? station.getCloseTime() : existing.getCloseTime();
            assertBusinessHours(openTime, closeTime);
        }

        // 不预检「这一行是否存在」：id 不存在时影响行数为 0 且不报错，
        // 而「改一个不存在的站点」和「改一个没改到任何字段的站点」本该表现一致。
        stationMapper.update(station);
        cacheClient.deleteAfterCommit(RedisConstant.CACHE_STATION_PREFIX + id);

        log.info("修改充电站：id={}", id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireStation(id);
        assertDeletable(id);
        stationMapper.deleteById(id);
        cacheClient.deleteAfterCommit(RedisConstant.CACHE_STATION_PREFIX + id);
        log.info("删除充电站：id={}", id);
    }

    @Override
    public StationVO getById(Long id) {
        StationVO station = cacheClient.queryWithPassThrough(
                RedisConstant.CACHE_STATION_PREFIX,
                id,
                new TypeReference<StationVO>() {},
                this::findStationVO,
                15L,
                TimeUnit.MINUTES);
        if (station == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        return station;
    }

    @Override
    public PageResult page(StationPageQueryDTO query) {
        // startPage 把分页参数放进一个 ThreadLocal，由该线程上的下一条 MyBatis 查询
        // 消费掉，所以它必须紧挨着 mapper.page(...)。中间插进去任何查询都会拿到
        // LIMIT 10 并把它消费掉，真正的分页查询退化成全表扫描 —— 不报错、依赖数据量、
        // 要等数据涨起来才出现。富化（补名字之类）只能放在拿到 rows 之后。
        // 本方法没有富化步骤，看不出风险，这正是危险之处：将来往这里加第一行
        // 「顺便查一下……」的代码时，没有任何东西会提醒你这是不允许的。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Station> rows = stationMapper.page(query);

        // 强转成 Page 只为了拿 total（PageHelper 返回的 List 实际就是 Page）。
        // 抛 ClassCastException 说明 startPage 没生效。
        long total = ((Page<Station>) rows).getTotal();

        List<StationVO> records = rows.stream().map(this::toVo).toList();

        return new PageResult(total, records);
    }

    // ---------- 私有：校验与转换 ----------

    /** 取站点，不存在则抛。三个调用点（getById / update / delete）的消息必须一字不差 —— 前端可能按 msg 做分支 */
    private Station requireStation(Long id) {
        Station station = stationMapper.getById(id);
        if (station == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        return station;
    }

    /** 缓存未命中时的数据库回退。返回 VO，避免把审计字段写进 Redis。 */
    private StationVO findStationVO(Long id) {
        Station station = stationMapper.getById(id);
        return station == null ? null : toVo(station);
    }

    /**
     * 删除守卫：站点下还有充电桩、或还有未完结的订单时，不许删。
     *
     * <p> 为什么拒绝而不是级联删除：一删站点就带走了它的全部充电桩，而那些桩上可能挂着
     * 正在充电的订单。一次误操作删掉 20 根桩，界面上只显示「删除站点」。硬删也必须存在：
     * {@code uk_station_code} 不看 status，站点编码建错一次就永久占用。
     *
     * <p> 顺序是有意的：先查桩（一个走 {@code idx_charger_station} 的 COUNT，成本最低，
     * 提示「请先删除或转移」当场可执行）。再查订单，它挡的是第一道挡不住的：桩都挪走了，
     * 但历史订单的 {@code station_id} 还指着这个站 —— 订单是 append-only 的，
     * 那个悬空引用会永久留在历史里。第二道在正常数据上是兜底网（每根桩自己就要过一遍
     * 同样的检查），真正会命中的是数据漂移；不要因为难以构造测试场景就删掉它。
     *
     * <p> 判据是 {@code OrderStatus.blockingCodes()} 而不是「有没有订单」：否则任何完成过
     * 充电的站点都会变成永久不可删。
     *
     * <p> 本方法的 SQL 侧约定：{@code status} 必须写在 {@code station_id} 前面，
     * 见 {@code ChargingOrderMapper.countByStation} —— 写反了会退化成扫全站历史。
     */
    private void assertDeletable(Long id) {
        int chargerCount = chargerMapper.countByStationId(id);
        if (chargerCount > 0) {
            throw new DataConflictException(
                    String.format(MessageConstant.STATION_HAS_CHARGER, chargerCount));
        }

        int orderCount = orderMapper.countByStation(id, OrderStatus.blockingCodes());
        if (orderCount > 0) {
            throw new DataConflictException(
                    String.format(MessageConstant.STATION_HAS_ORDER, orderCount));
        }
    }

    /**
     * 营业时间校验：结束必须严格晚于开始。
     *
     * <p> 相等也不行：{@code open == close} 会被所有下游解读成「全天营业」（零长度的休息
     * 区间和没有休息区间是一回事），「不营业」该用 {@code status = 0 停用}表达。
     * {@code close < open} 也不行，于是不支持跨夜营业 —— 真要支持是 schema 变更。
     *
     * <p> 用 {@code TimeCodec.toMinuteOfDay} 比较而不是字符串比较：字符串比较只在零填充
     * 的 {@code HH:mm:ss} 上恰好成立，而客户端提交的可能是 {@code "8:00"}。
     */
    private void assertBusinessHours(String openTime, String closeTime) {
        int open = TimeCodec.toMinuteOfDay(openTime);
        int close = TimeCodec.toMinuteOfDay(closeTime);
        if (close <= open) {
            throw new ParamErrorException("结束营业时间必须晚于开始营业时间："
                    + TimeCodec.toTimeString(open) + " - " + TimeCodec.toTimeString(close)
                    + "（本项目不支持跨夜营业，如需 22:00-06:00 请先调整数据结构）");
        }
    }

    /** 归一化时间字符串，为空时返回默认值。零填充的入库格式被 StationMapper.xml 的 ORDER BY 依赖 */
    private String normalizeOr(String time, String defaultValue) {
        return time == null || time.isBlank() ? defaultValue : TimeCodec.normalize(time);
    }

    /** 实体 → VO。{@code BeanUtils.copyProperties} 在这里安全，因为 {@link StationVO} 是白名单（危险的方向是复制进实体） */
    private StationVO toVo(Station station) {
        StationVO vo = new StationVO();
        BeanUtils.copyProperties(station, vo);

        // 派生字段不在 BeanUtils 的覆盖范围内，显式算：code → label 的映射在系统里只存在一份
        vo.setStatusLabel(labelOf(station.getStatus()));

        return vo;
    }

    /**
     * 状态码 → 文案。
     *
     * <p> {@code StationStatus.of} 对未知 code 会抛异常，这里不能让它抛：有人手工 UPDATE 了
     * 一个 9 不该让整个列表接口 500，那种情况下返回 null。「这一行的状态显示不出来」和
     * 「整个页面打不开」差得很远 —— 只读路径上的派生字段失败要降级，写路径上的校验失败要抛。
     */
    private String labelOf(Integer status) {
        if (status == null) {
            return null;
        }
        try {
            return StationStatus.of(status).getLabel();
        } catch (ParamErrorException e) {
            log.warn("站点状态值无法识别，status={}（数据是手工改过还是枚举漏了一个值？）", status);
            return null;
        }
    }
}
