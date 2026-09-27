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
 * <p>核心是 {@link #resolveTemplateIdAt}（下单链路的入口），其余是绑定行的增删改查。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StationTariffServiceImpl implements StationTariffService {

    private final StationTariffMapper stationTariffMapper;
    private final StationMapper stationMapper;
    private final TariffTemplateMapper tariffTemplateMapper;

    /** 错误消息里渲染时刻用的格式。不从全局 Jackson 配置读：Service 不依赖 Web 层的配置类，代价是改全局格式时这里会漏改 */
    private static final DateTimeFormatter API_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---------- 新增绑定 ----------

    @Override
    @Transactional
    public Long bind(StationTariffDTO stationTariffDTO) {
        requireStation(stationTariffDTO.getStationId());
        TariffTemplate template = requireTemplate(stationTariffDTO.getTemplateId());

        // 停用的模板不能用于新建绑定，否则「停用」就是一个不完整的操作：
        // 管理员停用了它，但仍然有人能拿它去配新站点。已存在的绑定不受影响 ——
        // 解析走它们自己的行，不经过这里。
        if (StatusConstant.DISABLE.equals(template.getStatus())) {
            throw new DataConflictException(MessageConstant.TEMPLATE_DISABLED);
        }

        StationTariff binding = new StationTariff();
        BeanUtils.copyProperties(stationTariffDTO, binding);

        // 字段初始值挡不住显式 JSON null（见 StationServiceImpl.create）：
        // priority 和 status 都是 NOT NULL 列，全列插入下 null 会撞 errno 1048。
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

    // ---------- 修改绑定 ----------

    @Override
    @Transactional
    public void update(Long id, StationTariffUpdateDTO stationTariffUpdateDTO) {
        // 必须先把库里那一行读出来 —— 要比较的 effectiveFrom 不在 DTO 里
        // （它是这个绑定的身份的一部分，见 StationTariffUpdateDTO 类注释）。
        StationTariff existing = requireBinding(id);

        StationTariff binding = new StationTariff();
        BeanUtils.copyProperties(stationTariffUpdateDTO, binding);
        binding.setId(id);

        // 不必在这里判 effectiveTo 是否为 null —— assertEffectiveRange 自己会处理
        // （null = 长期有效）。守卫写在方法里而不是调用点上，「忘判 null」才不可能
        // 发生在任何一个调用点。
        assertEffectiveRange(existing.getEffectiveFrom(), binding.getEffectiveTo());

        stationTariffMapper.update(binding);

        log.info("修改站点计费绑定：id={}", id);
    }

    // ---------- 删除绑定 ----------

    @Override
    @Transactional
    public void delete(Long id) {
        requireBinding(id);
        stationTariffMapper.deleteById(id);
        // 删除没有守卫，和别的域不同：绑定没有子记录，而且订单冻结的是模板 id，
        // 和这条绑定行无关。代价是丢掉一段历史 —— 事后追查一笔老订单为什么用了
        // 这个价时，会找不到依据。
        log.info("删除站点计费绑定：id={}", id);
    }

    // ---------- 查询 ----------

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

    // ---------- 解析：P1c 的入口 ----------

    /**
     * 解析某站某时刻的模板 id，解析不出来就抛
     *
     * <p>底层的 {@code resolveTemplateAt} 在「没有生效的绑定」时返回 {@code null}，
     * 本方法把它翻成 {@link TariffNotCoveredException}：方法签名里没有 null 这个可能，
     * 调用方也就无法忘记检查它。
     *
     * <p> 查询走 {@code idx_station_tariff_lookup
     * (station_id, status, effective_from, effective_to)}，
     * 列的顺序和 WHERE 条件的顺序完全一致。这条查询永久不缓存。
     */
    @Override
    public Long resolveTemplateIdAt(Long stationId, LocalDateTime at) {
        // 传 null 的时刻是调用方的 bug，不是「用当前时间」的暗示：悄悄替换成 now()
        // 的话，一笔本该按「下单那一刻」计价的订单会因为一次重构被改成「结算那一刻」——
        // 而两者的差别恰恰是这张表要防的东西。
        if (at == null) {
            throw new ParamErrorException("解析计价模板的时刻不能为空");
        }

        StationTariff binding = stationTariffMapper.resolveTemplateAt(stationId, at);
        if (binding == null) {
            // 级别是 warn：它每出现一次，就有一个用户走到了「充不了电」这条路径上，
            // 而原因是配置问题（站点没绑模板，或绑的模板已经过期），运维应该能从日志里发现。
            log.warn("站点在指定时刻没有生效的计费方案，stationId={}, at={}", stationId, at);
            throw new TariffNotCoveredException(MessageConstant.STATION_TARIFF_NOT_BOUND);
        }

        return binding.getTemplateId();
    }

    // ---------- 私有 ----------

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
     * 生效区间校验：失效时刻必须严格晚于生效时刻
     *
     * <p> 区间是半开的 {@code [effectiveFrom, effectiveTo)}，所以
     * {@code effectiveTo == effectiveFrom} 是一个空区间，永远不会被
     * {@code resolveTemplateAt} 命中 —— 而这样一条绑定在列表页上和正常绑定长得
     * 一模一样，运维建了它、看到它显示在列表里，以为配置好了，系统在该站下单时
     * 却说「没有配置计费方案」。
     *
     * <p> 允许 {@code effectiveTo} 为 {@code null}（长期有效，最常见的情况），
     * 所以这个方法第一件事就是判空返回。判空必须写在方法内部，不能靠调用点各自去判 ——
     * 冒烟测试真的踩到了：调用点没判空，「不填失效时刻」这个合法请求抛
     * {@code NullPointerException}，被兜底分支翻成 HTTP 500。
     *
     * <p> 用 {@code isAfter} 而不是 {@code compareTo}：{@code LocalDateTime} 有纳秒精度
     * 而 {@code datetime(3)} 只到毫秒，比较「内存里的值」和「库里的值」会踩四舍五入的坑。
     *
     * <p> 消息里的时刻用 {@code yyyy-MM-dd HH:mm:ss} 渲染：{@code LocalDateTime.toString()}
     * 会得到 {@code 2026-07-01T00:00}（ISO 格式、秒被省略），那是只有开发者才认得的形状。
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
     * <p> 列表里只有 {@code templateId} 没有信息量 —— 没有人记得模板 7 是哪个价，
     * 而这正是管理员打开这个页面要回答的问题。批量是为了消除 N+1。
     *
     * <p> 空集合必须先判掉：{@code <foreach>} 展开成 {@code IN ()} 是 SQL 语法错误，
     * 而「这个站点一条绑定都还没有」是新建站点的默认状态。
     */
    private List<StationTariffVO> toVos(List<StationTariff> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }

        Set<Long> templateIds = bindings.stream()
                .map(StationTariff::getTemplateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 模板查不到时（被并发删掉了）不报错，只是名字为 null —— 只读路径上的派生字段失败不抛
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
