package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.dto.StationDTO;
import com.chargeops.dto.StationPageQueryDTO;
import com.chargeops.dto.StationUpdateDTO;
import com.chargeops.entity.Station;
import com.chargeops.enumeration.StationStatus;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.mapper.ChargerMapper;
import com.chargeops.mapper.StationMapper;
import com.chargeops.result.PageResult;
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

import java.util.List;

/**
 * 充电站服务实现
 *
 * <p>这是 P1a 五个域里<b>第一个</b>，也是最简单的一个 ——
 * 站点没有任何外键依赖（它是被别人依赖的那一端）。
 * 但它把后面四个域都要用的三件事立了下来：
 * <ol>
 *   <li><b>PageHelper 的调用顺序</b>（见 {@link #page}）</li>
 *   <li><b>「取实体 → 判空 → 转 VO」的三段式</b>（见 {@link #getById}）</li>
 *   <li><b>删除守卫的形状</b>（见 {@link #assertDeletable}）</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StationServiceImpl implements StationService {

    private final StationMapper stationMapper;
    private final ChargerMapper chargerMapper;

    @Override
    @Transactional
    public Long create(StationDTO stationDTO) {
        Station station = new Station();
        BeanUtils.copyProperties(stationDTO, station);

        // ★★ 这里必须补默认值，不能只靠 DTO 的字段初始值。
        //
        //   字段初始值只挡得住「请求体里没写这个键」这一种情况。
        //   写成 {"openTime": null} 是【显式 null】，Jackson 照常调用 setter
        //   把 null 写进去 —— 字段初始值被覆盖掉了。
        //   而 StationMapper.xml 的 insert 是全列插入，null 会直接撞
        //   station.open_time 的 NOT NULL 约束（errno 1048），
        //   报出来的是一个只提到列名的 500，指向不了「客户端传了 null」。
        //
        //   所以「不传」和「传 null」必须在【这里】被归一成同一件事。
        //   这个坑在本次集成测试里连踩三次（station.open_time、
        //   charger.rated_power_kw、charger.gun_count），见 ChargerMapper.xml。
        station.setOpenTime(normalizeOr(stationDTO.getOpenTime(), StationDTO.DEFAULT_OPEN_TIME));
        station.setCloseTime(normalizeOr(stationDTO.getCloseTime(), StationDTO.DEFAULT_CLOSE_TIME));

        // 状态同理：不传或传 null 都按「启用」处理
        if (station.getStatus() == null) {
            station.setStatus(StationStatus.ENABLED.getCode());
        }

        assertBusinessHours(station.getOpenTime(), station.getCloseTime());

        stationMapper.insert(station);

        // ★ 不回查一次再返回 VO。useGeneratedKeys 已经把 id 回填了，
        //   而「刚插进去的那一行」和「查出来的那一行」之间没有任何区别 ——
        //   除非有别人并发改了它，但那个窗口里本来就没有正确的答案。
        //   少一次往返，也少一个「为什么返回的和刚提交的不一样」的疑点。
        log.info("新增充电站成功：id={}, code={}", station.getId(), station.getCode());
        return station.getId();
    }

    @Override
    @Transactional
    public void update(Long id, StationUpdateDTO stationUpdateDTO) {
        Station station = new Station();
        BeanUtils.copyProperties(stationUpdateDTO, station);
        station.setId(id);

        // ★ 时间字段要归一化，但【不能】补默认值 —— 这里 null 的含义是
        //   「不修改」而不是「用默认值」。给空值兜底会让「改个电话」
        //   顺便把营业时间重置成全天，一个没有任何提示的副作用。
        if (station.getOpenTime() != null) {
            station.setOpenTime(TimeCodec.normalize(station.getOpenTime()));
        }
        if (station.getCloseTime() != null) {
            station.setCloseTime(TimeCodec.normalize(station.getCloseTime()));
        }

        // ★ 跨字段校验必须拿【合并后的行】来做，不能只看 DTO。
        //
        //   「把关门时间从 22:00 改成 06:00」这个请求里只有一个字段，
        //   单看它看不出任何问题 —— 非法性来自它和库里那个 open_time 的关系。
        //   所以当 DTO 只给了一边时，另一边要从库里读。
        //
        //   代价是多一次查询。可接受：这个接口的调用频率以「天」计。
        if (station.getOpenTime() != null || station.getCloseTime() != null) {
            Station existing = requireStation(id);
            String openTime = station.getOpenTime() != null
                    ? station.getOpenTime() : existing.getOpenTime();
            String closeTime = station.getCloseTime() != null
                    ? station.getCloseTime() : existing.getCloseTime();
            assertBusinessHours(openTime, closeTime);
        }

        // ★ 不预检「这一行是否存在」。
        //
        //   <set> + <if> 的更新在 id 不存在时影响行数为 0，不报错。
        //   那要不要在这里先查一次？
        //   不查 —— 因为【改一个不存在的站点】和【改一个所有字段都为空的站点】
        //   在客户端看来是两件事，而它们本该表现一致：都没改到任何东西。
        //   真正需要区分的是「id 不存在」，而那是 GET 接口的职责。
        //   多一次查询换来的只是一句更明确的错误消息，代价是每次更新多一次往返。
        stationMapper.update(station);

        log.info("修改充电站：id={}", id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireStation(id);
        assertDeletable(id);
        stationMapper.deleteById(id);
        log.info("删除充电站：id={}", id);
    }

    @Override
    public StationVO getById(Long id) {
        return toVo(requireStation(id));
    }

    @Override
    public PageResult page(StationPageQueryDTO query) {
        // ★★ PageHelper 的调用约定 —— 每一处 page() 都要照这个顺序写。
        //
        //   startPage 把分页参数放进一个 ThreadLocal，由【该线程上的下一条
        //   MyBatis 查询】消费掉。所以：
        //     · startPage 必须紧挨着 mapper.page(...)，中间不能插别的查询
        //     · 中间插进去的那个查询会【拿到 LIMIT 10】并把它消费掉，
        //       于是真正的分页查询变成全表扫描 —— 静默、依赖数据量、
        //       而且要等数据涨起来才会出现
        //     · 拿到 rows 之后才能做补名字之类的富化（见 ChargerServiceImpl）
        //
        //   本站点服务没有富化步骤，所以这里看不出风险 —— 这正是它危险的地方：
        //   将来往 page() 里加第一行「顺便查一下……」的代码时，
        //   没有任何东西会提醒你这是不允许的。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        List<Station> rows = stationMapper.page(query);

        // ★ 强转成 Page 只为了拿 total。PageHelper 返回的 List 实际就是 Page
        //   （它继承自 ArrayList），所以这个强转在 PageHelper 被正确调用时
        //   永远成立 —— 反过来说，如果它抛了 ClassCastException，
        //   说明 startPage 没有生效（比如上面那句被谁删掉了）。
        //   这个异常虽然难看，但比一个 total 恒为 0 的响应要好。
        long total = ((Page<Station>) rows).getTotal();

        List<StationVO> records = rows.stream().map(this::toVo).toList();

        return new PageResult(total, records);
    }

    // ==================== 私有：校验与转换 ====================

    /**
     * 取站点，不存在则抛 —— 所有需要「这一行必须存在」的地方都走它
     *
     * <p>★ 抽成一个方法而不是在各处写 {@code if (x == null) throw}，
     * 是因为同一条 if 要在 {@code getById} / {@code update} / {@code delete}
     * 三处出现，而三处的消息必须一字不差 —— 前端可能按 msg 做分支。
     * 这是 {@code MessageConstant} 存在的同一个理由，只是在方法这一层。
     */
    private Station requireStation(Long id) {
        Station station = stationMapper.getById(id);
        if (station == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        return station;
    }

    /**
     * 删除守卫：站点下还有充电桩时不许删
     *
     * <p><b>★ 为什么是「拒绝」而不是「级联删除」。</b>
     * 级联删除看起来更方便，但一删站点就带走了它的全部充电桩，
     * 而那些桩上可能挂着正在充电的订单、也可能只是被手工登记错了位置。
     * 一次误操作删掉 20 根桩，而界面上只显示「删除站点」——
     * 代价和提示完全不成比例。<b>让删除变麻烦，是因为它本来就该麻烦。</b>
     *
     * <p>消息里带上具体数量（「还有 3 个充电桩」）而不是只说「不允许」：
     * 后者会让管理员以为这是一个无解的限制，转头去提工单。
     *
     * <p>常规的「下线」手段是 {@code status = 2 维护中} 或 {@code 0 停用}，
     * 而不是删除。但硬删<b>必须存在</b>：{@code uk_station_code} 不看 status，
     * 站点编码建错一次就永久占用，没有硬删就只能去生产库手敲 DELETE。
     *
     * <p>★★ <b>P1c 要在这里加第二个守卫。</b>
     * 本轮的检查只问「有没有桩」，P1c 之后还应该问
     * 「这些桩上有没有<b>在充的订单</b>」—— 一个站点在有人充电时被删掉，
     * 那笔订单会失去它的站点归属，而订单是 append-only 的，
     * 那个悬空引用会永久留在历史里。
     * 加检查的位置就在这里，查询大概会是
     * {@code charging_order} 按 {@code station_id} + 状态过滤的一个 COUNT。
     */
    private void assertDeletable(Long id) {
        int chargerCount = chargerMapper.countByStationId(id);
        if (chargerCount > 0) {
            throw new DataConflictException(
                    "该站点下还有 " + chargerCount + " 个充电桩，请先删除或转移");
        }
    }

    /**
     * 营业时间校验：结束必须<b>严格晚于</b>开始
     *
     * <p>★ 这一条同时表达了两件事，都值得说清楚：
     *
     * <ol>
     *   <li><b>相等也不行。</b>{@code open == close} 看起来像「不营业」，
     *       实际会被所有下游解读成「全天营业」（一个零长度的休息区间
     *       和没有休息区间是一回事）。用一个自己都觉得含糊的值去表达
     *       「不营业」是不对的 —— 那应该用 {@code status = 0 停用}。</li>
     *
     *   <li><b>{@code close < open} 也不行，于是【不支持跨夜营业】。</b>
     *       {@code 22:00-06:00} 这种夜间站点表达不了，因为「次日」这个概念
     *       在这张表里没有位置 —— 只有一对 open/close，没有任何字段能表示
     *       「结束时间是次日」。<b>这是从 DDL 继承来的限制，不是本轮漏掉的。</b>
     *       真要支持跨夜，要加的是「次日标记」或者改成两段营业时间，
     *       那是 schema 变更，不是加一行业务代码。</li>
     * </ol>
     *
     * <p>★ 这里用 {@code TimeCodec.toMinuteOfDay} 比较，而不是字符串比较。
     * 字符串比较在零填充的 {@code HH:mm:ss} 上<b>恰好</b>是对的
     * （字典序等于时间序），但那只在格式统一时成立 ——
     * 而客户端提交的可能是 {@code "8:00"}（{@code <input type="time">}
     * 在秒为 0 时提交的格式就是 {@code "08:00"}，但手填的会是别的）。
     * 转成分钟数是唯一不依赖格式假设的做法。
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

    /**
     * 归一化时间字符串，为空时返回默认值
     *
     * <p>{@code TimeCodec.normalize} 把 {@code "08:00"} 补成 {@code "08:00:00"}。
     * 入库格式统一是有意义的：{@code StationMapper.xml} 的 {@code ORDER BY}、
     * 以及将来任何对这两个字段的<b>字符串</b>操作，都依赖零填充这一性质。
     */
    private String normalizeOr(String time, String defaultValue) {
        return time == null || time.isBlank() ? defaultValue : TimeCodec.normalize(time);
    }

    /**
     * 实体 → VO
     *
     * <p>★ {@code BeanUtils.copyProperties} 在这里是安全的，
     * 判断依据和 {@code StaffServiceImpl} 里那条一样：
     * <b>目标类型 {@link StationVO} 是一个白名单</b>。
     * 它没有 {@code createUser} / {@code updateUser} 字段，
     * 所以无论源对象上有什么，都不可能跟着复制过去。
     *
     * <p>危险的方向是「复制进实体」（客户端能借此覆盖 id、status）——
     * 那个方向由 DTO 的窄字段集挡住，两边是独立生效的两层。
     */
    private StationVO toVo(Station station) {
        StationVO vo = new StationVO();
        BeanUtils.copyProperties(station, vo);

        // ★ 派生字段不在 BeanUtils 的覆盖范围内 —— 源对象上根本没有
        //   statusLabel 这个属性。这里显式算，正是为了让 code → label
        //   的映射在系统里只存在一份（枚举里那份）。
        vo.setStatusLabel(labelOf(station.getStatus()));

        return vo;
    }

    /**
     * 状态码 → 文案
     *
     * <p>★ {@code StationStatus.of} 对未知的 code 会抛异常，
     * 而这里不能让它抛：一个数据库里存在、代码不认识的状态值
     * （比如有人手工 UPDATE 了一个 9），不应该让整个列表接口 500。
     * 那种情况下返回 null，界面上显示空白 ——
     * <b>「这一行的状态显示不出来」和「整个页面打不开」是两个严重程度差很远的问题。</b>
     *
     * <p>这也是一条通用取舍：<b>只读路径上的派生字段失败要降级，写路径上的校验失败要抛。</b>
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
