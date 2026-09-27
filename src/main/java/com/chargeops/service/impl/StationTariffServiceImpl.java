package com.chargeops.service.impl;

import com.chargeops.constant.MessageConstant;
import com.chargeops.constant.StatusConstant;
import com.chargeops.dto.StationTariffDTO;
import com.chargeops.dto.StationTariffUpdateDTO;
import com.chargeops.entity.Station;
import com.chargeops.entity.StationTariff;
import com.chargeops.entity.TariffTemplate;
import com.chargeops.exception.DataConflictException;
import com.chargeops.exception.NotFoundException;
import com.chargeops.exception.ParamErrorException;
import com.chargeops.exception.TariffNotCoveredException;
import com.chargeops.mapper.StationMapper;
import com.chargeops.mapper.StationTariffMapper;
import com.chargeops.mapper.TariffTemplateMapper;
import com.chargeops.service.StationTariffService;
import com.chargeops.vo.StationTariffVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 站点计费绑定服务实现
 *
 * <p>核心是 {@link #resolveTemplateIdAt}（下单链路的入口），
 * 其余方法（增删改查）的形状和别的域一样。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StationTariffServiceImpl implements StationTariffService {

    private final StationTariffMapper stationTariffMapper;
    private final StationMapper stationMapper;
    private final TariffTemplateMapper tariffTemplateMapper;

    /**
     * 错误消息里渲染时刻用的格式
     *
     * <p>★ 和全局 Jackson 配置保持同一串模式，但<b>不是</b>从那里读来的：
     * 一个是序列化配置，一个是给人看的文案，变更理由不同。
     * 硬编码的代价是「改全局格式时这里会漏改」，表现为消息里的日期格式
     * 和接口返回的不一致 —— 不会造成错误，只让人困惑。
     * 换来的好处是 Service 不依赖 Web 层的配置类。
     */
    private static final DateTimeFormatter API_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== 新增绑定 ====================

    @Override
    @Transactional
    public Long bind(StationTariffDTO stationTariffDTO) {
        requireStation(stationTariffDTO.getStationId());
        TariffTemplate template = requireTemplate(stationTariffDTO.getTemplateId());

        // ★ 停用的模板不能用于【新建】绑定 —— status 的语义就是「这个模板还能不能
        //   被新绑定」。不拦的话，停用就是一个不完整的操作：管理员停用了它，
        //   但仍然有人能拿它去配新站点。
        //
        //   ★ 这不影响【已经存在】的绑定：那些绑定有自己的行，解析时不经过这里 ——
        //   「停用模板不会改变现有站点计费」这个承诺就是靠这两条路径实现的。
        if (StatusConstant.DISABLE.equals(template.getStatus())) {
            throw new DataConflictException(MessageConstant.TEMPLATE_DISABLED);
        }

        StationTariff binding = new StationTariff();
        BeanUtils.copyProperties(stationTariffDTO, binding);

        // ★ 字段初始值挡不住显式 JSON null —— 理由见 StationServiceImpl.create。
        //   priority 和 status 都是 NOT NULL 列，全列插入下 null 会撞 errno 1048。
        if (binding.getPriority() == null) {
            binding.setPriority(0);
        }
        if (binding.getStatus() == null) {
            binding.setStatus(StatusConstant.ENABLE);
        }

        assertEffectiveRange(binding.getEffectiveFrom(), binding.getEffectiveTo());

        stationTariffMapper.insert(binding);

        log.info("绑定站点计费模板成功：id={}, stationId={}, templateId={}, effectiveFrom={}",
                binding.getId(), binding.getStationId(), binding.getTemplateId(),
                binding.getEffectiveFrom());
        return binding.getId();
    }

    // ==================== 修改绑定 ====================

    @Override
    @Transactional
    public void update(Long id, StationTariffUpdateDTO stationTariffUpdateDTO) {
        // ★ 必须先把库里那一行读出来 —— 因为跨字段校验要比的
        //   {@code effectiveFrom} 不在 DTO 里（它是身份的一部分，
        //   见 StationTariffUpdateDTO 类注释）。
        StationTariff existing = requireBinding(id);

        StationTariff binding = new StationTariff();
        BeanUtils.copyProperties(stationTariffUpdateDTO, binding);
        binding.setId(id);

        // ★ 不需要在这里判 effectiveTo 是否为 null —— assertEffectiveRange
        //   自己会处理（null = 长期有效）。守卫写在方法里而不是调用点上，
        //   是为了让「忘判 null」在任何一个调用点都不可能发生。
        assertEffectiveRange(existing.getEffectiveFrom(), binding.getEffectiveTo());

        stationTariffMapper.update(binding);

        log.info("修改站点计费绑定：id={}", id);
    }

    // ==================== 删除绑定 ====================

    @Override
    @Transactional
    public void delete(Long id) {
        requireBinding(id);
        stationTariffMapper.deleteById(id);
        // ★ 删除没有守卫，和别的域不同 —— 绑定没有「子记录」，而且删掉一条绑定
        //   不会让任何已有订单失去依据（订单冻结的是模板 id，和这条绑定行无关）。
        //   唯一的代价是丢掉一段历史：那个时期在 station_tariff 里变成空白，
        //   事后追查老订单为什么用了这个价时没有依据。
        log.info("删除站点计费绑定：id={}", id);
    }

    // ==================== 查询 ====================

    @Override
    public StationTariffVO getById(Long id) {
        StationTariff binding = requireBinding(id);
        return toVos(List.of(binding)).get(0);
    }

    @Override
    public List<StationTariffVO> listByStationId(Long stationId) {
        requireStation(stationId);
        return toVos(stationTariffMapper.listByStationId(stationId));
    }

    // ==================== 解析：P1c 的入口 ====================

    /**
     * ★★ 解析某站某时刻的模板 id，解析不出来就抛
     *
     * <p>底层的 {@code resolveTemplateAt} 在「没有生效的绑定」时返回 {@code null}，
     * 本方法把它翻成 {@link TariffNotCoveredException}。<b>为什么是抛而不是
     * 返回 null</b>，见接口类注释第二节：方法签名里没有 null 这个可能，
     * 所以调用方也就无法忘记检查它。
     *
     * <p>★ 查询本身走 {@code idx_station_tariff_lookup
     * (station_id, status, effective_from, effective_to)}，
     * 列的顺序和 WHERE 条件的顺序完全一致。★ <b>这条查询永久不缓存</b>。
     */
    @Override
    public Long resolveTemplateIdAt(Long stationId, LocalDateTime at) {
        // ★ 传 null 的时刻是一个调用方的 bug，而不是「用当前时间」的暗示。
        //   悄悄替换成 now() 的话，一笔本该按「下单那一刻」计价的订单
        //   会因为一次重构被改成「结算那一刻」—— 而两者的差别
        //   恰恰是这张表要防的东西。
        if (at == null) {
            throw new ParamErrorException("解析计价模板的时刻不能为空");
        }

        StationTariff binding = stationTariffMapper.resolveTemplateAt(stationId, at);
        if (binding == null) {
            // ★★ 级别是 warn 而不是 info —— 它每出现一次，就有一个用户走到了
            //    「充不了电」这条路径上，而原因是配置问题（站点没绑模板，
            //    或绑的模板已经过期）。运维应该能从日志里发现这件事。
            log.warn("站点在指定时刻没有生效的计费方案，stationId={}, at={}", stationId, at);
            throw new TariffNotCoveredException(MessageConstant.STATION_TARIFF_NOT_BOUND);
        }

        return binding.getTemplateId();
    }

    // ==================== 私有 ====================

    private Station requireStation(Long stationId) {
        Station station = stationMapper.getById(stationId);
        if (station == null) {
            throw new NotFoundException(MessageConstant.STATION_NOT_FOUND);
        }
        return station;
    }

    private TariffTemplate requireTemplate(Long templateId) {
        TariffTemplate template = tariffTemplateMapper.getById(templateId);
        if (template == null) {
            throw new NotFoundException(MessageConstant.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    private StationTariff requireBinding(Long id) {
        StationTariff binding = stationTariffMapper.getById(id);
        if (binding == null) {
            throw new NotFoundException(MessageConstant.STATION_TARIFF_NOT_FOUND);
        }
        return binding;
    }

    /**
     * 生效区间校验：失效时刻必须<b>严格晚于</b>生效时刻
     *
     * <p>★ 区间是<b>半开</b>的 {@code [effectiveFrom, effectiveTo)}：{@code effectiveFrom}
     * 含、{@code effectiveTo} 不含，于是 {@code effectiveTo == effectiveFrom} 是
     * 一个<b>空区间</b>，永远不会被 {@code resolveTemplateAt} 命中。
     *
     * <p><b>为什么要拦它：</b>一条永远不会生效的绑定，和一条正常绑定在列表页上
     * <b>长得一模一样</b> —— 运维建了它、看到它显示在列表里、以为配置好了，
     * 而系统在该站点下单时会说「没有配置计费方案」。排查这个谜题的时间
     * 远大于在这里多一行判断。
     *
     * <p>★★ <b>允许 {@code effectiveTo} 为 {@code null}（长期有效），而且那是最常见
     * 的情况</b>，所以这个方法的第一件事就是判空返回。这个判空<b>必须写在方法内部</b>，
     * 不能靠调用点各自去判 —— 冒烟测试里真的踩到了：{@code bind} 一开始没判空，
     * 于是「不填失效时刻」这个最常见的合法请求抛 {@code NullPointerException}，
     * 被兜底分支翻成 HTTP 500，一个完全正常的操作看起来像服务挂了。
     *
     * <p>★ 用 {@code isAfter} 而不是 {@code compareTo}：{@code LocalDateTime} 有纳秒
     * 精度而 {@code datetime(3)} 只到毫秒，{@code compareTo} 比较「内存里的值」和
     * 「库里的值」会踩到四舍五入的坑。这里两边同源、暂时安全，但保持这个习惯。
     *
     * <p>★ 消息里的时刻用 {@code yyyy-MM-dd HH:mm:ss} 渲染：直接用
     * {@code LocalDateTime.toString()} 会得到 {@code 2026-07-01T00:00}
     * （ISO 格式、秒被省略），那是<b>只有开发者才认得的形状</b>，
     * 而这条消息是给调接口的人看的。
     */
    private void assertEffectiveRange(LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        if (effectiveTo == null) {
            return;
        }
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new ParamErrorException(
                    "失效时刻必须晚于生效时刻，否则这条绑定永远不会生效："
                            + API_TIME_FORMAT.format(effectiveFrom) + " -> "
                            + API_TIME_FORMAT.format(effectiveTo));
        }
    }

    /**
     * 批量转 VO，并补齐 {@code templateName} / {@code templateCode}
     *
     * <p>★ 绑定列表里只有 {@code templateId} 是<b>没有信息量的</b> ——
     * 没有人记得模板 7 是哪个价，而这正是管理员打开这个页面要回答的问题。
     *
     * <p>★ 批量的理由同 {@code ChargerServiceImpl.toVos}：消除 N+1。
     *
     * <p>★ 空集合必须先判掉：{@code <foreach>} 展开成 {@code IN ()} 是 SQL 语法错误，
     * 而「这个站点一条绑定都还没有」是<b>新建站点的默认状态</b>，
     * 所以这个分支比在别处更容易走到。
     */
    private List<StationTariffVO> toVos(List<StationTariff> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }

        Set<Long> templateIds = bindings.stream()
                .map(StationTariff::getTemplateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // ★ 模板查不到时（被并发删掉了）不报错，只是名字为 null。
        //   降级策略见 StationServiceImpl.labelOf：只读路径上的派生字段失败不抛。
        Map<Long, TariffTemplate> templates = templateIds.isEmpty()
                ? Map.of()
                : tariffTemplateMapper.listByIds(List.copyOf(templateIds)).stream()
                        .collect(Collectors.toMap(TariffTemplate::getId, t -> t));

        return bindings.stream()
                .map(binding -> {
                    StationTariffVO vo = new StationTariffVO();
                    BeanUtils.copyProperties(binding, vo);

                    TariffTemplate template = templates.get(binding.getTemplateId());
                    if (template != null) {
                        vo.setTemplateName(template.getName());
                        vo.setTemplateCode(template.getCode());
                    }

                    vo.setStatusLabel(
                            StatusConstant.ENABLE.equals(binding.getStatus()) ? "启用" : "停用");
                    return vo;
                })
                .toList();
    }
}
